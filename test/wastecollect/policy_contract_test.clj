(ns wastecollect.policy-contract-test
  "The governor contract as executable tests — the analog of
  `cloud-itonami-isic-6311`'s policy_contract_test / robotaxi's
  safety_contract_test. The single invariant under test:

    WasteDispatch-LLM never schedules/records/discloses/resolves a record
    the WasteDispatchGovernor would reject, and every decision (commit OR
    hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [wastecollect.store :as store]
            [wastecollect.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def coordinator {:actor-id "dc-1" :actor-role :dispatch-coordinator :phase 3})
(def officer     {:actor-id "do-1" :actor-role :dispute-officer :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(def clean-source {:class :generator-self-declaration :ref "jpn-waste-management-act:gen-100"})

(deftest authorized-schedule-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :pickup/schedule :subject "pk-300" :id "pk-300"
                   :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
                   :estimated-kg 300M :scheduled-date "2026-07-10" :source clean-source}
                  coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 300M (:estimated-kg (store/pickup db "pk-300"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))
    (is (= :commit (-> (store/ledger db) first :disposition)))))

(deftest unauthorized-role-is-held
  (testing "a :client-user role has no schedule permission → HOLD, no write"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :pickup/schedule :subject "pk-300" :id "pk-300"
                     :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
                     :estimated-kg 300M :scheduled-date "2026-07-10" :source clean-source}
                    {:actor-id "cl-1" :actor-role :client-user})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/pickup db "pk-300")) "SSoT unchanged")
      (is (= [:rbac] (-> (store/ledger db) first :basis))))))

(deftest unsourced-schedule-is-held
  (testing "a pickup schedule with no classification-basis citation → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :pickup/schedule :subject "pk-300" :id "pk-300"
                     :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
                     :estimated-kg 300M :scheduled-date "2026-07-10" :source clean-source
                     :unsourced? true}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/pickup db "pk-300")) "no schedule written"))))

(deftest hazard-flagged-schedule-is-held
  (testing "a pickup carrying any hazard flag is structurally rejected — this actor is non-hazardous-only"
    (let [[db actor] (fresh)
          res (exec-op actor "t4"
                    {:op :pickup/schedule :subject "pk-300" :id "pk-300"
                     :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
                     :estimated-kg 300M :scheduled-date "2026-07-10" :source clean-source
                     :hazard-flags #{:batteries}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:hazard-misclassification-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/pickup db "pk-300"))))))

(deftest facility-capacity-exceeded-is-held
  (testing "a pickup that would push a facility past its permitted daily capacity → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :pickup/schedule :subject "pk-300" :id "pk-300"
                     :generator-id "gen-200" :facility-id "fac-200" :waste-class :general
                     :estimated-kg 150M :scheduled-date "2026-07-10"
                     :source {:class :collector-visual-inspection :ref "us-rcra-hazardous-waste-listing:demo"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:facility-permit-capacity-gate} (-> (store/ledger db) first :basis))))))

(deftest facility-unpermitted-waste-class-is-held
  (testing "a waste-class the facility has no permit for at all → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t5b"
                    {:op :pickup/schedule :subject "pk-300" :id "pk-300"
                     :generator-id "gen-200" :facility-id "fac-200" :waste-class :organic
                     :estimated-kg 10M :scheduled-date "2026-07-10"
                     :source {:class :collector-visual-inspection :ref "demo"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:facility-permit-capacity-gate} (-> (store/ledger db) first :basis))))))

(deftest uncontracted-disclosure-is-held
  (testing "a report query from a tenant with no registered contract → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :report/query :subject "pk-100"}
                    {:actor-id "cl-2" :actor-role :client-user :tenant "tenant-ghost"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest over-disclosure-beyond-tier-is-held
  (testing "a report query pulling columns beyond the contract's tier → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :report/query :subject "pk-100" :greedy? true}
                    {:actor-id "cl-1" :actor-role :client-user :tenant "tenant-basic"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest clean-disclosure-within-tier-commits-directly
  (testing "a clean, in-tier report query auto-serves (it's a governed read)"
    (let [[_db actor] (fresh)
          res (exec-op actor "t8"
                    {:op :report/query :subject "pk-100"}
                    {:actor-id "cl-1" :actor-role :client-user :tenant "tenant-basic"})]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest bulk-volume-schedule-escalates-then-human-decides
  (testing "an otherwise-clean bulk-volume pickup interrupts for human approval"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t9"
                   {:op :pickup/schedule :subject "pk-300" :id "pk-300"
                    :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
                    :estimated-kg 1200M :scheduled-date "2026-07-10" :source clean-source}
                   coordinator)]
      (is (= :interrupted (:status r1)) "pauses for human approval")
      (is (= :bulk-volume (-> r1 :state :audit last :reason)))
      (testing "approve → commit"
        (let [r2 (g/run* actor {:approval {:status :approved :by "coordinator-1"}}
                         {:thread-id "t9" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 1200M (:estimated-kg (store/pickup db "pk-300"))))
          (is (= :commit (-> (store/ledger db) last :disposition)))))))
  (testing "reject → hold"
    (let [[db actor] (fresh)
          _  (exec-op actor "t10"
                  {:op :pickup/schedule :subject "pk-300" :id "pk-300"
                   :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
                   :estimated-kg 1200M :scheduled-date "2026-07-10" :source clean-source}
                  coordinator)
          r2 (g/run* actor {:approval {:status :rejected :by "coordinator-1"}}
                     {:thread-id "t10" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (nil? (store/pickup db "pk-300"))))))

(deftest dispute-request-always-escalates-regardless-of-confidence
  (testing "a generator/facility dispute always reaches a human, never auto-resolves"
    (let [[db actor] (fresh)
          before (store/manifest db "pk-100")
          r1 (exec-op actor "t11"
                   {:op :dispute/request :subject "pk-100" :disputed-field :actual-kg :claim 250M}
                   officer)]
      (is (= :interrupted (:status r1)))
      (is (= :dispute-request (-> r1 :state :audit last :reason)))
      (testing "approve → commit applies the dispute resolution"
        (let [r2 (g/run* actor {:approval {:status :approved :by "coordinator-1"}}
                         {:thread-id "t11" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 250M (:actual-kg (store/manifest db "pk-100"))))))
      (testing "a second, rejected dispute leaves the manifest unchanged"
        (let [[db2 actor2] (fresh)
              _  (exec-op actor2 "t12"
                      {:op :dispute/request :subject "pk-100" :disputed-field :actual-kg :claim 250M}
                      officer)
              r3 (g/run* actor2 {:approval {:status :rejected :by "coordinator-1"}}
                        {:thread-id "t12" :resume? true})]
          (is (= :hold (get-in r3 [:state :disposition])))
          (is (= before (store/manifest db2 "pk-100"))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations → N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :pickup/schedule :subject "pk-300" :id "pk-300"
                          :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
                          :estimated-kg 300M :scheduled-date "2026-07-10" :source clean-source}
               coordinator)
      (exec-op actor "b" {:op :pickup/schedule :subject "pk-301" :id "pk-301"
                          :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
                          :estimated-kg 300M :scheduled-date "2026-07-10" :source clean-source
                          :hazard-flags #{:sharps}}
               coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
