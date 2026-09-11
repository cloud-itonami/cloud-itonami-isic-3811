(ns wastecollect.licence-test
  "carrier-licence-gate — 『この pickup は安全か』ではなく
  『そもそも運搬してよいのか』を問う HARD gate の契約。"
  (:require [clojure.test :refer [deftest is testing]]
            [wastecollect.licence :as licence]
            [wastecollect.policy :as policy]
            [wastecollect.store :as store]))

;; --- licence adapter -------------------------------------------------------

(deftest nil-jurisdiction-is-blocked-not-unregulated
  (let [v (licence/carrier-verdict {:jurisdiction nil})]
    (is (false? (:open? v)))
    (is (= :blocked (:route v)))
    (is (re-find #"未調査" (:reason v)))))

(deftest uncatalogued-jurisdiction-is-blocked
  (let [v (licence/carrier-verdict {:jurisdiction "ATLANTIS"})]
    (is (false? (:open? v)) "カタログに無い法域を『規制が無い』と読まない")
    (is (= :blocked (:route v)))))

(deftest tokyo-is-blocked-without-the-licence
  (testing "2026-08-03 実測: 産業廃棄物収集運搬業許可 未取得"
    (let [v (licence/carrier-verdict {:jurisdiction "JPN-13" :licence-held? false})]
      (is (false? (:open? v)))
      (is (= "産業廃棄物収集運搬業許可" (:licence v)))
      (is (= :obtain-licence (:action (:next v)))))))

(deftest holding-the-licence-opens-the-principal-route
  (let [v (licence/carrier-verdict {:jurisdiction "JPN-13" :licence-held? true
                                    :attestations #{:route/principal}})]
    (is (:open? v) "名義人になれば principal が開く")
    (is (= :principal (:route v)))))

(deftest attestation-alone-does-not-confer-a-licence
  (testing "宣誓しただけでは許認可の代わりにならない"
    (let [v (licence/carrier-verdict {:jurisdiction "JPN-13" :licence-held? false
                                      :attestations #{:route/principal :route/defer}})]
      (is (false? (:open? v))))))

;; --- governor 統合 ---------------------------------------------------------

(def clean-proposal
  "他の HARD gate は全て通る提案 —— licence gate だけを単独で観測するため。
  gen-100 は demo-data で :jurisdiction :jpn(= カタログ \"JPN\")。"
  {:value {:generator-id "gen-100" :facility-id "fac-100" :waste-class :general
           :estimated-kg 300M :hazard-flags #{}}
   :source {:class :generator-self-declaration :ref "jpn-waste-management-act:gen-100"}
   :confidence 0.95})

(defn- st [] (store/seed-db))

(deftest unlicensed-carrier-is-a-hard-hold-even-when-the-pickup-is-clean
  (let [v (policy/check {:op :pickup/schedule :subject "p1"}
                        {:actor-role :dispatch-coordinator :jurisdiction "JPN-13"
                         :licence-held? false}
                        clean-proposal (st))]
    (is (not (:ok? v)))
    (is (:hard? v) "人間承認で覆せない HARD であること")
    (is (some #(= :carrier-licence-gate (:rule %)) (:violations v)))
    (testing "他の gate は通っている(licence gate 単独の効果であることの確認)"
      (is (= [:carrier-licence-gate] (mapv :rule (:violations v)))))))

(deftest licensed-carrier-with-a-clean-pickup-passes
  (let [v (policy/check {:op :pickup/schedule :subject "p1"}
                        {:actor-role :dispatch-coordinator :jurisdiction "JPN-13"
                         :licence-held? true :attestations #{:route/principal}}
                        clean-proposal (st))]
    (is (empty? (:violations v)) (pr-str (:violations v)))
    (is (:ok? v))))

(deftest missing-jurisdiction-in-context-fails-closed
  (testing "context に法域が無ければ通さない —— 指定漏れが全開放にならないこと"
    (let [v (policy/check {:op :pickup/schedule :subject "p1"}
                          {:actor-role :dispatch-coordinator :licence-held? true}
                          clean-proposal (st))]
      (is (:hard? v))
      (is (some #(= :carrier-licence-gate (:rule %)) (:violations v))))))

(deftest licence-gate-only-applies-to-pickup-scheduling
  (testing "manifest 記録は運搬の可否を問わない(別の op)"
    (let [v (policy/check {:op :manifest/record :subject "m1"}
                          {:actor-role :collection-crew :jurisdiction "JPN-13"
                           :licence-held? false}
                          {:source {:class :generator-self-declaration}
                           :confidence 0.95 :value {}}
                          (st))]
      (is (not (some #(= :carrier-licence-gate (:rule %)) (:violations v)))))))
