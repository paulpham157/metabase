(ns metabase.driver.sql-jdbc.connection
  "Logic for creating and managing connection pools for SQL JDBC drivers. Implementations for connection-related driver
  multimethods for SQL JDBC drivers."
  (:require
   [clojure.java.jdbc :as jdbc]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.settings :as driver.settings]
   [metabase.driver.sql-jdbc.connection.ssh-tunnel :as ssh]
   [metabase.driver.util :as driver.u]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [potemkin :as p]
   ^{:clj-kondo/ignore [:discouraged-namespace]}
   [toucan2.core :as t2])
  (:import
   (com.mchange.v2.c3p0 DataSources)
   (javax.sql DataSource)
   (org.apache.logging.log4j Level)))

(set! *warn-on-reflection* true)

;;; these are provided as conveniences because these settings used to live here; prefer getting them from
;;; `driver.settings` instead going forward.
(p/import-vars
 [driver.settings
  jdbc-data-warehouse-unreturned-connection-timeout-seconds
  jdbc-data-warehouse-debug-unreturned-connection-stack-traces])

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                   Interface                                                    |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmulti connection-details->spec
  "Given a Database `details-map`, return an unpooled JDBC connection spec. Driver authors should implement this method,
  but you probably shouldn't be *USE* this method directly! If you want a pooled connection spec (which you almost
  certainly do), use [[db->pooled-connection-spec]] instead.

  DO NOT USE THIS METHOD DIRECTLY UNLESS YOU KNOW WHAT YOU ARE DOING! THIS RETURNS AN UNPOOLED CONNECTION SPEC! IF YOU
  WANT A CONNECTION SPEC FOR RUNNING QUERIES USE [[db->pooled-connection-spec]] INSTEAD WHICH WILL RETURN A *POOLED*
  CONNECTION SPEC."
  {:added "0.32.0" :arglists '([driver details-map])}
  driver/dispatch-on-initialized-driver-safe-keys
  :hierarchy #'driver/hierarchy)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           Creating Connection Pools                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmulti data-warehouse-connection-pool-properties
  "c3p0 connection pool properties for connected data warehouse DBs. See
  https://www.mchange.com/projects/c3p0/#configuration_properties for descriptions of properties.

  The c3p0 dox linked above do a good job of explaining the purpose of these properties and why you might set them.
  Generally, I have tried to choose configuration options for the data warehouse connection pools that minimize memory
  usage and maximize reliability, even when it comes with some added performance overhead. These pools are used for
  powering Cards and the sync process, which are less sensitive to overhead than something like the application DB.

  Drivers that need to override the default properties below can provide custom implementations of this method."
  {:added "0.33.4" :arglists '([driver database])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti data-source-name
  "Name, from connection details, to use to identify a database in the c3p0 `dataSourceName`. This is used for so the
  DataSource has a useful identifier for debugging purposes.

  The default method uses the first non-nil value of the keys `:db`, `:dbname`, `:sid`, or `:catalog`; implement a new
  method if your driver does not have any of these keys in its details."
  {:changelog-test/ignore true, :arglists '([driver details]), :added "0.45.0"}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmethod data-source-name :default
  [_driver details]
  ((some-fn :db
            :dbname
            :sid
            :service-name
            :catalog)
   details))

(defmethod data-warehouse-connection-pool-properties :default
  [driver database]
  {;; only fetch one new connection at a time, rather than batching fetches (default = 3 at a time). This is done in
   ;; interest of minimizing memory consumption
   "acquireIncrement"             1
   ;; Never retry instead of the default of retrying 30 times (#51176)
   ;; While a couple queries may fail during a reboot, this should allow quicker recovery and less spinning on outdated
   ;; credentials
   ;; However, keep 1 retry for the tests to reduce flakiness.
   "acquireRetryAttempts"         (if driver-api/is-test? 1 0)
   ;; [From dox] Seconds a Connection can remain pooled but unused before being discarded.
   "maxIdleTime"                  (* 3 60 60) ; 3 hours
   "minPoolSize"                  (if (:router-database-id database)
                                    0 1)
   "initialPoolSize"              (if (:router-database-id database)
                                    0 1)
   "maxPoolSize"                  (driver.settings/jdbc-data-warehouse-max-connection-pool-size)
   ;; [From dox] If true, an operation will be performed at every connection checkout to verify that the connection is
   ;; valid. [...] ;; Testing Connections in checkout is the simplest and most reliable form of Connection testing,
   ;; but for better performance, consider verifying connections periodically using `idleConnectionTestPeriod`. [...]
   ;; If clients usually make complex queries and/or perform multiple operations, adding the extra cost of one fast
   ;; test per checkout will not much affect performance.
   ;;
   ;; As noted in the C3P0 dox, this does add some overhead, but since all of our drivers are JDBC 4 drivers, they can
   ;; call `Connection.isValid()`, which is reasonably efficient. In my profiling enabling this adds ~100µs for
   ;; Postgres databases on the same machince and ~70ms for remote databases on AWS east testing against a local
   ;; server on the West Coast.
   ;;
   ;; This suggests the additional cost of this test is more or less based entirely to the network latency of the
   ;; request. IRL the Metabase server and data warehouse are likely to be located in closer geographical proximity to
   ;; one another than my trans-contintental tests. Thus in the majority of cases the overhead should be next to
   ;; nothing, and in the worst case close to imperceptible.
   "testConnectionOnCheckout"     true
   ;; [From dox] Number of seconds that Connections in excess of minPoolSize should be permitted to remain idle in the
   ;; pool before being culled. Intended for applications that wish to aggressively minimize the number of open
   ;; Connections, shrinking the pool back towards minPoolSize if, following a spike, the load level diminishes and
   ;; Connections acquired are no longer needed. If maxIdleTime is set, maxIdleTimeExcessConnections should be smaller
   ;; if the parameter is to have any effect.
   ;;
   ;; Kill idle connections above the minPoolSize after 5 minutes.
   "maxIdleTimeExcessConnections" (* 5 60)
   ;; [From dox] Seconds. If set, if an application checks out but then fails to check-in [i.e. close()] a Connection
   ;; within the specified period of time, the pool will unceremoniously destroy() the Connection. This permits
   ;; applications with occasional Connection leaks to survive, rather than eventually exhausting the Connection
   ;; pool. And that's a shame. Zero means no timeout, applications are expected to close() their own
   ;; Connections. Obviously, if a non-zero value is set, it should be to a value longer than any Connection should
   ;; reasonably be checked-out. Otherwise, the pool will occasionally kill Connections in active use, which is bad.
   ;;
   ;; This should be the same as the query timeout. This theoretically shouldn't happen since the QP should kill
   ;; things after a certain timeout but it's better to be safe than sorry -- it seems like in practice some
   ;; connections disappear into the ether
   "unreturnedConnectionTimeout"  (driver.settings/jdbc-data-warehouse-unreturned-connection-timeout-seconds)
   ;; [From dox] If true, and if unreturnedConnectionTimeout is set to a positive value, then the pool will capture
   ;; the stack trace (via an Exception) of all Connection checkouts, and the stack traces will be printed when
   ;; unreturned checked-out Connections timeout. This is intended to debug applications with Connection leaks, that
   ;; is applications that occasionally fail to return Connections, leading to pool growth, and eventually
   ;; exhaustion (when the pool hits maxPoolSize with all Connections checked-out and lost). This parameter should
   ;; only be set while debugging, as capturing the stack trace will slow down every Connection check-out.
   ;;
   ;; As noted in the C3P0 docs, this does add some overhead to create the Exception at Connection checkout.
   ;; criterium/quick-bench indicates this is ~600ns of overhead per Exception created on my laptop, which is small
   ;; compared to the overhead added by testConnectionCheckout, above. The memory usage will depend on the size of the
   ;; stack trace, but clj-memory-meter reports ~800 bytes for a fresh Exception created at the REPL (which presumably
   ;; has a smaller-than-average stack).
   "debugUnreturnedConnectionStackTraces" (u/prog1 (driver.settings/jdbc-data-warehouse-debug-unreturned-connection-stack-traces)
                                            (when (and <> (not (driver-api/level-enabled? 'com.mchange Level/INFO)))
                                              (log/warn "jdbc-data-warehouse-debug-unreturned-connection-stack-traces"
                                                        "is enabled, but INFO logging is not enabled for the"
                                                        "com.mchange namespace. You must raise the log level for"
                                                        "com.mchange to INFO via a custom log4j config in order to"
                                                        "see stacktraces in the logs.")))
   ;; Set the data source name so that the c3p0 JMX bean has a useful identifier, which incorporates the DB ID, driver,
   ;; and name from the details
   "dataSourceName"               (format "db-%d-%s-%s"
                                          (u/the-id database)
                                          (name driver)
                                          (data-source-name driver (:details database)))})

(defn- connection-pool-spec
  "Like [[connection-pool/connection-pool-spec]] but also handles situations when the unpooled spec is a `:datasource`."
  [{:keys [^DataSource datasource], :as spec} pool-properties]
  (if datasource
    {:datasource (DataSources/pooledDataSource datasource (driver-api/map->properties pool-properties))}
    (driver-api/connection-pool-spec spec pool-properties)))

(defn ^:private default-ssh-tunnel-target-port  [driver]
  (when-let [port-info (some
                        #(when (= "port" (:name %)) %)
                        (driver/connection-properties driver))]
    (or (:default port-info)
        (:placeholder port-info))))

(defn- select-internal-keys-for-spec
  "Keep the ssh tunnel keys and others that might be carried on a connection spec or on details."
  [spec-or-details]
  (select-keys spec-or-details [:tunnel-enabled :tunnel-session :tunnel-tracker :tunnel-entrance-port :tunnel-entrance-host]))

(defn- create-pool!
  "Create a new C3P0 `ComboPooledDataSource` for connecting to the given `database`."
  [{:keys [id details], driver :engine, :as database}]
  {:pre [(map? database)]}
  (log/debug (u/format-color :cyan "Creating new connection pool for %s database %s ..." driver id))
  (let [details-with-tunnel (driver/incorporate-ssh-tunnel-details  ;; If the tunnel is disabled this returned unchanged
                             driver
                             (update details :port #(or % (default-ssh-tunnel-target-port driver))))
        details-with-auth   (driver.u/fetch-and-incorporate-auth-provider-details
                             driver
                             id
                             details-with-tunnel)
        spec                (connection-details->spec driver details-with-auth)
        properties          (data-warehouse-connection-pool-properties driver database)]
    (merge
     (connection-pool-spec spec properties)
     ;; also capture entries related to ssh tunneling for later use
     (select-internal-keys-for-spec details-with-auth)
     (select-internal-keys-for-spec spec)
     ;; remember when the password expires
     (select-keys details-with-auth [:password-expiry-timestamp]))))

(defn- destroy-pool! [database-id pool-spec]
  (log/debug (u/format-color :red "Closing old connection pool for database %s ..." database-id))
  (driver-api/destroy-connection-pool! pool-spec)
  (ssh/close-tunnel! pool-spec))

(defonce ^:private ^{:doc "A map of our currently open connection pools, keyed by Database `:id`."}
  database-id->connection-pool
  (atom {}))

(defonce ^:private ^{:doc "A map of DB details hash values, keyed by Database `:id`."}
  database-id->jdbc-spec-hash
  (atom {}))

(mu/defn- jdbc-spec-hash
  "Computes a hash value for the JDBC connection spec based on `database`'s `:details` map, for the purpose of
  determining if details changed and therefore the existing connection pool needs to be invalidated."
  [{driver :engine, :keys [details], :as database} :- [:maybe :map]]
  (when (some? database)
    (hash (connection-details->spec driver details))))

(defn- set-pool!
  "Atomically update the current connection pool for Database `database` with `database-id`. Use this function instead
  of modifying database-id->connection-pool` directly because it properly closes down old pools in a thread-safe way,
  ensuring no more than one pool is ever open for a single database. Also modifies the [[database-id->jdbc-spec-hash]]
  map with the hash value of the given DB's details map."
  [database-id pool-spec-or-nil database]
  {:pre [(integer? database-id)]}
  (let [[old-id->pool] (if pool-spec-or-nil
                         (swap-vals! database-id->connection-pool assoc database-id pool-spec-or-nil)
                         (swap-vals! database-id->connection-pool dissoc database-id))]
    ;; if we replaced a different pool with the new pool that is different from the old one, destroy the old pool
    (when-let [old-pool-spec (get old-id->pool database-id)]
      (when-not (identical? old-pool-spec pool-spec-or-nil)
        (destroy-pool! database-id old-pool-spec))))
  ;; update the db details hash cache with the new hash value
  (swap! database-id->jdbc-spec-hash assoc database-id (jdbc-spec-hash database))
  nil)

(defn invalidate-pool-for-db!
  "Invalidates the connection pool for the given database by closing it and removing it from the cache."
  [database]
  (set-pool! (u/the-id database) nil nil))

(defn- log-ssh-tunnel-reconnect-msg! [db-id]
  (log/warn (u/format-color :red "ssh tunnel for database %s looks closed; marking pool invalid to reopen it" db-id))
  nil)

(defn- log-jdbc-spec-hash-change-msg! [db-id]
  (log/warn (u/format-color :yellow "Hash of database %s details changed; marking pool invalid to reopen it" db-id))
  nil)

(defn- log-password-expiry! [db-id]
  (log/warn (u/format-color :yellow "Password of database %s expired; marking pool invalid to reopen it" db-id))
  nil)

(defn db->pooled-connection-spec
  "Return a JDBC connection spec that includes a c3p0 `ComboPooledDataSource`. These connection pools are cached so we
  don't create multiple ones for the same DB."
  [db-or-id-or-spec]
  (when-let [db-id (u/id db-or-id-or-spec)]
    (driver-api/check-allowed-access! db-id))
  (cond
    ;; db-or-id-or-spec is a Database instance or an integer ID
    (u/id db-or-id-or-spec)
    (let [database-id (u/the-id db-or-id-or-spec)
          ;; we need the Database instance no matter what (in order to compare details hash with cached value)
          db          (or (when (driver-api/instance-of? :model/Database db-or-id-or-spec)
                            (driver-api/instance->metadata db-or-id-or-spec :metadata/database))
                          (when (= (:lib/type db-or-id-or-spec) :metadata/database)
                            db-or-id-or-spec)
                          (driver-api/with-metadata-provider database-id
                            (driver-api/database (driver-api/metadata-provider))))
          get-fn      (fn [db-id log-invalidation?]
                        (let [details (get @database-id->connection-pool db-id ::not-found)]
                          (cond
                            ;; for the audit db, we pass the datasource for the app-db. This lets us use fewer db
                            ;; connections with *application-db* and 1 less connection pool. Note: This data-source is
                            ;; not in [[database-id->connection-pool]].
                            (:is-audit db)
                            {:datasource (driver-api/data-source)}

                            (= ::not-found details)
                            nil

                            ;; details hash changed from what is cached; invalid
                            (let [curr-hash (get @database-id->jdbc-spec-hash db-id)
                                  new-hash  (jdbc-spec-hash db)]
                              (when (and (some? curr-hash) (not= curr-hash new-hash))
                                ;; the hash didn't match, but it's possible that a stale instance of `DatabaseInstance`
                                ;; was passed in (ex: from a long-running sync operation); fetch the latest one from
                                ;; our app DB, and see if it STILL doesn't match
                                (not= curr-hash (-> (t2/select-one [:model/Database :id :engine :details] :id database-id)
                                                    jdbc-spec-hash))))
                            (when log-invalidation?
                              (log-jdbc-spec-hash-change-msg! db-id))

                            (let [{:keys [password-expiry-timestamp]} details]
                              (and (int? password-expiry-timestamp)
                                   (<= password-expiry-timestamp (System/currentTimeMillis))))
                            (when log-invalidation?
                              (log-password-expiry! db-id))

                            (nil? (:tunnel-session details)) ; no tunnel in use; valid
                            details

                            (ssh/ssh-tunnel-open? details) ; tunnel in use, and open; valid
                            details

                            :else ; tunnel in use, and not open; invalid
                            (when log-invalidation?
                              (log-ssh-tunnel-reconnect-msg! db-id)))))]
      (or
       ;; we have an existing pool for this database, so use it
       (get-fn database-id true)
       ;; Even tho `set-pool!` will properly shut down old pools if two threads call this method at the same time, we
       ;; don't want to end up with a bunch of simultaneous threads creating pools only to have them destroyed the
       ;; very next instant. This will cause their queries to fail. Thus we should do the usual locking here and make
       ;; sure only one thread will be creating a pool at a given instant.
       (locking database-id->connection-pool
         (or
          ;; check if another thread created the pool while we were waiting to acquire the lock
          (get-fn database-id false)
          ;; create a new pool and add it to our cache, then return it
          (u/prog1 (create-pool! db)
            (set-pool! database-id <> db))))))

    ;; already a `clojure.java.jdbc` spec map
    (map? db-or-id-or-spec)
    db-or-id-or-spec

    ;; invalid. Throw Exception
    :else
    (throw (ex-info (tru "Not a valid Database/Database ID/JDBC spec")
                    ;; don't log the actual spec lest we accidentally expose credentials
                    {:input (class db-or-id-or-spec)}))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             metabase.driver impls                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn do-with-connection-spec-for-testing-connection
  "Impl for [[with-connection-spec-for-testing-connection]]."
  [driver details f]
  (let [details (update details :port #(or % (default-ssh-tunnel-target-port driver)))]
    (ssh/with-ssh-tunnel [details-with-tunnel details]
      (let [details-with-auth (driver.u/fetch-and-incorporate-auth-provider-details
                               driver
                               details-with-tunnel)
            spec (connection-details->spec driver details-with-auth)]
        (f spec)))))

(defmacro with-connection-spec-for-testing-connection
  "Execute `body` with an appropriate [[clojure.java.jdbc]] connection spec based on connection `details`. Handles SSH
  tunneling as needed and properly cleans up after itself.

    (with-connection-spec-for-testing-connection [jdbc-spec [:my-driver conn-details]]
      (do-something-with-spec jdbc-spec)"
  {:added "0.45.0", :style/indent 1}
  [[jdbc-spec-binding [driver details]] & body]
  `(do-with-connection-spec-for-testing-connection ~driver ~details (^:once fn* [~jdbc-spec-binding] ~@body)))

(defn can-connect-with-spec?
  "Can we connect to a JDBC database with [[clojure.java.jdbc]] `jdbc-spec` and run a simple query?"
  [jdbc-spec]
  (let [[first-row] (jdbc/query jdbc-spec ["SELECT 1"])
        [result]    (vals first-row)]
    (= result 1)))

(defn can-connect?
  "Default implementation of [[driver/can-connect?]] for SQL JDBC drivers. Checks whether we can perform a simple
  `SELECT 1` query."
  [driver details]
  (with-connection-spec-for-testing-connection [jdbc-spec [driver details]]
    (can-connect-with-spec? jdbc-spec)))
