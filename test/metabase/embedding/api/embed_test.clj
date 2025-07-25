(ns ^:mb/driver-tests metabase.embedding.api.embed-test
  "Tests for /api/embed endpoints."
  (:require
   [buddy.sign.jwt :as jwt]
   [buddy.sign.util :as buddy-util]
   [clj-time.core :as time]
   [clojure.data.csv :as csv]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [crypto.random :as crypto-random]
   [dk.ative.docjure.spreadsheet :as spreadsheet]
   [metabase.config.core :as config]
   [metabase.dashboards.api-test :as api.dashboard-test]
   [metabase.embedding.api.common :as api.embed.common]
   [metabase.parameters.chain-filter-test :as chain-filer-test]
   [metabase.public-sharing.api-test :as public-test]
   [metabase.queries.api.card-test :as api.card-test]
   [metabase.query-processor.middleware.constraints :as qp.constraints]
   [metabase.query-processor.middleware.process-userland-query-test :as process-userland-query-test]
   [metabase.query-processor.pivot.test-util :as api.pivots]
   [metabase.query-processor.test-util :as qp.test-util]
   [metabase.test :as mt]
   [metabase.test.http-client :as client]
   [metabase.tiles.api-test :as tiles.api-test]
   [metabase.util :as u]
   [toucan2.core :as t2])
  (:import
   (java.io ByteArrayInputStream)))

(set! *warn-on-reflection* true)

(defn random-embedding-secret-key [] (crypto-random/hex 32))

(def ^:dynamic *secret-key* nil)

(defn sign [claims] (jwt/sign claims *secret-key*))

(defn do-with-new-secret-key! [f]
  (binding [*secret-key* (random-embedding-secret-key)]
    (mt/with-temporary-setting-values [embedding-secret-key *secret-key*]
      (f))))

(defmacro with-new-secret-key! {:style/indent 0} [& body]
  `(do-with-new-secret-key! (fn [] ~@body)))

(defn- the-id-or-entity-id
  "u/the-id doesn't work on entity-ids, so we should just pass them through."
  [id-or-entity-id]
  (try (u/the-id id-or-entity-id)
       (catch Exception _ id-or-entity-id)))

(defn card-token [card-or-id & [additional-token-keys]]
  (sign (merge {:resource {:question (the-id-or-entity-id card-or-id)}
                :params   {}}
               additional-token-keys)))

(defn dash-token [dash-or-id & [additional-token-keys]]
  (sign (merge {:resource {:dashboard (the-id-or-entity-id dash-or-id)}
                :params   {}}
               additional-token-keys)))

(defn do-with-temp-card [m f]
  (let [m (merge (when-not (:dataset_query m)
                   {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})})
                 m)]
    (mt/with-temp [:model/Card card m]
      (f card))))

(defmacro with-temp-card {:style/indent 1} [[binding & [card]] & body]
  `(do-with-temp-card
    ~card
    (fn [~binding]
      ~@body)))

(defn do-with-temp-dashcard [{:keys [dash card dashcard card-fn]} f]
  (with-temp-card [card (if (ifn? card-fn) (card-fn card) card)]
    (mt/with-temp [:model/Dashboard     dashboard (merge
                                                   (when-not (:parameters dash)
                                                     {:parameters [{:id      "_VENUE_ID_"
                                                                    :name    "Venue ID"
                                                                    :slug    "venue_id"
                                                                    :type    "id"
                                                                    :target  [:dimension (mt/id :venues :id)]
                                                                    :default nil}]})
                                                   dash)
                   :model/DashboardCard dashcard  (merge {:dashboard_id       (u/the-id dashboard)
                                                          :card_id            (u/the-id card)
                                                          :parameter_mappings (or (:parameter_mappings dashcard)
                                                                                  [{:parameter_id "_VENUE_ID_"
                                                                                    :card_id      (u/the-id card)
                                                                                    :target       [:dimension [:field (mt/id :venues :id) nil]]}])}
                                                         dashcard)]
      (f dashcard))))

(defmacro with-temp-dashcard
  {:style/indent 1, :arglists '([[dashcard-binding {:keys [dash card dashcard]}] & body])}
  [[dashcard-binding options] & body]
  `(do-with-temp-dashcard
    ~options
    (fn [~dashcard-binding]
      ~@body)))

(defmacro with-embedding-enabled-and-new-secret-key! {:style/indent 0} [& body]
  `(mt/with-temporary-setting-values [~'enable-embedding-static true
                                      ~'enable-embedding-interactive true]
     (with-new-secret-key!
       ~@body)))

(defn ^:deprecated test-query-results
  ([actual]
   (is (=? {:data       {:cols             [(mt/obj->json->obj (qp.test-util/aggregate-col :count))]
                         :rows             [[100]]
                         :insights         nil
                         :results_timezone "UTC"}
            :json_query {}
            :status     "completed"}
           actual)))

  ([results-format actual]
   (case results-format
     ""
     (test-query-results actual)

     "/json"
     (is (= [{:Count "100"}]
            actual))

     "/csv"
     (is (= "Count\n100\n"
            actual))

     "/xlsx"
     (let [actual (->> (ByteArrayInputStream. actual)
                       spreadsheet/load-workbook
                       (spreadsheet/select-sheet "Query result")
                       (spreadsheet/select-columns {:A :col}))]
       (is (= [{:col "Count"} {:col 100.0}]
              actual))))))

(defn dissoc-id-and-name [obj]
  (cond-> obj
    (map? obj) (dissoc :id :name)))

(def successful-card-info
  "Data that should be returned if `GET /api/embed/card/:token` completes successfully (minus `:id` and `:name`).
   This should only be the bare minimum amount of info needed to display the Card, leaving out other data we wouldn't
   want the public to have access to."
  {:description            nil
   :display                "table"
   :visualization_settings {}
   :dataset_query          {:type "query"}
   :parameters             []
   :param_fields           {}})

(def successful-dashboard-info
  {:auto_apply_filters true, :description nil, :parameters [], :dashcards [], :tabs [],
   :param_fields {} :width "fixed"})

(def ^:private yesterday (time/minus (time/now) (time/days 1)))

;;; ------------------------------------------- GET /api/embed/card/:token -------------------------------------------

(defn card-url [card & [additional-token-params]] (str "embed/card/" (card-token card additional-token-params)))

(deftest it-should-be-possible-to-use-this-endpoint-successfully-if-all-the-conditions-are-met
  (with-embedding-enabled-and-new-secret-key!
    (with-temp-card [card {:enable_embedding true}]
      (is (= successful-card-info
             (dissoc-id-and-name
              (client/client :get 200 (card-url card))))))))

(deftest we-should-fail-when-attempting-to-use-an-expired-token
  (with-embedding-enabled-and-new-secret-key!
    (with-temp-card [card {:enable_embedding true}]
      (is (re= #"Token is expired.*"
               (client/client :get 400 (card-url card {:exp (buddy-util/to-timestamp yesterday)})))))))

(deftest bad-card-id-fails
  (with-embedding-enabled-and-new-secret-key!
    (let [card-url (str "embed/card/" (sign {:resource {:question "8"}
                                             :params   {}}))]
      (is #(re-matches #"Invalid input:.+value must be an integer greater than zero.+got.+8"
                       (client/client :get 400 card-url))))))

(deftest check-that-the-endpoint-doesn-t-work-if-embedding-isn-t-enabled
  (mt/with-temporary-setting-values [enable-embedding false]
    (with-new-secret-key!
      (with-temp-card [card]
        (is (= "Embedding is not enabled."
               (client/client :get 400 (card-url card))))))))

(deftest check-that-if-embedding-is-enabled-globally-but-not-for-the-card-the-request-fails
  (with-embedding-enabled-and-new-secret-key!
    (with-temp-card [card]
      (is (= "Embedding is not enabled for this object."
             (client/client :get 400 (card-url card)))))))

(deftest global-embedding-requests-fail-with-wrong-key
  (testing (str "check that if embedding is enabled globally and for the object that requests fail if they are signed "
                "with the wrong key")
    (with-embedding-enabled-and-new-secret-key!
      (with-temp-card [card {:enable_embedding true}]
        (is (= "Message seems corrupt or manipulated"
               (client/client :get 400 (with-new-secret-key! (card-url card)))))))))

(deftest check-that-only-enabled-params-that-are-not-present-in-the-jwt-come-back
  (testing "check that only ENABLED params that ARE NOT PRESENT IN THE JWT come back"
    (with-embedding-enabled-and-new-secret-key!
      (with-temp-card [card {:enable_embedding true
                             :dataset_query    {:database (mt/id)
                                                :type     :native
                                                :native   {:template-tags {:a {:type "date", :name "a", :display_name "a" :id "a"}
                                                                           :b {:type "date", :name "b", :display_name "b" :id "b"}
                                                                           :c {:type "date", :name "c", :display_name "c" :id "c"}
                                                                           :d {:type "date", :name "d", :display_name "d" :id "d"}}}}
                             :embedding_params {:a "locked", :b "disabled", :c "enabled", :d "enabled"}}]
        (is (=? {:parameters [{:id      "d"
                               :type    "date/single"
                               :target  ["variable" ["template-tag" "d"]]
                               :name    "d"
                               :slug    "d"
                               :default nil}]}
                (client/client :get 200 (card-url card {:params {:c 100}}))))
        (testing "even if the value of the parameter is nil"
          (is (=? {:parameters [{:id      "d"
                                 :type    "date/single"
                                 :target  ["variable" ["template-tag" "d"]]
                                 :name    "d"
                                 :slug    "d"
                                 :default nil}]}
                  (client/client :get 200 (card-url card {:params {:c nil}})))))))))

(deftest parameters-should-include-legacy-template-tags
  (testing "parameters should get from both template-tags and card.parameters"
     ;; in 44 we added card.parameters but we didn't migrate template-tags to parameters
     ;; because doing such migration is costly.
     ;; so there are cards where some parameters in template-tags does not exist in card.parameters
     ;; that why we need to keep concat both of them then dedupe by id
    (with-embedding-enabled-and-new-secret-key!
      (with-temp-card [card (public-test/card-with-embedded-params)]
        (is (= [;; the parameter with id = "c" exists in both card.parameters and tempalte-tags should have info
                ;; merge of both places
                {:id "c",
                 :type "date/single",
                 :display_name "c",
                 :target ["variable" ["template-tag" "c"]],
                 :name "c",
                 :slug "c",
                                    ;; order importance: the default from template-tag is in the final result
                 :default "C TAG"
                 :required false
                 :values_source_type    "static-list"
                 :values_source_config {:values ["BBQ" "Bakery" "Bar"]}}
                                    ;; the parameter id = "d" is in template-tags, but not card.parameters,
                                    ;; when fetching card we should get it returned
                {:id "d",
                 :type "date/single",
                 :target ["variable" ["template-tag" "d"]],
                 :name "d",
                 :slug "d",
                 :default "D TAG"
                 :required false}]
               (:parameters (client/client :get 200 (card-url card)))))))))

(deftest parameters-should-include-relevant-template-tags-only
  (testing "should work with non-parameter template tags"
    (with-embedding-enabled-and-new-secret-key!
      (with-temp-card [card (public-test/card-with-snippet-and-card-template-tags)]
        (is (= [{:type "date/single",
                 :name "a",
                 :id "a",
                 :default "A TAG",
                 :target ["variable" ["template-tag" "a"]],
                 :slug "a"
                 :required false}]
               (:parameters (client/client :get 200 (card-url card)))))))))

;;; ------------------------- GET /api/embed/card/:token/query (and JSON/CSV/XLSX variants) --------------------------

(defn card-query-url
  "Generate a query URL for an embedded card"
  [card-or-id response-format-route-suffix & [additional-token-keys]]
  {:pre [(#{"" "/json" "/csv" "/xlsx"} response-format-route-suffix)]}
  (str "embed/card/"
       (card-token card-or-id additional-token-keys)
       "/query"
       response-format-route-suffix
       (when-not (str/blank? response-format-route-suffix)
         "?format_rows=true")))

(def ^:private response-format->request-options
  {""      nil
   "/json" nil
   "/csv"  nil
   "/xlsx" {:as :byte-array}})

(def ^:private response-format->status-code
  {""      202
   "/json" 200
   "/csv"  200
   "/xlsx" 200})

(defmacro ^:private do-response-formats {:style/indent 1} [[response-format-binding request-options-binding] & body]
  `(doseq [[response-format# ~request-options-binding] response-format->request-options
           :let                                        [~response-format-binding response-format#]]
     (testing (format "response-format = %s\n" (pr-str response-format#))
       ~@body)))

(deftest card-query-test
  (testing "GET /api/embed/card/:token/query and GET /api/embed/card/:token/query/:export-format"
    (mt/test-helpers-set-global-values!
      (do-response-formats [response-format _request-options]
        (testing "check that the endpoint doesn't work if embedding isn't enabled"
          (mt/with-temporary-setting-values [enable-embedding false]
            (with-new-secret-key!
              (with-temp-card [card]
                (is (= "Embedding is not enabled."
                       (client/real-client :get 400 (card-query-url card response-format))))
                (is (= "Embedding is not enabled."
                       (client/real-client :get 400 (card-query-url (:entity_id card) response-format {}))))))))))))

(deftest card-query-test-2
  (testing "GET /api/embed/card/:token/query and GET /api/embed/card/:token/query/:export-format"
    (mt/test-helpers-set-global-values!
      (do-response-formats [response-format request-options]
        (with-embedding-enabled-and-new-secret-key!
          (let [expected-status (response-format->status-code response-format)]
            (testing "it should be possible to run a Card successfully if you jump through the right hoops..."
              (with-temp-card [card {:enable_embedding true}]
                #_{:clj-kondo/ignore [:deprecated-var]}
                (test-query-results
                 response-format
                 (client/real-client :get expected-status (card-query-url card response-format)
                                     {:request-options request-options}))
                #_{:clj-kondo/ignore [:deprecated-var]}
                (test-query-results
                 response-format
                 (client/real-client :get expected-status (card-query-url (:entity_id card) response-format)
                                     {:request-options request-options}))))))))))

(deftest card-query-test-3
  (testing "GET /api/embed/card/:token/query and GET /api/embed/card/:token/query/:export-format"
    (mt/test-helpers-set-global-values!
      (do-response-formats [response-format _request-options]
        (with-embedding-enabled-and-new-secret-key!
          (let [expected-status (response-format->status-code response-format)]
            (testing (str "If the card has an invalid query we should just get a generic \"query failed\" "
                          "exception (rather than leaking query info)")
              (with-temp-card [card {:enable_embedding true, :dataset_query {:database (mt/id)
                                                                             :type     :native
                                                                             :native   {:query "SELECT * FROM XYZ"}}}]
                (is (= {:status     "failed"
                        :error      "An error occurred while running the query."
                        :error_type "invalid-query"}
                       (client/real-client :get expected-status (card-query-url card response-format))))
                (is (= {:status     "failed"
                        :error      "An error occurred while running the query."
                        :error_type "invalid-query"}
                       (client/real-client :get expected-status (card-query-url (:entity_id card) response-format))))))))))))

(deftest card-query-test-4
  (testing "GET /api/embed/card/:token/query and GET /api/embed/card/:token/query/:export-format"
    (mt/test-helpers-set-global-values!
      (do-response-formats [response-format _request-options]
        (with-embedding-enabled-and-new-secret-key!
          (testing "check that if embedding *is* enabled globally but not for the Card the request fails"
            (with-temp-card [card]
              (is (= "Embedding is not enabled for this object."
                     (client/real-client :get 400 (card-query-url card response-format) {})))))
          (testing "check that if embedding *is* enabled globally but not for the Card the request fails with entity ids"
            (with-temp-card [card]
              (is (= "Embedding is not enabled for this object."
                     (client/real-client :get 400 (card-query-url (:entity_id card) response-format) {}))))))))))

(deftest card-query-test-5
  (testing "GET /api/embed/card/:token/query and GET /api/embed/card/:token/query/:export-format"
    (mt/test-helpers-set-global-values!
      (do-response-formats [response-format _request-options]
        (with-embedding-enabled-and-new-secret-key!
          (testing (str "check that if embedding is enabled globally and for the object that requests fail if they are "
                        "signed with the wrong key")
            (with-temp-card [card {:enable_embedding true}]
              (is (= "Message seems corrupt or manipulated"
                     (client/real-client :get 400 (with-new-secret-key! (card-query-url card response-format)))))
              (is (= "Message seems corrupt or manipulated"
                     (client/real-client :get 400 (with-new-secret-key! (card-query-url (:entity_id card) response-format))))))))))))

(deftest download-formatted-without-constraints-test
  (testing (str "Downloading CSV/JSON/XLSX results shouldn't be subject to the default query constraints -- even if "
                "the query comes in with `add-default-userland-constraints` (as will be the case if the query gets "
                "saved from one that had it -- see #9831 and #10399)")
    (with-redefs [qp.constraints/default-query-constraints (constantly {:max-results 10, :max-results-bare-rows 10})]
      (with-embedding-enabled-and-new-secret-key!
        (with-temp-card [card {:enable_embedding true
                               :dataset_query    (assoc (mt/mbql-query venues)
                                                        :middleware
                                                        {:add-default-userland-constraints? true
                                                         :userland-query?                   true})}]
          (let [results (client/client :get 200 (card-query-url card "/csv"))]
            (is (= 101
                   (count (csv/read-csv results)))))
          (let [entity-id-results (client/client :get 200 (card-query-url (:entity_id card) "/csv"))]
            (is (= 101
                   (count (csv/read-csv entity-id-results))))))))))

(deftest card-locked-params-test
  (mt/test-helpers-set-global-values!
    (with-embedding-enabled-and-new-secret-key!
      (with-temp-card [card {:enable_embedding true, :embedding_params {:venue_id "locked"}}]
        (do-response-formats [response-format request-options]
          (testing (str "check that if embedding is enabled globally and for the object requests fail if the token is "
                        "missing a `:locked` parameter")
            (is (= "You must specify a value for :venue_id in the JWT."
                   (client/client :get 400 (card-query-url card response-format)))))

          (testing "if `:locked` param is present, request should succeed"
            #_{:clj-kondo/ignore [:deprecated-var]}
            (test-query-results
             response-format
             (client/real-client :get (response-format->status-code response-format)
                                 (card-query-url card response-format {:params {:venue_id 100}})
                                 {:request-options request-options})))

          (testing "If `:locked` parameter is present in URL params, request should fail"
            (is (= "You can only specify a value for :venue_id in the JWT."
                   (let [url (card-query-url card response-format {:params {:venue_id 100}})]
                     (client/client :get 400 (str url (if (str/includes? url "format_rows")
                                                        "&venue_id=100"
                                                        "?venue_id=100"))))))))))))

(deftest card-disabled-params-test
  (with-embedding-enabled-and-new-secret-key!
    (with-temp-card [card {:enable_embedding true, :embedding_params {:venue_id "disabled"}}]
      (do-response-formats [response-format _request-options]
        (testing (str "check that if embedding is enabled globally and for the object requests fail if they pass a "
                      "`:disabled` parameter")
          (is (= "You're not allowed to specify a value for :venue_id."
                 (client/client :get 400 (card-query-url card response-format {:params {:venue_id 100}})))))

        (testing "If a `:disabled` param is passed in the URL the request should fail"
          (is (= "You're not allowed to specify a value for :venue_id."
                 (let [url (card-query-url card response-format)]
                   (client/client :get 400 (str url (if (str/includes? url "format_rows")
                                                      "&venue_id=200"
                                                      "?venue_id=200")))))))))))

(deftest card-enabled-params-test
  (mt/test-helpers-set-global-values!
    (with-embedding-enabled-and-new-secret-key!
      (with-temp-card [card {:enable_embedding true, :embedding_params {:venue_id "enabled"}}]
        (do-response-formats [response-format request-options]
          (testing "If `:enabled` param is present in both JWT and the URL, the request should fail"
            (is (= "You can't specify a value for :venue_id if it's already set in the JWT."
                   (let [url (card-query-url card response-format {:params {:venue_id 100}})]
                     (client/client :get 400 (str url (if (str/includes? url "format_rows")
                                                        "&venue_id=100"
                                                        "?venue_id=100")))))))

          (testing "If an `:enabled` param is present in the JWT, that's ok"
            #_{:clj-kondo/ignore [:deprecated-var]}
            (test-query-results
             response-format
             (client/real-client :get (response-format->status-code response-format)
                                 (card-query-url card response-format {:params {:venue_id "enabled"}})
                                 {:request-options request-options})))

          (testing "If an `:enabled` param is present in URL params but *not* the JWT, that's ok"
            #_{:clj-kondo/ignore [:deprecated-var]}
            (test-query-results
             response-format
             (let [url (card-query-url card response-format)]
               (client/real-client :get (response-format->status-code response-format)
                                   (str url (if (str/includes? url "format_rows")
                                              "&venue_id=200"
                                              "?venue_id=200"))
                                   {:request-options request-options})))))))))

(defn card-with-date-field-filter-default
  []
  {:enable_embedding true
   :dataset_query
   {:database (mt/id)
    :type     :native
    :native   {:query         "SELECT COUNT(*) AS \"count\" FROM CHECKINS WHERE {{date}}"
               :template-tags {:date {:name         "date"
                                      :display-name "Date"
                                      :id           "_date_"
                                      :type         "dimension"
                                      :default      "Q1-2014"
                                      :dimension    [:field (mt/id :checkins :date) nil]
                                      :widget-type  "date/quarter-year"}}}}})

(deftest default-value-card-query-test
  (testing "GET /api/embed/card/:token/query with default values for params"
    (with-embedding-enabled-and-new-secret-key!
      (testing "if the param is enabled"
        (mt/with-temp
          [:model/Card card (assoc (card-with-date-field-filter-default) :embedding_params {:date :enabled})]
          (testing "the default should apply if no param value is provided"
            (is (= [[107]]
                   (mt/rows (client/client :get 202 (card-query-url card "")))))
            (testing "check this is the same result as when a default value is provided"
              (is (= [[107]]
                     (mt/rows (client/client :get 202 (str (card-query-url card "") "?date=Q1-2014")))))))
          (testing "an empty value should apply if provided as an empty string in the query params"
            (is (= [[1000]]
                   (mt/rows (client/client :get 202 (str (card-query-url card "") "?date="))))))
          (testing "an empty value should apply if provided as nil in the JWT params"
            (is (= [[1000]]
                   (mt/rows (client/client :get 202 (card-query-url card "" {:params {:date nil}}))))))))
      (testing "if the param is disabled"
        (mt/with-temp
          [:model/Card card (assoc (card-with-date-field-filter-default) :embedding_params {:date :disabled})]
          (testing "the default should apply if no param is provided"
            (is (= [[107]]
                   (mt/rows (client/client :get 202 (card-query-url card ""))))))
          (testing "you can't apply an empty param value if the parameter is disabled"
            (is (= "You're not allowed to specify a value for :date."
                   (client/client :get 400 (str (card-query-url card "") "?date=")))))))
      (testing "if the param is locked"
        (mt/with-temp
          [:model/Card card (assoc (card-with-date-field-filter-default) :embedding_params {:date :locked})]
          (testing "an empty value with `nil` as the param's value is invalid and should result in an error"
            (is (= "You must specify a value for :date in the JWT."
                   (client/client :get 400 (card-query-url card "" {:params {:date nil}}))))
            (testing "check this is different to when a non-nil value is provided"
              (is (= [[138]]
                     (mt/rows (client/client :get 202 (card-query-url card "" {:params {:date "Q2-2014"}})))))))
          (testing "an empty string value is invalid and should result in an error"
            (is (= "You must specify a value for :date in the JWT."
                   (client/client :get 400 (card-query-url card "" {:params {:date ""}}))))))))))

(defn- card-with-date-field-filter []
  {:dataset_query    {:database (mt/id)
                      :type     :native
                      :native   {:query         "SELECT COUNT(*) AS \"count\" FROM CHECKINS WHERE {{date}}"
                                 :template-tags {:date {:name         "date"
                                                        :display-name "Date"
                                                        :id           "_date_"
                                                        :type         "dimension"
                                                        :dimension    [:field (mt/id :checkins :date) nil]
                                                        :widget-type  "date/quarter-year"}}}}
   :enable_embedding true
   :embedding_params {:date :enabled}})

(deftest csv-reports-count
  (testing "GET /api/embed/card/:token/query/:export-format"
    (testing "make sure CSV (etc.) downloads take editable params into account (#6407)"
      (with-embedding-enabled-and-new-secret-key!
        (mt/with-temp [:model/Card card (card-with-date-field-filter)]
          (is (= "count\n107\n"
                 (client/client :get 200 (str (card-query-url card "/csv") "&date=Q1-2014")))))))))

(deftest csv-forward-url-test
  (mt/test-helpers-set-global-values!
    (with-embedding-enabled-and-new-secret-key!
      (mt/with-temp [:model/Card card (card-with-date-field-filter)]
        ;; make sure the URL doesn't include /api/ at the beginning like it normally would
        (binding [client/*url-prefix* ""]
          (mt/with-temporary-setting-values [site-url (str "http://localhost:" (config/config-str :mb-jetty-port) client/*url-prefix*)]
            (is (= "count\n107\n"
                   (client/real-client :get 200 (str "embed/question/" (card-token card) ".csv?date=Q1-2014"))))))))))

;;; ---------------------------------------- GET /api/embed/dashboard/:token -----------------------------------------

(defn dashboard-url [dashboard & [additional-token-keys entity-id]]
  (str "embed/dashboard/" (dash-token dashboard additional-token-keys entity-id)))

(deftest it-should-be-possible-to-call-this-endpoint-successfully
  (with-embedding-enabled-and-new-secret-key!
    (mt/with-temp [:model/Dashboard dash {:enable_embedding true}]
      (is (= successful-dashboard-info
             (dissoc-id-and-name
              (client/client :get 200 (dashboard-url dash)))))
      (is (= successful-dashboard-info
             (dissoc-id-and-name
              (client/client :get 200 (dashboard-url (:entity_id dash) dash))))))))

(deftest bad-dashboard-id-fails
  (with-embedding-enabled-and-new-secret-key!
    (let [dashboard-url (str "embed/dashboard/" (sign {:resource {:dashboard "8"} :params   {}}))]
      (is (re-matches
           #"Invalid input: .+got.+8.+"
           (client/client :get 400 dashboard-url))))))

(deftest we-should-fail-when-attempting-to-use-an-expired-token-2
  (with-embedding-enabled-and-new-secret-key!
    (mt/with-temp [:model/Dashboard dash {:enable_embedding true}]
      (is (re= #"^Token is expired.*"
               (client/client :get 400 (dashboard-url dash {:exp (buddy-util/to-timestamp yesterday)})))))))

(deftest check-that-the-dashboard-endpoint-doesn-t-work-if-embedding-isn-t-enabled
  (mt/with-temporary-setting-values [enable-embedding false]
    (with-new-secret-key!
      (mt/with-temp [:model/Dashboard dash]
        (is (= "Embedding is not enabled."
               (client/client :get 400 (dashboard-url dash))))))))

(deftest check-that-if-embedding--is--enabled-globally-but-not-for-the-dashboard-the-request-fails
  (with-embedding-enabled-and-new-secret-key!
    (mt/with-temp [:model/Dashboard dash]
      (is (= "Embedding is not enabled for this object."
             (client/client :get 400 (dashboard-url dash)))))))

(deftest global-embedding-check-key
  (testing (str "check that if embedding is enabled globally and for the object that requests fail if they are signed "
                "with the wrong key")
    (with-embedding-enabled-and-new-secret-key!
      (mt/with-temp [:model/Dashboard dash {:enable_embedding true}]
        (is (= "Message seems corrupt or manipulated"
               (client/client :get 400 (with-new-secret-key! (dashboard-url dash)))))))))

(deftest only-enabled-params-that-are-not-present-in-the-jwt-come-back
  (testing "check that only ENABLED params that ARE NOT PRESENT IN THE JWT come back"
    (with-embedding-enabled-and-new-secret-key!
      (mt/with-temp [:model/Dashboard dash {:enable_embedding true
                                            :embedding_params {:a "locked", :b "disabled", :c "enabled", :d "enabled"}
                                            :parameters       [{:id "_a", :slug "a", :name "a", :type "date"}
                                                               {:id "_b", :slug "b", :name "b", :type "date"}
                                                               {:id "_c", :slug "c", :name "c", :type "date"}
                                                               {:id "_d", :slug "d", :name "d", :type "date"}]}]
        (is (=? [{:id "_d", :slug "d", :name "d", :type "date"}]
                (:parameters (client/client :get 200 (dashboard-url dash {:params {:c 100}})))))
        (is (=? [{:id "_d", :slug "d", :name "d", :type "date"}]
                (:parameters (client/client :get 200 (dashboard-url dash {:params {:c 100}} (:entity_id dash))))))))))

(deftest locked-params-are-substituted-into-text-cards
  (testing "check that locked params are substituted into text cards with mapped variables on the backend"
    (with-embedding-enabled-and-new-secret-key!
      (mt/with-temp [:model/Dashboard     dash {:enable_embedding true
                                                :parameters       [{:id "_a" :slug "a" :name "a" :type :string/=}]}
                     :model/DashboardCard _ {:dashboard_id           (:id dash)
                                             :parameter_mappings     [{:parameter_id "_a"
                                                                       :target       [:text-tag "foo"]}]
                                             :visualization_settings {:virtual_card {:display "text"}
                                                                      :text         "Text card with variable: {{foo}}"}}]
        (is (= "Text card with variable: bar"
               (-> (client/client :get 200 (dashboard-url dash {:params {:a "bar"}}))
                   :dashcards
                   first
                   :visualization_settings
                   :text)))
        (testing "with entity id"
          (is (= "Text card with variable: bar"
                 (-> (client/client :get 200 (dashboard-url dash {:params {:a "bar"}} (:entity_id dash)))
                     :dashcards
                     first
                     :visualization_settings
                     :text))))))))

(deftest locked-params-removes-values-fields-and-mappings-test
  (testing "check that locked params are removed in parameter mappings, param_values, and param_fields"
    (with-embedding-enabled-and-new-secret-key!
      (mt/with-temp [:model/Dashboard     dashboard     {:enable_embedding true
                                                         :embedding_params {:venue_name "locked"}
                                                         :name             "Test Dashboard"
                                                         :parameters       [{:name      "venue_name"
                                                                             :slug      "venue_name"
                                                                             :id        "foo"
                                                                             :type      :string/=
                                                                             :sectionId "string"}]}
                     :model/Card          {card-id :id} {:name "Dashboard Test Card"}
                     :model/DashboardCard {_ :id}       {:dashboard_id       (:id dashboard)
                                                         :card_id            card-id
                                                         :parameter_mappings [{:card_id      card-id
                                                                               :slug         "venue_name"
                                                                               :parameter_id "foo"
                                                                               :target       [:dimension
                                                                                              [:field (mt/id :venues :name) nil]]}
                                                                              {:card_id      card-id
                                                                               :parameter_id "bar"
                                                                               :target       [:dimension
                                                                                              [:field (mt/id :categories :name) nil]]}]}]
        (let [embedding-dashboard (client/client :get 200 (dashboard-url dashboard {:params {:foo "BCD Tofu House"}}))]
          (is (= nil
                 (-> embedding-dashboard
                     :param_fields
                     (get (mt/id :venues :name)))))
          (is (= 1
                 (-> embedding-dashboard
                     :dashcards
                     first
                     :parameter_mappings
                     count))))
        (let [eid-embedding-dashboard (client/client :get 200 (dashboard-url dashboard {:params {:foo "BCD Tofu House"}} (:entity_id dashboard)))]
          (is (= nil
                 (-> eid-embedding-dashboard
                     :param_fields
                     (get (mt/id :venues :name)))))
          (is (= 1
                 (-> eid-embedding-dashboard
                     :dashcards
                     first
                     :parameter_mappings
                     count))))))))

(deftest locked-params-removes-values-fields-when-not-used-in-enabled-params
  (testing "check that locked params are not removed in parameter mappings, param_values, and param_fields when an enabled param uses them (#37914)"
    (with-embedding-enabled-and-new-secret-key!
      (mt/with-temp [:model/Dashboard     dashboard     {:enable_embedding true
                                                         :embedding_params {:venue_name   "locked"
                                                                            :venue_name_2 "enabled"}
                                                         :name             "Test Dashboard"
                                                         :parameters       [{:name      "venue_name"
                                                                             :slug      "venue_name"
                                                                             :id        "foo"
                                                                             :type      :string/=
                                                                             :sectionId "string"}
                                                                            {:name      "venue_name_2"
                                                                             :slug      "venue_name_2"
                                                                             :id        "bar"
                                                                             :type      :string/=
                                                                             :sectionId "string"}]}
                     :model/Card          {card-id :id} {:name "Dashboard Test Card"}
                     :model/DashboardCard {_ :id}       {:dashboard_id       (:id dashboard)
                                                         :card_id            card-id
                                                         :parameter_mappings [{:card_id      card-id
                                                                               :slug         "venue_name"
                                                                               :parameter_id "foo"
                                                                               :target       [:dimension
                                                                                              [:field (mt/id :venues :name) nil]]}
                                                                              {:card_id      card-id
                                                                               :slug         "venue_name_2"
                                                                               :parameter_id "bar"
                                                                               :target       [:dimension
                                                                                              [:field (mt/id :venues :name) nil]]}]}]
        (let [embedding-dashboard (client/client :get 200 (dashboard-url dashboard {:params {:foo "BCD Tofu House"}}))]
          (is (=? {:foo [{}]
                   :bar [{}]}
                  (:param_fields embedding-dashboard)))
          (is (= 1
                 (-> embedding-dashboard
                     :dashcards
                     first
                     :parameter_mappings
                     count))))
        (let [eid-embedding-dashboard (client/client :get 200 (dashboard-url dashboard {:params {:foo "BCD Tofu House"}} (:entity_id dashboard)))]
          (is (=? {:foo [{}]
                   :bar [{}]}
                  (:param_fields eid-embedding-dashboard)))
          (is (= 1
                 (-> eid-embedding-dashboard
                     :dashcards
                     first
                     :parameter_mappings
                     count))))))))

;;; ---------------------- GET /api/embed/dashboard/:token/dashcard/:dashcard-id/card/:card-id -----------------------

(defn dashcard-url
  "The URL for a request to execute a query for a Card on an embedded Dashboard."
  [dashcard & [additional-token-keys entity-id]]
  (str "embed/dashboard/" (dash-token (:dashboard_id dashcard) additional-token-keys entity-id)
       "/dashcard/" (u/the-id dashcard)
       "/card/" (:card_id dashcard)))

(defn- dashcard->dash-eid [dashcard]
  (t2/select-one-fn :entity_id :model/Dashboard :id (:dashboard_id dashcard)))

(deftest it-should-be-possible-to-run-a-card-successfully-if-you-jump-through-the-right-hoops---
  (testing "it should be possible to run a Card successfully if you jump through the right hoops..."
    (with-embedding-enabled-and-new-secret-key!
      (with-temp-dashcard [dashcard {:dash {:enable_embedding true}}]
        #_{:clj-kondo/ignore [:deprecated-var]}
        (test-query-results (client/client :get 202 (dashcard-url dashcard)))
        #_{:clj-kondo/ignore [:deprecated-var]}
        (test-query-results (client/client :get 202 (dashcard-url dashcard {} (dashcard->dash-eid dashcard))))))))

(deftest downloading-csv-json-xlsx-results-from-the-dashcard-endpoint-shouldn-t-be-subject-to-the-default-query-constraints
  (testing (str "Downloading CSV/JSON/XLSX results from the dashcard endpoint shouldn't be subject to the default "
                "query constraints (#10399)")
    (with-redefs [qp.constraints/default-query-constraints (constantly {:max-results 10, :max-results-bare-rows 10})]
      (with-embedding-enabled-and-new-secret-key!
        (with-temp-dashcard [dashcard {:dash {:enable_embedding true}
                                       :card {:dataset_query (assoc (mt/mbql-query venues)
                                                                    :middleware
                                                                    {:add-default-userland-constraints? true
                                                                     :userland-query?                   true})}}]
          (let [results (client/client :get 200 (str (dashcard-url dashcard) "/csv"))]
            (is (= 101
                   (count (csv/read-csv results)))))
          (let [eid-results (client/client :get 200 (str (dashcard-url dashcard {} (dashcard->dash-eid dashcard)) "/csv"))]
            (is (= 101
                   (count (csv/read-csv eid-results))))))))))

(deftest embed-download-query-execution-test
  (testing "Tests that embedding download context shows up in the query execution table when downloading cards."
    ;; Clear out the query execution log so that test doesn't read stale state
    (t2/delete! :model/QueryExecution)
    (mt/test-helpers-set-global-values!
      (with-embedding-enabled-and-new-secret-key!
        (with-temp-dashcard [dashcard {:dash {:enable_embedding true}
                                       :card {:dataset_query (mt/mbql-query venues)}}]
          (let [query (assoc
                       (mt/mbql-query venues)
                       :constraints nil
                       :middleware {:js-int-to-string? false
                                    :ignore-cached-results? false
                                    :process-viz-settings? true
                                    :format-rows? false}
                       :viz-settings {}
                       :async? true
                       :cache-strategy nil)]
            (process-userland-query-test/with-query-execution! [qe query]
              (client/client :get 200 (str (dashcard-url dashcard) "/csv"))
              (is (= :embedded-csv-download
                     (:context
                      (qe)))))
            (process-userland-query-test/with-query-execution! [qe query]
              (client/client :get 200 (str (dashcard-url dashcard) "/json"))
              (is (= :embedded-json-download
                     (:context
                      (qe)))))
            (process-userland-query-test/with-query-execution! [qe query]
              (client/client :get 200 (str (dashcard-url dashcard) "/xlsx"))
              (is (= :embedded-xlsx-download
                     (:context
                      (qe)))))))))))

(deftest downloading-csv-json-xlsx-results-from-the-dashcard-endpoint-respects-column-settings
  (testing "Downloading CSV/JSON/XLSX results should respect the column settings of the dashcard, such as column order and hidden/shown setting. (#33727)"
    (with-redefs [qp.constraints/default-query-constraints (constantly {:max-results 10, :max-results-bare-rows 10})]
      (with-embedding-enabled-and-new-secret-key!
        (with-temp-dashcard [dashcard {:dash     {:enable_embedding true}
                                       :card     {:dataset_query (assoc (mt/mbql-query venues)
                                                                        :limit 1
                                                                        :middleware
                                                                        {:add-default-userland-constraints? true
                                                                         :userland-query?                   true})}
                                       ;; we set column settings on the Dashcard only to see that the settings are respected in the output
                                       :dashcard {:visualization_settings
                                                  {:column_settings {}
                                                   :table.columns
                                                   [{:name "NAME" :fieldRef [:field (mt/id :venues :name) nil] :enabled true}
                                                    {:name "ID" :fieldRef [:field (mt/id :venues :id) nil] :enabled true}
                                                    {:name "CATEGORY_ID" :fieldRef [:field (mt/id :venues :category_id) nil] :enabled true}
                                                    {:name "LATITUDE" :fieldRef [:field (mt/id :venues :latitude) nil] :enabled false}
                                                    {:name "LONGITUDE" :fieldRef [:field (mt/id :venues :longitude) nil] :enabled false}
                                                    {:name "PRICE" :fieldRef [:field (mt/id :venues :price) nil] :enabled true}]}}}]
          (let [results (client/client :get 200 (str (dashcard-url dashcard) "/csv"))]
            (is (= ["Name" "ID" "Category ID" "Price"]
                   (first (csv/read-csv results)))))
          (let [eid-results (client/client :get 200 (str (dashcard-url dashcard {} (dashcard->dash-eid dashcard)) "/csv"))]
            (is (= ["Name" "ID" "Category ID" "Price"]
                   (first (csv/read-csv eid-results))))))))))

(deftest generic-query-failed-exception-test
  (testing (str "...but if the card has an invalid query we should just get a generic \"query failed\" exception "
                "(rather than leaking query info)")
    (with-embedding-enabled-and-new-secret-key!
      (with-temp-dashcard [dashcard {:dash {:enable_embedding true}
                                     :card {:dataset_query (mt/native-query {:query "SELECT * FROM XYZ"})}}]
        (is (= {:status     "failed"
                :error      "An error occurred while running the query."
                :error_type "invalid-query"}
               (client/client :get 202 (dashcard-url dashcard))))))))

(deftest check-that-the-dashcard-endpoint-doesn-t-work-if-embedding-isn-t-enabled
  (mt/with-temporary-setting-values [enable-embedding false]
    (with-new-secret-key!
      (with-temp-dashcard [dashcard]
        (is (= "Embedding is not enabled."
               (client/client :get 400 (dashcard-url dashcard))))))))

(deftest dashcard-check-that-if-embedding--is--enabled-globally-but-not-for-the-dashboard-the-request-fails
  (with-embedding-enabled-and-new-secret-key!
    (with-temp-dashcard [dashcard]
      (is (= "Embedding is not enabled for this object."
             (client/client :get 400 (dashcard-url dashcard)))))))

(deftest dashcard-global-embedding-check-key
  (testing (str "check that if embedding is enabled globally and for the object that requests fail if they are signed "
                "with the wrong key")
    (with-embedding-enabled-and-new-secret-key!
      (with-temp-dashcard [dashcard {:dash {:enable_embedding true}}]
        (is (= "Message seems corrupt or manipulated"
               (client/client :get 400 (with-new-secret-key! (dashcard-url dashcard)))))))))

(deftest dashboard-locked-params-test
  (with-embedding-enabled-and-new-secret-key!
    (with-temp-dashcard [dashcard {:dash {:enable_embedding true, :embedding_params {:venue_id "locked"}}}]
      (testing (str "check that if embedding is enabled globally and for the object requests fail if the token is "
                    "missing a `:locked` parameter")
        (is (= "You must specify a value for :venue_id in the JWT."
               (client/client :get 400 (dashcard-url dashcard)))))

      (testing "if `:locked` param is supplied, request should succeed"
        (is (=? {:status   "completed"
                 :data     {:rows [[1]]}}
                (client/client :get 202 (dashcard-url dashcard {:params {:venue_id 100}})))))

      (testing "if `:locked` parameter is present in URL params, request should fail"
        (is (= "You must specify a value for :venue_id in the JWT."
               (client/client :get 400 (str (dashcard-url dashcard) "?venue_id=100"))))))))

(deftest dashboard-disabled-params-test
  (with-embedding-enabled-and-new-secret-key!
    (with-temp-dashcard [dashcard {:dash {:enable_embedding true, :embedding_params {:venue_id "disabled"}}}]
      (testing (str "check that if embedding is enabled globally and for the object requests fail if they pass a "
                    "`:disabled` parameter")
        (is (= "You're not allowed to specify a value for :venue_id."
               (client/client :get 400 (dashcard-url dashcard {:params {:venue_id 100}})))))

      (testing "If a `:disabled` param is passed in the URL the request should fail"
        (is (= "You're not allowed to specify a value for :venue_id."
               (client/client :get 400 (str (dashcard-url dashcard) "?venue_id=200"))))))))

(deftest dashboard-enabled-params-test
  (with-embedding-enabled-and-new-secret-key!
    (with-temp-dashcard [dashcard {:dash {:enable_embedding true, :embedding_params {:venue_id "enabled"}}}]
      (testing "If `:enabled` param is present in both JWT and the URL, the request should fail"
        (is (= "You can't specify a value for :venue_id if it's already set in the JWT."
               (client/client :get 400 (str (dashcard-url dashcard {:params {:venue_id 100}}) "?venue_id=200")))))

      (testing "If an `:enabled` param is present in the JWT, that's ok"
        (is (=? {:status "completed"
                 :data   {:rows [[1]]}}
                (client/client :get 202 (dashcard-url dashcard {:params {:venue_id 50}})))))

      (testing "If an `:enabled` param is present in URL params but *not* the JWT, that's ok"
        (is (=? {:status   "completed"
                 :data     {:rows [[1]]}}
                (client/client :get 202 (str (dashcard-url dashcard) "?venue_id=1"))))))))

(deftest dashboard-native-query-params-with-default-test
  (testing "GET api/embed/dashboard/:token/dashcard/:dashcard-id/card/:card-id with default values for params"
    (with-embedding-enabled-and-new-secret-key!
      (mt/with-temp
        [:model/Card      card      (card-with-date-field-filter-default)
         :model/Dashboard dashboard {:enable_embedding true
                                     :embedding_params {:date "enabled"}
                                     :parameters       [{:name "Date"
                                                         :slug "date"
                                                         :id "_DATE_ID_"
                                                         :type :date/quarter-year
                                                         :sectionId "date"}]}
         :model/DashboardCard dashcard {:dashboard_id       (u/the-id dashboard)
                                        :card_id            (u/the-id card)
                                        :parameter_mappings [{:parameter_id "_DATE_ID_"
                                                              :card_id (u/the-id card)
                                                              :target [:dimension [:template-tag "date"]]}]}]
        (testing "the default should apply if no param value is provided"
          (is (= [[107]]
                 (mt/rows (client/client :get 202 (dashcard-url dashcard)))))
          (testing "check this is the same result as when a default value is provided"
            (is (= [[107]]
                   (mt/rows (client/client :get 202 (str (dashcard-url dashcard) "?date=Q1-2014")))))))
        (testing "an empty value should apply if provided as an empty string in the query params"
          (is (= [[1000]]
                 (mt/rows (client/client :get 202 (str (dashcard-url dashcard) "?date="))))))
        (testing "an empty value should apply if provided as nil in the JWT params"
          (is (= [[1000]]
                 (mt/rows (client/client :get 202 (dashcard-url dashcard {:params {:date nil}}))))))
        (testing "if the param is disabled"
          (mt/with-temp-vals-in-db :model/Dashboard (u/the-id dashboard) {:embedding_params {:date "disabled"}}
            (testing "the default should apply if no param is provided"
              (is (= [[107]]
                     (mt/rows (client/client :get 202 (dashcard-url dashcard))))))
            (testing "you can't apply an empty param value if the parameter is disabled"
              (is (= "You're not allowed to specify a value for :date."
                     (client/client :get 400 (str (dashcard-url dashcard) "?date=")))))))
        (testing "if the param is locked"
          (mt/with-temp-vals-in-db :model/Dashboard (u/the-id dashboard) {:embedding_params {:date "locked"}}
            (testing "an empty value specified as `nil` is invalid and should result in an error"
              (is (= "You must specify a value for :date in the JWT."
                     (client/client :get 400 (dashcard-url dashcard {:params {:date nil}}))))
              (testing "check this is different to when a non-nil value is provided"
                (is (= [[138]]
                       (mt/rows (client/client :get 202 (dashcard-url dashcard {:params {:date "Q2-2014"}})))))))
            (testing "an empty string value is invalid and should result in an error"
              (is (= "You must specify a value for :date in the JWT."
                     (client/client :get 400 (dashcard-url dashcard {:params {:date ""}})))))))))))

;;; -------------------------------------------------- Other Tests ---------------------------------------------------

(deftest remove-embedding-params
  (testing (str "parameters that are not in the `embedding-params` map at all should get removed by "
                "`remove-locked-and-disabled-params`")
    (is (= {:parameters []}
           (#'api.embed.common/remove-locked-and-disabled-params {:parameters {:slug "foo"}} {})))))

(deftest make-sure-that-multiline-series-word-as-expected---4768-
  (testing "make sure that multiline series word as expected (#4768)"
    (with-embedding-enabled-and-new-secret-key!
      (mt/with-temp [:model/Card series-card {:dataset_query {:database (mt/id)
                                                              :type     :query
                                                              :query    {:source-table (mt/id :venues)}}}]
        (with-temp-dashcard [dashcard {:dash {:enable_embedding true}}]
          (mt/with-temp [:model/DashboardCardSeries _ {:dashboardcard_id (u/the-id dashcard)
                                                       :card_id          (u/the-id series-card)
                                                       :position         0}]
            (is (= "completed"
                   (:status (client/client :get 202 (dashcard-url (assoc dashcard :card_id (u/the-id series-card)))))))))))))

;;; ------------------------------- GET /api/embed/card/:token/params/:param/values --------------------------------

(deftest card-param-values
  (letfn [(search [card param-key prefix & [entity-id]]
            (client/client :get 200 (format "embed/card/%s/params/%s/search/%s"
                                            (card-token card nil entity-id) param-key prefix)))
          (dropdown [card param-key  & [entity-id]]
            (client/client :get 200 (format "embed/card/%s/params/%s/values"
                                            (card-token card nil entity-id) param-key)))]
    (mt/with-temporary-setting-values [enable-embedding-static true]
      (with-new-secret-key!
        (api.card-test/with-card-param-values-fixtures [{:keys [card field-filter-card param-keys]}]
          (t2/update! :model/Card (:id field-filter-card)
                      {:enable_embedding true
                       :embedding_params (zipmap (map :slug (:parameters field-filter-card))
                                                 (repeat "enabled"))})
          (t2/update! :model/Card (:id card)
                      {:enable_embedding true
                       :embedding_params (zipmap (map :slug (:parameters card))
                                                 (repeat "enabled"))})
          (testing "field filter based param"
            (let [response (dropdown field-filter-card (:field-values param-keys))]
              (is (false? (:has_more_values response)))
              (is (set/subset? #{["20th Century Cafe"] ["33 Taps"]}
                               (-> response :values set))))

            (let [response (search field-filter-card (:field-values param-keys) "bar")]
              (is (set/subset? #{["Barney's Beanery"] ["bigmista's barbecue"]}
                               (-> response :values set)))
              (is (not ((into #{} (mapcat identity) (:values response)) "The Virgil")))))
          (testing "field filter based param entity-id"
            (let [response (dropdown field-filter-card (:field-values param-keys) (:entity_id field-filter-card))]
              (is (false? (:has_more_values response)))
              (is (set/subset? #{["20th Century Cafe"] ["33 Taps"]}
                               (-> response :values set))))
            (let [response (search field-filter-card (:field-values param-keys) "bar" (:entity_id field-filter-card))]
              (is (set/subset? #{["Barney's Beanery"] ["bigmista's barbecue"]}
                               (-> response :values set)))
              (is (not ((into #{} (mapcat identity) (:values response)) "The Virgil")))))
          (testing "static based param"
            (let [response (dropdown card (:static-list param-keys))]
              (is (= {:has_more_values false,
                      :values          [["African"] ["American"] ["Asian"]]}
                     response)))
            (let [response (search card (:static-list param-keys) "af")]
              (is (= {:has_more_values false,
                      :values          [["African"]]}
                     response))))
          (testing "static based param entity-id"
            (let [response (dropdown card (:static-list param-keys) (:entity_id card))]
              (is (= {:has_more_values false,
                      :values          [["African"] ["American"] ["Asian"]]}
                     response)))
            (let [response (search card (:static-list param-keys) "af" (:entity_id card))]
              (is (= {:has_more_values false,
                      :values          [["African"]]}
                     response))))
          (testing "card based param"
            (let [response (dropdown card (:card param-keys))]
              (is (= {:values          [["20th Century Cafe"] ["25°"] ["33 Taps"]
                                        ["800 Degrees Neapolitan Pizzeria"] ["BCD Tofu House"]]
                      :has_more_values false}
                     response)))
            (let [response (search card (:card param-keys) "red")]
              (is (= {:has_more_values false,
                      :values          [["Fred 62"] ["Red Medicine"]]}
                     response))))
          (testing "card based param entity-id"
            (let [response (dropdown card (:card param-keys) (:entity_id card))]
              (is (= {:values          [["20th Century Cafe"] ["25°"] ["33 Taps"]
                                        ["800 Degrees Neapolitan Pizzeria"] ["BCD Tofu House"]]
                      :has_more_values false}
                     response)))
            (let [response (search card (:card param-keys) "red" (:entity_id card))]
              (is (= {:has_more_values false,
                      :values          [["Fred 62"] ["Red Medicine"]]}
                     response)))))))))

;;; ------------------------------------------------ Chain filtering -------------------------------------------------

(defn- do-with-chain-filter-fixtures! [f]
  ;; Enable perms-related EE features to ensure changes to perms code don't break embedded chain filters (#54601)
  (mt/with-additional-premium-features #{:sandboxes :advanced-permissions :impersonation}
    (with-embedding-enabled-and-new-secret-key!
      (api.dashboard-test/with-chain-filter-fixtures [{:keys [dashboard], :as m}]
        (t2/update! :model/Dashboard (u/the-id dashboard) {:enable_embedding true})
        (letfn [(token [params]
                  (dash-token dashboard (when params {:params params})))
                (values-url [& [params param-key]]
                  (format "embed/dashboard/%s/params/%s/values"
                          (token params) (or param-key "_CATEGORY_ID_")))
                (search-url [& [params param-key query]]
                  (format "embed/dashboard/%s/params/%s/search/%s"
                          (token params) (or param-key "_CATEGORY_NAME_") (or query "food")))]
          (f (assoc m
                    :token token
                    :values-url values-url
                    :search-url search-url)))))))

(defmacro ^:private with-chain-filter-fixtures! [[binding] & body]
  `(do-with-chain-filter-fixtures! (fn [~binding] ~@body)))

(deftest chain-filter-embedding-disabled-test
  (with-chain-filter-fixtures! [{:keys [dashboard values-url search-url]}]
    (testing "without embedding enabled for dashboard"
      (t2/update! :model/Dashboard (u/the-id dashboard) {:enable_embedding false})
      (testing "GET /api/embed/dashboard/:token/params/:param-key/values"
        (is (= "Embedding is not enabled for this object."
               (client/client :get 400 (values-url)))))
      (testing "GET /api/embed/dashboard/:token/params/:param-key/search/:query"
        (is (= "Embedding is not enabled for this object."
               (client/client :get 400 (search-url))))))))

(deftest chain-filter-random-params-test
  (with-chain-filter-fixtures! [{:keys [values-url search-url]}]
    (testing "Requests should fail if parameter is not explicitly enabled"
      (testing "\nGET /api/embed/dashboard/:token/params/:param-key/values"
        (is (= "Cannot search for values: \"category_id\" is not an enabled parameter."
               (client/client :get 400 (values-url)))))
      (testing "\nGET /api/embed/dashboard/:token/params/:param-key/search/:query"
        (is (= "Cannot search for values: \"category_name\" is not an enabled parameter."
               (client/client :get 400 (search-url))))))))

(deftest params-with-static-list-test
  (testing "embedding with parameter that has source is a static list"
    (with-chain-filter-fixtures! [{:keys [dashboard values-url search-url]}]
      (t2/update! :model/Dashboard (:id dashboard)
                  {:embedding_params {"static_category" "enabled", "static_category_label" "enabled"}})
      (testing "Should work if the param we're fetching values for is enabled"
        (testing "\nGET /api/embed/dashboard/:token/params/:param-key/values"
          (is (= {:values          [["African"] ["American"] ["Asian"]]
                  :has_more_values false}
                 (client/client :get 200 (values-url {} "_STATIC_CATEGORY_")))))
        (testing "\nGET /api/embed/dashboard/:token/params/:param-key/search/:query"
          (is (= {:values          [["African" "Af"]]
                  :has_more_values false}
                 (client/client :get 200 (search-url {} "_STATIC_CATEGORY_LABEL_" "AF")))))))))

(deftest chain-filter-enabled-params-test
  (with-chain-filter-fixtures! [{:keys [dashboard values-url search-url]}]
    (t2/update! :model/Dashboard (:id dashboard)
                {:embedding_params {"category_id" "enabled", "category_name" "enabled", "price" "enabled"}})
    (testing "Should work if the param we're fetching values for is enabled"
      (testing "\nGET /api/embed/dashboard/:token/params/:param-key/values"
        (is (= {:values          [[2 "American"] [3 "Artisan"] [4 "Asian"] [5 "BBQ"] [6 "Bakery"]]
                :has_more_values false}
               (chain-filer-test/take-n-values 5 (client/client :get 200 (values-url))))))
      (testing "\nGET /api/embed/dashboard/:token/params/:param-key/search/:query"
        (is (= {:values          [["Fast Food"] ["Food Truck"] ["Seafood"]]
                :has_more_values false}
               (chain-filer-test/take-n-values 3 (client/client :get 200 (search-url)))))))

    (testing "If an ENABLED constraint param is present in the JWT, that's ok"
      (testing "\nGET /api/embed/dashboard/:token/params/:param-key/values"
        (is (= {:values          [[40 "Japanese"] [67 "Steakhouse"]]
                :has_more_values false}
               (client/client :get 200 (values-url {"price" 4})))))
      (testing "\nGET /api/embed/dashboard/:token/params/:param-key/search/:query"
        (is (= {:values          []
                :has_more_values false}
               (client/client :get 200 (search-url {"price" 4}))))))

    (testing "If an ENABLED param is present in query params but *not* the JWT, that's ok"
      (testing "\nGET /api/embed/dashboard/:token/params/:param-key/values"
        (is (= {:values          [[40 "Japanese"] [67 "Steakhouse"]]
                :has_more_values false}
               (client/client :get 200 (str (values-url) "?_PRICE_=4")))))
      (testing "\nGET /api/embed/dashboard/:token/params/:param-key/search/:query"
        (is (= {:values          []
                :has_more_values false}
               (client/client :get 200 (str (search-url) "?_PRICE_=4"))))))

    (testing "If ENABLED param is present in both JWT and the URL, the request should fail"
      (doseq [url-fn [values-url search-url]
              :let   [url (str (url-fn {"price" 4}) "?_PRICE_=4")]]
        (testing (str "\n" url)
          (is (= "You can't specify a value for :price if it's already set in the JWT."
                 (client/client :get 400 url))))))))

(deftest chain-filter-ignore-current-user-permissions-test
  (testing "Should not fail if request is authenticated but current user does not have data permissions"
    (mt/with-temp-copy-of-db
      (mt/with-no-data-perms-for-all-users!
        (with-chain-filter-fixtures! [{:keys [dashboard values-url search-url]}]
          (t2/update! :model/Dashboard (:id dashboard)
                      {:embedding_params {"category_id" "enabled", "category_name" "enabled", "price" "enabled"}})
          (testing "Should work if the param we're fetching values for is enabled"
            (testing "\nGET /api/embed/dashboard/:token/params/:param-key/values"
              (is (= {:values          [[2 "American"] [3 "Artisan"] [4 "Asian"] [5 "BBQ"] [6 "Bakery"]]
                      :has_more_values false}
                     (chain-filer-test/take-n-values 5 (mt/user-http-request :rasta :get 200 (values-url))))))
            (testing "\nGET /api/embed/dashboard/:token/params/:param-key/search/:query"
              (is (= {:values          [["Fast Food"] ["Food Truck"] ["Seafood"]]
                      :has_more_values false}
                     (chain-filer-test/take-n-values 3 (mt/user-http-request :rasta :get 200 (search-url))))))))))))

(deftest chain-filter-locked-params-test
  (with-chain-filter-fixtures! [{:keys [dashboard values-url search-url]}]
    (testing "Requests should fail if searched param is locked"
      (t2/update! :model/Dashboard (:id dashboard)
                  {:embedding_params {"category_id" "locked", "category_name" "locked"}})
      (doseq [url [(values-url) (search-url)]]
        (testing (str "\n" url)
          (is (re= #"Cannot search for values: \"category_(?:(?:name)|(?:id))\" is not an enabled parameter."
                   (client/client :get 400 url))))))

    (testing "Search param enabled\n"
      (t2/update! :model/Dashboard (:id dashboard)
                  {:embedding_params {"category_id" "enabled", "category_name" "enabled", "price" "locked"}})

      (testing "Requests should fail if the token is missing a locked parameter"
        (doseq [url [(values-url) (search-url)]]
          (testing (str "\n" url)
            (is (= "You must specify a value for :price in the JWT."
                   (client/client :get 400 url))))))

      (testing "if `:locked` param is supplied, request should succeed"
        (testing "\nGET /api/embed/dashboard/:token/params/:param-key/values"
          (is (= {:values          [[40 "Japanese"] [67 "Steakhouse"]]
                  :has_more_values false}
                 (client/client :get 200 (values-url {"price" 4})))))
        (testing "\nGET /api/embed/dashboard/:token/params/:param-key/search/:query"
          (is (= {:values          []
                  :has_more_values false}
                 (client/client :get 200 (search-url {"price" 4}))))))

      (testing "if `:locked` parameter is present in URL params, request should fail"
        (doseq [url-fn [values-url search-url]
                :let   [url (url-fn {"price" 4})]]
          (testing (str "\n" url)
            (is (= "You can only specify a value for :price in the JWT."
                   (client/client :get 400 (str url "?_PRICE_=4"))))))))))

(deftest chain-filter-disabled-params-test
  (with-chain-filter-fixtures! [{:keys [dashboard values-url search-url]}]
    (testing "Requests should fail if searched param is disabled"
      (t2/update! :model/Dashboard (:id dashboard)
                  {:embedding_params {"category_id" "disabled", "category_name" "disabled"}})
      (doseq [url [(values-url) (search-url)]]
        (testing (str "\n" url)
          (is (re= #"Cannot search for values: \"category_(?:(?:name)|(?:id))\" is not an enabled parameter\."
                   (client/client :get 400 url))))))

    (testing "Search param enabled\n"
      (t2/update! :model/Dashboard (:id dashboard)
                  {:embedding_params {"category_id" "enabled", "category_name" "enabled", "price" "disabled"}})

      (testing "Requests should fail if the token has a disabled parameter"
        (doseq [url-fn [values-url search-url]
                :let   [url (url-fn {"price" 4})]]
          (testing (str "\n" url)
            (is (= "You're not allowed to specify a value for :price."
                   (client/client :get 400 url))))))

      (testing "Requests should fail if the URL has a disabled parameter"
        (doseq [url-fn [values-url search-url]
                :let   [url (str (url-fn) "?_PRICE_=4")]]
          (testing (str "\n" url)
            (is (= "You're not allowed to specify a value for :price."
                   (client/client :get 400 url)))))))))

;; Pivot tables

(defn- pivot-card-query-url [card-or-id response-format & [additional-token-keys]]
  (str "/embed/pivot/card/"
       (card-token card-or-id additional-token-keys)
       "/query"
       response-format))

(deftest pivot-embed-query-test
  (mt/test-drivers (api.pivots/applicable-drivers)
    (mt/dataset test-data
      (testing "GET /api/embed/pivot/card/:token/query"
        (testing "check that the endpoint doesn't work if embedding isn't enabled"
          (mt/with-temporary-setting-values [enable-embedding-static false]
            (with-new-secret-key!
              (with-temp-card [card (api.pivots/pivot-card)]
                (is (= "Embedding is not enabled."
                       (client/client :get 400 (pivot-card-query-url card ""))))))))

        (with-embedding-enabled-and-new-secret-key!
          (let [expected-status 202]
            (testing "it should be possible to run a Card successfully if you jump through the right hoops..."
              (with-temp-card [card (merge {:enable_embedding true} (api.pivots/pivot-card))]
                (let [result (client/client :get expected-status (pivot-card-query-url card "") {:request-options nil})
                      rows   (mt/rows result)]
                  (is (nil? (:row_count result))) ;; row_count isn't included in public endpoints
                  (is (= "completed" (:status result)))
                  (is (= 6 (count (get-in result [:data :cols]))))
                  (is (= 1144 (count rows))))
                (let [eid-result (client/client :get expected-status
                                                (pivot-card-query-url (:entity_id card) "")
                                                {:request-options nil})
                      eid-rows   (mt/rows eid-result)]
                  (is (nil? (:row_count eid-result))) ;; row_count isn't included in public endpoints
                  (is (= "completed" (:status eid-result)))
                  (is (= 6 (count (get-in eid-result [:data :cols]))))
                  (is (= 1144 (count eid-rows)))))))

          (testing "check that if embedding *is* enabled globally but not for the Card the request fails"
            (with-temp-card [card (api.pivots/pivot-card)]
              (is (= "Embedding is not enabled for this object."
                     (client/client :get 400 (pivot-card-query-url card ""))))))

          (testing (str "check that if embedding is enabled globally and for the object that requests fail if they are "
                        "signed with the wrong key")
            (with-temp-card [card (merge {:enable_embedding true} (api.pivots/pivot-card))]
              (is (= "Message seems corrupt or manipulated"
                     (client/client :get 400 (with-new-secret-key! (pivot-card-query-url card ""))))))))))))

(defn- pivot-dashcard-url
  ([dashcard] (pivot-dashcard-url dashcard (:dashboard_id dashcard)))
  ([dashcard dashboard-id & [additional-token-keys]]
   (str "embed/pivot/dashboard/" (dash-token dashboard-id additional-token-keys)
        "/dashcard/" (u/the-id dashcard)
        "/card/" (:card_id dashcard))))

(deftest pivot-dashcard-success-test
  (mt/test-drivers (api.pivots/applicable-drivers)
    (mt/dataset test-data
      (with-embedding-enabled-and-new-secret-key!
        (with-temp-dashcard [dashcard {:dash     {:enable_embedding true, :parameters []}
                                       :card     (api.pivots/pivot-card)
                                       :dashcard {:parameter_mappings []}}]
          (let [result (client/client :get 202 (pivot-dashcard-url dashcard (:dashboard_id dashcard)))
                rows   (mt/rows result)]
            (is (nil? (:row_count result))) ;; row_count isn't included in public endpoints
            (is (= "completed" (:status result)))
            (is (= 6 (count (get-in result [:data :cols]))))
            (is (= 1144 (count rows))))
          (let [eid-result (client/client :get 202 (pivot-dashcard-url dashcard (dashcard->dash-eid dashcard)))
                eid-rows   (mt/rows eid-result)]
            (is (nil? (:row_count eid-result))) ;; row_count isn't included in public endpoints
            (is (= "completed" (:status eid-result)))
            (is (= 6 (count (get-in eid-result [:data :cols]))))
            (is (= 1144 (count eid-rows)))))))))

(deftest pivot-dashcard-embedding-disabled-test
  (mt/dataset test-data
    (mt/with-temporary-setting-values [enable-embedding-static false]
      (with-new-secret-key!
        (with-temp-dashcard [dashcard {:dash     {:parameters []}
                                       :card     (api.pivots/pivot-card)
                                       :dashcard {:parameter_mappings []}}]
          (is (= "Embedding is not enabled."
                 (client/client :get 400 (pivot-dashcard-url dashcard)))))))))

(deftest pivot-dashcard-embedding-disabled-for-card-test
  (mt/dataset test-data
    (with-embedding-enabled-and-new-secret-key!
      (with-temp-dashcard [dashcard {:dash     {:parameters []}
                                     :card     (api.pivots/pivot-card)
                                     :dashcard {:parameter_mappings []}}]
        (is (= "Embedding is not enabled for this object."
               (client/client :get 400 (pivot-dashcard-url dashcard))))))))

(deftest pivot-dashcard-signing-check-test
  (mt/dataset test-data
    (testing (str "check that if embedding is enabled globally and for the object that requests fail if they are signed "
                  "with the wrong key")
      (with-embedding-enabled-and-new-secret-key!
        (with-temp-dashcard [dashcard {:dash     {:enable_embedding true, :parameters []}
                                       :card     (api.pivots/pivot-card)
                                       :dashcard {:parameter_mappings []}}]
          (is (= "Message seems corrupt or manipulated"
                 (client/client :get 400 (with-new-secret-key! (pivot-dashcard-url dashcard))))))))))

(deftest pivot-dashcard-locked-params-test
  (mt/dataset test-data
    (mt/test-helpers-set-global-values!
      (with-embedding-enabled-and-new-secret-key!
        (with-temp-dashcard [dashcard {:dash     {:enable_embedding true
                                                  :embedding_params {:abc "locked"}
                                                  :parameters       [{:id     "_ORDER_ID_"
                                                                      :name   "Order ID"
                                                                      :slug   "abc"
                                                                      :type   "id"
                                                                      :target [:dimension (mt/id :orders :id)]}]}
                                       :card     (api.pivots/pivot-card)
                                       :dashcard {:parameter_mappings []}}]
          (testing (str "check that if embedding is enabled globally and for the object requests fail if the token is "
                        "missing a `:locked` parameter")
            (is (= "You must specify a value for :abc in the JWT."
                   (client/client :get 400 (pivot-dashcard-url dashcard)))))

          (testing "if `:locked` param is supplied, request should succeed"
            (let [result (client/client :get 202 (pivot-dashcard-url dashcard (:dashboard_id dashcard) {:params {:abc 100}}))
                  rows   (mt/rows result)]
              (is (nil? (:row_count result))) ;; row_count isn't included in public endpoints
              (is (= "completed" (:status result)))
              (is (= 6 (count (get-in result [:data :cols]))))
              (is (= 1144 (count rows)))))
          (testing "if `:locked` param is supplied, request should succeed with entity-id"
            (let [eid-result (client/client :get 202 (pivot-dashcard-url dashcard (dashcard->dash-eid dashcard) {:params {:abc 100}}))
                  eid-rows   (mt/rows eid-result)]
              (is (nil? (:row_count eid-result))) ;; row_count isn't included in public endpoints
              (is (= "completed" (:status eid-result)))
              (is (= 6 (count (get-in eid-result [:data :cols]))))
              (is (= 1144 (count eid-rows)))))

          (testing "if `:locked` parameter is present in URL params, request should fail"
            (is (= "You must specify a value for :abc in the JWT."
                   (client/client :get 400 (str (pivot-dashcard-url dashcard) "?abc=100"))))))))))

(deftest pivot-dashcard-disabled-params-test
  (mt/dataset test-data
    (with-embedding-enabled-and-new-secret-key!
      (with-temp-dashcard [dashcard {:dash     {:enable_embedding true
                                                :embedding_params {:abc "disabled"}
                                                :parameters       []}
                                     :card     (api.pivots/pivot-card)
                                     :dashcard {:parameter_mappings []}}]
        (testing (str "check that if embedding is enabled globally and for the object requests fail if they pass a "
                      "`:disabled` parameter")
          (is (= "You're not allowed to specify a value for :abc."
                 (client/client :get 400 (pivot-dashcard-url dashcard (:dashboard_id dashcard) {:params {:abc 100}})))))

        (testing "If a `:disabled` param is passed in the URL the request should fail"
          (is (= "You're not allowed to specify a value for :abc."
                 (client/client :get 400 (str (pivot-dashcard-url dashcard) "?abc=200")))))))))

(deftest pivot-dashcard-enabled-params-test
  (mt/dataset test-data
    (with-embedding-enabled-and-new-secret-key!
      (with-temp-dashcard [dashcard {:dash     {:enable_embedding true
                                                :embedding_params {:abc "enabled"}
                                                :parameters       [{:id      "_ORDER_ID_"
                                                                    :name    "Order ID"
                                                                    :slug    "abc"
                                                                    :type    "id"
                                                                    :target  [:dimension (mt/id :orders :id)]}]}
                                     :card     (api.pivots/pivot-card)
                                     :dashcard {:parameter_mappings []}}]
        (testing "If `:enabled` param is present in both JWT and the URL, the request should fail"
          (is (= "You can't specify a value for :abc if it's already set in the JWT."
                 (client/client :get 400 (str (pivot-dashcard-url dashcard (:dashboard_id dashcard) {:params {:abc 100}}) "?abc=200")))))

        (testing "If an `:enabled` param is present in the JWT, that's ok"
          (let [result (client/client :get 202 (pivot-dashcard-url dashcard (:dashboard_id dashcard) {:params {:abc 100}}))
                rows   (mt/rows result)]
            (is (nil? (:row_count result))) ;; row_count isn't included in public endpoints
            (is (= "completed" (:status result)))
            (is (= 6 (count (get-in result [:data :cols]))))
            (is (= 1144 (count rows)))))

        (testing "If an `:enabled` param is present in the JWT, that's ok with entity-id"
          (let [eid-result (client/client :get 202 (pivot-dashcard-url dashcard (dashcard->dash-eid dashcard) {:params {:abc 100}}))
                eid-rows   (mt/rows eid-result)]
            (is (nil? (:row_count eid-result))) ;; row_count isn't included in public endpoints
            (is (= "completed" (:status eid-result)))
            (is (= 6 (count (get-in eid-result [:data :cols]))))
            (is (= 1144 (count eid-rows)))))

        (testing "If an `:enabled` param is present in URL params but *not* the JWT, that's ok"
          (let [result (client/client :get 202 (str (pivot-dashcard-url dashcard) "?abc=200"))
                rows   (mt/rows result)]
            (is (nil? (:row_count result))) ;; row_count isn't included in public endpoints
            (is (= "completed" (:status result)))
            (is (= 6 (count (get-in result [:data :cols]))))
            (is (= 1144 (count rows)))))))))

(deftest apply-slug->value-test
  (testing "For operator filter types treat a lone value as a one-value sequence (#20438)"
    (is (= (#'api.embed.common/apply-slug->value [{:type    :string/=
                                                   :target  [:dimension [:template-tag "NAME"]]
                                                   :name    "Name"
                                                   :slug    "NAME"
                                                   :default nil}]
                                                 {:NAME ["Aaron Hand"]})
           (#'api.embed.common/apply-slug->value [{:type    :string/=
                                                   :target  [:dimension [:template-tag "NAME"]]
                                                   :name    "Name"
                                                   :slug    "NAME"
                                                   :default nil}]
                                                 {:NAME "Aaron Hand"})))))

(deftest handle-single-params-for-operator-filters-test
  (testing "Query endpoints should work with a single URL parameter for an operator filter (#20438)"
    (mt/dataset test-data
      (with-embedding-enabled-and-new-secret-key!
        (mt/with-temp [:model/Card {card-id :id, :as card} {:dataset_query    (mt/native-query
                                                                                {:query         "SELECT count(*) AS count FROM PUBLIC.PEOPLE WHERE true [[AND {{NAME}}]]"
                                                                                 :template-tags {"NAME"
                                                                                                 {:id           "9ddca4ca-3906-83fd-bc6b-8480ae9ab05e"
                                                                                                  :name         "NAME"
                                                                                                  :display-name "Name"
                                                                                                  :type         :dimension
                                                                                                  :dimension    [:field (mt/id :people :name) nil]
                                                                                                  :widget-type  :string/=
                                                                                                  :default      nil}}})
                                                            :enable_embedding true
                                                            :embedding_params {:NAME "enabled"}}]
          (testing "Card"
            (is (= [[1]]
                   (mt/rows (client/client :get 202 (card-query-url card "") :NAME "Hudson Borer"))
                   (mt/rows (client/client :get 202 (card-query-url card "") :NAME "Hudson Borer" :NAME "x")))))
          (testing "Dashcard"
            (mt/with-temp [:model/Dashboard {dashboard-id :id} {:enable_embedding true
                                                                :embedding_params {:name "enabled"}
                                                                :parameters       [{:name      "Name"
                                                                                    :slug      "name"
                                                                                    :id        "_name_"
                                                                                    :type      "string/="
                                                                                    :sectionId "string"}]}

                           :model/DashboardCard dashcard {:card_id            card-id
                                                          :dashboard_id       dashboard-id
                                                          :parameter_mappings [{:parameter_id "_name_"
                                                                                :card_id      card-id
                                                                                :target       [:dimension [:template-tag "NAME"]]}]}]
              (is (= [[1]]
                     (mt/rows (client/client :get 202 (dashcard-url dashcard) :name "Hudson Borer"))
                     (mt/rows (client/client :get 202 (dashcard-url dashcard) :name "Hudson Borer" :name "x")))))))))))

(deftest pass-numeric-param-as-number-test
  (testing "Embedded numeric params should work with numeric (as opposed to string) values in the JWT (#20845)"
    (mt/dataset test-data
      (with-embedding-enabled-and-new-secret-key!
        (mt/with-temp [:model/Card card {:dataset_query    (mt/native-query
                                                             {:query         "SELECT count(*) FROM orders WHERE quantity = {{qty_locked}}"
                                                              :template-tags {"qty_locked" {:name         "qty_locked"
                                                                                            :display-name "Quantity (Locked)"
                                                                                            :type         :number}}})
                                         :enable_embedding true
                                         :embedding_params {:qty_locked "locked"}}]
          (is (= [3443]
                 (mt/first-row (client/client :get 202 (card-query-url card "" {:params {:qty_locked 1}}))))))))))

(deftest biginteger-numeric-param-between-test
  (testing "Embedded numeric params with mixed long and biginteger values in a between filter should be correctly applied"
    (mt/dataset test-data
      (with-embedding-enabled-and-new-secret-key!
        (mt/with-temp [:model/Card source-card {:dataset_query (mt/native-query
                                                                 {:query "SELECT CAST('9223372036854775808' AS DECIMAL) as NUMBER UNION ALL
                                                                          SELECT CAST('0' AS DECIMAL) as NUMBER UNION ALL
                                                                          SELECT CAST('-9223372036854775809' as DECIMAL) as NUMBER"})}
                       :model/Card target-card {:dataset_query {:database (mt/id)
                                                                :type     :query
                                                                :query    {:source-table (format "card__%s" (u/the-id source-card))
                                                                           :aggregation  [[:count]]}}}
                       :model/Dashboard dashboard {:parameters       [{:id "number" :slug "number" :name "number" :type :number/between}]
                                                   :enable_embedding true
                                                   :embedding_params {:number "enabled"}}
                       :model/DashboardCard dashcard {:dashboard_id       (u/the-id dashboard)
                                                      :card_id            (u/the-id target-card)
                                                      :parameter_mappings [{:parameter_id "number"
                                                                            :card_id      (u/the-id target-card)
                                                                            :target       [:dimension [:field "NUMBER" {:base-type :type/Decimal}]]}]}]
          (is (=? {:status "completed"
                   :data   {:rows [[3]]}}
                  (client/client :get 202 (dashcard-url dashcard))))
          (is (=? {:status "completed"
                   :data   {:rows [[2]]}}
                  (client/client :get 202 (dashcard-url dashcard {:params {:number ["-9223372036854775809", 0]}})))))))))

(deftest format-export-middleware-test
  (testing "The `:format-export?` query processor middleware has the intended effect on file exports."
    (let [q             {:database (mt/id)
                         :type     :native
                         :native   {:query "SELECT 2000 AS number, '2024-03-26'::DATE AS date;"}}
          output-helper {:csv  (fn [output] (->> output csv/read-csv last))
                         :json (fn [output] (->> output (map (juxt :NUMBER :DATE)) last))}]
      (with-embedding-enabled-and-new-secret-key!
        (mt/with-temp [:model/Card {card-id :id} {:enable_embedding true
                                                  :display :table :dataset_query q}
                       :model/Dashboard {dashboard-id :id} {:enable_embedding true
                                                            :embedding_params {:name "enabled"}}
                       :model/DashboardCard {dashcard-id :id} {:dashboard_id dashboard-id
                                                               :card_id      card-id}]
          (doseq [[export-format apply-formatting? expected] [[:csv true ["2,000" "March 26, 2024"]]
                                                              [:csv false ["2000" "2024-03-26"]]
                                                              [:json true ["2,000" "March 26, 2024"]]
                                                              [:json false [2000 "2024-03-26"]]]]
            (testing (format "export_format %s yields expected output for %s exports." apply-formatting? export-format)
              (is (= expected
                     (->> (mt/user-http-request
                           :crowberto :get 200
                           (format "embed/card/%s/query/%s?format_rows=%s"
                                   (card-token card-id) (name export-format) apply-formatting?))
                          ((get output-helper export-format)))))
              (is (= expected
                     (->> (mt/user-http-request
                           :crowberto :get 200
                           (format "embed/dashboard/%s/dashcard/%s/card/%s/%s?format_rows=%s"
                                   (dash-token dashboard-id) dashcard-id card-id (name export-format) apply-formatting?))
                          ((get output-helper export-format))))))))))))

(deftest filter-linked-to-locked-filter-test
  (testing "Filter linked to locked filter works in various common configurations."
    (mt/dataset test-data
      (with-embedding-enabled-and-new-secret-key!
        (mt/with-temp [:model/Card {card-id :id} {:enable_embedding true
                                                  :display          :table
                                                  :dataset_query    {:database (mt/id)
                                                                     :type     :query
                                                                     :query    {:source-table (mt/id :products)}}}
                       :model/Dashboard {dashboard-id :id} {:enable_embedding true
                                                            :parameters
                                                            [{:name      "Category"
                                                              :slug      "category"
                                                              :id        "ad5f614b"
                                                              :type      :string/=
                                                              :sectionId "string"}
                                                             {:name                "Title"
                                                              :slug                "title"
                                                              :id                  "7ef6f58c"
                                                              :type                :string/=
                                                              :sectionId           "string"
                                                              :filteringParameters ["ad5f614b"]}]
                                                            :embedding_params {:category "locked"
                                                                               :title    "enabled"}}
                       :model/DashboardCard {dashcard-id :id} {:dashboard_id dashboard-id
                                                               :card_id      card-id
                                                               :parameter_mappings
                                                               [{:parameter_id "ad5f614b"
                                                                 :card_id      card-id
                                                                 :target       [:dimension [:field (mt/id :products :category) {:base-type :type/Text}]]}
                                                                {:parameter_id "7ef6f58c"
                                                                 :card_id      card-id
                                                                 :target       [:dimension [:field (mt/id :products :title) {:base-type :type/Text}]]}]}]
          (doseq [{:keys [test-str params]}
                  [{:test-str "Locked filter is not set in the token, so requests should fail."
                    :params   {}}
                   {:test-str "Locked filter is set to `nil` in the token, so requests should fail."
                    :params   {:category nil}}]]
            (testing test-str
              (let [token (dash-token dashboard-id {:params params})]
                (is (= "You must specify a value for :category in the JWT."
                       (mt/user-http-request :crowberto :get 400
                                             (format "embed/dashboard/%s/dashcard/%s/card/%s" token dashcard-id card-id))))
                (is (= "You must specify a value for :category in the JWT."
                       (mt/user-http-request :crowberto :get 400
                                             (format "embed/dashboard/%s/params/%s/values" token "7ef6f58c")))))))
          (doseq [{:keys [test-str params expected-row-count expected-values-count]}
                  [{:test-str              "Locked filter is set to a list of values in the token."
                    :params                {:category ["Widget"]}
                    :expected-row-count    54
                    :expected-values-count 54}
                   {:test-str              "Locked filter is set to an empty list in the token."
                    :params                {:category []}
                    :expected-row-count    200
                    :expected-values-count 199}]]
            (testing test-str
              (let [token        (dash-token dashboard-id {:params params})
                    row-count    (-> (mt/user-http-request :crowberto :get 202
                                                           (format "embed/dashboard/%s/dashcard/%s/card/%s" token dashcard-id card-id))
                                     (get-in [:data :rows])
                                     count)
                    values-count (-> (mt/user-http-request :crowberto :get 200
                                                           (format "embed/dashboard/%s/params/%s/values" token "7ef6f58c"))
                                     :values
                                     count)]
                (testing "query has the expected results"
                  (is (= expected-row-count row-count)))
                (testing "the correct amount of filter values are returned"
                  (is (= expected-values-count values-count)))))))))))

(deftest querying-a-dashboard-dashcard-updates-last-viewed-at
  (mt/test-helpers-set-global-values!
    (mt/dataset test-data
      (with-embedding-enabled-and-new-secret-key!
        (with-temp-dashcard [dashcard {:dash {:enable_embedding true
                                              :last_viewed_at #t "2000-01-01"}}]
          (let [dashboard-id (t2/select-one-fn :id :model/Dashboard :id (:dashboard_id dashcard))
                original-last-viewed-at (t2/select-one-fn :last_viewed_at :model/Dashboard dashboard-id)]
            (mt/with-temporary-setting-values [synchronous-batch-updates true]
              (client/client :get 202 (dashcard-url dashcard))
              (is (not= original-last-viewed-at (t2/select-one-fn :last_viewed_at :model/Dashboard :id dashboard-id))))))))))

(deftest dashboard-param-value-remapping-test
  (mt/dataset test-data
    (with-embedding-enabled-and-new-secret-key!
      (mt/with-temp [:model/Card {orders-card-id :id} {:enable_embedding true
                                                       :display          :table
                                                       :dataset_query    (mt/mbql-query orders)}
                     :model/Dashboard {dashboard-id :id} {:enable_embedding true
                                                          :parameters
                                                          [{:name      "Price"
                                                            :slug      "price"
                                                            :id        "price-param"
                                                            :type      :number/=}
                                                           {:name                "User ID"
                                                            :slug                "user-id"
                                                            :id                  "user-id-param"
                                                            :type                :number/between
                                                            :filteringParameters ["price-param"]}]
                                                          :embedding_params {:price   "locked"
                                                                             :user-id "enabled"}}
                     :model/DashboardCard _ {:dashboard_id dashboard-id
                                             :card_id      orders-card-id
                                             :parameter_mappings
                                             [{:parameter_id "price-param"
                                               :card_id      orders-card-id
                                               :target       [:dimension [:field (mt/id :orders :total) {:base-type :type/Float}]]}
                                              {:parameter_id "user-id-param"
                                               :card_id      orders-card-id
                                               :target       [:dimension [:field (mt/id :orders :user_id) {:base-type :type/Integer}]]}]}]
        (doseq [{:keys [test-str params]}
                [{:test-str "Locked filter is not set in the token, so requests should fail."
                  :params   {}}
                 {:test-str "Locked filter is set to `nil` in the token, so requests should fail."
                  :params   {:price nil}}]]
          (testing test-str
            (let [token (dash-token dashboard-id {:params params})]
              (is (= "Cannot get remapped value for parameter: \"price\" is not an enabled parameter."
                     (mt/user-http-request :crowberto :get 400
                                           (format "embed/dashboard/%s/params/%s/remapping?value=%s" token "price-param" "15.69"))))
              (is (= "You must specify a value for :price in the JWT."
                     (mt/user-http-request :crowberto :get 400
                                           (format "embed/dashboard/%s/params/%s/remapping?value=%s" token "user-id-param" 437)))))))
        (doseq [{:keys [test-str params expected]}
                [{:test-str "Locked filter is set to a list of values in the token, value can be found."
                  :params   {:price ["15.69" "32.14"]}
                  :expected [437 "Danial Keeling"]}
                 {:test-str "Locked filter is set to a single value in the token, value filtered out."
                  :params   {:price "15.69"}
                  :expected ["42"]}
                 {:test-str "Locked filter is set to an empty list in the token, value can be found."
                  :params   {:price []}
                  :expected [42 "Reyes Strosin"]}]]
          (testing test-str
            (let [token (dash-token dashboard-id {:params params})
                  value (first expected)
                  url   (format "embed/dashboard/%s/params/%s/remapping?value=%s" token "user-id-param" value)]
              (is (= expected (client/client :get 200 url))))))))))

(deftest card-param-value-remapping-test
  (let [param-static-list          "_STATIC_CATEGORY_"
        param-static-list-label    "_STATIC_CATEGORY_LABEL_"
        param-card                 "_CARD_"
        param-field-values         "name_param_id"
        param-labeled-field-values "id"
        param-not-enabled          "not-enabled-id"]
    (with-embedding-enabled-and-new-secret-key!
      (mt/with-temp
        [:model/Card source-card {:database_id   (mt/id)
                                  :table_id      (mt/id :venues)
                                  :dataset_query (mt/mbql-query venues {:limit 5})}
         :model/Card field-filter-card {:enable_embedding true
                                        :dataset_query
                                        {:database (mt/id)
                                         :type     :native
                                         :native   {:query         "SELECT COUNT(*) FROM VENUES WHERE {{NAME}} and {{PRICE}}"
                                                    :template-tags {"NAME" {:id           param-field-values
                                                                            :name         "NAME"
                                                                            :display_name "Name"
                                                                            :type         :dimension
                                                                            :dimension    [:field (mt/id :venues :name) nil]
                                                                            :required     true}
                                                                    "PRICE" {:id           param-not-enabled
                                                                             :name         "PRICE"
                                                                             :display_name "Price"
                                                                             :type         :dimension
                                                                             :dimension    [:field (mt/id :venues :price) nil]
                                                                             :required     true}}}}
                                        :name       "native card with field filter"
                                        :parameters [{:id     param-field-values
                                                      :type   :string/=
                                                      :target [:dimension [:template-tag "NAME"]]
                                                      :name   "Name"
                                                      :slug   "name"}
                                                     {:id     param-not-enabled
                                                      :type   :number/>=
                                                      :target [:dimension [:template-tag "PRICE"]]
                                                      :name   "Price"
                                                      :slug   "price"}]
                                        :embedding_params {:name :enabled
                                                           :price :locked}}
         :model/Card name-mapped-card {:enable_embedding true
                                       :dataset_query
                                       {:database (mt/id)
                                        :type     :native
                                        :native   {:query         "SELECT COUNT(*) FROM PEOPLE WHERE {{ID}}"
                                                   :template-tags {"id" {:id           param-labeled-field-values
                                                                         :name         "ID"
                                                                         :display_name "Id"
                                                                         :type         :dimension
                                                                         :dimension    [:field (mt/id :people :id) nil]
                                                                         :required     true}}}}
                                       :name       "native card with named field filter"
                                       :parameters [{:id     param-labeled-field-values
                                                     :type   :number/>=
                                                     :target [:dimension [:template-tag "id"]]
                                                     :name   "Id"
                                                     :slug   "id"}]
                                       :embedding_params {:id :enabled}}
         :model/Card card {:enable_embedding true
                           :database_id   (mt/id)
                           :dataset_query (mt/mbql-query venues)
                           :parameters    [{:name                 "Static Category"
                                            :slug                 "static_category"
                                            :id                   param-static-list
                                            :type                 "category"
                                            :values_source_type   "static-list"
                                            :values_source_config {:values ["African" "American" "Asian"]}}
                                           {:name                 "Static Category label"
                                            :slug                 "static_category_label"
                                            :id                   param-static-list-label
                                            :type                 "category"
                                            :values_source_type   "static-list"
                                            :values_source_config {:values [["Af rican" "Af"] ["American" "Am"] ["Asian" "As"]]}}
                                           {:name                 "Card as source"
                                            :slug                 "card"
                                            :id                   param-card
                                            :type                 "category"
                                            :values_source_type   "card"
                                            :values_source_config {:card_id     (:id source-card)
                                                                   :value_field (mt/$ids $venues.name)}}]
                           :embedding_params (zipmap [:static_category :static_category_label :card] (repeat :enabled))
                           :table_id      (mt/id :venues)}]
        (let [url #(format "embed/card/%s/params/%s/remapping?value=%s" (card-token %1) %2 %3)
              request #(client/client :get 200 (url %1 %2 %3))]
          (are [card value-source value] (= [value] (request card value-source value))
            field-filter-card param-field-values      "20th Century Cafe"
            field-filter-card param-field-values      "Not a value in the DB"
            card              param-card              "33 Taps"
            card              param-card              "Not provided by the card"
            card              param-static-list       "African"
            card              param-static-list       "Whatever"
            card              param-static-list-label "European")
          (is (= ["Af rican" "Af"] (request card param-static-list-label "Af rican")))
          (is (= [42 "Reyes Strosin"] (request name-mapped-card param-labeled-field-values "42")))
          (is (= "Cannot get remapped value for parameter: \"price\" is not an enabled parameter."
                 (client/client :get 400 (url field-filter-card param-not-enabled "3")))))))))

;;; ------------------------------------------ Tile endpoints ---------------------------------------------------------

(defn- png? [s]
  (= [\P \N \G] (drop 1 (take 4 s))))

(defn- venues-query
  []
  {:database (mt/id)
   :type     :query
   :query    {:source-table (mt/id :people)
              :fields [[:field (mt/id :people :id) nil]
                       [:field (mt/id :people :state) nil]
                       [:field (mt/id :people :latitude) nil]
                       [:field (mt/id :people :longitude) nil]]}})

(deftest card-tile-query-test
  (testing "GET api/embed/tiles/card/:uuid/:zoom/:x/:y/:lat-field/:lon-field"
    (with-embedding-enabled-and-new-secret-key!
      (mt/with-temp [:model/Card {card-id :id} {:dataset_query (venues-query)
                                                :enable_embedding true}]
        (let [token (card-token card-id)]
          (is (png? (mt/user-http-request
                     :crowberto :get 200 (format "embed/tiles/card/%s/1/1/1/%s/%s"
                                                 token
                                                 (tiles.api-test/encoded-lat-field-ref)
                                                 (tiles.api-test/encoded-lon-field-ref))))))))))

(deftest dashcard-tile-query-test
  (testing "GET api/embed/tiles/dashboard/:uuid/dashcard/:dashcard-id/card/:card-id/:zoom/:x/:y/:lat-field/:lon-field"
    (with-embedding-enabled-and-new-secret-key!
      (mt/with-temp [:model/Dashboard     {dashboard-id :id} {:enable_embedding true}

                     :model/Card          {card-id :id}      {:dataset_query (venues-query)}
                     :model/DashboardCard {dashcard-id :id}  {:card_id card-id
                                                              :dashboard_id dashboard-id}]
        (let [token (dash-token dashboard-id)]
          (is (png? (mt/user-http-request
                     :crowberto :get 200 (format "embed/tiles/dashboard/%s/dashcard/%d/card/%d/1/1/1/%s/%s"
                                                 token
                                                 dashcard-id
                                                 card-id
                                                 (tiles.api-test/encoded-lat-field-ref)
                                                 (tiles.api-test/encoded-lon-field-ref))))))))))

(deftest embedded-string-parameter-case-sensitivity-regression-test
  "Regression test for metabase#29371 - Case-sensitive field filters in embedded dashboards.
   Embedded dashboards should apply case-insensitive default options for string operators."
  (mt/dataset test-data
    (mt/with-temp
      [:model/Card card {:database_id (mt/id)
                         :table_id (mt/id :venues)
                         :dataset_query (mt/mbql-query venues)}
       :model/Dashboard dashboard {:parameters [{:name "Name Contains"
                                                 :slug "name_contains"
                                                 :id "_NAME_CONTAINS_"
                                                 :type :string/contains}]}
       :model/DashboardCard dashcard {:card_id (:id card)
                                      :dashboard_id (:id dashboard)
                                      :parameter_mappings [{:parameter_id "_NAME_CONTAINS_"
                                                            :card_id (:id card)
                                                            :target [:dimension (mt/$ids venues $name)]}]}]
      (with-embedding-enabled-and-new-secret-key!
        (t2/update! :model/Dashboard (:id dashboard)
                    {:enable_embedding true
                     :embedding_params {"name_contains" "enabled"}})

        (letfn [(dashcard-query-url [params]
                  (format "embed/dashboard/%s/dashcard/%s/card/%s"
                          (dash-token dashboard (when params {:params params}))
                          (:id dashcard)
                          (:id card)))]

          (testing "Field filter should work case-insensitively in embedded dashboards"
            (testing "Query with lowercase 'red' should find venues with 'Red' in the name"
              (let [response    (client/client :get 202 (dashcard-query-url {"name_contains" "red"}))
                    _           (is (= "completed" (:status response)))
                    ;; Get venue data from the response
                    venue-rows  (->> response :data :rows)
                    venue-ids   (set (map first venue-rows))
                    venue-names (set (map second venue-rows))]
                ;; Verify we found the right number of venues
                (is (= #{1 10} venue-ids)
                    "Should find venues 1 and 10 when filtering with lowercase 'red' (case-insensitive)")
                ;; Explicitly verify that we matched both "Red Medicine" (uppercase) and "Fred 62" (lowercase)
                (testing "Should match venues with both uppercase 'Red' and lowercase 'red'"
                  (is (some #(re-find #"Red" %) venue-names)
                      "Should find at least one venue with uppercase 'Red' in the name")
                  (is (some #(re-find #"red" %) venue-names)
                      "Should find at least one venue with lowercase 'red' in the name"))))))))))
