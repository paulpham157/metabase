(ns metabase.pulse.dashboard-subscription-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase.channel.core :as channel]
   [metabase.channel.email.result-attachment :as email.result-attachment]
   [metabase.channel.impl.slack :as channel.slack]
   [metabase.channel.render.body :as body]
   [metabase.channel.shared :as channel.shared]
   [metabase.notification.payload.execute :as notification.payload.execute]
   [metabase.notification.payload.temp-storage :as notification.temp-storage]
   [metabase.notification.test-util :as notification.tu]
   [metabase.permissions.models.data-permissions :as data-perms]
   [metabase.permissions.models.permissions-group :as perms-group]
   [metabase.pulse.send :as pulse.send]
   [metabase.pulse.test-util :as pulse.test-util]
   [metabase.system.core :as system]
   [metabase.test :as mt]
   [metabase.util :as u]
   [metabase.util.random :as random]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn do-with-dashboard-sub-for-card
  "Creates a Pulse, Dashboard, and other relevant rows for a `card` (using `pulse` and `pulse-card` properties if
  specified), then invokes

    (f pulse)"
  [{:keys [dashboard pulse pulse-card channel card dashcard]
    :or   {channel :email}}
   f]
  (mt/with-temp [:model/Pulse         {pulse-id :id, :as pulse} (merge {:name         "Aviary KPIs"
                                                                        :dashboard_id (u/the-id dashboard)}
                                                                       pulse)
                 :model/PulseCard     _ (merge {:pulse_id pulse-id
                                                :card_id  (u/the-id card)
                                                :position 0}
                                               pulse-card)
                 :model/DashboardCard _ (merge {:dashboard_id (u/the-id dashboard)
                                                :row          0
                                                :card_id      (u/the-id card)}
                                               dashcard)
                 :model/PulseChannel  {pc-id :id} (case channel
                                                    :email
                                                    {:pulse_id pulse-id}

                                                    :slack
                                                    {:pulse_id     pulse-id
                                                     :channel_type "slack"
                                                     :details      {:channel "#general"}})]
    (if (= channel :email)
      (mt/with-temp [:model/PulseChannelRecipient _ {:user_id          (pulse.test-util/rasta-id)
                                                     :pulse_channel_id pc-id}]
        (f pulse))
      (f pulse))))

(defmacro with-dashboard-sub-for-card
  "e.g.

    (with-dashboard-sub-for-card [pulse {:card my-card, :pulse pulse-properties, ...}]
      ...)"
  [[pulse-binding properties] & body]
  `(do-with-dashboard-sub-for-card ~properties (fn [~pulse-binding] ~@body)))

(defn- do-test!
  "Run a single Pulse test with a standard set of boilerplate. Creates Card, Pulse, and other related objects using
  `card`, `dashboard`, `pulse`, and `pulse-card` properties, then sends the Pulse; finally, test assertions in
  `assert` are invoked.  `assert` can contain `:email` and/or `:slack` assertions, which are used to test an email and
  Slack version of that Pulse respectively. `:assert` functions have the signature

    (f object-ids send-pulse!-response)

  Example:

    (do-test
     {:card   {:dataset_query (mt/mbql-query checkins)}
      :assert {:slack (fn [{:keys [pulse-id]} response]
                        (is (= {:sent pulse-id}
                               response)))}})"
  [{:keys [card dashboard dashcard pulse pulse-card fixture display], assertions :assert}]
  {:pre [(map? assertions) ((some-fn :email :slack) assertions)]}
  (doseq [channel-type [:email :slack]
          :let         [f (get assertions channel-type)]
          :when        f]
    (assert (fn? f))
    (testing (format "sent to %s channel" channel-type)
      (mt/with-temp [:model/Dashboard     {dashboard-id :id} (->> dashboard
                                                                  (merge {:name "Aviary KPIs"
                                                                          :description "How are the birds doing today?"}))
                     :model/Card          {card-id :id} (merge {:name pulse.test-util/card-name
                                                                :display (or display :line)} card)]
        (with-dashboard-sub-for-card [{pulse-id :id}
                                      {:card       card-id
                                       :creator_id (mt/user->id :rasta)
                                       :dashboard  dashboard-id
                                       :dashcard   dashcard
                                       :pulse      pulse
                                       :pulse-card pulse-card
                                       :channel    channel-type}]
          (letfn [(thunk* []
                    (f {:dashboard-id dashboard-id,
                        :card-id card-id,
                        :pulse-id pulse-id}
                       ((if (= :email channel-type)
                          :channel/email
                          :channel/slack)
                        (pulse.test-util/with-captured-channel-send-messages!
                          (mt/with-temporary-setting-values [site-url "https://testmb.com"]
                            (pulse.send/send-pulse! (t2/select-one :model/Pulse pulse-id)))))))
                  (thunk []
                    (if fixture
                      (fixture {:dashboard-id dashboard-id,
                                :card-id card-id,
                                :pulse-id pulse-id} thunk*)
                      (thunk*)))]
            (case channel-type
              :email (thunk)
              :slack (pulse.test-util/slack-test-setup! (thunk)))))))))

(defn- tests!
  "Convenience for writing multiple tests using [[do-test]]. `common` is a map of shared properties as passed
  to [[do-test]] that is deeply merged with the individual maps for each test. Other args are alternating `testing`
  context messages and properties as passed to [[do-test]]:

    (tests
     ;; shared properties used for both tests
     {:card {:dataset_query (mt/mbql-query)}}

     \"Test 1\"
     {:assert {:email (fn [_object-ids _response] (is ...))}}

     \"Test 2\"
     ;; override just the :display property of the Card
     {:card   {:display \"table\"}
      :assert {:email (fn [_object-ids _response] (is ...))}})"
  [common & {:as message->m}]
  (doseq [[message m] message->m]
    (testing message
      (do-test! (merge-with merge common m)))))

(defn- rasta-dashsub-message
  [& [data]]
  (merge {:subject       "Aviary KPIs"
          :recipients    #{"rasta@metabase.com"}
          :message-type  :attachments,
          :message        [{"Aviary KPIs" true}
                           pulse.test-util/png-attachment]
          :recipient-type nil}
         data))

(defn do-with-dashboard-fixture-for-dashboard
  "Impl for [[with-link-card-fixture-for-dashboard]]."
  [dashboard thunk]
  (let [dashboard-id          (:id dashboard)
        link-card-viz-setting (fn [model id]
                                {:virtual_card {:display "link"}
                                 :link         {:entity {:id    id
                                                         :model model}}})
        rasta-id              (mt/user->id :rasta)
        rasta-pc-id           (t2/select-one-fn :id :model/Collection :personal_owner_id rasta-id)]
    (mt/with-temp
      [:model/Collection    {coll-id :id}      {:name        "Linked collection name"
                                                :description "Linked collection desc"
                                                :location    (format "/%d/" rasta-pc-id)}
       :model/Database      {db-id :id}        {:name        "Linked database name"
                                                :description "Linked database desc"}
       :model/Table         {table-id :id}     {:db_id        db-id
                                                :name        "Linked table name"
                                                :display_name "Linked table dname"
                                                :description "Linked table desc"}
       :model/Card          {card-id :id}      {:name          "Linked card name"
                                                :description   "Linked card desc"
                                                :display       "bar"
                                                :collection_id coll-id}
       :model/Card          {model-id :id}     {:type          :model
                                                :name          "Linked model name"
                                                :description   "Linked model desc"
                                                :display       "table"
                                                :collection_id coll-id}
       :model/Dashboard     {dash-id :id}      {:name          "Linked Dashboard name"
                                                :description   "Linked Dashboard desc"
                                                :collection_id coll-id}
       :model/DashboardCard _                  {:dashboard_id           dashboard-id
                                                :row                    1
                                                :visualization_settings (link-card-viz-setting "collection" coll-id)}
       :model/DashboardCard _                  {:dashboard_id           dashboard-id
                                                :row                    2
                                                :visualization_settings (link-card-viz-setting "database" db-id)}
       :model/DashboardCard _                  {:dashboard_id           dashboard-id
                                                :row                    3
                                                :visualization_settings (link-card-viz-setting "table" table-id)}
       :model/DashboardCard _                  {:dashboard_id           dashboard-id
                                                :row                    4
                                                :visualization_settings (link-card-viz-setting "dashboard" dash-id)}
       :model/DashboardCard _                  {:dashboard_id           dashboard-id
                                                :row                    5
                                                :visualization_settings (link-card-viz-setting "card" card-id)}
       :model/DashboardCard _                  {:dashboard_id           dashboard-id
                                                :row                    6
                                                :visualization_settings (link-card-viz-setting "dataset" model-id)}
       :model/DashboardCard _                  {:dashboard_id           dashboard-id
                                                :row                    7
                                                :visualization_settings {:virtual_card {:display "link"}
                                                                         :link         {:url "https://metabase.com"}}}]
      (thunk {:collection-owner-id rasta-id
              :collection-id       coll-id
              :database-id         db-id
              :table-id            table-id
              :card-id             card-id
              :model-id            model-id
              :dashboard-id        dash-id
              :url                 "https://metabase.com"}))))

(defmacro with-link-card-fixture-for-dashboard
  "Given a dashboard, prepare a list of linkcards that connected to it and execute the body."
  {:style/indent 2}
  [dashboard [binding] & body]
  `(do-with-dashboard-fixture-for-dashboard
    ~dashboard
    (fn [~binding] ~@body)))

(defn- append-subscription-branding-content
  "Appends branding content to the :fields list in the Slack link-section.
   Unless we're running the Pro/Enterprise plan, all Slack header links will include branding"
  [fields]
  (conj fields {:text "Made with Metabase :blue_heart:"
                :type "mrkdwn"}))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                     Tests                                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn execute-dashboard
  [& args]
  (let [dashboard-result (apply notification.payload.execute/execute-dashboard args)]
    (map channel.shared/maybe-realize-data-rows dashboard-result)))

(deftest ^:parallel execute-dashboard-test
  (testing "it runs for each non-virtual card"
    (mt/with-temp [:model/Card          {card-id-1 :id} {:dataset_query (mt/mbql-query venues)}
                   :model/Card          {card-id-2 :id} {:dataset_query (mt/mbql-query venues)}
                   :model/Dashboard     {dashboard-id :id, :as dashboard} {:name "Birdfeed Usage"}
                   :model/DashboardCard _ {:dashboard_id dashboard-id :card_id card-id-1}
                   :model/DashboardCard _ {:dashboard_id dashboard-id :card_id card-id-2}
                   :model/User {user-id :id} {}]
      (let [result (execute-dashboard (:id dashboard) user-id nil)]
        (is (malli= [:sequential
                     {:min 2, :max 2}
                     [:map
                      [:card     :map]
                      [:dashcard :map]
                      [:result   :map]
                      [:type     [:= :card]]]]
                    result))))))

(deftest ^:parallel execute-dashboard-test-2
  (testing "hides empty card when card.hide_empty is true and there are no results."
    (mt/with-temp [:model/Card          {card-id-1 :id} {:dataset_query (mt/mbql-query venues)}
                   :model/Card          {card-id-2 :id} {:dataset_query (assoc-in (mt/mbql-query venues) [:query :limit] 0)}
                   :model/Card          {card-id-3 :id} {:dataset_query (assoc-in (mt/mbql-query venues) [:query :limit] 0)}
                   :model/Card          {card-id-4 :id} {:dataset_query (mt/mbql-query venues)}
                   :model/Dashboard     {dashboard-id :id, :as dashboard} {:name "Birdfeed Usage"}
                   :model/DashboardCard _ {:dashboard_id dashboard-id :card_id card-id-1}
                   :model/DashboardCard _ {:dashboard_id dashboard-id :card_id card-id-2 :visualization_settings {:card.hide_empty true}}
                   :model/DashboardCard _ {:dashboard_id dashboard-id :card_id card-id-3}
                   :model/DashboardCard _ {:dashboard_id dashboard-id :card_id card-id-4}
                   :model/User {user-id :id} {}]
      (let [result (execute-dashboard (:id dashboard) user-id nil)]
        (is (= 3 (count result)))))))

(deftest ^:parallel execute-dashboard-test-3
  (testing "dashboard cards are ordered correctly -- by rows, and then by columns (#17419)"
    (mt/with-temp [:model/Card          {card-id-1 :id} {:dataset_query (mt/mbql-query venues)}
                   :model/Card          {card-id-2 :id} {:dataset_query (mt/mbql-query venues)}
                   :model/Card          {card-id-3 :id} {:dataset_query (mt/mbql-query venues)}
                   :model/Dashboard     {dashboard-id :id, :as dashboard} {:name "Birdfeed Usage"}
                   :model/DashboardCard _ {:dashboard_id dashboard-id :card_id card-id-1 :row 1 :col 0}
                   :model/DashboardCard _ {:dashboard_id dashboard-id :card_id card-id-2 :row 0 :col 1}
                   :model/DashboardCard _ {:dashboard_id dashboard-id :card_id card-id-3 :row 0 :col 0}
                   :model/User {user-id :id} {}]
      (let [result (execute-dashboard (:id dashboard) user-id nil)]
        (is (= [card-id-3 card-id-2 card-id-1]
               (map #(-> % :card :id) result)))))))

(deftest ^:parallel execute-dashboard-test-4
  (testing "virtual (text) cards are returned as a viz settings map"
    (mt/with-temp [:model/Card          _ {:dataset_query (mt/mbql-query venues)}
                   :model/Card          _ {:dataset_query (mt/mbql-query venues)}
                   :model/Dashboard     {dashboard-id :id, :as dashboard} {:name "Birdfeed Usage"}
                   :model/DashboardCard _ {:dashboard_id dashboard-id
                                           :visualization_settings {:virtual_card {}, :text "test"}}
                   :model/User {user-id :id} {}]
      (is (= [{:virtual_card {} :text "test" :type :text}]
             (execute-dashboard (:id dashboard) user-id nil))))))

(deftest basic-table-test
  (tests!
   {:pulse {:skip_if_empty false} :display :table}
   "9 results, so no attachment aside from dashboard icon"
   {:card (pulse.test-util/checkins-query-card {:aggregation nil, :limit 9})

    :fixture
    (fn [_ thunk]
      (with-redefs [body/attached-results-text (pulse.test-util/wrap-function @#'body/attached-results-text)]
        (mt/with-temporary-setting-values [site-name "Metabase Test"]
          (thunk))))

    :assert
    {:email
     (fn [_ [email]]
       (is (= (rasta-dashsub-message
               {:message [{;; No "Pulse:" prefix
                           "Aviary KPIs" true
                           ;; Includes dashboard description
                           "How are the birds doing today?" true
                           ;; Includes name of subscription creator
                           "Sent by Rasta Toucan" true
                           ;; Includes everything
                           "More results have been included" false
                           ;; Inline table
                           "ID</th>" true
                           ;; Links to source dashboard
                           "<a class=\\\"title\\\" href=\\\"https://testmb.com/dashboard/\\d+\\\"" true
                           ;; Links to Metabase instance
                           "Sent from <a href=\\\"https://testmb.com\\\"" true
                           ;; Links to subscription management page in account settings
                           "\\\"https://testmb.com/account/notifications\\\"" true
                           "Manage your subscriptions" true}
                          pulse.test-util/png-attachment]})
              (mt/summarize-multipart-single-email email
                                                   #"Aviary KPIs"
                                                   #"How are the birds doing today?"
                                                   #"Sent by Rasta Toucan"
                                                   #"More results have been included"
                                                   #"ID</th>"
                                                   #"<a class=\"title\" href=\"https://testmb.com/dashboard/\d+\""
                                                   #"Sent from <a href=\"https://testmb.com\""
                                                   #"\"https://testmb.com/account/notifications\""
                                                   #"Manage your subscriptions"))))
     :slack
     (fn [{:keys [card-id dashboard-id]} [pulse-results]]
       ;; If we don't force the thunk, the rendering code will never execute and attached-results-text won't be
       ;; called
       (testing "\"more results in attachment\" text should not be present for Slack Pulses"
         (testing "Pulse results"
           (is (= {:channel-id "#general"
                   :attachments
                   [{:blocks [{:type "header", :text {:type "plain_text", :text "Aviary KPIs", :emoji true}}
                              {:type "section",
                               :fields (append-subscription-branding-content [{:type "mrkdwn"
                                                                               :text (str "<https://testmb.com/dashboard/"
                                                                                          dashboard-id
                                                                                          "|*Sent from Metabase Test by Rasta Toucan*>")}])}]}
                    {:title             pulse.test-util/card-name
                     :rendered-info     {:attachments false
                                         :content     true}
                     :inline-parameters []
                     :title_link        (str "https://testmb.com/question/" card-id)
                     :attachment-name   "image.png"
                     :fallback          pulse.test-util/card-name}]}
                  (pulse.test-util/thunk->boolean pulse-results))))
         (testing "attached-results-text should be invoked exactly once"
           (is (= 1
                  (count (pulse.test-util/input @#'body/attached-results-text)))))
         (testing "attached-results-text should return nil since it's a slack message"
           (is (= [nil]
                  (pulse.test-util/output @#'body/attached-results-text))))))}}))

(deftest virtual-card-test
  (tests!
   {:pulse {:skip_if_empty false}, :dashcard {:row 0, :col 0}}
   "Dashboard subscription that includes a virtual (markdown) card"
   {:card (pulse.test-util/checkins-query-card {})

    :fixture
    (fn [{dashboard-id :dashboard-id} thunk]
      (mt/with-temp [:model/DashboardCard _ {:dashboard_id dashboard-id
                                             :row 1
                                             :col 1
                                             :visualization_settings {:text "# header"}}]
        (mt/with-temporary-setting-values [site-name "Metabase Test"]
          (thunk))))

    :assert
    {:email
     (fn [_ [email]]
       (testing "Markdown cards are included in email subscriptions"
         (is (= (rasta-dashsub-message {:message [{"Aviary KPIs" true
                                                   "header"      true}
                                                  pulse.test-util/png-attachment]})
                (mt/summarize-multipart-single-email email #"Aviary KPIs"
                                                     #"header")))))

     :slack
     (fn [{:keys [card-id dashboard-id]} [pulse-results]]
       (testing "Markdown cards are included in attachments list as :blocks sublists, and markdown is
                  converted to mrkdwn (Slack markup language)"
         (is (= {:channel-id "#general"
                 :attachments
                 [{:blocks [{:type "header", :text {:type "plain_text", :text "Aviary KPIs", :emoji true}}
                            {:type "section",
                             :fields (append-subscription-branding-content [{:type "mrkdwn",
                                                                             :text (str "<https://testmb.com/dashboard/"
                                                                                        dashboard-id
                                                                                        "|*Sent from Metabase Test by Rasta Toucan*>")}])}]}
                  {:title             pulse.test-util/card-name
                   :rendered-info     {:attachments false, :content true, :render/text true},
                   :inline-parameters []
                   :title_link        (str "https://testmb.com/question/" card-id)
                   :attachment-name   "image.png"
                   :fallback          pulse.test-util/card-name}
                  {:blocks [{:type "section" :text {:type "mrkdwn" :text "*header*"}}]}]}
                (pulse.test-util/thunk->boolean pulse-results)))))}}))

(deftest virtual-card-heading-test
  (tests!
   {:pulse {:skip_if_empty false}, :dashcard {:row 0, :col 0}}
   "Dashboard subscription that includes a virtual card. For heading cards we escape markdown, add a heading markdown, and don't subsitute tags."
   {:card (pulse.test-util/checkins-query-card {})

    :fixture
    (fn [{dashboard-id :dashboard-id} thunk]
      (mt/with-temp [:model/DashboardCard _ {:dashboard_id dashboard-id
                                             :row 1
                                             :col 1
                                             :visualization_settings {:text "# header, quote isn't escaped" :virtual_card {:display "heading"}}}]
        (mt/with-temporary-setting-values [site-name "Metabase Test"]
          (thunk))))

    :assert
    {:email
     (fn [_ [email]]
       (testing "Markdown cards are included in email subscriptions"
         (is (= (rasta-dashsub-message {:message [{"Aviary KPIs"                 true
                                                   "header, quote isn't escaped" true}
                                                  pulse.test-util/png-attachment]})
                (mt/summarize-multipart-single-email email #"Aviary KPIs"
                                                     #"header, quote isn't escaped")))))

     :slack
     (fn [{:keys [card-id dashboard-id]} [pulse-results]]
       (testing "Markdown cards are included in attachments list as :blocks sublists, and markdown isn't
                  converted to mrkdwn (Slack markup language)"
         (is (= {:channel-id "#general"
                 :attachments
                 [{:blocks [{:type "header", :text {:type "plain_text", :text "Aviary KPIs", :emoji true}}
                            {:type "section",
                             :fields (append-subscription-branding-content [{:type "mrkdwn"
                                                                             :text (str "<https://testmb.com/dashboard/"
                                                                                        dashboard-id
                                                                                        "|*Sent from Metabase Test by Rasta Toucan*>")}])}]}
                  {:title             pulse.test-util/card-name
                   :rendered-info     {:attachments false, :content true, :render/text true},
                   :inline-parameters []
                   :title_link        (str "https://testmb.com/question/" card-id)
                   :attachment-name   "image.png"
                   :fallback          pulse.test-util/card-name}
                  {:blocks [{:type "section" :text {:type "mrkdwn" :text "*# header, quote isn't escaped*"}}]}]}
                (pulse.test-util/thunk->boolean pulse-results)))))}}))

(deftest dashboard-filter-test
  (with-redefs [channel.slack/attachment-text-length-limit 15]
    (tests!
     {:pulse     {:skip_if_empty false}
      :dashboard pulse.test-util/test-dashboard}
     "Dashboard subscription that includes a dashboard filters"
     {:card (pulse.test-util/checkins-query-card {})

      :fixture
      (fn [_ thunk]
        (mt/with-temporary-setting-values [site-name "Metabase Test"]
          (thunk)))

      :assert
      {:email
       (fn [_ [email]]
         (testing "Markdown cards are included in email subscriptions"
           (is (= (rasta-dashsub-message {:message [{"Aviary KPIs" true
                                                     "<a class=\\\"title\\\" href=\\\"https://testmb.com/dashboard/\\d+\\?state=CA&state=NY&state=NJ&quarter_and_year=Q1-2021\\\"" true}
                                                    pulse.test-util/png-attachment]})
                  (mt/summarize-multipart-single-email email #"Aviary KPIs"
                                                       #"<a class=\"title\" href=\"https://testmb.com/dashboard/\d+\?state=CA&state=NY&state=NJ&quarter_and_year=Q1-2021\"")))))

       :slack
       (fn [{:keys [card-id dashboard-id]} [pulse-results]]
         (testing "Markdown cards are included in attachments list as :blocks sublists, and markdown is
                   converted to mrkdwn (Slack markup language) and truncated appropriately"
           (is (= {:channel-id "#general"
                   :attachments
                   [{:blocks [{:type "header", :text {:type "plain_text", :text "Aviary KPIs", :emoji true}}

                              {:type "section",
                               :fields [{:type "mrkdwn", :text "*State*\nCA, NY…"}  ;; "*State*\nCA, NY and NJ"
                                        {:type "mrkdwn", :text "*Quarter and Y…"}]} ;; "*Quarter and Year*\nQ1, 2021"
                              {:type "section",
                               :fields (append-subscription-branding-content [{:type "mrkdwn",
                                                                               :text (str "<https://testmb.com/dashboard/"
                                                                                          dashboard-id
                                                                                          "?state=CA&state=NY&state=NJ&quarter_and_year=Q1-2021|*Sent from Metabase Test by Rasta Toucan*>")}])}]}

                    {:title             pulse.test-util/card-name
                     :rendered-info     {:attachments false, :content true, :render/text true},
                     :inline-parameters []
                     :title_link        (str "https://testmb.com/question/" card-id)
                     :attachment-name   "image.png"
                     :fallback          pulse.test-util/card-name}]}
                  (pulse.test-util/thunk->boolean pulse-results)))))}})))

(deftest dashboard-with-header-filters-test
  (tests!
   {:pulse     {:skip_if_empty false}
    :dashboard pulse.test-util/test-dashboard}
   "Dashboard subscription that includes a header card with inline_parameters"
   {:card (pulse.test-util/checkins-query-card {})

    :fixture
    (fn [{dashboard-id :dashboard-id} thunk]
      (mt/with-temp [:model/DashboardCard _ {:dashboard_id dashboard-id
                                             :row 0
                                             :col 0
                                             :visualization_settings {:virtual_card {:display "heading"}
                                                                      :text "## Dashboard Header"}
                                             :inline_parameters ["63e719d0"]}]
        (mt/with-temporary-setting-values [site-name "Metabase Test"]
          (thunk))))

    :assert
    {:email
     (fn [_ [email]]
       (testing "Header card with inline parameters includes parameter values below header"
         (is (= (rasta-dashsub-message {:message [{"(?s)## Dashboard Header.*State.*CA, NY, and NJ" true}
                                                  pulse.test-util/png-attachment]})
                (mt/summarize-multipart-single-email email #"(?s)## Dashboard Header.*State.*CA, NY, and NJ")))))

     :slack
     (fn [{:keys [card-id dashboard-id]} [pulse-results]]
       (testing "Header card with inline parameters includes parameter values below header"
         (is (= {:channel-id "#general"
                 :attachments
                 [{:blocks [{:type "header", :text {:type "plain_text", :text "Aviary KPIs", :emoji true}}
                            {:type "section",
                             :fields [{:type "mrkdwn", :text "*Quarter and Year*\nQ1, 2021"}]}
                            {:type "section",
                             :fields (append-subscription-branding-content [{:type "mrkdwn",
                                                                             :text (str "<https://testmb.com/dashboard/"
                                                                                        dashboard-id
                                                                                        "?state=CA&state=NY&state=NJ&quarter_and_year=Q1-2021|*Sent from Metabase Test by Rasta Toucan*>")}])}]}
                  {:title             pulse.test-util/card-name
                   :rendered-info     {:attachments false, :content true, :render/text true},
                   :inline-parameters [],
                   :title_link        (str "https://testmb.com/question/" card-id)
                   :attachment-name   "image.png"
                   :fallback          pulse.test-util/card-name}
                  {:blocks [{:type "section" :text {:type "mrkdwn" :text "*## Dashboard Header*"}}
                            {:type "section",
                             :fields [{:type "mrkdwn", :text "*State*\nCA, NY, and NJ"}]}]}]}
                (pulse.test-util/thunk->boolean pulse-results)))))}}))

(deftest dashboard-with-dashcard-filters-test
  (tests!
   {:pulse     {:skip_if_empty false}
    :dashboard pulse.test-util/test-dashboard
    :dashcard  {:inline_parameters ["63e719d0"]}}
   "Dashboard subscription that includes a card with dashcard-level inline parameters"
   {:card (pulse.test-util/checkins-query-card {})

    :fixture
    (fn [_ thunk]
      (mt/with-temporary-setting-values [site-name "Metabase Test"]
        (thunk)))

    :assert
    {:email
     (fn [_ [email]]
       (testing "Dashcard with inline parameters includes parameter values below card title"
         (is (= (rasta-dashsub-message {:message [{"(?s)Test card.*State.*CA, NY, and NJ" true}
                                                  pulse.test-util/png-attachment]})
                (mt/summarize-multipart-single-email email #"(?s)Test card.*State.*CA, NY, and NJ")))))

     :slack
     (fn [{:keys [card-id dashboard-id]} [pulse-results]]
       (testing "Dashcard with inline parameters shows State parameter in dashboard fields"
         (is (= {:channel-id "#general"
                 :attachments
                 [{:blocks [{:type "header", :text {:type "plain_text", :text "Aviary KPIs", :emoji true}}
                            {:type "section",
                             :fields [{:type "mrkdwn", :text "*Quarter and Year*\nQ1, 2021"}]}
                            {:type "section",
                             :fields (append-subscription-branding-content [{:type "mrkdwn",
                                                                             :text (str "<https://testmb.com/dashboard/"
                                                                                        dashboard-id
                                                                                        "?state=CA&state=NY&state=NJ&quarter_and_year=Q1-2021|*Sent from Metabase Test by Rasta Toucan*>")}])}]}
                  {:title             pulse.test-util/card-name
                   :rendered-info     {:attachments false, :content true, :render/text true},
                   :inline-parameters [{:value ["CA" "NY" "NJ"]
                                        :name "State"
                                        :slug "state"
                                        :id "63e719d0"
                                        :type :string/=
                                        :sectionId "location"}]
                   :title_link        (str "https://testmb.com/question/" card-id)
                   :attachment-name   "image.png"
                   :fallback          pulse.test-util/card-name}]}
                (pulse.test-util/thunk->boolean pulse-results)))))}}))

(deftest dashboard-with-link-card-test
  (tests!
   {:pulse     {:skip_if_empty false}
    :dashboard pulse.test-util/test-dashboard}
   "Dashboard that has link cards should render correctly"
   {:card    (pulse.test-util/checkins-query-card {})

    :fixture
    (fn [{dashboard-id :dashboard-id} thunk]
      (mt/with-temporary-setting-values [site-name "Metabase Test"]
        (with-link-card-fixture-for-dashboard (t2/select-one :model/Dashboard :id dashboard-id) [_]
          (thunk))))

    :assert
    {:email
     (fn [_ [email]]
       (is (every?
            true?
            (-> (mt/summarize-multipart-single-email email
                                                     #"https://testmb\.com/collection/\d+"
                                                     #"Linked collection name"
                                                     #"Linked collection desc"

                                                     #"https://testmb\.com/browse/\d+"
                                                     #"Linked database name"
                                                     #"Linked database desc"

                                                     #"https://testmb\.com/question\?db=\d+table=\d+"
                                                     #"Linked table dname"
                                                     #"Linked table desc"

                                                     #"https://testmb\.com/question/\d+"
                                                     #"Linked card name"
                                                     #"Linked card desc"

                                                     #"https://testmb\.com/question/\d+"
                                                     #"Linked model name"
                                                     #"Linked model desc"

                                                     #"https://testmb\.com/dashboard/\d+"
                                                     #"Linked Dashboard name"
                                                     #"Linked Dashboard desc")
                (get "rasta@metabase.com")
                first
                :body
                first
                vals))))

     :slack
     (fn [_ [pulse-results]]
       (is (=? {:channel-id "#general",
                :attachments
                [{:blocks
                  [{:type "header", :text {:type "plain_text", :text "Aviary KPIs", :emoji true}}
                   {:type "section",
                    :fields
                    [{:type "mrkdwn", :text "*State*\nCA, NY, and NJ"}
                     {:type "mrkdwn", :text "*Quarter and Year*\nQ1, 2021"}]}
                   {:type "section",
                    :fields (append-subscription-branding-content [{:type "mrkdwn"
                                                                    :text #"<https://testmb\.com/dashboard/\d+\?state=CA&state=NY&state=NJ&quarter_and_year=Q1-2021\|\*Sent from Metabase Test by Rasta Toucan\*>"}])}]}

                 {:title "Test card",
                  :rendered-info {:attachments false, :content true, :render/text true},
                  :inline-parameters [],
                  :title_link #"https://testmb.com/question/.+",
                  :attachment-name "image.png",
                  :fallback "Test card"}
                 {:blocks
                  [{:type "section",
                    :text
                    {:type "mrkdwn",
                     :text #"\*<https://testmb\.com/collection/\d+\|Linked collection name>\*\nLinked collection desc"}}]}
                 {:blocks
                  [{:type "section",
                    :text
                    {:type "mrkdwn", :text #"\*<https://testmb\.com/browse/\d+\|Linked database name>\*\nLinked database desc"}}]}
                 {:blocks
                  [{:type "section",
                    :text
                    {:type "mrkdwn",
                     :text #"\*<https://testmb\.com/question\?db=\d+&table=\d+\|Linked table dname>\*\nLinked table desc"}}]}
                 {:blocks
                  [{:type "section",
                    :text
                    {:type "mrkdwn",
                     :text #"\*<https://testmb\.com/dashboard/\d+\|Linked Dashboard name>\*\nLinked Dashboard desc"}}]}
                 {:blocks
                  [{:type "section",
                    :text {:type "mrkdwn", :text #"\*<https://testmb\.com/question/\d+\|Linked card name>\*\nLinked card desc"}}]}
                 {:blocks
                  [{:type "section",
                    :text
                    {:type "mrkdwn", :text #"\*<https://testmb\.com/question/\d+\|Linked model name>\*\nLinked model desc"}}]}
                 {:blocks
                  [{:type "section", :text {:type "mrkdwn", :text "*<https://metabase.com|https://metabase.com>*"}}]}]}
               (pulse.test-util/thunk->boolean pulse-results))))}}))

(deftest mrkdwn-length-limit-test
  (with-redefs [channel.slack/block-text-length-limit 10]
    (tests!
     {:pulse {:skip_if_empty false}, :dashcard {:row 0, :col 0}}
     "Dashboard subscription that includes a Markdown card that exceeds Slack's length limit when converted to mrkdwn"
     {:card (pulse.test-util/checkins-query-card {})

      :fixture
      (fn [{dashboard-id :dashboard-id} thunk]
        (mt/with-temp [:model/DashboardCard _ {:dashboard_id dashboard-id
                                               :row 1
                                               :col 1
                                               :visualization_settings {:text "abcdefghijklmnopqrstuvwxyz"}}]
          (thunk)))

      :assert
      {:slack
       (fn [_object-ids [pulse-results]]
         (is (= {:blocks [{:type "section" :text {:type "mrkdwn" :text "abcdefghi…"}}]}
                (nth (:attachments (pulse.test-util/thunk->boolean pulse-results)) 2))))}})))

(deftest archived-dashboard-test
  (tests!
   {:dashboard {:archived true}}
   "Dashboard subscriptions are not sent if dashboard is archived"
   {:card (pulse.test-util/checkins-query-card {})

    :assert
    {:slack
     (fn [_ [pulse-results]]
       (is (= {:attachments []} (pulse.test-util/thunk->boolean pulse-results))))

     :email
     (fn [_ emails]
       (is (zero? (count emails))))}}))

(deftest use-default-values-test
  (testing "Dashboard Subscriptions SHOULD use default values for Dashboard parameters when running (#20516)"
    (mt/dataset test-data
      (mt/with-temp [:model/Dashboard {dashboard-id :id, :as dashboard} {:name       "20516 Dashboard"
                                                                         :parameters [{:name    "Category"
                                                                                       :slug    "category"
                                                                                       :id      "_MBQL_CATEGORY_"
                                                                                       :type    "category"
                                                                                       :default ["Doohickey"]}
                                                                                      {:name    "SQL Category"
                                                                                       :slug    "sql_category"
                                                                                       :id      "_SQL_CATEGORY_"
                                                                                       :type    "category"
                                                                                       :default ["Gizmo"]}]}]
        (testing "MBQL query"
          (mt/with-temp [:model/Card {mbql-card-id :id} {:name          "Orders"
                                                         :dataset_query (mt/mbql-query products
                                                                          {:fields   [$id $title $category]
                                                                           :order-by [[:asc $id]]
                                                                           :limit    2})}
                         :model/DashboardCard _ {:parameter_mappings [{:parameter_id "_MBQL_CATEGORY_"
                                                                       :card_id      mbql-card-id
                                                                       :target       [:dimension [:field (mt/id :products :category) nil]]}]
                                                 :card_id            mbql-card-id
                                                 :dashboard_id       dashboard-id}]
            (let [[mbql-results] (map :result (execute-dashboard (:id dashboard) (mt/user->id :rasta) (:parameters dashboard)))]
              (is (= [[2 "Small Marble Shoes"        "Doohickey"]
                      [3 "Synergistic Granite Chair" "Doohickey"]]
                     (mt/rows mbql-results))))))
        (testing "SQL Query"
          (mt/with-temp [:model/Card {sql-card-id :id} {:name          "Products (SQL)"
                                                        :dataset_query (mt/native-query
                                                                         {:query
                                                                          (str "SELECT id, title, category\n"
                                                                               "FROM products\n"
                                                                               "WHERE {{category}}\n"
                                                                               "ORDER BY id ASC\n"
                                                                               "LIMIT 2")

                                                                          :template-tags
                                                                          {"category"
                                                                           {:id           "_SQL_CATEGORY_TEMPLATE_TAG_"
                                                                            :name         "category"
                                                                            :display-name "Category"
                                                                            :type         :dimension
                                                                            :dimension    [:field (mt/id :products :category) nil]
                                                                            :widget-type  :category}}})}
                         :model/DashboardCard _ {:parameter_mappings [{:parameter_id "_SQL_CATEGORY_"
                                                                       :card_id      sql-card-id
                                                                       :target       [:dimension [:template-tag "category"]]}]
                                                 :card_id            sql-card-id
                                                 :dashboard_id       dashboard-id}]
            (let [[results] (map :result (execute-dashboard (:id dashboard) (mt/user->id :rasta) (:parameters dashboard)))]
              (is (= [[1  "Rustic Paper Wallet"   "Gizmo"]
                      [10 "Mediocre Wooden Table" "Gizmo"]]
                     (mt/rows results))))))))))

(deftest substitute-parameters-in-virtual-cards
  (testing "Parameters in virtual (text) cards should have parameter values substituted appropriately"
    (mt/with-temp [:model/Dashboard {dashboard-id :id :as dashboard} {:name "Params in Text Card Test"
                                                                      :parameters [{:name    "Category"
                                                                                    :slug    "category"
                                                                                    :id      "TEST_ID"
                                                                                    :type    "category"
                                                                                    :default ["Doohickey" "Gizmo"]}]}
                   :model/DashboardCard _ {:parameter_mappings [{:parameter_id "TEST_ID"
                                                                 :target       [:text-tag "foo"]}]
                                           :dashboard_id       dashboard-id
                                           :visualization_settings {:text "{{foo}}"}}]
      (is (= [{:text "Doohickey and Gizmo" :type :text}]
             (execute-dashboard (:id dashboard) (mt/user->id :rasta) (:parameters dashboard)))))))

(deftest no-native-perms-test
  (testing "A native query on a dashboard executes succesfully even if the subscription creator does not have native
           query permissions (#28947)"
    (mt/with-full-data-perms-for-all-users!
      (mt/with-temp [:model/Dashboard {dashboard-id :id, :as dashboard} {:name "Dashboard"}
                     :model/Card      {card-id :id} {:name          "Products (SQL)"
                                                     :dataset_query (mt/native-query
                                                                      {:query "SELECT * FROM venues LIMIT 1"})}
                     :model/DashboardCard _ {:dashboard_id dashboard-id
                                             :card_id      card-id}]
        (data-perms/set-database-permission! (perms-group/all-users) (mt/id) :perms/create-queries :no)
        (is (= [[1 "Red Medicine" 4 10.0646 -165.374 3]]
               (-> (execute-dashboard (:id dashboard) (mt/user->id :rasta) nil)
                   first :result :data :rows)))))))

(deftest actions-are-skipped-test
  (testing "Actions should be filtered out"
    (mt/with-temp
      [:model/Dashboard     {dashboard-id :id
                             :as dashboard}   {:name "Dashboard"}
       :model/DashboardCard _                 {:dashboard_id           dashboard-id
                                               :visualization_settings {:text "Markdown"}
                                               :row                    1}
       :model/DashboardCard _                 {:dashboard_id           dashboard-id
                                               :visualization_settings {:virtual_card {:display "link"}
                                                                        :link         {:url "https://metabase.com"}}
                                               :row                    2}
       :model/DashboardCard _                 {:dashboard_id           dashboard-id
                                               :visualization_settings {:virtual_card {:display "action"}}
                                               :row                    3}]
      (is (=? [{:text "Markdown"}
               {:text "### [https://metabase.com](https://metabase.com)"}]
              (execute-dashboard (:id dashboard) (mt/user->id :rasta) nil)))))

  (testing "Link cards are returned and info should be newly fetched"
    (mt/with-temp [:model/Dashboard dashboard {:name "Test Dashboard"}]
      (with-link-card-fixture-for-dashboard dashboard [{:keys [collection-owner-id
                                                               collection-id
                                                               database-id
                                                               table-id
                                                               card-id
                                                               model-id
                                                               dashboard-id]}]
        (let [site-url (system/site-url)]
          (testing "should returns all link cards and name are newly fetched"
            (doseq [[model id] [[:model/Card card-id]
                                [:model/Table table-id]
                                [:model/Database database-id]
                                [:model/Dashboard dashboard-id]
                                [:model/Collection collection-id]
                                [:model/Card model-id]]]
              (t2/update! model id {:name (format "New %s name" (name model))}))
            (is (=? [{:text (format "### [New Collection name](%s/collection/%d)\nLinked collection desc" site-url collection-id)}
                     {:text (format "### [New Database name](%s/browse/%d)\nLinked database desc" site-url database-id)}
                     {:text (format "### [Linked table dname](%s/question?db=%d&table=%d)\nLinked table desc" site-url database-id table-id)}
                     {:text (format "### [New Dashboard name](%s/dashboard/%d)\nLinked Dashboard desc" site-url dashboard-id)}
                     {:text (format "### [New Card name](%s/question/%d)\nLinked card desc" site-url card-id)}
                     {:text (format "### [New Card name](%s/question/%d)\nLinked model desc" site-url model-id)}
                     {:text (format "### [https://metabase.com](https://metabase.com)")}]
                    (execute-dashboard (:id dashboard) collection-owner-id nil))))
          (testing "it should filter out models that current users does not have permission to read"
            (is (=? [{:text (format "### [New Database name](%s/browse/%d)\nLinked database desc" site-url database-id)}
                     {:text (format "### [Linked table dname](%s/question?db=%d&table=%d)\nLinked table desc" site-url database-id table-id)}
                     {:text (format "### [https://metabase.com](https://metabase.com)")}]
                    (execute-dashboard (:id dashboard) (mt/user->id :lucky) nil)))))))))

(deftest iframe-cards-are-skipped-test
  (testing "iframe cards should be filtered out"
    (mt/with-temp
      [:model/Dashboard     {dashboard-id :id
                             :as dashboard}   {:name "Dashboard"}
       :model/DashboardCard _                 {:dashboard_id           dashboard-id
                                               :visualization_settings {:text "Markdown"}
                                               :row                    1}
       :model/DashboardCard _                 {:dashboard_id           dashboard-id
                                               :visualization_settings {:virtual_card {:display "link"}
                                                                        :link         {:url "https://metabase.com"}}
                                               :row                    2}
       :model/DashboardCard _                 {:dashboard_id           dashboard-id
                                               :visualization_settings {:virtual_card {:display "iframe"}}
                                               :row                    3}]
      (is (=? [{:text "Markdown"}
               {:text "### [https://metabase.com](https://metabase.com)"}]
              (execute-dashboard (:id dashboard) (mt/user->id :rasta) nil)))))

  (testing "Link cards are returned and info should be newly fetched"
    (mt/with-temp [:model/Dashboard dashboard {:name "Test Dashboard"}]
      (with-link-card-fixture-for-dashboard dashboard [{:keys [collection-owner-id
                                                               collection-id
                                                               database-id
                                                               table-id
                                                               card-id
                                                               model-id
                                                               dashboard-id]}]
        (let [site-url (system/site-url)]
          (testing "should returns all link cards and name are newly fetched"
            (doseq [[model id] [[:model/Card card-id]
                                [:model/Table table-id]
                                [:model/Database database-id]
                                [:model/Dashboard dashboard-id]
                                [:model/Collection collection-id]
                                [:model/Card model-id]]]
              (t2/update! model id {:name (format "New %s name" (name model))}))
            (is (=? [{:text (format "### [New Collection name](%s/collection/%d)\nLinked collection desc" site-url collection-id)}
                     {:text (format "### [New Database name](%s/browse/%d)\nLinked database desc" site-url database-id)}
                     {:text (format "### [Linked table dname](%s/question?db=%d&table=%d)\nLinked table desc" site-url database-id table-id)}
                     {:text (format "### [New Dashboard name](%s/dashboard/%d)\nLinked Dashboard desc" site-url dashboard-id)}
                     {:text (format "### [New Card name](%s/question/%d)\nLinked card desc" site-url card-id)}
                     {:text (format "### [New Card name](%s/question/%d)\nLinked model desc" site-url model-id)}
                     {:text (format "### [https://metabase.com](https://metabase.com)")}]
                    (execute-dashboard (:id dashboard) collection-owner-id nil))))

          (testing "it should filter out models that current users does not have permission to read"
            (is (=? [{:text (format "### [New Database name](%s/browse/%d)\nLinked database desc" site-url database-id)}
                     {:text (format "### [Linked table dname](%s/question?db=%d&table=%d)\nLinked table desc" site-url database-id table-id)}
                     {:text (format "### [https://metabase.com](https://metabase.com)")}]
                    (execute-dashboard (:id dashboard) (mt/user->id :lucky) nil)))))))))

(deftest execute-dashboard-with-tabs-test
  (mt/with-temp
    [:model/Dashboard           {dashboard-id :id
                                 :as dashboard}   {:name "Dashboard"}
     :model/DashboardTab {tab-id-2 :id}    {:name         "The second tab"
                                            :position     1
                                            :dashboard_id dashboard-id}
     :model/DashboardTab {tab-id-1 :id}    {:name         "The first tab"
                                            :position     0
                                            :dashboard_id dashboard-id}
     :model/DashboardCard       _                 {:dashboard_id           dashboard-id
                                                   :dashboard_tab_id       tab-id-1
                                                   :row                    2
                                                   :visualization_settings {:text "Card 2 tab-1"}}
     :model/DashboardCard       _                 {:dashboard_id           dashboard-id
                                                   :dashboard_tab_id       tab-id-1
                                                   :row                    1
                                                   :visualization_settings {:text "Card 1 tab-1"}}
     :model/DashboardCard       _                 {:dashboard_id           dashboard-id
                                                   :dashboard_tab_id       tab-id-2
                                                   :row                    2
                                                   :visualization_settings {:text "Card 2 tab-2"}}
     :model/DashboardCard       _                 {:dashboard_id           dashboard-id
                                                   :dashboard_tab_id       tab-id-2
                                                   :row                    1
                                                   :visualization_settings {:text "Card 1 tab-2"}}]
    (testing "tabs are correctly rendered"
      (is (= [{:text "The first tab", :type :tab-title}
              {:text "Card 1 tab-1", :type :text}
              {:text "Card 2 tab-1", :type :text}
              {:text "The second tab", :type :tab-title}
              {:text "Card 1 tab-2", :type :text}
              {:text "Card 2 tab-2", :type :text}]
             (execute-dashboard (:id dashboard) (mt/user->id :rasta) nil))))))

(deftest execute-dashboard-with-empty-tabs-test
  (testing "Dashboard with one tab."
    (mt/with-temp
      [:model/Dashboard           {dashboard-id :id
                                   :as          dashboard}   {:name "Dashboard"}
       :model/DashboardTab {}                {:name         "The second tab"
                                              :position     1
                                              :dashboard_id dashboard-id}
       :model/DashboardTab {tab-id-1 :id}    {:name         "The first tab"
                                              :position     0
                                              :dashboard_id dashboard-id}
       :model/DashboardCard       _                 {:dashboard_id           dashboard-id
                                                     :dashboard_tab_id       tab-id-1
                                                     :row                    2
                                                     :visualization_settings {:text "Card 2 tab-1"}}
       :model/DashboardCard       _                 {:dashboard_id           dashboard-id
                                                     :dashboard_tab_id       tab-id-1
                                                     :row                    1
                                                     :visualization_settings {:text "Card 1 tab-1"}}]
      (testing "Tab title is omitted (#45123)"
        (is (= [{:text "Card 1 tab-1", :type :text}
                {:text "Card 2 tab-1", :type :text}]
               (execute-dashboard (:id dashboard) (mt/user->id :rasta) nil))))))
  (testing "Dashboard with multiple tabs"
    (mt/with-temp
      [:model/Dashboard           {dashboard-id :id
                                   :as          dashboard}   {:name "Dashboard"}
       :model/DashboardTab {}                {:name         "The second tab"
                                              :position     1
                                              :dashboard_id dashboard-id}
       :model/DashboardTab {tab-id-1 :id}    {:name         "The first tab"
                                              :position     0
                                              :dashboard_id dashboard-id}
       :model/DashboardCard       _                 {:dashboard_id           dashboard-id
                                                     :dashboard_tab_id       tab-id-1
                                                     :row                    2
                                                     :visualization_settings {:text "Card 2 tab-1"}}
       :model/DashboardCard       _                 {:dashboard_id           dashboard-id
                                                     :dashboard_tab_id       tab-id-1
                                                     :row                    1
                                                     :visualization_settings {:text "Card 1 tab-1"}}]
      (testing "Tab title is omitted when only 1 tab contains cards."
        (is (= [{:text "Card 1 tab-1", :type :text}
                {:text "Card 2 tab-1", :type :text}]
               (execute-dashboard (:id dashboard) (mt/user->id :rasta) nil)))))))

(deftest render-dashboard-with-tabs-test
  (tests!
   {:pulse     {:skip_if_empty false}
    :dashboard pulse.test-util/test-dashboard}
   "Dashboard that has link cards should render correctly"
   {:card    (pulse.test-util/checkins-query-card {})

    :fixture
    (fn [{dashboard-id :dashboard-id} thunk]
      (mt/with-temporary-setting-values [site-name "Metabase Test"]
        (mt/with-temp
          [:model/DashboardTab {tab-id-2 :id}    {:name         "The second tab"
                                                  :position     1
                                                  :dashboard_id dashboard-id}
           :model/DashboardTab {tab-id-1 :id}    {:name         "The first tab"
                                                  :position     0
                                                  :dashboard_id dashboard-id}
           :model/DashboardCard       _                 {:dashboard_id           dashboard-id
                                                         :dashboard_tab_id       tab-id-1
                                                         :row                    1
                                                         :visualization_settings {:text "Card 1 tab-1"}}
           :model/DashboardCard       _                 {:dashboard_id           dashboard-id
                                                         :dashboard_tab_id       tab-id-1
                                                         :row                    2
                                                         :visualization_settings {:text "Card 2 tab-1"}}
           :model/DashboardCard       _                 {:dashboard_id           dashboard-id
                                                         :dashboard_tab_id       tab-id-2
                                                         :row                    1
                                                         :visualization_settings {:text "Card 1 tab-2"}}
           :model/DashboardCard       _                 {:dashboard_id           dashboard-id
                                                         :dashboard_tab_id       tab-id-2
                                                         :row                    2
                                                         :visualization_settings {:text "Card 2 tab-2"}}]
          ;; dashcards from this setup is currently not belong to any tabs, we should make sure them belong to one
          (t2/update! :model/DashboardCard :dashboard_id dashboard-id :dashboard_tab_id nil {:dashboard_tab_id tab-id-1})
          (thunk))))
    :assert
    {:email
     (fn [_ [email]]
       (is (every?
            true?
            (-> (mt/summarize-multipart-single-email email
                                                     #"The first tab"
                                                     #"Card 1 tab-1"
                                                     #"Card 2 tab-1"
                                                     #"The second tab"
                                                     #"Card 1 tab-2"
                                                     #"Card 2 tab-2")
                (get "rasta@metabase.com")
                first
                :body
                first
                vals))))

     :slack
     (fn [_ [pulse-results]]
       (is (=? {:channel-id "#general",
                :attachments
                [{:blocks
                  [{:type "header", :text {:type "plain_text", :text "Aviary KPIs", :emoji true}}
                   {:type "section",
                    :fields
                    [{:type "mrkdwn", :text "*State*\nCA, NY, and NJ"}
                     {:type "mrkdwn", :text "*Quarter and Year*\nQ1, 2021"}]}
                   {:type "section",
                    :fields (append-subscription-branding-content [{:type "mrkdwn"
                                                                    :text #"<https://testmb\.com/dashboard/\d+\?state=CA&state=NY&state=NJ&quarter_and_year=Q1-2021\|\*Sent from Metabase Test by Rasta Toucan\*>"}])}]}

                 {:blocks [{:type "section", :text {:type "mrkdwn", :text "*The first tab*"}}]}
                 {:title "Test card",
                  :rendered-info {:attachments false, :content true, :render/text true},
                  :inline-parameters [],
                  :title_link #"https://testmb.com/question/.+",
                  :attachment-name "image.png",
                  :fallback "Test card"}
                 {:blocks [{:type "section", :text {:type "mrkdwn", :text "Card 1 tab-1"}}]}
                 {:blocks [{:type "section", :text {:type "mrkdwn", :text "Card 2 tab-1"}}]}
                 {:blocks [{:type "section", :text {:type "mrkdwn", :text "*The second tab*"}}]}
                 {:blocks [{:type "section", :text {:type "mrkdwn", :text "Card 1 tab-2"}}]}
                 {:blocks [{:type "section", :text {:type "mrkdwn", :text "Card 2 tab-2"}}]}]}
               (pulse.test-util/thunk->boolean pulse-results))))}}))

(defn- result-attachment!
  [part]
  (let [{{{:keys [rows]} :data, :as result} :result} (channel.shared/maybe-realize-data-rows part)]
    (when (seq rows)
      [(let [^java.io.ByteArrayOutputStream baos (java.io.ByteArrayOutputStream.)]
         (with-open [os baos]
           (#'email.result-attachment/stream-api-results-to-export-format! os {:export-format :csv :format-rows? true} result)
           (let [output-string (.toString baos "UTF-8")]
             {:type         :attachment
              :content-type :csv
              :content      output-string})))])))

(defn- metadata->field-ref
  [{:keys [name field_ref]} enabled?]
  {:name name :field_ref field_ref :enabled enabled?})

(deftest dashboard-subscription-attachments-test
  (testing "Dashboard subscription attachments respect dashcard viz settings."
    (mt/with-fake-inbox
      (notification.tu/with-notification-testing-setup!
        (mt/with-temp [:model/Card {card-id :id :as c} (pulse.test-util/checkins-query-card {:breakout [!day.date]})
                       :model/Dashboard {dash-id :id} {:name "just dash"}]
          (let [;; with the helper `metadata->field-ref` we turn column metadata into column field refs
                ;; with an additional key `:enabled`. Here the 1st col is enabled, and the 2nd is disabled
                viz {:table.columns (mapv metadata->field-ref (:result_metadata c) [true false])}]
            (mt/with-temp [:model/DashboardCard {dash-card-id :id} {:dashboard_id           dash-id
                                                                    :card_id                card-id
                                                                    :visualization_settings viz}
                           :model/Pulse         {pulse-id :id, :as pulse}  {:name         "just pulse"
                                                                            :dashboard_id dash-id}
                           :model/PulseCard     _ {:pulse_id          pulse-id
                                                   :card_id           card-id
                                                   :position          0
                                                   :dashboard_card_id dash-card-id
                                                   :include_csv       true}
                           :model/PulseChannel  {pc-id :id} {:pulse_id pulse-id}
                           :model/PulseChannelRecipient _ {:user_id          (pulse.test-util/rasta-id)
                                                           :pulse_channel_id pc-id}]
              (with-redefs [email.result-attachment/result-attachment result-attachment!]
                (pulse.send/send-pulse! pulse)
                (is (= 1
                       (-> @mt/inbox
                           (get (:email (mt/fetch-user :rasta)))
                           last
                           :body
                           last
                           :content
                           str/split-lines
                           (->> (mapv #(str/split % #",")))
                           first
                           count)))))))))))

(deftest attachment-filenames-stay-readable-test
  (testing "Filenames remain human-readable (#41669)"
    (let [tmp (#'email.result-attachment/create-temp-file! ".tmp")
          {:keys [file-name]} (#'email.result-attachment/create-result-attachment-map :csv "テストSQL質問" tmp)]
      (is (= "テストSQL質問" (first (str/split file-name #"_")))))))

(deftest dashboard-description-markdown-test
  (testing "Dashboard description renders markdown"
    (mt/with-temp [:model/Card                  {card-id :id} {:name          "Test card"
                                                               :dataset_query {:database (mt/id)
                                                                               :type     :native
                                                                               :native   {:query "select * from checkins"}}
                                                               :display       :table}
                   :model/Dashboard             {dashboard-id :id} {:description "# dashboard description"}
                   :model/DashboardCard         {dashboard-card-id :id} {:dashboard_id dashboard-id
                                                                         :card_id      card-id}
                   :model/Pulse                 {pulse-id :id} {:name         "Pulse Name"
                                                                :dashboard_id dashboard-id}
                   :model/PulseCard             _ {:pulse_id          pulse-id
                                                   :card_id           card-id
                                                   :dashboard_card_id dashboard-card-id}
                   :model/PulseChannel          {pc-id :id} {:pulse_id pulse-id}
                   :model/PulseChannelRecipient _ {:user_id          (pulse.test-util/rasta-id)
                                                   :pulse_channel_id pc-id}]
      (is (= "<h1>dashboard description</h1>"
             (->> (pulse.test-util/with-captured-channel-send-messages!
                    (pulse.send/send-pulse! (t2/select-one :model/Pulse pulse-id)))
                  :channel/email first :message first :content
                  (re-find #"<h1>dashboard description</h1>")))))))

(deftest attachments-test
  (tests!
   {:card (pulse.test-util/checkins-query-card {})}
   "csv"
   {:pulse-card {:include_csv true}
    :assert
    {:email
     (fn [_ [email]]
       (is (= (rasta-dashsub-message {:message [{"Aviary KPIs" true}
                                                pulse.test-util/png-attachment
                                                pulse.test-util/csv-attachment]})
              (mt/summarize-multipart-single-email email #"Aviary KPIs"))))}}

   "xlsx"
   {:pulse-card {:include_xls true}
    :assert
    {:email
     (fn [_ [email]]
       (is (= (rasta-dashsub-message {:message [{"Aviary KPIs" true}
                                                pulse.test-util/png-attachment
                                                pulse.test-util/xls-attachment]})
              (mt/summarize-multipart-single-email email #"Aviary KPIs"))))}}

   "no result should not include csv"
   {:card {:dataset_query (mt/mbql-query venues {:filter [:= $id -1]})}
    :pulse-card {:include_csv true}
    :assert
    {:email
     (fn [_ [email]]
       (is (= (rasta-dashsub-message {:message [{"Aviary KPIs" true}
                                                ;; no result
                                                pulse.test-util/png-attachment
                                                ;; icon
                                                pulse.test-util/png-attachment]})
              (mt/summarize-multipart-single-email email
                                                   #"Aviary KPIs"))))}}))

(deftest multi-series-test
  (mt/with-temp
    [:model/Card                {card-1 :id}      {:name          "Source card"
                                                   :display       "line"
                                                   :dataset_query (mt/mbql-query orders
                                                                    {:aggregation [[:sum $orders.total]]
                                                                     :breakout [$orders.created_at]})}
     :model/Card                {card-2 :id}      {:name          "Serie card"
                                                   :display       "line"
                                                   :dataset_query (mt/mbql-query orders
                                                                    {:aggregation [[:sum $orders.subtotal]]
                                                                     :breakout [$orders.created_at]})}
     :model/Dashboard           {dash-id :id}     {:name "Aviary KPIs"}
     :model/DashboardCard       {dash-card-1 :id} {:dashboard_id dash-id
                                                   :card_id      card-1}
     :model/DashboardCardSeries _                 {:dashboardcard_id dash-card-1
                                                   :card_id          card-2
                                                   :position         0}
     :model/Pulse               {pulse-id :id}    {:dashboard_id dash-id}
     :model/PulseCard            _                {:pulse_id pulse-id
                                                   :card_id   card-1
                                                   :position 0}
     :model/PulseChannel        {pc-id :id}       {:pulse_id pulse-id
                                                   :channel_type "email"}
     :model/PulseChannelRecipient _               {:user_id          (pulse.test-util/rasta-id)
                                                   :pulse_channel_id pc-id}]
    (testing "Able to send pulse with multi series card without rendering error #46892"
      (let [error-msg (str @#'body/error-rendered-message)]
        (is (= (rasta-dashsub-message {:message [{error-msg false}
                                                 ;; no result
                                                 pulse.test-util/png-attachment
                                                 ;; icon
                                                 pulse.test-util/png-attachment]})
               (-> (pulse.test-util/with-captured-channel-send-messages!
                     (pulse.send/send-pulse! (t2/select-one :model/Pulse pulse-id)))
                   :channel/email
                   first
                   (mt/summarize-multipart-single-email (re-pattern error-msg)))))))))

(deftest exclude-archived-cards-test
  (mt/with-temp
    [:model/Card                {card :id}             {:name          "My Precious Card"
                                                        :dataset_query (mt/mbql-query orders {:limit 1})}
     :model/Card                {archived-card-id :id} {:name          "Archived Card"
                                                        :dataset_query (mt/mbql-query orders {:limit 1})
                                                        :archived      true}
     :model/Dashboard           {dash-id :id}          {:name "Aviary KPIs"}
     :model/DashboardCard       _                      {:dashboard_id dash-id
                                                        :card_id      card}
     :model/DashboardCard       _                      {:dashboard_id dash-id
                                                        :card_id      archived-card-id}
     :model/Pulse               {pulse-id :id}         {:dashboard_id dash-id}
     :model/PulseCard            _                     {:pulse_id pulse-id
                                                        :card_id   card
                                                        :position 0}
     :model/PulseCard            _                     {:pulse_id pulse-id
                                                        :card_id   archived-card-id
                                                        :position 1}
     :model/PulseChannel        {pc-id :id}            {:pulse_id pulse-id
                                                        :channel_type "email"}
     :model/PulseChannelRecipient _                    {:user_id          (pulse.test-util/rasta-id)
                                                        :pulse_channel_id pc-id}]
    (testing "Archived cards are not included in the result #47649"
      (is (= (rasta-dashsub-message {:message [{"My Precious Card" true
                                                "Archived Card"    false}
                                               ;; active card result
                                               pulse.test-util/png-attachment]})
             (-> (pulse.test-util/with-captured-channel-send-messages!
                   (pulse.send/send-pulse! (t2/select-one :model/Pulse pulse-id)))
                 :channel/email
                 first
                 (mt/summarize-multipart-single-email #"My Precious Card" #"Archived Card")))))))

(deftest cleanup-all-temp-files-test
  (mt/with-temp [:model/Dashboard     {dashboard-id :id} {:name "Aviary KPIs"
                                                          :description "How are the birds doing today?"}
                 :model/Card          {card-id :id} {:name pulse.test-util/card-name
                                                     :dataset_query (mt/mbql-query orders {:limit 2})}]
    (with-redefs [notification.temp-storage/temp-dir (delay (let [dir (io/file (System/getProperty "java.io.tmpdir")
                                                                               (str "metabase-test" (random/random-name)))]
                                                              (.mkdirs dir)
                                                              dir))
                  notification.payload.execute/rows-to-disk-threadhold 1
                  channel/send!                      (fn [& _args]
                                                       (testing "sanity check that there are files there to cleanup"
                                                         (is (not-empty (.listFiles ^java.io.File @@#'notification.temp-storage/temp-dir)))))]
      (with-dashboard-sub-for-card [{pulse-id :id}
                                    {:card         card-id
                                     :creator_id   (mt/user->id :rasta)
                                     :dashboard    dashboard-id
                                     :channel-type :email}]
        (pulse.send/send-pulse! (t2/select-one :model/Pulse pulse-id))
        (is (empty? (.listFiles ^java.io.File @@#'notification.temp-storage/temp-dir)))))))

(deftest dashboard-with-rows-saved-to-disk-test
  (testing "whether the rows of a dashboard saved to disk or in memory, all channels should work"
    (doseq [limit [1 #_10]]
      (with-redefs [notification.payload.execute/rows-to-disk-threadhold 5]
        (testing (if (> limit @#'notification.payload.execute/rows-to-disk-threadhold)
                   "dashboard has rows saved to disk"
                   "dashboard has rows saved in memory")
          (mt/with-temp [:model/Card          {card-id :id} {:name          pulse.test-util/card-name
                                                             :dataset_query (mt/mbql-query orders {:limit limit})}
                         :model/Dashboard     {dashboard-id :id} {:name "Aviary KPIs"}
                         :model/DashboardCard _ {:dashboard_id dashboard-id
                                                 :card_id      card-id}
                         :model/Pulse         {pulse-id :id} {:name         "Pulse Name"
                                                              :dashboard_id dashboard-id}
                         :model/PulseCard     _ {:pulse_id          pulse-id
                                                 :card_id           card-id
                                                 :position          0}
                         :model/PulseChannel  {pc-id :id} {:pulse_id     pulse-id
                                                           :channel_type "email"}
                         :model/PulseChannel  _           {:pulse_id     pulse-id
                                                           :channel_type "slack"
                                                           :details      {:channel "#general"}}
                         :model/PulseChannelRecipient _ {:user_id          (pulse.test-util/rasta-id)
                                                         :pulse_channel_id pc-id}]
            (let [pulse-results (pulse.test-util/with-captured-channel-send-messages!
                                  (pulse.send/send-pulse! (t2/select-one :model/Pulse pulse-id)))]
              ;; Test email channel
              (is (= (rasta-dashsub-message
                      {:message [{pulse.test-util/card-name true}
                                 pulse.test-util/png-attachment]})
                     (mt/summarize-multipart-single-email
                      (first (:channel/email pulse-results))
                      #"Test card")))

              ;; Test slack channel
              (is (=? {:channel-id "#general",
                       :attachments
                       [{:blocks
                         [{:type "header", :text {:type "plain_text", :text "Aviary KPIs", :emoji true}}
                          {:type "section",
                           :fields (append-subscription-branding-content [{:type "mrkdwn"}])}]}
                        {:title "Test card",
                         :rendered-info {:attachments false, :content true},
                         :inline-parameters [],
                         :attachment-name "image.png",
                         :fallback "Test card"}]}
                      (pulse.test-util/thunk->boolean (first (:channel/slack pulse-results))))))))))))
