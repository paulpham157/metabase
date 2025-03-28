(ns metabase-enterprise.advanced-permissions.models.field-values-test
  (:require
   [clojure.test :refer :all]
   [metabase-enterprise.impersonation.driver
    :as impersonation]
   [metabase-enterprise.impersonation.util-test
    :as impersonation.util-test]
   [metabase.models.params.field-values :as params.field-values]
   [metabase.test :as mt]
   [metabase.util :as u]
   [toucan2.core :as t2]))

(deftest get-or-create-advanced-field-values!
  (mt/with-premium-features #{:advanced-permissions}
    (let [field (t2/select-one :model/Field :id (mt/id :categories :id))]
      (try
        (testing "creates new field values for user using impersonation"
          (impersonation.util-test/with-impersonations! {:impersonations [{:db-id (mt/id) :attribute "impersonation_attr"}]
                                                         :attributes     {"impersonation_attr" "impersonation_role"}}
            (let [hash-key-1 (impersonation/hash-key-for-impersonation (u/the-id field))]
              (params.field-values/get-or-create-advanced-field-values! :impersonation field)
              (is (= #{hash-key-1}
                     (into #{}
                           (map :hash_key (t2/select :model/FieldValues :field_id (u/the-id field) :type :impersonation)))))

              (testing "calling a second time shouldn't create new FieldValues"
                (params.field-values/get-or-create-advanced-field-values! :impersonation field)
                (is (= 1 (t2/count :model/FieldValues :field_id (u/the-id field) :type :impersonation))))

              (testing "changing the impersonation role creates new FieldValues"
                (impersonation.util-test/with-impersonations! {:impersonations [{:db-id (mt/id) :attribute "impersonation_attr"}]
                                                               :attributes     {"impersonation_attr" "impersonation_role_2"}}
                  (let [hash-key-2 (impersonation/hash-key-for-impersonation (u/the-id field))]
                    (params.field-values/get-or-create-advanced-field-values! :impersonation field)
                    (is (= #{hash-key-1 hash-key-2}
                           (into #{}
                                 (map :hash_key (t2/select :model/FieldValues :field_id (u/the-id field) :type :impersonation)))))
                    (is (= 2 (t2/count :model/FieldValues :field_id (u/the-id field) :type :impersonation)))))))))
        (finally
          (t2/delete! :model/FieldValues :field_id (u/the-id field) :type :impersonation))))))
