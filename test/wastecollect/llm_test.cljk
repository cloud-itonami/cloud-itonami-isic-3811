(ns wastecollect.llm-test
  "WasteDispatch-LLM proposal generation, unit-level (no governor/actor
  involved — that integration is covered by policy_contract_test)."
  (:require [clojure.test :refer [deftest is testing]]
            [wastecollect.store :as store]
            [wastecollect.llm :as llm]))

(deftest schedule-proposal-carries-source-and-cites
  (let [db (store/seed-db)
        p (llm/infer db {:op :pickup/schedule :subject "pk-300" :id "pk-300"
                         :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
                         :estimated-kg 300M :scheduled-date "2026-07-10"
                         :source {:class :generator-self-declaration :ref "demo"}})]
    (is (= :pickup-upsert (:effect p)))
    (is (= {:class :generator-self-declaration :ref "demo"} (:source p)))
    (is (>= (:confidence p) 0.9))))

(deftest unsourced-schedule-proposal-carries-nil-source
  (testing "the LLM layer does not filter — that is the governor's job; this only proves the injected failure mode actually reaches the proposal"
    (let [db (store/seed-db)
          p (llm/infer db {:op :pickup/schedule :subject "pk-300" :id "pk-300"
                           :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
                           :estimated-kg 300M :scheduled-date "2026-07-10"
                           :source {:class :generator-self-declaration :ref "demo"}
                           :unsourced? true})]
      (is (nil? (:source p)))
      (is (>= (:confidence p) 0.85) "still high-confidence — proves source-provenance cannot rely on confidence as a proxy"))))

(deftest schedule-proposal-carries-through-hazard-flags
  (testing "the LLM does not strip hazard-flags — that is the governor's job (hazard-misclassification-gate)"
    (let [db (store/seed-db)
          p (llm/infer db {:op :pickup/schedule :subject "pk-300" :id "pk-300"
                           :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
                           :estimated-kg 300M :scheduled-date "2026-07-10"
                           :source {:class :generator-self-declaration :ref "demo"}
                           :hazard-flags #{:batteries}})]
      (is (= #{:batteries} (get-in p [:value :hazard-flags]))))))

(deftest manifest-proposal-carries-actual-kg
  (let [db (store/seed-db)
        p (llm/infer db {:op :manifest/record :subject "pk-100" :pickup-id "pk-100"
                         :actual-kg 310M :waste-class :general
                         :source {:class :facility-intake-scan :ref "demo"}})]
    (is (= :manifest-record (:effect p)))
    (is (= 310M (get-in p [:value :actual-kg])))))

(deftest disclosure-proposal-greedy-adds-extra-columns
  (let [db (store/seed-db)
        clean (llm/infer db {:op :report/query :subject "pk-100"})
        greedy (llm/infer db {:op :report/query :subject "pk-100" :greedy? true})]
    (is (< (count (:columns clean)) (count (:columns greedy))))
    (is (some #{:hazard-flags :source} (:columns greedy)))))

(deftest dispute-proposal-never-marks-high-confidence
  (let [db (store/seed-db)
        p (llm/infer db {:op :dispute/request :subject "pk-100" :disputed-field :actual-kg :claim 250M})]
    (is (= :dispute-apply (:effect p)))
    (is (< (:confidence p) 0.9) "disputes are claims pending human verification, never auto-confident")))
