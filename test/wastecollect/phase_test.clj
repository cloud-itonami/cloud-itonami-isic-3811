(ns wastecollect.phase-test
  "Phase 0→3 staged rollout through the OperationActor. The phase can only
  make the actor MORE conservative than the governor: hold writes that
  aren't enabled yet, force human approval before auto-commit is
  unlocked."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [wastecollect.store :as store]
            [wastecollect.operation :as op]))

;; carrier-licence-gate(HARD)を通すための宣言 —— phase gate の挙動を観測する
;; には、まず licence gate を通っている必要がある。
(def coordinator {:actor-id "dc-1" :actor-role :dispatch-coordinator
                  :licence-held? true :attestations #{:route/principal}})
(def crew        {:actor-id "cr-1" :actor-role :collection-crew})
(def officer     {:actor-id "do-1" :actor-role :dispute-officer})

(def clean-schedule
  {:op :pickup/schedule :subject "pk-300" :id "pk-300"
   :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
   :estimated-kg 300M :scheduled-date "2026-07-10"
   :source {:class :generator-self-declaration :ref "jpn-waste-management-act:gen-100"}})

(def clean-manifest
  {:op :manifest/record :subject "pk-100" :pickup-id "pk-100" :actual-kg 300M
   :waste-class :general
   :source {:class :facility-intake-scan :ref "eu-waste-framework-directive:pk-100"}})

(def clean-report
  {:op :report/query :subject "pk-100"})

(def dispute-req
  {:op :dispute/request :subject "pk-100" :disputed-field :actual-kg :claim 250M})

(defn- run [phase req ctx]
  (let [s (store/seed-db)
        actor (op/build s)]
    [s (g/run* actor {:request req :context (assoc ctx :phase phase)}
               {:thread-id (str "ph-" phase "-" (:op req))})]))

(deftest phase0-holds-all-writes
  (let [[s res] (run 0 clean-schedule coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))
    (is (nil? (store/pickup s "pk-300")) "SSoT untouched in phase 0")))

(deftest phase0-allows-governed-reads
  (testing "report/query is a read → phase 0 lets it through (governor still applies)"
    (let [[_ res] (run 0 clean-report {:actor-id "cl-1" :actor-role :client-user :tenant "tenant-basic"})]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest phase1-forces-approval-on-clean-schedule
  (testing "a clean schedule that auto-commits in phase 3 must go to a human in phase 1"
    (let [[_ res] (run 1 clean-schedule coordinator)]
      (is (= :interrupted (:status res)))
      (is (= :phase-approval (-> res :state :audit last :reason))))))

(deftest phase2-enables-manifest-record-under-approval
  (let [[_ res] (run 2 clean-manifest crew)]
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase3-auto-commits-clean-schedule
  (let [[s res] (run 3 clean-schedule coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 300M (:estimated-kg (store/pickup s "pk-300"))))))

(deftest governor-hold-beats-phase
  (testing "a hard governor violation (hazard flag) holds even in the most permissive phase"
    (let [[_ res] (run 3 (assoc clean-schedule :hazard-flags #{:batteries}) coordinator)]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest dispute-request-never-auto-commits-at-any-phase
  (testing "a generator/facility dispute never reaches :commit without an explicit human :approval"
    (doseq [ph [0 1 2 3]]
      (let [[_ res] (run ph dispute-req officer)]
        (is (not= :commit (get-in res [:state :disposition]))
            (str "phase " ph " must not auto-commit a dispute"))))))
