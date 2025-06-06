(ns metabase.warehouse-schema.models.table
  (:require
   [metabase.api.common :as api]
   [metabase.app-db.core :as app-db]
   [metabase.audit-app.core :as audit]
   [metabase.driver :as driver]
   [metabase.models.humanization :as humanization]
   [metabase.models.interface :as mi]
   [metabase.models.serialization :as serdes]
   [metabase.permissions.core :as perms]
   [metabase.premium-features.core :refer [defenterprise]]
   [metabase.search.spec :as search.spec]
   [metabase.util :as u]
   [metabase.util.malli :as mu]
   [methodical.core :as methodical]
   [toucan2.core :as t2]))

;;; ----------------------------------------------- Constants + Entity -----------------------------------------------

(def visibility-types
  "Valid values for `Table.visibility_type` (field may also be `nil`).
   (Basically any non-nil value is a reason for hiding the table.)"
  #{:hidden :technical :cruft})

(def field-orderings
  "Valid values for `Table.field_order`.
  `:database`     - use the same order as in the table definition in the DB;
  `:alphabetical` - order alphabetically by name;
  `:custom`       - the user manually set the order in the data model
  `:smart`        - Try to be smart and order like you'd usually want it: first PK, followed by `:type/Name`s, then
                    `:type/Temporal`s, and from there on in alphabetical order."
  #{:database :alphabetical :custom :smart})

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(methodical/defmethod t2/table-name :model/Table [_model] :metabase_table)

(doto :model/Table
  (derive :metabase/model)
  (derive ::mi/read-policy.full-perms-for-perms-set)
  (derive ::mi/write-policy.full-perms-for-perms-set)
  (derive :hook/timestamped?)
  ;; Deliberately **not** deriving from `:hook/entity-id` because we should not be randomizing the `entity_id`s on
  ;; databases, tables or fields. Since the sync process can create them in multiple instances, randomizing them would
  ;; cause duplication rather than good matching if the two instances are later linked by serdes.
  #_(derive :hook/entity-id))

(t2/deftransforms :model/Table
  {:entity_type     mi/transform-keyword
   :visibility_type mi/transform-keyword
   :field_order     mi/transform-keyword})

(methodical/defmethod t2/model-for-automagic-hydration [:default :table]
  [_original-model _k]
  :model/Table)

(t2/define-after-select :model/Table
  [table]
  (dissoc table :is_defective_duplicate :unique_table_helper))

(t2/define-before-insert :model/Table
  [table]
  (let [defaults {:display_name (humanization/name->human-readable-name (:name table))
                  :field_order  (driver/default-field-order (t2/select-one-fn :engine :model/Database :id (:db_id table)))}]
    (merge defaults table)))

(t2/define-before-delete :model/Table
  [table]
  ;; We need to use toucan to delete the fields instead of cascading deletes because MySQL doesn't support columns with cascade delete
  ;; foreign key constraints in generated columns. #44866
  (t2/delete! :model/Field :table_id (:id table)))

(defn- set-new-table-permissions!
  [table]
  (t2/with-transaction [_conn]
    (let [all-users-group  (perms/all-users-group)
          non-magic-groups (perms/non-magic-groups)
          non-admin-groups (conj non-magic-groups all-users-group)]
      ;; Data access permissions
      (if (= (:db_id table) audit/audit-db-id)
        (do
         ;; Tables in audit DB should start out with no query access in all groups
          (perms/set-new-table-permissions! non-admin-groups table :perms/view-data :unrestricted)
          (perms/set-new-table-permissions! non-admin-groups table :perms/create-queries :no))
        (do
          ;; Normal tables start out with unrestricted data access in all groups, but query access only in All Users
          (perms/set-new-table-permissions! non-admin-groups table :perms/view-data :unrestricted)
          (perms/set-new-table-permissions! [all-users-group] table :perms/create-queries :query-builder)
          (perms/set-new-table-permissions! non-magic-groups table :perms/create-queries :no)))
      ;; Download permissions
      (perms/set-new-table-permissions! [all-users-group] table :perms/download-results :one-million-rows)
      (perms/set-new-table-permissions! non-magic-groups table :perms/download-results :no)
      ;; Table metadata management
      (perms/set-new-table-permissions! non-admin-groups table :perms/manage-table-metadata :no))))

(t2/define-after-insert :model/Table
  [table]
  (u/prog1 table
    (set-new-table-permissions! table)))

(defmethod mi/can-read? :model/Table
  ([instance]
   (and (perms/user-has-permission-for-table?
         api/*current-user-id*
         :perms/view-data
         :unrestricted
         (:db_id instance)
         (:id instance))
        (perms/user-has-permission-for-table?
         api/*current-user-id*
         :perms/create-queries
         :query-builder
         (:db_id instance)
         (:id instance))))
  ([_ pk]
   (mi/can-read? (t2/select-one :model/Table pk))))

(defenterprise current-user-can-write-table?
  "OSS implementation. Returns a boolean whether the current user can write the given field."
  metabase-enterprise.advanced-permissions.common
  [_instance]
  (mi/superuser?))

(defmethod mi/can-write? :model/Table
  ([instance]
   (current-user-can-write-table? instance))
  ([_ pk]
   (mi/can-write? (t2/select-one :model/Table pk))))

;;; ------------------------------------------------ SQL Permissions ------------------------------------------------

(mu/defmethod mi/visible-filter-clause :model/Table
  [_                  :- :keyword
   column-or-exp      :- :any
   user-info          :- perms/UserInfo
   permission-mapping :- perms/PermissionMapping]
  [:in column-or-exp
   (perms/visible-table-filter-select :id user-info permission-mapping)])

;;; ------------------------------------------------ Serdes Hashing -------------------------------------------------

(defmethod serdes/hash-fields :model/Table
  [_table]
  [:schema :name (serdes/hydrated-hash :db :db_id)])

;;; ------------------------------------------------ Field ordering -------------------------------------------------

(def field-order-rule
  "How should we order fields."
  [[:position :asc] [:%lower.name :asc]])

(defn update-field-positions!
  "Update `:position` of field belonging to table `table` accordingly to `:field_order`"
  [table]
  (doall
   (map-indexed (fn [new-position field]
                  (t2/update! :model/Field (u/the-id field) {:position new-position}))
                ;; Can't use `select-field` as that returns a set while we need an ordered list
                (t2/select [:model/Field :id]
                           :table_id  (u/the-id table)
                           {:order-by (case (:field_order table)
                                        :custom       [[:custom_position :asc]]
                                        :smart        [[[:case
                                                         (app-db/isa :semantic_type :type/PK)       0
                                                         (app-db/isa :semantic_type :type/Name)     1
                                                         (app-db/isa :semantic_type :type/Temporal) 2
                                                         :else                                     3]
                                                        :asc]
                                                       [:%lower.name :asc]]
                                        :database     [[:database_position :asc]]
                                        :alphabetical [[:%lower.name :asc]])}))))

(defn- valid-field-order?
  "Field ordering is valid if all the fields from a given table are present and only from that table."
  [table field-ordering]
  (= (t2/select-pks-set :model/Field
                        :table_id (u/the-id table)
                        :active   true)
     (set field-ordering)))

(defn custom-order-fields!
  "Set field order to `field-order`."
  [table field-order]
  {:pre [(valid-field-order? table field-order)]}
  (t2/with-transaction [_]
    (t2/update! :model/Table (u/the-id table) {:field_order :custom})
    (dorun
     (map-indexed (fn [position field-id]
                    (t2/update! :model/Field field-id {:position        position
                                                       :custom_position position}))
                  field-order))))

;;; --------------------------------------------------- Hydration ----------------------------------------------------

(methodical/defmethod t2/batched-hydrate [:model/Table :field_values]
  "Return the FieldValues for all Fields belonging to a single `table`."
  [_model k tables]
  (mi/instances-with-hydrated-data
   tables k
   #(-> (group-by :table_id (t2/select [:model/FieldValues :field_id :values :field.table_id]
                                       {:join  [[:metabase_field :field] [:= :metabase_fieldvalues.field_id :field.id]]
                                        :where [:and
                                                [:in :field.table_id [(map :id tables)]]
                                                [:= :field.visibility_type  "normal"]
                                                [:= :metabase_fieldvalues.type "full"]]}))
        (update-vals (fn [fvs] (->> fvs (map (juxt :field_id :values)) (into {})))))
   :id))

(methodical/defmethod t2/batched-hydrate [:model/Table :pk_field]
  [_model k tables]
  (mi/instances-with-hydrated-data
   tables k
   #(t2/select-fn->fn :table_id :id
                      :model/Field
                      :table_id        [:in (map :id tables)]
                      :semantic_type   (app-db/isa :type/PK)
                      :visibility_type [:not-in ["sensitive" "retired"]])
   :id))

(defn- with-objects [hydration-key fetch-objects-fn tables]
  (let [table-ids         (set (map :id tables))
        table-id->objects (group-by :table_id (when (seq table-ids)
                                                (fetch-objects-fn table-ids)))]
    (for [table tables]
      (assoc table hydration-key (get table-id->objects (:id table) [])))))

(mi/define-batched-hydration-method with-segments
  :segments
  "Efficiently hydrate the Segments for a collection of `tables`."
  [tables]
  (with-objects :segments
    (fn [table-ids]
      (t2/select :model/Segment :table_id [:in table-ids], :archived false, {:order-by [[:name :asc]]}))
    tables))

(mi/define-batched-hydration-method with-metrics
  :metrics
  "Efficiently hydrate the Metrics for a collection of `tables`."
  [tables]
  (with-objects :metrics
    (fn [table-ids]
      (->> (t2/select :model/Card
                      :table_id [:in table-ids],
                      :archived false,
                      :type :metric,
                      {:order-by [[:name :asc]]})
           (filter mi/can-read?)))
    tables))

(defn with-fields
  "Efficiently hydrate the Fields for a collection of `tables`."
  [tables]
  (with-objects :fields
    (fn [table-ids]
      (t2/select :model/Field
                 :active          true
                 :table_id        [:in table-ids]
                 :visibility_type [:not= "retired"]
                 {:order-by       field-order-rule}))
    tables))

(mi/define-batched-hydration-method fields
  :fields
  "Efficiently hydrate the Fields for a collection of `tables`"
  [tables]
  (with-fields tables))

;;; ------------------------------------------------ Convenience Fns -------------------------------------------------

(defn database
  "Return the `Database` associated with this `Table`."
  [table]
  (t2/select-one :model/Database :id (:db_id table)))

;;; ------------------------------------------------- Serialization -------------------------------------------------
(defmethod serdes/dependencies "Table" [table]
  [[{:model "Database" :id (:db_id table)}]])

(defmethod serdes/generate-path "Table" [_ table]
  (let [db-name (t2/select-one-fn :name :model/Database :id (:db_id table))]
    (filterv some? [{:model "Database" :id db-name}
                    (when (:schema table)
                      {:model "Schema" :id (:schema table)})
                    {:model "Table" :id (:name table)}])))

(defmethod serdes/entity-id "Table" [_ {:keys [name]}]
  name)

(defmethod serdes/load-find-local "Table"
  [path]
  (let [db-name     (-> path first :id)
        schema-name (when (= 3 (count path))
                      (-> path second :id))
        table-name  (-> path last :id)
        db-id       (t2/select-one-pk :model/Database :name db-name)]
    (t2/select-one :model/Table :name table-name :db_id db-id :schema schema-name)))

(defmethod serdes/make-spec "Table" [_model-name _opts]
  {:copy      [:name :description :entity_type :active :display_name :visibility_type :schema
               :points_of_interest :caveats :show_in_getting_started :field_order :initial_sync_status :is_upload
               :database_require_filter :is_defective_duplicate :unique_table_helper]
   :skip      [:estimated_row_count :view_count]
   :transform {:created_at (serdes/date)
               :db_id      (serdes/fk :model/Database :name)}})

(defmethod serdes/storage-path "Table" [table _ctx]
  (concat (serdes/storage-path-prefixes (serdes/path table))
          [(:name table)]))

;;;; ------------------------------------------------- Search ----------------------------------------------------------

(search.spec/define-spec "table"
  {:model        :model/Table
   :attrs        {;; legacy search uses :active for this, but then has a rule to only ever show active tables
                  ;; so we moved that to the where clause
                  :archived      false
                  :collection-id false
                  :creator-id    false
                  :database-id   :db_id
                  :view-count    true
                  :created-at    true
                  :updated-at    true}
   :search-terms [:name :display_name :description]
   :render-terms {:initial-sync-status true
                  :table-id            :id
                  :table-description   :description
                  :table-name          :name
                  :table-schema        :schema
                  :database-name       :db.name}
   :where        [:and
                  :active
                  [:= :visibility_type nil]
                  [:= :db.router_database_id nil]
                  [:not= :db_id [:inline audit/audit-db-id]]]
   :joins        {:db [:model/Database [:= :db.id :this.db_id]]}})
