(ns wastecollect.sim
  "Demo runner: push seven representative operations through one
  OperationActor and watch the WasteDispatchGovernor + approval workflow
  earn the WasteDispatch-LLM the right to schedule, record or resolve a
  dispute.

    op1  正当なpickupスケジュール(出典あり・容量内)         → commit
    op2  pickupスケジュールが出典なし(分類根拠欠落)         → source-provenance REJECT → hold
    op3  開示クエリが tier/basic 契約なのに hazard-flags/source を要求 → licensed-disclosure REJECT → hold
    op3a 開示クエリが未契約 tenant から                     → licensed-disclosure REJECT → hold
    op4  危険物フラグ付きの収集をスケジュール(非危険物専用actor) → hazard-misclassification REJECT → hold
    op5  紛争申立て(どの phase でも常に人間レビュー)        → escalate → approve → commit
    op6  施設の許可容量を超過するpickup                     → facility-permit-capacity REJECT → hold
    op7  大口(bulk)pickup(出典・容量は正常でも人間承認)     → escalate → approve → commit

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [wastecollect.store :as store]
            [wastecollect.operation :as op]
            [wastecollect.facts :as facts]
            [wastecollect.report :as report]))

(defn- line [& xs] (println (apply str xs)))

(defn- run-op!
  "Run one operation on its own thread-id. If it interrupts for human
  approval, a dispatch coordinator 'approves' and we resume."
  [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  人間レビュー待ち (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "coordinator-1"}}
                             {:thread-id thread-id :resume? true})]
            (line "   ▶  " (if approve? "承認 → " "却下 → ") "disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   → disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [db    (store/seed-db)
        actor (op/build db)
        ;; :phase 3 (supervised-auto) explicitly -- default-phase is 1
        ;; (assisted, no auto-commit) so this demo can showcase the full
        ;; governed contract end to end.
        coordinator {:actor-id "dc-1" :actor-role :dispatch-coordinator :phase 3}
        officer     {:actor-id "do-1" :actor-role :dispute-officer :phase 3}]

    (line "── R0 分類根拠カバレッジ(正直な現状) ──")
    (line (pr-str (facts/coverage)))

    (line "\n── OperationActor (WasteDispatch-LLM sealed; WasteDispatchGovernor active) ──")

    (line "\nop1  正当なpickupスケジュール(出典あり・容量内)")
    (run-op! actor "op1"
             {:op :pickup/schedule :subject "pk-200" :id "pk-200"
              :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
              :estimated-kg 300M :scheduled-date "2026-07-10"
              :source {:class :generator-self-declaration :ref "jpn-waste-management-act:gen-100"}}
             coordinator true)

    (line "\nop2  pickupスケジュール — WasteDispatch-LLM が出典なしで提案(分類根拠欠落)")
    (run-op! actor "op2"
             {:op :pickup/schedule :subject "pk-201" :id "pk-201"
              :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
              :estimated-kg 200M :scheduled-date "2026-07-10"
              :source {:class :generator-self-declaration :ref "jpn-waste-management-act:gen-100"}
              :unsourced? true}
             coordinator true)

    (line "\nop3  開示クエリ(tier/basic 契約なのに hazard-flags/source まで要求)")
    (run-op! actor "op3"
             {:op :report/query :subject "pk-100" :greedy? true}
             {:actor-id "cl-1" :actor-role :client-user :tenant "tenant-basic"} true)

    (line "\nop3a 開示クエリ(登録されていない tenant から)")
    (run-op! actor "op3a"
             {:op :report/query :subject "pk-100"}
             {:actor-id "cl-2" :actor-role :client-user :tenant "tenant-ghost"} true)

    (line "\nop4  危険物フラグ付きの収集をスケジュール(このactorは非危険物収集専用)")
    (run-op! actor "op4"
             {:op :pickup/schedule :subject "pk-202" :id "pk-202"
              :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
              :estimated-kg 100M :scheduled-date "2026-07-10"
              :source {:class :generator-self-declaration :ref "jpn-waste-management-act:gen-100"}
              :hazard-flags #{:batteries}}
             coordinator true)

    (line "\nop5  紛争申立て — 記録済み実重量への異議(どの phase でも常に人間レビュー)")
    (run-op! actor "op5"
             {:op :dispute/request :subject "pk-100" :disputed-field :actual-kg :claim 250M}
             officer true)

    (line "\nop6  施設の許可容量を超過するpickup(Demo Transfer Stationの:general上限100kg)")
    (run-op! actor "op6"
             {:op :pickup/schedule :subject "pk-203" :id "pk-203"
              :generator-id "gen-200" :facility-id "fac-200" :waste-class :general
              :estimated-kg 150M :scheduled-date "2026-07-10"
              :source {:class :collector-visual-inspection :ref "us-rcra-hazardous-waste-listing:pk-203"}}
             coordinator true)

    (line "\nop7  大口(bulk)pickup(出典・容量は正常でも人間承認)")
    (run-op! actor "op7"
             {:op :pickup/schedule :subject "pk-204" :id "pk-204"
              :generator-id "gen-100" :facility-id "fac-100" :waste-class :general
              :estimated-kg 1200M :scheduled-date "2026-07-10"
              :source {:class :generator-self-declaration :ref "jpn-waste-management-act:gen-100"}}
             coordinator true)

    (line "\n── 開示(governor が承認した tier/basic 列のみ) ──")
    (line (pr-str (report/render-pickup db "pk-100" [:pickup-id :generator-id :facility-id :waste-class :scheduled-date])))

    (line "\n── 監査台帳 (append-only; 誰が・何を・どの契約/出典で schedule/開示したか) ──")
    (doseq [f (store/ledger db)]
      (line "  " (store/ledger-line f)))

    (line "\ndone.")))
