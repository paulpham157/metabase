(ns ^:mb/driver-tests metabase-enterprise.sandbox.query-processor.middleware.row-level-restrictions-test
  (:require
   [clojure.core.async :as a]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [medley.core :as m]
   [metabase-enterprise.sandbox.query-processor.middleware.row-level-restrictions :as row-level-restrictions]
   [metabase-enterprise.test :as met]
   [metabase.api.common :as api]
   [metabase.driver :as driver]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.driver.util :as driver.u]
   [metabase.legacy-mbql.normalize :as mbql.normalize]
   [metabase.lib.core :as lib]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.lib.test-util :as lib.tu]
   [metabase.lib.util.match :as lib.util.match]
   [metabase.permissions.models.data-permissions :as data-perms]
   [metabase.permissions.models.permissions :as perms]
   [metabase.permissions.models.permissions-group :as perms-group]
   [metabase.query-processor :as qp]
   [metabase.query-processor.middleware.cache-test :as cache-test]
   [metabase.query-processor.middleware.permissions :as qp.perms]
   [metabase.query-processor.middleware.process-userland-query-test :as process-userland-query-test]
   [metabase.query-processor.pivot :as qp.pivot]
   [metabase.query-processor.preprocess :as qp.preprocess]
   [metabase.query-processor.store :as qp.store]
   [metabase.query-processor.streaming.test-util :as streaming.test-util]
   [metabase.query-processor.util :as qp.util]
   [metabase.query-processor.util.add-alias-info :as add]
   [metabase.request.core :as request]
   [metabase.test :as mt]
   [metabase.test.fixtures :as fixtures]
   [metabase.test.util :as tu]
   [metabase.util :as u]
   [metabase.util.honey-sql-2 :as h2x]
   [metabase.util.log :as log]
   [toucan2.core :as t2]))

(use-fixtures :once (fixtures/initialize :db))

(defn- identifier
  ([table-key]
   (qp.store/with-metadata-provider (mt/id)
     (sql.qp/->honeysql (or driver/*driver* :h2)
                        (lib.metadata/table (qp.store/metadata-provider) (mt/id table-key)))))

  ([table-key field-key]
   (let [field-id (mt/id table-key field-key)
         field-name (t2/select-one-fn :name :model/Field :id field-id)]
     (qp.store/with-metadata-provider (mt/id)
       (sql.qp/->honeysql
        (or driver/*driver* :h2)
        [:field field-id {::add/source-table (mt/id table-key)
                          ::add/source-alias field-name
                          ::add/desired-alias field-name}])))))

(defn- venues-category-mbql-gtap-def []
  {:query (mt/mbql-query venues)
   :remappings {:cat ["variable" [:field (mt/id :venues :category_id) nil]]}})

(defn- venues-price-mbql-gtap-def []
  {:query (mt/mbql-query venues)
   :remappings {:price ["variable" [:field (mt/id :venues :price) nil]]}})

(defn- checkins-user-mbql-gtap-def []
  {:query (mt/mbql-query checkins {:filter [:> $date "2014-01-01"]})
   :remappings {:user ["variable" [:field (mt/id :checkins :user_id) nil]]}})

(defn- format-honeysql [honeysql]
  (let [add-top-1000 (fn [honeysql]
                       (-> honeysql
                           (dissoc :select)
                           (assoc :select-top (into [[:inline 1000]] (:select honeysql)))))
        honeysql (cond-> honeysql
                   (= driver/*driver* :sqlserver)
                   add-top-1000

                       ;; SparkSQL has to have an alias source table (or at least our driver is written as if it has to
                       ;; have one.) HACK
                   (= driver/*driver* :sparksql)
                   (update :from (fn [[table]]
                                   [[table [(sql.qp/->honeysql
                                             :sparksql
                                             (h2x/identifier :table-alias @(resolve 'metabase.driver.sparksql/source-table-alias)))]]])))]
    (first (sql.qp/format-honeysql driver/*driver* honeysql))))

(defn- venues-category-native-gtap-def []
  (driver/with-driver (or driver/*driver* :h2)
    (assert (driver.u/supports? driver/*driver* :native-parameters (mt/db)))
    {:query (mt/native-query
              {:query
               (format-honeysql
                {:select [:*]
                 :from [[(identifier :venues)]]
                 :where [:=
                         (identifier :venues :category_id)
                         [:raw "{{cat}}"]]
                 :order-by [[(identifier :venues :id) :asc]]})

               :template_tags
               {:cat {:name "cat" :display_name "cat" :type "number" :required true}}})
     :remappings {:cat ["variable" ["template-tag" "cat"]]}}))

(defn- parameterized-sql-with-join-gtap-def []
  (driver/with-driver (or driver/*driver* :h2)
    (assert (driver.u/supports? driver/*driver* :native-parameters (mt/db)))
    {:query (mt/native-query
              {:query
               (format-honeysql
                {:select [[(identifier :checkins :id)]
                          [(identifier :checkins :user_id)]
                          [(identifier :venues :name)]
                          [(identifier :venues :category_id)]]
                 :from [[(identifier :checkins)]]
                 :left-join [[(identifier :venues)]
                             [:= (identifier :checkins :venue_id) (identifier :venues :id)]]
                 :where [:=
                         (identifier :checkins :user_id)
                         [:raw "{{user}}"]]
                 :order-by [[(identifier :checkins :id) :asc]]})

               :template_tags
               {"user" {:name "user"
                        :display-name "User ID"
                        :type :number
                        :required true}}})
     :remappings {:user ["variable" ["template-tag" "user"]]}}))

(defn- venue-names-native-gtap-def []
  (driver/with-driver (or driver/*driver* :h2)
    {:query (mt/native-query
              {:query
               (format-honeysql
                {:select [[(identifier :venues :id)]
                          [(identifier :venues :name)]]
                 :from [[(identifier :venues)]]
                 :order-by [[(identifier :venues :id) :asc]]})})}))

(defn- run-venues-count-query []
  (mt/format-rows-by
   [int]
   (mt/rows
    (mt/run-mbql-query venues {:aggregation [[:count]]}))))

(defn- run-checkins-count-broken-out-by-price-query []
  (mt/format-rows-by
   [#(some-> % int) int]
   (mt/rows
    (mt/run-mbql-query checkins
      {:aggregation [[:count]]
       :order-by [[:asc $venue_id->venues.price]]
       :breakout [$venue_id->venues.price]}))))

(deftest ^:parallel all-table-ids-test
  (testing (str "make sure that `all-table-ids` can properly find all Tables in the query, even in cases where a map "
                "has a `:source-table` and some of its children also have a `:source-table`"))
  (is (= (mt/$ids nil
           #{$$checkins $$venues $$users $$categories})
         (#'row-level-restrictions/all-table-ids
          (mt/mbql-query nil
            {:source-table $$checkins
             :joins [{:source-table $$venues}
                     {:source-query {:source-table $$users
                                     :joins [{:source-table $$categories}]}}]})))))

;; TODO -- #19754 adds [[mt/remove-source-metadata]] that can be used here (once it gets merged)
(defn- remove-metadata [m]
  (lib.util.match/replace m
    (_ :guard (every-pred map? :source-metadata))
    (remove-metadata (dissoc &match :source-metadata))))

(defn- apply-row-level-permissions [query]
  (-> (qp.store/with-metadata-provider (mt/id)
        (#'row-level-restrictions/apply-sandboxing (mbql.normalize/normalize query)))
      remove-metadata))

(deftest middleware-test
  (testing "Make sure the middleware does the correct transformation given the GTAPs we have"
    (met/with-gtaps! {:gtaps {:checkins (checkins-user-mbql-gtap-def)
                              :venues (dissoc (venues-price-mbql-gtap-def) :query)}
                      :attributes {"user" 5, "price" 1}}
      (testing "Should add a filter for attributes-only GTAP"
        (is (=? (mt/query checkins
                  {:type :query
                   :query {:source-query {:source-table $$checkins
                                          :fields [$id $date $user_id $venue_id]
                                          :filter [:and
                                                                          ;; This still gets :default bucketing!
                                                                          ;; auto-bucket-datetimes puts :day bucketing
                                                                          ;; on both parts of this filter, since it's
                                                                          ;; matching a YYYY-mm-dd string. Then
                                                                          ;; optimize-temporal-filters sees that the
                                                                          ;; :type/Date column already has :day
                                                                          ;; granularity, and switches both to :default
                                                   [:> !default.date [:absolute-datetime
                                                                      #t "2014-01-01"
                                                                      :default]]
                                                   [:=
                                                    $user_id
                                                    [:value 5 {:base_type :type/Integer
                                                               :semantic_type :type/FK
                                                               :database_type "INTEGER"
                                                               :name "USER_ID"}]]]
                                          ::row-level-restrictions/gtap? true
                                          :query-permissions/gtapped-table $$checkins}
                           :joins [{:source-query
                                    {:source-table $$venues
                                     :fields [$venues.id $venues.name $venues.category_id
                                              $venues.latitude $venues.longitude $venues.price]
                                     :filter [:=
                                              $venues.price
                                              [:value 1 {:base_type :type/Integer
                                                         :semantic_type :type/Category
                                                         :database_type "INTEGER"
                                                         :name "PRICE"}]]
                                     ::row-level-restrictions/gtap? true
                                     :query-permissions/gtapped-table $$venues}
                                    :alias "v"
                                    :strategy :left-join
                                    :condition [:= $venue_id &v.venues.id]}]
                           :aggregation [[:count]]}

                   ::row-level-restrictions/original-metadata [{:base_type :type/Integer
                                                                :semantic_type :type/Quantity
                                                                :name "count"
                                                                :display_name "Count"
                                                                :source :aggregation
                                                                :field_ref [:aggregation 0]}]
                   :query-permissions/perms {:gtaps {:perms/view-data {(mt/id :checkins) :unrestricted}
                                                     :perms/create-queries {(mt/id :checkins) :query-builder
                                                                            (mt/id :venues) :query-builder}}}})
                (apply-row-level-permissions
                 (mt/mbql-query checkins
                   {:aggregation [[:count]]
                    :joins [{:source-table $$venues
                             :alias "v"
                             :strategy :left-join
                             :condition [:= $venue_id &v.venues.id]}]}))))))))

(deftest middleware-native-query-test
  (testing "Make sure the middleware does the correct transformation given the GTAPs we have"
    (testing "Should substitute appropriate value in native query"
      (met/with-gtaps! {:gtaps {:venues (venues-category-native-gtap-def)}
                        :attributes {"cat" 50}}
        (is (=? (mt/query nil
                  {:database (mt/id)
                   :type :query
                   :query {:aggregation [[:count]]
                           :source-query {:native (str "SELECT * FROM \"PUBLIC\".\"VENUES\" "
                                                       "WHERE \"PUBLIC\".\"VENUES\".\"CATEGORY_ID\" = 50 "
                                                       "ORDER BY \"PUBLIC\".\"VENUES\".\"ID\" ASC")
                                          :query-permissions/gtapped-table $$venues
                                          :params []}}

                   ::row-level-restrictions/original-metadata [{:base_type :type/Integer
                                                                :semantic_type :type/Quantity
                                                                :name "count"
                                                                :display_name "Count"
                                                                :source :aggregation
                                                                :field_ref [:aggregation 0]}]
                   :query-permissions/perms {:gtaps {:perms/create-queries :query-builder-and-native}}})
                (apply-row-level-permissions
                 (mt/mbql-query venues
                   {:aggregation [[:count]]}))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                END-TO-END TESTS                                                |
;;; +----------------------------------------------------------------------------------------------------------------+

;; ->honeysql is not implemented for mongo
(defn- e2e-test-drivers []
  (into #{}
        (filter #(isa? driver/hierarchy % :sql))
        (mt/normal-drivers-with-feature :nested-queries)))

(deftest e2e-test-1
  (mt/test-drivers (e2e-test-drivers)
    (testing "Basic test around querying a table by a user with segmented only permissions and a GTAP question that is a native query"
      (met/with-gtaps! {:gtaps {:venues (venues-category-native-gtap-def)}, :attributes {"cat" 50}}
        (is (= [[10]]
               (run-venues-count-query)))))))

(deftest e2e-test-2
  (mt/test-drivers (e2e-test-drivers)
    (testing "Basic test around querying a table by a user with segmented only permissions and a GTAP question that is MBQL"
      (met/with-gtaps! {:gtaps {:venues (venues-category-mbql-gtap-def)}, :attributes {"cat" 50}}
        (is (= [[10]]
               (run-venues-count-query)))))))

(deftest e2e-test-3
  (mt/test-drivers (e2e-test-drivers)
    (testing (str "When processing a query that requires a user attribute and that user attribute isn't there, throw an "
                  "exception letting the user know it's missing")
      (met/with-gtaps! {:gtaps {:venues (venues-category-mbql-gtap-def)}, :attributes {"something_random" 50}}
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Query requires user attribute `cat`"
             (mt/run-mbql-query venues {:aggregation [[:count]]})))))))

(deftest e2e-test-4
  (mt/test-drivers (e2e-test-drivers)
    (testing (str "When processing a query that requires a user attribute and that user attribute is nil, throw an "
                  "exception letting the user know it's missing")
      (met/with-gtaps! {:gtaps {:venues (venues-category-mbql-gtap-def)}, :attributes {"cat" nil}}
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Query requires user attribute `cat`"
             (mt/run-mbql-query venues {:aggregation [[:count]]})))))))

(deftest e2e-test-5
  (mt/test-drivers (e2e-test-drivers)
    (testing "Another basic test, same as above, but with a numeric string that needs to be coerced"
      (met/with-gtaps! {:gtaps {:venues (venues-category-mbql-gtap-def)}, :attributes {"cat" "50"}}
        (is (= [[10]]
               (run-venues-count-query)))))))

(deftest e2e-test-6
  (mt/test-drivers (e2e-test-drivers)
    (testing "Another basic test, this one uses a stringified float for the login attribute"
      (met/with-gtaps! {:gtaps {:venues {:query (mt/mbql-query venues)
                                         :remappings {:cat ["variable" [:field (mt/id :venues :latitude) nil]]}}}
                        :attributes {"cat" "34.1018"}}
        (is (= [[3]]
               (run-venues-count-query)))))))

(deftest e2e-test-7
  (mt/test-drivers (e2e-test-drivers)
    (testing "Tests that users can have a different parameter name in their query than they have in their user attributes"
      (met/with-gtaps! {:gtaps {:venues {:query (:query (venues-category-native-gtap-def))
                                         :remappings {:something.different ["variable" ["template-tag" "cat"]]}}}
                        :attributes {"something.different" 50}}
        (is (= [[10]]
               (run-venues-count-query)))))))

(deftest e2e-test-8
  (mt/test-drivers (e2e-test-drivers)
    (testing "Make sure that you can still use a SQL-based GTAP without needing to have SQL read perms for the Database"
      (met/with-gtaps! {:gtaps {:venues (venue-names-native-gtap-def)}}
        (is (= [[1 "Red Medicine"] [2 "Stout Burgers & Beers"]]
               (mt/formatted-rows
                [int str]
                (mt/run-mbql-query venues
                  {:limit 2, :order-by [[:asc [:field (mt/id :venues :id)]]]}))))))))

(deftest e2e-test-9
  (mt/test-drivers (e2e-test-drivers)
    (testing "When no card_id is included in the GTAP, should default to a query against the table, with the GTAP criteria applied"
      (met/with-gtaps! {:gtaps {:venues (dissoc (venues-category-mbql-gtap-def) :query)}
                        :attributes {"cat" 50}}
        (is (= [[10]]
               (run-venues-count-query)))))))

(deftest e2e-test-10
  (mt/test-drivers (e2e-test-drivers)
    (testing "Same test as above but make sure we coerce a numeric string correctly"
      (met/with-gtaps! {:gtaps {:venues (dissoc (venues-category-mbql-gtap-def) :query)}
                        :attributes {"cat" "50"}}
        (is (= [[10]]
               (run-venues-count-query)))))))

(deftest e2e-test-11
  (mt/test-drivers (e2e-test-drivers)
    (testing "Admins always bypass sandboxes, even if they are in a sandboxed group"
      (met/with-gtaps-for-user! :crowberto {:gtaps {:venues (venues-category-mbql-gtap-def)}
                                            :attributes {"cat" 50}}
        (is (= [[100]]
               (run-venues-count-query)))))))

(deftest e2e-test-12
  (mt/test-drivers (e2e-test-drivers)
    (testing "A non-admin impersonating an admin (i.e. when running a public or embedded question) should always bypass sandboxes (#30535)"
      (met/with-gtaps-for-user! :rasta {:gtaps {:venues (venues-category-mbql-gtap-def)}
                                        :attributes {"cat" 50}}
        (mt/with-test-user :rasta
          (request/as-admin
            (is (= [[100]]
                   (run-venues-count-query)))))))))

(deftest e2e-test-13
  (mt/test-drivers (e2e-test-drivers)
    (testing "Users with view access to the related collection should bypass segmented permissions"
      (mt/with-temp-copy-of-db
        (mt/with-temp [:model/Collection collection {}
                       :model/Card card {:collection_id (u/the-id collection)}]
          (mt/with-group [group]
            (mt/with-no-data-perms-for-all-users!
              (data-perms/set-database-permission! (perms-group/all-users) (mt/id) :perms/view-data :unrestricted)
              (data-perms/set-database-permission! (perms-group/all-users) (mt/id) :perms/create-queries :no)
              (perms/grant-collection-read-permissions! group collection)
              (mt/with-test-user :rasta
                (binding [qp.perms/*card-id* (u/the-id card)]
                  (is (= 1
                         (count (mt/rows
                                 (qp/process-query {:database (mt/id)
                                                    :type :query
                                                    :query {:source-table (mt/id :venues)
                                                            :limit 1}}))))))))))))))

(deftest e2e-test-14
  (mt/test-drivers (e2e-test-drivers)
    (testing (str "This test isn't covering a row level restrictions feature, but rather checking it it doesn't break "
                  "querying of a card as a nested query. Part of the row level perms check is looking at the table (or "
                  "card) to see if row level permissions apply. This was broken when it wasn't expecting a card and "
                  "only expecting resolved source-tables")
      (mt/with-temp [:model/Card card {:dataset_query (mt/mbql-query venues)}]
        (let [query (mt/mbql-query nil
                      {:source-table (format "card__%s" (u/the-id card))
                       :aggregation [["count"]]})]
          (mt/with-test-user :rasta
            (mt/with-native-query-testing-context query
              (is (= [[100]]
                     (mt/format-rows-by
                      [int]
                      (mt/rows (qp/process-query query))))))))))))

;; Test that we can follow FKs to related tables and breakout by columns on those related tables. This test has
;; several things wrapped up which are detailed below

(defn- row-level-restrictions-fk-drivers
  "Drivers to test row-level restrictions against foreign keys with."
  []
  (mt/normal-drivers-with-feature :nested-queries :left-join))

(defn- row-level-restrictions-fk-sql-drivers
  "SQL drivers to test row-level restrictions against foreign keys with."
  []
  (into #{} (filter #(isa? driver/hierarchy % :sql)) (row-level-restrictions-fk-drivers)))

(deftest e2e-fks-test
  (mt/test-drivers (row-level-restrictions-fk-drivers)
    (testing (str "1 - Creates a GTAP filtering question, looking for any checkins happening on or after 2014\n"
                  "2 - Apply the `user` attribute, looking for only our user (i.e. `user_id` =  5)\n"
                  "3 - Checkins are related to Venues, query for checkins, grouping by the Venue's price\n"
                  "4 - Order by the Venue's price to ensure a predictably ordered response")
      (met/with-gtaps! {:gtaps {:checkins (checkins-user-mbql-gtap-def)
                                :venues nil}
                        :attributes {"user" 5}}
        (is (= [[1 10] [2 36] [3 4] [4 5]]
               (run-checkins-count-broken-out-by-price-query)))))))

(deftest e2e-fks-test-2
  (mt/test-drivers (row-level-restrictions-fk-drivers)
    (testing (str "Test that we're able to use a GTAP for an FK related table. For this test, the user has segmented "
                  "permissions on checkins and venues, so we need to apply a GTAP to the original table (checkins) in "
                  "addition to the related table (venues). This test uses a GTAP question for both tables")
      (met/with-gtaps! {:gtaps {:checkins (checkins-user-mbql-gtap-def)
                                :venues (venues-price-mbql-gtap-def)}
                        :attributes {"user" 5, "price" 1}}
        (is (= #{[nil 45] [1 10]}
               (set (run-checkins-count-broken-out-by-price-query))))))))

(deftest e2e-fks-test-3
  (mt/test-drivers (row-level-restrictions-fk-drivers)
    (testing "Test that the FK related table can be a \"default\" GTAP, i.e. a GTAP where the `card_id` is nil"
      (met/with-gtaps! {:gtaps {:checkins (checkins-user-mbql-gtap-def)
                                :venues (dissoc (venues-price-mbql-gtap-def) :query)}
                        :attributes {"user" 5, "price" 1}}
        (is (= #{[nil 45] [1 10]}
               (set (run-checkins-count-broken-out-by-price-query))))))))

(deftest e2e-fks-test-4
  (mt/test-drivers (row-level-restrictions-fk-drivers)
    (testing (str "Test that we have multiple FK related, segmented tables. This test has checkins with a GTAP "
                  "question with venues and users having the default GTAP and segmented permissions")
      (met/with-gtaps! {:gtaps {:checkins (checkins-user-mbql-gtap-def)
                                :venues (dissoc (venues-price-mbql-gtap-def) :query)
                                :users {:remappings {:user ["variable" [:field (mt/id :users :id) nil]]}}}
                        :attributes {"user" 5, "price" 1}}
        (is (= #{[nil "Quentin Sören" 45] [1 "Quentin Sören" 10]}
               (set
                (mt/format-rows-by
                 [#(when % (int %)) str int]
                 (mt/rows
                  (mt/run-mbql-query checkins
                    {:aggregation [[:count]]
                     :order-by [[:asc $venue_id->venues.price]]
                     :breakout [$venue_id->venues.price $user_id->users.name]}))))))))))

(defn- run-query-returning-remark! [run-query-fn]
  (let [remark (atom nil)
        orig qp.util/query->remark]
    (with-redefs [qp.util/query->remark (fn [driver outer-query]
                                          (u/prog1 (orig driver outer-query)
                                            (reset! remark <>)))]
      (let [results (run-query-fn)]
        (or (some-> @remark (str/replace #"queryHash: \w+" "queryHash: <hash>"))
            (log/infof "NO REMARK FOUND:\n %s" (u/pprint-to-str 'red results))
            (throw (ex-info "No remark found!" {:results results})))))))

(deftest remark-test
  (testing "make sure GTAP queries still include ID of user who ran them in the remark"
    (met/with-gtaps! {:gtaps {:venues (venues-category-mbql-gtap-def)}
                      :attributes {"cat" 50}}
      (is (= (format "Metabase:: userID: %d queryType: MBQL queryHash: <hash>" (mt/user->id :rasta))
             (run-query-returning-remark!
              (fn []
                (mt/user-http-request :rasta :post "dataset" (mt/mbql-query venues {:aggregation [[:count]]})))))))))

(deftest breakouts-test
  (mt/test-drivers (row-level-restrictions-fk-sql-drivers)
    (testing "Make sure that if a GTAP is in effect we can still do stuff like breakouts (#229)"
      (met/with-gtaps! {:gtaps {:venues (venues-category-native-gtap-def)}
                        :attributes {"cat" 50}}
        (is (= [[1 6] [2 4]]
               (mt/format-rows-by
                [int int]
                (mt/rows
                 (mt/run-mbql-query venues
                   {:aggregation [[:count]]
                    :breakout [$price]})))))))))

(deftest sql-with-join-test
  (mt/test-drivers (into #{}
                         (filter #(driver.u/supports? % :parameterized-sql nil))
                         (row-level-restrictions-fk-sql-drivers))
    (testing (str "If we use a parameterized SQL GTAP that joins a Table the user doesn't have access to, does it "
                  "still work? (EE #230) If we pass the query in directly without anything that would require nesting "
                  "it, it should work")
      (is (= [[2 1]
              [72 1]]
             (mt/format-rows-by
              [int int identity int]
              (mt/rows
               (met/with-gtaps! {:gtaps {:checkins (parameterized-sql-with-join-gtap-def)}
                                 :attributes {"user" 1}}
                 (mt/run-mbql-query checkins
                   {:order-by [[:asc $id]]
                    :limit 2})))))))))

(deftest sql-with-join-test-2
  (mt/test-drivers (into #{}
                         (filter #(driver.u/supports? % :parameterized-sql nil))
                         (row-level-restrictions-fk-sql-drivers))
    (testing (str "If we use a parameterized SQL GTAP that joins a Table the user doesn't have access to, does it "
                  "still work? (EE #230) If we pass the query in directly without anything that would require nesting "
                  "it, it should work")
      (is (= [[2 1]
              [72 1]]
             (mt/format-rows-by
              [int int identity int]
              (mt/rows
               (met/with-gtaps! {:gtaps {:checkins (parameterized-sql-with-join-gtap-def)}
                                 :attributes {"user" 1}}
                 (mt/run-mbql-query checkins
                   {:order-by [[:asc $id]]
                    :limit 2})))))))))

(deftest correct-metadata-test
  (testing (str "We should return the same metadata as the original Table when running a query against a sandboxed "
                "Table (EE #390)\n")
    (let [cols (fn []
                 (mt/cols
                  (mt/run-mbql-query venues
                    {:order-by [[:asc $id]]
                     :limit 2})))
          original-cols (cols)
          ;; `with-gtaps!` copies the test DB so this function will update the IDs in `original-cols` so they'll match
          ;; up with the current copy
          expected-cols (fn []
                          (for [col original-cols
                                :let [id (mt/id :venues (keyword (u/lower-case-en (:name col))))]]
                            (-> col
                                (assoc :id id
                                       :table_id (mt/id :venues)
                                       :field_ref [:field id nil])
                                (dissoc :fk_target_field_id))))]
      (testing "A query with a simple attributes-based sandbox should have the same metadata"
        (met/with-gtaps! {:gtaps {:venues (dissoc (venues-category-mbql-gtap-def) :query)}
                          :attributes {"cat" 50}}
          (is (=? (expected-cols)
                  (cols)))))

      (testing "A query with an equivalent MBQL query sandbox should have the same metadata"
        (met/with-gtaps! {:gtaps {:venues (venues-category-mbql-gtap-def)}
                          :attributes {"cat" 50}}
          (is (=? (expected-cols)
                  (cols)))))

      (testing "A query with an equivalent native query sandbox should have the same metadata"
        (met/with-gtaps! {:gtaps {:venues {:query (mt/native-query
                                                    {:query
                                                     (str "SELECT ID, NAME, CATEGORY_ID, LATITUDE, LONGITUDE, PRICE "
                                                          "FROM VENUES "
                                                          "WHERE CATEGORY_ID = {{cat}}")

                                                     :template_tags
                                                     {:cat {:name "cat" :display_name "cat" :type "number" :required true}}})
                                           :remappings {:cat ["variable" ["template-tag" "cat"]]}}}
                          :attributes {"cat" 50}}
          (is (=? (expected-cols)
                  (cols)))))

      (testing (str "If columns are added/removed/reordered we should still merge in metadata for the columns we're "
                    "able to match from the original Table")
        (met/with-gtaps! {:gtaps {:venues {:query (mt/native-query
                                                    {:query
                                                     (str "SELECT NAME, ID, LONGITUDE, PRICE, 1 AS ONE "
                                                          "FROM VENUES "
                                                          "WHERE CATEGORY_ID = {{cat}}")

                                                     :template_tags
                                                     {:cat {:name "cat" :display_name "cat" :type "number" :required true}}})
                                           :remappings {:cat ["variable" ["template-tag" "cat"]]}}}
                          :attributes {"cat" 50}}
          (let [[id-col name-col _ _ longitude-col price-col] (expected-cols)]
            (is (=? [name-col id-col longitude-col price-col]
                    (cols)))))))))

(deftest sql-with-joins-test
  (testing "Should be able to use a Saved Question with no source Metadata as a GTAP (EE #525)"
    (met/with-gtaps! (mt/$ids
                       {:gtaps {:venues {:query (mt/native-query
                                                  {:query (str "SELECT DISTINCT VENUES.* "
                                                               "FROM VENUES "
                                                               "LEFT JOIN CHECKINS"
                                                               "       ON CHECKINS.VENUE_ID = VENUES.ID "
                                                               "WHERE CHECKINS.USER_ID IN ({{sandbox}})")
                                                   :template-tags {"sandbox"
                                                                   {:name "sandbox"
                                                                    :display-name "Sandbox"
                                                                    :type :text}}})
                                         :remappings {"user_id" [:variable [:template-tag "sandbox"]]}}
                                :checkins {:remappings {"user_id" [:dimension $checkins.user_id]}}}
                        :attributes {"user_id" 1}})
      (is (= [[2 "2014-09-18T00:00:00Z" 1 31 31 "Bludso's BBQ" 5 33.8894 -118.207 2]
              [72 "2015-04-18T00:00:00Z" 1 1 1 "Red Medicine" 4 10.0646 -165.374 3]
              [80 "2013-12-27T00:00:00Z" 1 99 99 "Golden Road Brewing" 10 34.1505 -118.274 2]]
             (mt/rows
              (mt/run-mbql-query checkins
                {:joins [{:fields :all
                          :source-table $$venues
                          :condition [:= $venue_id &Venue.venues.id]
                          :alias "Venue"}]
                 :order-by [[:asc $id]]
                 :limit 3})))))))

(deftest run-sql-queries-to-infer-columns-test
  (testing "Run SQL queries to infer the columns when used as GTAPS (#13716)\n"
    (testing "Should work with SQL queries that return less columns than there were in the original Table\n"
      (met/with-gtaps! (mt/$ids
                         {:gtaps      {:venues   {:query      (mt/native-query
                                                                {:query         (str "SELECT * "
                                                                                     "FROM VENUES "
                                                                                     "WHERE VENUES.ID IN ({{sandbox}})")
                                                                 :template-tags {"sandbox"
                                                                                 {:name         "sandbox"
                                                                                  :display-name "Sandbox"
                                                                                  :type         :text}}})
                                                  :remappings {"venue_id" [:variable [:template-tag "sandbox"]]}}
                                       :checkins {}}
                          :attributes {"venue_id" 1}})
        (let [venues-gtap-card-id (t2/select-one-fn :card_id :model/GroupTableAccessPolicy
                                                    :group_id (:id &group)
                                                    :table_id (mt/id :venues))]
          (is (integer? venues-gtap-card-id))
          (testing "GTAP Card should not yet current have result_metadata"
            (is (= nil
                   (t2/select-one-fn :result_metadata :model/Card :id venues-gtap-card-id))))
          (testing "Should be able to run the query"
            (is (= [[1 "Red Medicine" 1 "Red Medicine" 4 10.0646 -165.374 3]]
                   (mt/rows
                    (mt/run-mbql-query venues
                      {:fields [$id $name] ; joined fields get appended automatically because we specify :all :below
                       :joins [{:fields :all
                                :source-table $$venues
                                :condition [:= $id &Venue.id]
                                :alias "Venue"}]
                       :order-by [[:asc $id]]
                       :limit 3})))))
          (testing "After running the query the first time, result_metadata should have been saved for the GTAP Card"
            (is (=? [{:name "ID"
                      :base_type :type/BigInteger
                      :display_name "ID"}
                     {:name         "NAME"
                      :base_type    :type/Text
                      :display_name "Name"}
                     {:name "CATEGORY_ID"}
                     {:name "LATITUDE"}
                     {:name "LONGITUDE"}
                     {:name "PRICE"}]
                    (t2/select-one-fn :result_metadata :model/Card :id venues-gtap-card-id)))))))))

(defn- do-with-sql-gtap! [sql f]
  (met/with-gtaps! (mt/$ids
                     {:gtaps {:venues {:query (mt/native-query
                                                {:query sql
                                                 :template-tags {"sandbox"
                                                                 {:name "sandbox"
                                                                  :display-name "Sandbox"
                                                                  :type :text}}})
                                       :remappings {"venue_id" [:variable [:template-tag "sandbox"]]}}
                              :checkins {}}
                      :attributes {"venue_id" 1}})
    (let [venues-gtap-card-id (t2/select-one-fn :card_id :model/GroupTableAccessPolicy
                                                :group_id (:id &group)
                                                :table_id (mt/id :venues))]
      (is (integer? venues-gtap-card-id))
      (testing "GTAP Card should not yet current have result_metadata"
        (is (= nil
               (t2/select-one-fn :result_metadata :model/Card :id venues-gtap-card-id))))
      (f {:run-query (fn []
                       (mt/run-mbql-query venues
                         {:fields [$id $name]
                          :joins [{:fields :all
                                   :source-table $$venues
                                   :condition [:= $id &Venue.id]
                                   :alias "Venue"}]
                          :order-by [[:asc $id]]
                          :limit 3}))}))))

(deftest run-queries-to-infer-columns-error-on-column-type-changes-test
  (testing "If we have to run a query to infer columns (see above) we should validate column constraints (#14099)\n"
    (testing "Removing columns should be ok."
      (do-with-sql-gtap!
       (str "SELECT * "
            "FROM VENUES "
            "WHERE ID IN ({{sandbox}})")
       (fn [{:keys [run-query]}]
         (testing "Query without weird stuff going on should work"
           (is (= [[1 "Red Medicine" 1 "Red Medicine" 4 10.0646 -165.374 3]]
                  (mt/rows (run-query))))))))))

(deftest run-queries-to-infer-columns-error-on-column-type-changes-test-2
  (testing "If we have to run a query to infer columns (see above) we should validate column constraints (#14099)\n"
    (testing "Don't allow people to change the types of columns in the original Table"
      (do-with-sql-gtap!
       (str "SELECT ID, 100 AS NAME "
            "FROM VENUES "
            "WHERE ID IN ({{sandbox}})")
       (fn [{:keys [run-query]}]
         (testing "Should throw an Exception when running the query"
           (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"Sandbox Questions can't return columns that have different types than the Table they are sandboxing"
                (run-query)))))))))

(deftest run-queries-to-infer-columns-error-on-column-type-changes-test-3
  (testing "If we have to run a query to infer columns (see above) we should validate column constraints (#14099)\n"
    (testing "Don't allow people to change the types of columns in the original Table"
      (testing "Should be ok if you change the type of the column to a *SUBTYPE* of the original Type"
        (do-with-sql-gtap!
         (str "SELECT cast(ID AS bigint) AS ID, NAME, CATEGORY_ID, LATITUDE, LONGITUDE, PRICE "
              "FROM VENUES "
              "WHERE ID IN ({{sandbox}})")
         (fn [{:keys [run-query]}]
           (is (= [[1 "Red Medicine" 1 "Red Medicine" 4 10.0646 -165.374 3]]
                  (mt/rows (run-query))))))))))

(deftest dont-cache-sandboxes-test
  (cache-test/with-mock-cache! [save-chan]
    (met/with-gtaps! {:gtaps {:venues (venues-category-mbql-gtap-def)}
                      :attributes {"cat" 50}}
      (letfn [(run-query []
                (qp/process-query (assoc (mt/mbql-query venues {:aggregation [[:count]]})
                                         :cache-strategy {:type :ttl
                                                          :multiplier 60
                                                          :avg-execution-ms 10
                                                          :min-duration-ms 0})))]
        (testing "Run the query, should not be cached"
          (let [result (run-query)]
            (is (= nil
                   (:cached (:cache/details result))))
            (is (= [[10]]
                   (mt/rows result)))))
        (testing "Cache entry should be saved within 5 seconds"
          (let [[_ chan] (a/alts!! [save-chan (a/timeout 5000)])]
            (is (= save-chan
                   chan))))

        (testing "Run it again, should be cached"
          (let [result (run-query)]
            (is (true?
                 (:cached (:cache/details result))))
            (is (= [[10]]
                   (mt/rows result)))))
        (testing "Run the query with different User attributes, should not get the cached result"
          (met/with-user-attributes! :rasta {"cat" 40}
            ;; re-bind current user so updated attributes come in to effect
            (mt/with-test-user :rasta
              (is (= {"cat" 40}
                     (:login_attributes @api/*current-user*)))
              (let [result (run-query)]
                (is (= nil
                       (:cached (:cache/details result))))
                (is (= [[9]]
                       (mt/rows result)))))))))))

(deftest remapped-fks-test
  (testing "Sandboxing should work with remapped FK columns (#14629)"
    (mt/dataset test-data
      ;; set up GTAP against reviews
      (met/with-gtaps! (mt/$ids reviews
                         {:gtaps {:reviews {:remappings {"user_id" [:dimension $product_id]}}}
                          :attributes {"user_id" 1}})
        ;; grant full data perms for products
        (data-perms/set-table-permission! &group (mt/id :products) :perms/create-queries :query-builder)
        (data-perms/set-database-permission! &group (mt/id) :perms/view-data :unrestricted)
        (mt/with-test-user :rasta
          (testing "Sanity check: should be able to query products"
            (is (=? {:status :completed}
                    (mt/run-mbql-query products {:limit 10}))))
          (testing "Try the sandbox without remapping in place"
            (let [result (mt/run-mbql-query reviews {:order-by [[:asc $id]]})]
              (is (=? {:status :completed
                       :row_count 8}
                      result))
              (is (= [1
                      1
                      "christ"
                      5
                      (str "Ad perspiciatis quis et consectetur. Laboriosam fuga voluptas ut et modi ipsum. Odio et "
                           "eum numquam eos nisi. Assumenda aut magnam libero maiores nobis vel beatae officia.")
                      "2018-05-15T20:25:48.517Z"]
                     (first (mt/rows result))))))
          (testing "Ok, add remapping and it should still work"
            (qp.store/with-metadata-provider (lib.tu/remap-metadata-provider
                                              (mt/application-database-metadata-provider (mt/id))
                                              (mt/id :reviews :product_id)
                                              (mt/id :products :title))
              (let [result (mt/run-mbql-query reviews {:order-by [[:asc $id]]})]
                (is (=? {:status :completed
                         :row_count 8}
                        result))
                (is (= [1
                        1
                        "christ"
                        5
                        (str "Ad perspiciatis quis et consectetur. Laboriosam fuga voluptas ut et modi ipsum. Odio et "
                             "eum numquam eos nisi. Assumenda aut magnam libero maiores nobis vel beatae officia.")
                        "2018-05-15T20:25:48.517Z"
                        "Rustic Paper Wallet"] ; <- Includes the remapped column
                       (first (mt/rows result))))))))))))

(deftest sandboxing-linked-table-perms
  (testing "Sandboxing based on a column in a linked table should work even if the user doesn't have self-service query
           permissions for the linked table (#15105)"
    (mt/dataset test-data
      (met/with-gtaps! (mt/$ids orders
                         {:gtaps {:orders {:remappings {"user_id" [:dimension $user_id->people.id]}}}
                          :attributes {"user_id" 1}})
        (mt/with-test-user :rasta
          (is (= [11]
                 (-> (mt/run-mbql-query orders {:aggregation [[:count]]})
                     mt/rows
                     first))))))))

(deftest drill-thru-on-joins-test
  (testing "should work on questions with joins, with sandboxed target table, where target fields cannot be filtered (#13642)"
    ;; Sandbox ORDERS and PRODUCTS
    (mt/dataset test-data
      (met/with-gtaps! (mt/$ids nil
                         {:gtaps {:orders {:remappings {:user_id [:dimension $orders.user_id]}}
                                  :products {:remappings {:user_cat [:dimension $products.category]}}}
                          :attributes {:user_id "1"
                                       :user_cat "Widget"}})
        ;; create query with joins
        (let [query (mt/mbql-query orders
                      {:aggregation [[:count]]
                       :breakout [&products.products.category]
                       :joins [{:fields :all
                                :source-table $$products
                                :condition [:= $product_id &products.products.id]
                                :alias "products"}]
                       :limit 10})]
          (testing "Should be able to run the query"
            (is (=? {:data {:rows [[nil 5] ["Widget" 6]]}
                     :status :completed
                     :row_count 2}
                    (qp/process-query query))))
          (testing "should be able to save the query as a Card and run it"
            (mt/with-temp [:model/Collection {collection-id :id} {}
                           :model/Card {card-id :id} {:dataset_query query, :collection_id collection-id}]
              (perms/grant-collection-read-permissions! &group collection-id)
              (is (=? {:data {:rows [[nil 5] ["Widget" 6]]}
                       :status "completed"
                       :row_count 2}
                      (mt/user-http-request :rasta :post 202 (format "card/%d/query" card-id))))))
          (letfn [(test-drill-thru []
                    (testing "Drill-thru question should work"
                      (let [drill-thru-query
                            (mt/mbql-query orders
                              {:filter [:= $products.category "Widget"]
                               :joins [{:fields :all
                                        :source-table $$products
                                        :condition [:= $product_id &products.products.id]
                                        :alias "products"}]
                               :limit 10})

                            test-preprocessing
                            (fn []
                              (testing "`resolve-joined-fields` middleware should infer `:field` `:join-alias` correctly"
                                (is (= [:=
                                        [:field (mt/id :products :category) {:join-alias "products"}]
                                        [:value "Widget" {:base_type :type/Text
                                                          :semantic_type (t2/select-one-fn :semantic_type :model/Field
                                                                                           :id (mt/id :products :category))
                                                          :database_type "CHARACTER VARYING"
                                                          :name "CATEGORY"}]]
                                       (get-in (qp.preprocess/preprocess drill-thru-query) [:query :filter])))))]
                        (testing "As an admin"
                          (mt/with-test-user :crowberto
                            (test-preprocessing)
                            (is (=? {:status :completed
                                     :row_count 10}
                                    (qp/process-query drill-thru-query)))))
                        (testing "As a sandboxed user"
                          (test-preprocessing)
                          (is (=? {:status :completed
                                   :row_count 6}
                                  (qp/process-query drill-thru-query)))))))]
            (test-drill-thru)
            (qp.store/with-metadata-provider (lib.tu/remap-metadata-provider
                                              (mt/application-database-metadata-provider (mt/id))
                                              (mt/id :orders :product_id)
                                              (mt/id :products :title))
              (test-drill-thru))))))))

(deftest drill-thru-on-implicit-joins-test
  (testing "drill-through should work on implicit joined tables with sandboxes should have correct metadata (#13641)"
    (mt/dataset test-data
      ;; create Sandbox on ORDERS
      (met/with-gtaps! (mt/$ids nil
                         {:gtaps {:orders {:remappings {:user_id [:dimension $orders.user_id]}}}
                          :attributes {:user_id "1"}})
        ;; make sure the sandboxed group can still access the Products table, which is referenced below.
        (data-perms/set-database-permission! &group (mt/id) :perms/view-data :unrestricted)
        (data-perms/set-table-permission! &group (mt/id :products) :perms/create-queries :query-builder)
        (letfn [(do-tests []
                  ;; create a query based on the sandboxed Table
                  (testing "should be able to run the query. Results should come back with correct metadata"
                    (let [query (mt/mbql-query orders
                                  {:aggregation [[:count]]
                                   :breakout [$product_id->products.category]
                                   :order-by [[:asc $product_id->products.category]]
                                   :limit 5})]
                      (letfn [(test-metadata []
                                (is (=? {:status :completed
                                         :data {:results_metadata
                                                {:columns [{:name "CATEGORY"
                                                            :field_ref (mt/$ids $orders.product_id->products.category)}
                                                           {:name "count"
                                                            :field_ref [:aggregation 0]}]}}}
                                        (qp/process-query query))))]
                        (testing "as an admin"
                          (mt/with-test-user :crowberto
                            (test-metadata)))
                        (testing "as a sandboxed user"
                          (test-metadata)))))
                  (testing "Drill-thru question should work"
                    (letfn [(test-drill-thru-query []
                              (is (=? {:status :completed}
                                      (mt/run-mbql-query orders
                                        {:filter [:= $product_id->products.category "Doohickey"]
                                         :order-by [[:asc $product_id->products.category]]
                                         :limit 5}))))]
                      (testing "as admin"
                        (mt/with-test-user :crowberto
                          (test-drill-thru-query)))
                      (testing "as sandboxed user"
                        (test-drill-thru-query)))))]
          (do-tests)
          (qp.store/with-metadata-provider (lib.tu/remap-metadata-provider
                                            (mt/application-database-metadata-provider (mt/id))
                                            (mt/id :orders :product_id)
                                            (mt/id :products :title))
            (do-tests)))))))

(defn- set-query-metadata-for-gtap-card!
  "Find the GTAP Card associated with Group and table-name and add `:result_metadata` to it. Because we (probably) need
  a parameter in order to run the query to get metadata, pass `param-name` and `param-value` template tag parameters
  when running the query."
  [group table-name param-name param-value]
  (let [card-id (t2/select-one-fn :card_id :model/GroupTableAccessPolicy
                                  :group_id (u/the-id group), :table_id (mt/id table-name))
        card (t2/select-one :model/Card :id (u/the-id card-id))
        results (mt/with-test-user :crowberto
                  (-> (:dataset_query card)
                      (assoc :parameters [{:type :category
                                           :target [:variable [:template-tag param-name]]
                                           :value param-value}])
                      qp/process-query))
        metadata (get-in results [:data :results_metadata :columns])]
    (is (seq metadata))
    (t2/update! :model/Card card-id {:result_metadata metadata})))

(deftest native-fk-remapping-test
  (testing "FK remapping should still work for questions with native sandboxes (EE #520)"
    (mt/dataset test-data
      (let [mbql-sandbox-results (met/with-gtaps! {:gtaps (mt/$ids
                                                            {:orders {:remappings {"user_id" [:dimension $orders.user_id]}}
                                                             :products {:remappings {"user_cat" [:dimension $products.category]}}})
                                                   :attributes {"user_id" 1, "user_cat" "Widget"}}
                                   (qp.store/with-metadata-provider (lib.tu/remap-metadata-provider
                                                                     (mt/application-database-metadata-provider (mt/id))
                                                                     (mt/id :orders :product_id)
                                                                     (mt/id :products :title))
                                     (mt/run-mbql-query orders)))]
        (testing "Sanity check: merged results metadata should not get normalized incorrectly"
          (is (=? {:type {:type/Number {}}}
                  (-> (get-in mbql-sandbox-results [:data :cols])
                      (nth 3)
                      :fingerprint))))
        (doseq [orders-gtap-card-has-metadata? [true false]
                products-gtap-card-has-metadata? [true false]]
          (testing (format "\nwith GTAP metadata for Orders? %s Products? %s"
                           (pr-str orders-gtap-card-has-metadata?)
                           (pr-str products-gtap-card-has-metadata?))
            (met/with-gtaps! {:gtaps {:orders {:query (mt/native-query
                                                        {:query "SELECT * FROM ORDERS WHERE USER_ID={{uid}} AND TOTAL > 10"
                                                         :template-tags {"uid" {:display-name "User ID"
                                                                                :id "1"
                                                                                :name "uid"
                                                                                :type :number}}})
                                               :remappings {"user_id" [:variable [:template-tag "uid"]]}}
                                      :products {:query (mt/native-query
                                                          {:query "SELECT * FROM PRODUCTS WHERE CATEGORY={{cat}} AND PRICE > 10"
                                                           :template-tags {"cat" {:display-name "Category"
                                                                                  :id "2"
                                                                                  :name "cat"
                                                                                  :type :text}}})
                                                 :remappings {"user_cat" [:variable [:template-tag "cat"]]}}}
                              :attributes {"user_id" "1", "user_cat" "Widget"}}
              (when orders-gtap-card-has-metadata?
                (set-query-metadata-for-gtap-card! &group :orders "uid" 1))
              (when products-gtap-card-has-metadata?
                (set-query-metadata-for-gtap-card! &group :products "cat" "Widget"))
              (qp.store/with-metadata-provider (lib.tu/remap-metadata-provider
                                                (mt/application-database-metadata-provider (mt/id))
                                                (mt/id :orders :product_id)
                                                (mt/id :products :title))
                (testing "Sandboxed results should be the same as they would be if the sandbox was MBQL"
                  (letfn [(format-col [col]
                            (-> (m/filter-keys simple-keyword? col)
                                (dissoc :field_ref :id :table_id :fk_field_id :options :position :fk_target_field_id)))
                          (format-results [results]
                            (-> results
                                (update-in [:data :cols] (partial map format-col))
                                (m/dissoc-in [:data :native_form])
                                (m/dissoc-in [:data :results_metadata :checksum])
                                (update-in [:data :results_metadata :columns] (partial map format-col))))]
                    (is (= (format-results mbql-sandbox-results)
                           (format-results (mt/run-mbql-query orders))))))
                (testing "Should be able to run a query against Orders"
                  (is (= [[1 1 14 37.65 2.07 39.72 nil "2019-02-11T21:40:27.892Z" 2 "Awesome Concrete Shoes"]]
                         (mt/rows (mt/run-mbql-query orders {:limit 1})))))))))))))

(deftest pivot-query-test
  (mt/test-drivers (row-level-restrictions-fk-drivers)
    (testing "Pivot table queries should work with sandboxed users (#14969)"
      (mt/dataset test-data
        (met/with-gtaps! {:gtaps (mt/$ids
                                   {:orders {:remappings {:user_id [:dimension $orders.user_id]}}
                                    :products {:remappings {:user_cat [:dimension $products.category]}}})
                          :attributes {:user_id 1, :user_cat "Widget"}}
          (data-perms/set-table-permission! &group (mt/id :people) :perms/create-queries :query-builder)
          (data-perms/set-database-permission! &group (mt/id) :perms/view-data :unrestricted)
          (is (= (->> [["Twitter" nil 0 401.51]
                       ["Twitter" "Widget" 0 498.59]
                       [nil nil 1 401.51]
                       [nil "Widget" 1 498.59]
                       ["Twitter" nil 2 900.1]
                       [nil nil 3 900.1]]
                      (sort-by (let [nil-first? (mt/sorts-nil-first? driver/*driver* :type/Text)
                                     sort-str (fn [s]
                                                (cond
                                                  (some? s) s
                                                  nil-first? "A"
                                                  :else "Z"))]
                                 (fn [[x y group]]
                                   [group (sort-str x) (sort-str y)]))))
                 (mt/formatted-rows
                  [str str int 2.0]
                  (qp.pivot/run-pivot-query
                   (mt/mbql-query orders
                     {:joins [{:source-table $$people
                               :fields :all
                               :condition [:= $user_id &P.people.id]
                               :alias "P"}]
                      :aggregation [[:sum $total]]
                      :breakout [&P.people.source
                                 $product_id->products.category]
                      :limit 5}))))))))))

(deftest caching-test
  (testing "Make sure Sandboxing works in combination with caching (#18579)"
    (mt/with-model-cleanup [[:model/QueryCache :updated_at]]
      (met/with-gtaps! {:gtaps {:venues {:query (mt/mbql-query venues {:order-by [[:asc $id]], :limit 5})}}}
        (let [card-id (t2/select-one-fn :card_id :model/GroupTableAccessPolicy :group_id (u/the-id &group))
              _ (is (integer? card-id))
              query (t2/select-one-fn :dataset_query :model/Card :id card-id)
              run-query (fn []
                          (let [results (qp/process-query (assoc query :cache-strategy {:type :ttl
                                                                                        :multiplier 60
                                                                                        :avg-execution-ms 10
                                                                                        :min-duration-ms 0}))]
                            {:cached? (boolean (:cached (:cache/details results)))
                             :num-rows (count (mt/rows results))}))]
          (testing "Make sure the underlying card for the GTAP returns cached results without sandboxing"
            (mt/with-current-user nil
              (testing "First run -- should not be cached"
                (is (= {:cached? false, :num-rows 5}
                       (run-query))))
              (testing "Should be cached by now"
                (is (= {:cached? true, :num-rows 5}
                       (run-query))))))
          (testing "Ok, now try to access the Table that is sandboxed by the cached Card"
            ;; this should *NOT* be cached because we're generating a nested query with sandboxing in play.
            (is (= {:cached? false, :num-rows 5}
                   (run-query)))))))))

(deftest persistence-disabled-when-sandboxed-test
  (mt/test-drivers (mt/normal-drivers-with-feature :persist-models)
    (mt/dataset test-data
      ;; with-gtaps! creates a new copy of the database. So make sure to do that before anything else. Gets really
      ;; confusing when `(mt/id)` and friends change value halfway through the test
      (met/with-gtaps! {:gtaps {:products
                                {:remappings {:category
                                              ["dimension"
                                               [:field (mt/id :products :category)
                                                nil]]}}}}
        (mt/with-persistence-enabled! [persist-models!]
          (mt/with-temp [:model/Card model {:type :model
                                            :dataset_query (mt/mbql-query
                                                             products
                                                              ;; note does not include the field we have to filter on. No way
                                                              ;; to use the sandbox filter on the cached table
                                                             {:fields [$id $price]})}]
            ;; persist model (as admin, so sandboxing is not applied to the persisted query)
            (mt/with-test-user :crowberto
              (persist-models!))
            (let [persisted-info (t2/select-one :model/PersistedInfo
                                                :database_id (mt/id)
                                                :card_id (:id model))]
              (is (= "persisted" (:state persisted-info))
                  "Model failed to persist")
              (is (string? (:table_name persisted-info)))

              (let [query (mt/mbql-query nil
                                    ;; just generate a select count(*) from card__<id>
                            {:aggregation [:count]
                             :source-table (str "card__" (:id model))})
                    regular-result (mt/with-test-user :crowberto
                                     (qp/process-query query))
                    sandboxed-result (met/with-user-attributes! :rasta {"category" "Gizmo"}
                                       (mt/with-test-user :rasta
                                         (qp/process-query query)))]
                (testing "Unsandboxed"
                  (testing "Sees full result set"
                    (is (= 200 (-> regular-result mt/rows ffirst))
                        "Expected 200 product results from cached, non-sandboxed results"))
                  (testing "Uses the cache table"
                    (is (str/includes? (-> regular-result :data :native_form :query)
                                       (:table_name persisted-info))
                        "Did not use the persisted model cache")))
                (testing "Sandboxed"
                  (testing "sees partial result"
                    (is (= 51 (-> sandboxed-result mt/rows ffirst))
                        "Sandboxed user got whole results instead of filtered"))
                  (testing "Does not use the cache table"
                    (is (not (str/includes? (-> sandboxed-result :data :native_form :query)
                                            (:table_name persisted-info)))
                        "Erroneously used the persisted model cache")))))))))))

(deftest is-sandboxed-success-test
  (testing "Integration test that checks that is_sandboxed is recorded in query_execution correctly for a sandboxed query"
    (met/with-gtaps! {:gtaps {:categories {:query (mt/mbql-query categories {:filter [:<= $id 3]})}}}
      (mt/with-temp [:model/Card card {:database_id (mt/id)
                                       :table_id (mt/id :categories)
                                       :dataset_query (mt/mbql-query categories)}]
        (let [query (:dataset_query card)]
          (process-userland-query-test/with-query-execution! [qe query]
            (qp/process-query (qp/userland-query query))
            (is (=? {:is_sandboxed true}
                    (qe)))))))))

(deftest sandbox-join-permissions-test
  (testing "Sandboxed query fails when sandboxed table is joined to a table that the current user doesn't have access to"
    (met/with-gtaps! (mt/$ids orders
                       {:gtaps {:orders {:remappings {"user_id" [:dimension $user_id->people.id]}}}
                        :attributes {"user_id" 1}})
      (data-perms/set-table-permission! &group (mt/id :products) :perms/view-data :legacy-no-self-service)
      (data-perms/set-table-permission! &group (mt/id :products) :perms/create-queries :no)
      (let [query (mt/mbql-query orders
                    {:limit 5
                     :aggregation [:count]
                     :joins [{:source-table $$products
                              :fields :all
                              :alias "Products"
                              :condition [:= $product_id &Products.products.id]}]})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"You do not have permissions to run this query"
             (qp/process-query query))))

      (mt/with-temp [:model/Card card {:dataset_query (mt/mbql-query products)}]
        (let [query (mt/mbql-query orders
                      {:limit 5
                       :aggregation [:count]
                       :joins [{:source-table (str "card__" (:id card))
                                :fields :all
                                :strategy :left-join
                                :alias "Products"
                                :condition [:= $product_id &Products.products.id]}]})]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"You do not have permissions to run this query"
               (qp/process-query query))))))))

(deftest sandbox-join-permissions-unrestricted-test
  (testing "sandboxing with unrestricted data perms on the sandboxed table works"
    (met/with-gtaps! (mt/$ids orders
                       {:gtaps {:orders {:remappings {"user_id" [:dimension $user_id->people.id]}}}
                        :attributes {"user_id" 1}})
      (data-perms/set-table-permission! &group (mt/id :people) :perms/view-data :unrestricted)
      (let [query (mt/mbql-query orders)]
        (is (= 11 (count (mt/rows (qp/process-query query)))))))))

(deftest sandbox-join-permissions-not-allowed-when-table-blocked-test
  (testing "sandboxed query fails when sandboxed table is joined to a table that the current user is blocked on"
    (met/with-gtaps! (mt/$ids orders
                       {:gtaps {:orders {:remappings {"user_id" [:dimension $user_id->people.id]}}}
                        :attributes {"user_id" 1}})
      (data-perms/set-table-permission! &group (mt/id :people) :perms/view-data :blocked)
      (let [query (mt/mbql-query orders)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"You do not have permissions to run this query"
             (qp/process-query query)))))))

(deftest sandbox-join-permissions-test-uses-nested-sandboxes-test
  (testing "Nested sandbox query works when sandboxed definition is based on a fk to another sandboxed table"
    (met/with-gtaps! (mt/$ids orders
                       {:attributes {"user_id" 1}
                        :gtaps {:orders {:remappings {"user_id" [:dimension $user_id->people.id]}}
                                            ;; Since noone's zipcode == 1, this sandboxed table will return nothing
                                :people {:remappings {"user_id" [:dimension $people.zip]}}}})
      (data-perms/set-table-permission! &group (mt/id :people) :perms/view-data :unrestricted)
      (is (= 0 (count (mt/rows (qp/process-query (mt/mbql-query orders)))))))))

(deftest native-sandbox-table-level-block-perms-test
  (testing "A sandbox powered by a native query source card can be used even when other tables have block perms (#49969)"
    (met/with-gtaps! {:gtaps {:venues (venues-category-native-gtap-def)}
                      :attributes {"cat" 50}}
      (data-perms/set-table-permission! &group (mt/id :people) :perms/view-data :blocked)
      (is (= 10 (count (mt/rows (qp/process-query (mt/mbql-query venues)))))))))

(deftest native-sandbox-no-query-metadata-streaming-test
  (testing "A sandbox powered by a native query source card can be used via the streaming API even if the card has no
           stored results_metadata (#49985)"
    (met/with-gtaps! {:gtaps {:venues (venues-category-native-gtap-def)}
                      :attributes {"cat" 50}}
      (let [sandbox-card-id (t2/select-one-fn :card_id
                                              :model/GroupTableAccessPolicy
                                              :group_id (:id &group)
                                              :table_id (mt/id :venues))]
        (is (nil? (t2/select-one-fn :result_metadata :model/Card sandbox-card-id)))
        (is (= 10 (count (mt/rows (streaming.test-util/process-query-basic-streaming :api (mt/mbql-query venues))))))
        (is (not (nil? (t2/select-one-fn :result_metadata :model/Card sandbox-card-id))))))))

(deftest filter-by-column-sandboxing-test
  (mt/test-drivers (mt/normal-drivers)
    (testing "Sandboxing with filtering by a column works for all supported drivers"
      (met/with-gtaps! {:gtaps {:venues {:remappings {:cat ["variable" [:field (mt/id :venues :category_id) nil]]}}
                                :checkins {:remappings {:user ["variable" [:field (mt/id :checkins :user_id) nil]]
                                                        :venue ["variable" [:field (mt/id :checkins :venue_id) nil]]}}},
                        :attributes {:cat 10
                                     :user 1
                                     :venue 47}}
        (let [mp (mt/metadata-provider)
              venues (lib.metadata/table mp (mt/id :venues))
              venues-id (lib.metadata/field mp (mt/id :venues :id))
              venues-query (-> (lib/query mp venues)
                               (lib/order-by venues-id :asc))
              checkins (lib.metadata/table mp (mt/id :checkins))
              checkins-query (-> (lib/query mp checkins)
                                 (lib/aggregate (lib/count)))]
          (is (= [[34 "Beachwood BBQ & Brewing" 10 33.7701 -118.191 2]
                  [99 "Golden Road Brewing" 10 34.1505 -118.274 2]]
                 (->> venues-query
                      qp/process-query
                      (mt/formatted-rows [int str int 4.0 3.0 int]))))
          (is (= [[2]]
                 (->> checkins-query
                      qp/process-query
                      (mt/formatted-rows [int])))))))))

(deftest jwt-attributes-sandboxing-test
  (testing "Sandboxes should work the same whether attributes are set through jwt_attributes or login_attributes"
    (mt/test-drivers (mt/normal-drivers)
      (met/with-gtaps! {:gtaps {:venues {:remappings {:cat ["variable" [:field (mt/id :venues :category_id) nil]]}}}}
        (testing "with login_attributes"
          (met/with-user-attributes! :rasta {"cat" 50}
            (mt/with-test-user :rasta
              (is (= [[10]]
                     (run-venues-count-query))))))

        (testing "with jwt_attributes"
          (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"cat" 50}
                                                                     :login_attributes {}}
            (mt/with-test-user :rasta
              (is (= [[10]]
                     (run-venues-count-query))))))

        (testing "login_attributes override jwt_attributes when both present"
          (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"cat" 40}
                                                                     :login_attributes {"cat" 50}}
            (mt/with-test-user :rasta
              (is (= [[10]]
                     (run-venues-count-query))))))))))

(deftest jwt-login-attributes-merge-test
  (testing "Verify attribute merging behavior between jwt_attributes and login_attributes"
    (mt/test-drivers (row-level-restrictions-fk-drivers)
      (met/with-gtaps! {:gtaps {:checkins (checkins-user-mbql-gtap-def)
                                :venues (venues-price-mbql-gtap-def)}}
        (testing "attributes from different sources are properly merged"
          (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"user" 5}
                                                                     :login_attributes {"price" 1}}
            (mt/with-test-user :rasta
              (is (= #{[nil 45] [1 10]}
                     (set (run-checkins-count-broken-out-by-price-query)))))))

        (testing "login_attributes take precedence for conflicting keys"
          (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"user" 3, "price" 2}
                                                                     :login_attributes {"user" 5, "price" 1}}
            (mt/with-test-user :rasta
              (is (= #{[nil 45] [1 10]}
                     (set (run-checkins-count-broken-out-by-price-query)))))))))))

(deftest jwt-attributes-native-query-test
  (mt/test-drivers (e2e-test-drivers)
    (testing "Native SQL GTAP queries work with jwt_attributes"
      (met/with-gtaps! {:gtaps {:venues (venues-category-native-gtap-def)}}
        (testing "Basic test with jwt_attributes"
          (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"cat" 50}
                                                                     :login_attributes {}}
            (mt/with-test-user :rasta
              (is (= [[10]]
                     (run-venues-count-query))))))

        (testing "Numeric string coercion works with jwt_attributes"
          (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"cat" "50"}
                                                                     :login_attributes {}}
            (mt/with-test-user :rasta
              (is (= [[10]]
                     (run-venues-count-query))))))))))

(deftest jwt-attributes-missing-error-test
  (testing "Missing required attributes throw appropriate errors for both jwt_attributes and login_attributes"
    (mt/test-drivers (e2e-test-drivers)
      (met/with-gtaps! {:gtaps {:venues (venues-category-mbql-gtap-def)}}
        (testing "Missing attribute in jwt_attributes throws error"
          (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"something_random" 50}
                                                                     :login_attributes {}}
            (mt/with-test-user :rasta
              (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #"Query requires user attribute `cat`"
                   (mt/run-mbql-query venues {:aggregation [[:count]]}))))))

        (testing "Nil attribute in jwt_attributes throws error"
          (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"cat" nil}
                                                                     :login_attributes {}}
            (mt/with-test-user :rasta
              (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #"Query requires user attribute `cat`"
                   (mt/run-mbql-query venues {:aggregation [[:count]]}))))))))))

(deftest jwt-attributes-caching-test
  (testing "Caching works correctly with jwt_attributes vs login_attributes"
    (cache-test/with-mock-cache! [save-chan]
      (met/with-gtaps! {:gtaps {:venues (venues-category-mbql-gtap-def)}}
        (letfn [(run-cached-query []
                  (qp/process-query (assoc (mt/mbql-query venues {:aggregation [[:count]]})
                                           :cache-strategy {:type :ttl
                                                            :multiplier 60
                                                            :avg-execution-ms 10
                                                            :min-duration-ms 0})))]
          (testing "Run query with login_attributes"
            (met/with-user-attributes! :rasta {"cat" 50}
              (mt/with-test-user :rasta
                (let [result (run-cached-query)]
                  (is (= nil
                         (:cached (:cache/details result))))
                  (is (= [[10]]
                         (mt/rows result)))))))

          (testing "Cache entry saved"
            (let [[_ chan] (a/alts!! [save-chan (a/timeout 5000)])]
              (is (= save-chan chan))))

          (testing "Different jwt_attributes don't use cached result"
            (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"cat" 40}
                                                                       :login_attributes {}}
              (mt/with-test-user :rasta
                (let [result (run-cached-query)]
                  (is (= nil
                         (:cached (:cache/details result))))
                  (is (= [[9]]
                         (mt/rows result))))))))))))

(deftest jwt-attributes-different-param-names-test
  (mt/test-drivers (e2e-test-drivers)
    (testing "GTAP remapping with different parameter names works with jwt_attributes"
      (met/with-gtaps! {:gtaps {:venues {:query (:query (venues-category-native-gtap-def))
                                         :remappings {:something.different ["variable" ["template-tag" "cat"]]}}}}
        (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"something.different" 50}
                                                                   :login_attributes {}}
          (mt/with-test-user :rasta
            (is (= [[10]]
                   (run-venues-count-query)))))))))

(deftest jwt-attributes-float-coercion-test
  (mt/test-drivers (e2e-test-drivers)
    (testing "Float attribute values work correctly with jwt_attributes"
      (met/with-gtaps! {:gtaps {:venues {:query (mt/mbql-query venues)
                                         :remappings {:cat ["variable" [:field (mt/id :venues :latitude) nil]]}}}}
        (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"cat" "34.1018"}
                                                                   :login_attributes {}}
          (mt/with-test-user :rasta
            (is (= [[3]]
                   (run-venues-count-query)))))))))

(deftest jwt-attributes-fk-relationships-test
  (mt/test-drivers (row-level-restrictions-fk-drivers)
    (testing "FK relationships and joins work correctly with jwt_attributes"
      (met/with-gtaps! {:gtaps {:checkins (checkins-user-mbql-gtap-def)
                                :venues (dissoc (venues-price-mbql-gtap-def) :query)
                                :users {:remappings {:user ["variable" [:field (mt/id :users :id) nil]]}}}
                        :attributes {"price" 1}}
        (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"user" 5}
                                                                   :login_attributes {"price" 1}}
          (mt/with-test-user :rasta
            (is (= #{[nil "Quentin Sören" 45] [1 "Quentin Sören" 10]}
                   (set
                    (mt/format-rows-by
                     [#(when % (int %)) str int]
                     (mt/rows
                      (mt/run-mbql-query checkins
                        {:aggregation [[:count]]
                         :order-by [[:asc $venue_id->venues.price]]
                         :breakout [$venue_id->venues.price $user_id->users.name]}))))))))))))

(deftest jwt-attributes-admin-bypass-test
  (mt/test-drivers (e2e-test-drivers)
    (testing "Admins bypass sandboxes regardless of jwt_attributes or login_attributes"
      (met/with-gtaps-for-user! :crowberto {:gtaps {:venues (venues-category-mbql-gtap-def)}}
        (testing "Admin with jwt_attributes still bypasses sandbox"
          (tu/with-temp-vals-in-db :model/User (mt/user->id :crowberto) {:jwt_attributes {"cat" 50}
                                                                         :login_attributes {}}
            (mt/with-test-user :crowberto
              (is (= [[100]]
                     (run-venues-count-query))))))))))

(deftest jwt-attributes-parameterized-sql-test
  (mt/test-drivers (into #{}
                         (filter #(driver.u/supports? % :parameterized-sql nil))
                         (row-level-restrictions-fk-sql-drivers))
    (testing "Parameterized SQL GTAPs work with jwt_attributes"
      (met/with-gtaps! {:gtaps {:checkins (parameterized-sql-with-join-gtap-def)}}
        (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"user" 1}
                                                                   :login_attributes {}}
          (mt/with-test-user :rasta
            (is (= [[2 1]
                    [72 1]]
                   (mt/format-rows-by
                    [int int identity int]
                    (mt/rows
                     (mt/run-mbql-query checkins
                       {:order-by [[:asc $id]]
                        :limit 2})))))))))))

(deftest jwt-attributes-attributes-only-gtap-test
  (mt/test-drivers (e2e-test-drivers)
    (testing "Attributes-only GTAPs (no card_id) work with jwt_attributes"
      (met/with-gtaps! {:gtaps {:venues (dissoc (venues-category-mbql-gtap-def) :query)}}
        (testing "Basic attributes-only GTAP with jwt_attributes"
          (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"cat" 50}
                                                                     :login_attributes {}}
            (mt/with-test-user :rasta
              (is (= [[10]]
                     (run-venues-count-query))))))

        (testing "Numeric string coercion with attributes-only GTAP"
          (tu/with-temp-vals-in-db :model/User (mt/user->id :rasta) {:jwt_attributes {"cat" "50"}
                                                                     :login_attributes {}}
            (mt/with-test-user :rasta
              (is (= [[10]]
                     (run-venues-count-query))))))))))
