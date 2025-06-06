(ns metabase.query-processor.middleware.metrics
  (:require
   [clojure.walk :as walk]
   [medley.core :as m]
   [metabase.analytics.core :as analytics]
   [metabase.lib.core :as lib]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.lib.options :as lib.options]
   [metabase.lib.util :as lib.util]
   [metabase.lib.util.match :as lib.util.match]
   [metabase.lib.walk :as lib.walk]
   [metabase.util :as u]
   [metabase.util.malli :as mu]))

(defn- filters->condition
  [filters]
  (when (seq filters)
    (lib.util/fresh-uuids
     (if (next filters)
       (apply lib/and filters)
       (first filters)))))

(def ^:private aggregations-pred-arg
  #{:count-where
    :sum-where
    :distinct-where})

(def ^:private nullary-aggregations
  {:count lib/sum
   :cum-count lib/cum-sum})

(def ^:private aggregations-expr-1st-arg
  #{:avg
    :cum-sum
    :distinct
    :max
    :median
    :min
    :percentile
    :stddev
    :sum
    :var})

(defn- and-join-conditions
  [c1 c2]
  (let [c2-operator (first c2)
        c2-maybe-unwrapped (cond-> c2
                             (= :and c2-operator) (subvec 2))
        c1-operator (first c1)]
    (lib.util/fresh-uuids
     (if (= :and c1-operator)
       (if (= :and c2-operator)
         (into c1 c2-maybe-unwrapped)
         (conj c1 c2-maybe-unwrapped))
       (if (= :and c2-operator)
         (apply lib/and c1 c2-maybe-unwrapped)
         (lib/and c1 c2-maybe-unwrapped))))))

(defn- transform-aggregation-with-predicate
  "For `aggregation` with predicate arg, merge the predicate with the `condition` from filter. Return aggregation with
  merged condition."
  [condition aggregation]
  (assert (vector? aggregation))
  (if (empty? condition)
    aggregation
    (let [predicate-arg-index (dec (count aggregation))
          original-predicate (aggregation predicate-arg-index)
          adjusted-predicate (and-join-conditions original-predicate condition)]
      (assoc aggregation predicate-arg-index adjusted-predicate))))

(defn- transform-0-arity-aggregation
  [condition aggregation]
  (if (or (not (vector? aggregation))
          (not (contains? nullary-aggregations (first aggregation)))
          (empty? condition))
    aggregation
    (let [operator (first aggregation)
          opts (lib.options/options aggregation)
          aggregating-fn (nullary-aggregations operator)]
      (-> (aggregating-fn (lib/case [[condition 1]] 0))
          ;; explicit overwrite of new options with options of original clause
          (lib.options/with-options opts)
          (with-meta (meta aggregation))))))

(defn- transform-share-aggregation
  [condition aggregation]
  (let [opts (lib.options/options aggregation)
        predicate (nth aggregation 2)]
    (-> (lib// (lib/count-where (and-join-conditions predicate condition))
               (lib/count-where condition))
        (lib.options/with-options opts)
        (with-meta (meta aggregation)))))

(defn- case-wrap-metric-aggregation
  [aggregation condition]
  (cond->> aggregation
    (seq condition)
    (walk/postwalk
     (fn [form]
       (if-not (and (vector? form)
                    (not (map-entry? form)))
         form
         (let [operator (first form)]
           (cond

             (= :share operator)
             (transform-share-aggregation condition form)

             (contains? aggregations-pred-arg operator)
             (transform-aggregation-with-predicate condition form)

             (contains? nullary-aggregations operator)
             (transform-0-arity-aggregation condition form)

             (contains? aggregations-expr-1st-arg operator)
             (assoc form 2 (lib/case [[condition (nth form 2)]]))

             :else
             form)))))))

(defn- metric-query-filters->aggregation
  "Entrypoint into transformation of metric aggregation into one that has case wrapped column arg."
  [metric-query]
  (if-some [filters (not-empty (lib/filters metric-query))]
    (let [aggregation-names (select-keys (last (lib/returned-columns metric-query))
                                         [:name :display-name])]
      (-> metric-query
          (lib.util/update-query-stage -1 update-in [:aggregation 0]
                                       #(-> %
                                            (case-wrap-metric-aggregation (filters->condition filters))
                                            (lib.options/update-options merge aggregation-names)))
          (lib.util/update-query-stage -1 dissoc :filters)))
    metric-query))

(defn- replace-metric-aggregation-refs [query stage-number lookup]
  (if-let [aggregations (lib/aggregations query stage-number)]
    (let [columns (lib/visible-columns query stage-number)]
      (assoc-in query [:stages stage-number :aggregation]
                (lib.util.match/replace aggregations
                  [:metric _ metric-id]
                  (if-let [{replacement :aggregation metric-name :name} (get lookup metric-id)]
                    ;; We have to replace references from the source-metric with references appropriate for
                    ;; this stage (expression/aggregation -> field, field-id to string)
                    (let [replacement (lib.util.match/replace replacement
                                        [(tag :guard #{:expression :field :aggregation}) _ _]
                                        (if-let [col (lib/find-matching-column &match columns)]
                                          (lib/ref col)
                                          ;; This is probably due to a field-id where it shouldn't be
                                          &match))]
                      (update (lib.util/fresh-uuids replacement)
                              1
                              #(merge
                                %
                                {:name metric-name}
                                (select-keys % [:name :display-name])
                                (select-keys (get &match 1) [:lib/uuid :name :display-name]))))
                    (throw (ex-info "Incompatible metric" {:match &match :lookup lookup}))))))
    query))

(defn- find-metric-ids
  [x]
  (lib.util.match/match x
    [:metric _ (id :guard pos-int?)]
    id))

(defn- find-first-metric-id
  [x]
  (first (find-metric-ids x)))

(defn- fetch-metrics
  [query metric-ids]
  (->> metric-ids
       (lib.metadata/bulk-metadata-or-throw query :metadata/card)
       (into {}
             (map (fn [card-metadata]
                    (let [metric-query (->> (:dataset-query card-metadata)
                                            (lib/query query)
                                            ((requiring-resolve 'metabase.query-processor.preprocess/preprocess))
                                            (lib/query query)
                                            metric-query-filters->aggregation)
                          metric-name (:name card-metadata)]
                      (if-let [aggregation (first (lib/aggregations metric-query))]
                        [(:id card-metadata)
                         {:query metric-query
                              ;; Aggregation inherits `:name` of original aggregation used in a metric query. The original
                              ;; name is added in `preprocess` above if metric is defined using unnamed aggregation.
                          :aggregation aggregation
                          :name metric-name}]
                        (throw (ex-info "Source metric missing aggregation" {:source metric-query})))))))
       not-empty))

(defn- fetch-referenced-metrics
  [query stage]
  (fetch-metrics query (find-metric-ids stage)))

(defn- expression-with-name-from-source
  [query agg-stage-index [_ {:lib/keys [expression-name]} :as expression]]
  (lib/expression query agg-stage-index expression-name expression))

(defn- update-metric-query-expression-names
  [metric-query unique-name-fn]
  (let [original+new-name-pairs (into []
                                      (keep (fn [[_ {:lib/keys [expression-name]}]]
                                              (let [new-name (unique-name-fn expression-name)]
                                                (when-not (= new-name expression-name)
                                                  [expression-name new-name]))))
                                      (lib/expressions metric-query))]
    (reduce
     (fn [metric-query [original-name new-name]]
       (let [expression (m/find-first (comp #{original-name} :lib/expression-name second) (lib/expressions metric-query))]
         (lib/replace-clause
          metric-query
          expression
          (lib/with-expression-name expression new-name))))
     metric-query
     original+new-name-pairs)))

(defn- temp-query-at-stage-path
  [query stage-path]
  (cond-> query
    stage-path (lib.walk/query-for-path stage-path)
    stage-path :query))

(defn- aggregation-stage-index
  [stages]
  (count (take-while (complement (comp find-metric-ids :aggregation)) stages)))

(defn- add-join-aliases
  [x source-field->join-alias]
  (lib.util.match/replace x
    [:field (opts :guard (every-pred (comp source-field->join-alias :source-field) (complement :join-alias))) _]
    (assoc-in &match [1 :join-alias] (-> opts :source-field source-field->join-alias))))

(defn- include-implicit-joins
  [query agg-stage-index metric-query]
  (let [metric-joins (lib/joins metric-query -1)
        existing-joins (into #{}
                             (map (juxt :fk-field-id :alias))
                             (lib/joins query agg-stage-index))
        new-joins (remove (comp existing-joins (juxt :fk-field-id :alias)) metric-joins)
        source-field->join-alias (dissoc (into {} (map (juxt :fk-field-id :alias)) new-joins) nil)
        query-with-joins (reduce #(lib/join %1 agg-stage-index %2)
                                 query
                                 new-joins)]
    (lib.util/update-query-stage query-with-joins agg-stage-index add-join-aliases source-field->join-alias)))

(defn- splice-compatible-metrics
  "Splices in metric definitions that are compatible with the query."
  [query path expanded-stages]
  (let [agg-stage-index (aggregation-stage-index expanded-stages)]
    (if-let [lookup (->> expanded-stages
                         (drop agg-stage-index)
                         first
                         :aggregation
                         (fetch-referenced-metrics query))]
      (let [temp-query (temp-query-at-stage-path query path)
            unique-name-fn (lib.util/unique-name-generator
                            (map
                             (comp :lib/expression-name second)
                             (lib/expressions temp-query)))
            new-query (reduce
                       (fn [query [metric-id {metric-query :query}]]
                         (if (and (= (lib.util/source-table-id query) (lib.util/source-table-id metric-query))
                                  (or (= (lib/stage-count metric-query) 1)
                                      (= (:qp/stage-had-source-card (last (:stages metric-query)))
                                         (:qp/stage-had-source-card (lib.util/query-stage query agg-stage-index)))))
                           (let [metric-query (update-metric-query-expression-names metric-query unique-name-fn)
                                 lookup (-> lookup
                                            (assoc-in [metric-id :query] metric-query)
                                            (assoc-in [metric-id :aggregation] (first (lib/aggregations metric-query))))]
                             (as-> query $q
                               (reduce #(expression-with-name-from-source %1 agg-stage-index %2)
                                       $q (lib/expressions metric-query -1))
                               (include-implicit-joins $q agg-stage-index metric-query)
                               (replace-metric-aggregation-refs $q agg-stage-index lookup)))
                           (throw (ex-info "Incompatible metric" {:query query
                                                                  :metric metric-query}))))
                       temp-query
                       lookup)]
        (:stages new-query))
      expanded-stages)))

(defn- find-metric-transition
  "Finds an unadjusted transition between a metric source-card and the next stage."
  [query expanded-stages]
  (some (fn [[[idx-a stage-a] [_idx-b stage-b]]]
          (let [stage-a-source (:qp/stage-is-from-source-card stage-a)
                metric-metadata (some->> stage-a-source (lib.metadata/card query))]
            (when (and
                   stage-a-source
                   (not= stage-a-source (:qp/stage-is-from-source-card stage-b))
                   (= (:type metric-metadata) :metric)
                    ;; This indicates this stage has not been processed
                    ;; because metrics must have aggregations
                    ;; if it is missing, then it has been removed in this process
                   (:aggregation stage-a))
              [idx-a metric-metadata])))
        (partition-all 2 1 (m/indexed expanded-stages))))

(defn- update-metric-transition-stages
  "Adjusts source-card metrics referencing themselves in the next stage.

   The final stage of the metric is adjusted by removing:
   ```
     :aggregation
     :breakout
     :order-by
   ```

   `:fields` are added explictly to pass previous-stage fields onto the following-stage

   The following stages will have `[:metric {} id]` clauses
   replaced with the actual aggregation of the metric."
  [query stage-path expanded-stages last-metric-stage-number metric-metadata]
  (mu/disable-enforcement
    (let [[pre-transition-stages [last-metric-stage _following-stage & following-stages]]
          (split-at last-metric-stage-number expanded-stages)
          metric-name (:name metric-metadata)
          metric-aggregation (-> last-metric-stage :aggregation first)
          stage-query (temp-query-at-stage-path query stage-path)
          stage-query (update-in stage-query
                                 [:stages last-metric-stage-number]
                                 (fn [stage]
                                   (dissoc stage :breakout :order-by :aggregation :fields :lib/stage-metadata)))
          ;; Needed for field references to resolve further in the pipeline
          stage-query (lib/with-fields stage-query last-metric-stage-number (lib/fieldable-columns stage-query last-metric-stage-number))
          new-metric-stage (lib.util/query-stage stage-query last-metric-stage-number)
          lookup {(:id metric-metadata)
                  {:name metric-name :aggregation metric-aggregation}}
          stage-query (replace-metric-aggregation-refs
                       stage-query
                       (inc last-metric-stage-number)
                       lookup)
          new-following-stage (lib.util/query-stage stage-query (inc last-metric-stage-number))
          combined-stages (vec (remove nil? (concat pre-transition-stages
                                                    [new-metric-stage new-following-stage]
                                                    following-stages)))]
      combined-stages)))

(defn- adjust-metric-stages
  "`expanded-stages` are the result of :stages from fetch-source-query.
   All source-card stages have been spliced in already.

   To adjust:

   We look for the transition between the last stage of a metric and the next stage following it.
   We adjust those two stages - as explained in `expand`.
   "
  [query path expanded-stages]
  ;; Find a transition point, if it exists
  (let [[idx metric-metadata] (find-metric-transition query expanded-stages)
        [first-stage] expanded-stages]
    (cond
      idx
      (let [new-stages (update-metric-transition-stages query path expanded-stages idx metric-metadata)]
        (recur (assoc-in query (conj path :stages) new-stages) path new-stages))

      (or (:source-table first-stage)
          (and (:native first-stage)
               (:qp/stage-is-from-source-card first-stage)))
      (splice-compatible-metrics query path expanded-stages)

      :else
      expanded-stages)))

(defn adjust
  "Looks for `[:metric {} id]` clause references and adjusts the query accordingly.

   Expects stages to have been processed by `fetch-source-query/resolve-source-cards`
   such that source card stages have been spliced in across the query.

   Metrics can be referenced in two scenarios:
   1. Compatible source table metrics.
      Multiple metrics can be referenced in the first stage of a query that references a `:source-table`
      Those metrics must:
      - Be single stage metrics.
      - Have the same `:source-table` as the query
   2. Metric source cards can reference themselves.
      A query built from a `:source-card` of `:type :metric` can reference itself."
  [query]
  (if-not (find-first-metric-id (:stages query))
    query
    (do
      (analytics/inc! :metabase-query-processor/metrics-adjust)
      (try
        (let [query (lib.walk/walk
                     query
                     (fn [_query path-type path stage-or-join]
                       (when (= path-type :lib.walk/join)
                         (update stage-or-join :stages #(adjust-metric-stages query path %)))))]
          (u/prog1
            (update query :stages #(adjust-metric-stages query nil %))
            (when-let [metric-id (find-first-metric-id (:stages <>))]
              (let [metric-data (-> (fetch-metrics query [metric-id])
                                    (get metric-id)
                                    u/ignore-exceptions)]
                (throw (ex-info "Failed to replace metric"
                                {:metric-id   metric-id
                                 :metric-data metric-data}))))))
        (catch Throwable e
          (analytics/inc! :metabase-query-processor/metrics-adjust-errors)
          (throw e))))))
