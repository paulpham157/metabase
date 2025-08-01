(ns metabase.queries.metadata
  (:require
   [metabase.legacy-mbql.normalize :as mbql.normalize]
   [metabase.lib.schema.id :as lib.schema.id]
   [metabase.lib.util :as lib.util]
   [metabase.models.interface :as mi]
   [metabase.util :as u]
   [metabase.util.malli :as mu]
   [metabase.warehouse-schema.field :as schema.field]
   [metabase.warehouse-schema.table :as schema.table]
   [toucan2.core :as t2]))

(defn- get-databases
  [ids]
  (when (seq ids)
    (into [] (filter mi/can-read?)
          (t2/select :model/Database :id [:in ids]))))

(defn- field-ids->table-ids
  [field-ids]
  (if (seq field-ids)
    (t2/select-fn-set :table_id :model/Field :id [:in field-ids])
    #{}))

(defn- split-tables-and-legacy-card-refs [source-ids]
  (-> (reduce (fn [m src]
                (if-let [card-id (lib.util/legacy-string-table-id->card-id src)]
                  (update m :cards conj! card-id)
                  (update m :tables conj! src)))
              {:cards  (transient #{})
               :tables (transient #{})}
              source-ids)
      (update-vals persistent!)))

(defn- query->template-tag-field-ids [query]
  (when-let [template-tags (some-> query :native :template-tags vals seq)]
    (for [{tag-type :type, [dim-tag id _opts] :dimension} template-tags
          :when (and (#{:dimension :temporal-unit} tag-type)
                     (= dim-tag :field)
                     (integer? id))]
      id)))

(defn- batch-fetch-query-metadata*
  "Fetch dependent metadata for ad-hoc queries."
  [queries]
  (let [source-ids                (into #{} (mapcat #(lib.util/collect-source-tables (:query %)))
                                        queries)
        {source-table-ids :tables
         source-card-ids  :cards} (split-tables-and-legacy-card-refs source-ids)
        source-tables             (concat (schema.table/batch-fetch-table-query-metadatas source-table-ids)
                                          (schema.table/batch-fetch-card-query-metadatas source-card-ids
                                                                                         {:include-database? false}))
        fk-target-field-ids       (into #{} (comp (mapcat :fields)
                                                  (keep :fk_target_field_id))
                                        source-tables)
        fk-target-table-ids       (into #{} (remove source-table-ids)
                                        (field-ids->table-ids fk-target-field-ids))
        fk-target-tables          (schema.table/batch-fetch-table-query-metadatas fk-target-table-ids)
        tables                    (concat source-tables fk-target-tables)
        template-tag-field-ids    (into #{} (mapcat query->template-tag-field-ids) queries)
        query-database-ids        (into #{} (keep :database) queries)
        database-ids              (into query-database-ids
                                        (keep :db_id)
                                        tables)]
    {;; TODO: This is naive and issues multiple queries currently. That's probably okay for most dashboards,
     ;; since they tend to query only a handful of databases at most.
     :databases (sort-by :id (get-databases database-ids))
     ;; apparently some of these tables come back with normal integer IDs and some come back with string IDs... not sure
     ;; what the hecc is going on but I guess maybe some of them in 2024 are still using that old `card__<id>` hack or
     ;; something. Idk. Anyways let's just sort the ones with numeric IDs first and the ones with string IDs last. We're
     ;; sorting these in a sane order because lots of tests expect them to be sorted by numeric ID and break if you sort
     ;; by something like `(str (:id %))`. -- Cam
     :tables    (sort-by (fn [{:keys [id]}]
                           (if (integer? id)
                             [id ""]
                             [Integer/MAX_VALUE (str id)]))
                         tables)
     :fields    (sort-by :id (schema.field/get-fields template-tag-field-ids))}))

(defn batch-fetch-query-metadata
  "Fetch dependent metadata for ad-hoc queries."
  [queries]
  (batch-fetch-query-metadata* (map mbql.normalize/normalize queries)))

(defn batch-fetch-card-metadata
  "Fetch dependent metadata for cards.

  Models and native queries need their definitions walked as well as their own, card-level metadata."
  [cards]
  (let [queries (into (vec (keep :dataset_query cards)) ; All the queries on all the cards
                      ;; Plus the card-level metadata of each model and native query
                      (comp (filter (fn [card] (or (= :model (:type card))
                                                   (= :native (-> card :dataset_query :type)))))
                            (map (fn [card] {:query {:source-table (str "card__" (u/the-id card))}})))
                      cards)]
    (batch-fetch-query-metadata queries)))

(defn- click-behavior->link-details
  [{:keys [linkType type targetId] :as _click-behavior}]
  (when (= type "link")
    (when-let [link-type (case linkType
                           "question"  :card
                           "dashboard" :dashboard
                           nil)]
      {:type link-type
       :id   targetId})))

(mu/defn- get-cards :- [:maybe [:sequential [:map [:id ::lib.schema.id/card]]]]
  [ids :- [:maybe [:or
                   [:sequential ::lib.schema.id/card]
                   [:set ::lib.schema.id/card]]]]
  (when (seq ids)
    (let [cards (into [] (filter mi/can-read?)
                      (t2/select :model/Card :id [:in ids]))]
      (t2/hydrate cards :can_write))))

(defn- dashcard->click-behaviors [dashcard]
  (let [viz-settings        (:visualization_settings dashcard)
        top-click-behavior  (:click_behavior viz-settings)
        col-click-behaviors (keep (comp :click_behavior val)
                                  (:column_settings viz-settings))]
    (conj col-click-behaviors top-click-behavior)))

(defn- batch-fetch-linked-dashboards
  [dashboard-ids]
  (when (seq dashboard-ids)
    (let [dashboards (->> (t2/select :model/Dashboard :id [:in dashboard-ids])
                          (filter mi/can-read?))]
      (t2/hydrate dashboards
                  :can_write
                  :param_fields))))

(defn- batch-fetch-dashboard-links
  [dashcards]
  (let [links      (group-by :type
                             (into #{} (comp (mapcat dashcard->click-behaviors)
                                             (keep click-behavior->link-details))
                                   dashcards))
        link-cards (get-cards (into #{} (map :id) (:card links)))
        dashboards (->> (:dashboard links)
                        (into #{} (map :id))
                        batch-fetch-linked-dashboards)]
    {:cards      (sort-by :id link-cards)
     :dashboards (sort-by :id dashboards)}))

(defn batch-fetch-dashboard-metadata
  "Fetch dependent metadata for dashboards."
  [dashboards]
  (let [dashcards (mapcat :dashcards dashboards)
        cards     (for [{:keys [card series]} dashcards
                        :let   [all (conj series card)]
                        card all]
                    card)
        card-ids  (into #{} (map :id) cards)
        links     (batch-fetch-dashboard-links dashcards)]
    (merge
     (->> (remove (comp card-ids :id) (:cards links))
          (concat cards)
          batch-fetch-card-metadata)
     {:cards      (or (:cards links)      [])
      :dashboards (or (:dashboards links) [])})))
