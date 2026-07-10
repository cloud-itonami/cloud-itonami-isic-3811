(ns wastecollect.llm
  "WasteDispatch-LLM client — the *contained intelligence node*.

  It normalizes pickup-schedule requests, drafts manifest records from
  collection-crew input, proposes client-report column sets, and drafts
  dispute resolutions. CRITICAL: it is a smart-but-untrusted advisor. It
  returns a *proposal* (with a rationale + the fields/source it cited),
  never a committed or disclosed record. Every output is censored
  downstream by `wastecollect.policy` (the WasteDispatchGovernor) before
  anything touches the SSoT or is disclosed to a client.

  Like `cloud-itonami-isic-6311`'s MarketData-LLM, this is a deterministic
  mock so the actor graph runs offline and the governor contract is
  exercised end-to-end. In production this calls a real LLM (kotoba-llm)
  with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why — SCANNED by the source-provenance gate
     :cites      [kw|str ..]    ; fields/attrs the LLM used
     :source     {:class kw :ref str}|nil ; SCANNED by source-provenance
     :effect     kw             ; how a commit would mutate the SSoT
     :value      map|nil        ; the pickup/manifest/dispute patch
     :columns    [kw ..]|nil    ; proposed disclosure column set
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [wastecollect.store :as store]))

(defn- propose-schedule
  "Pickup-schedule normalization — the LLM only normalizes/validates the
  intake request (adds no new hazard-classification facts). `:unsourced?`
  injects the failure mode we must defend against: a schedule request
  arriving with no classification-basis citation at all — the
  WasteDispatchGovernor's source-provenance-gate must reject this
  outright, regardless of how confident the LLM is."
  [_db {:keys [id generator-id facility-id waste-class estimated-kg scheduled-date
              source hazard-flags unsourced?]}]
  (let [src (when-not unsourced? source)]
    {:summary   (str "pickup schedule: " generator-id " → " facility-id " (" waste-class ")")
     :rationale "出典引用済みの intake 情報の正規化のみ。新規事実の生成なし。"
     :cites     [:generator-id :facility-id :waste-class :estimated-kg]
     :source    src
     :effect    :pickup-upsert
     :value     {:id id :generator-id generator-id :facility-id facility-id
                 :waste-class waste-class :estimated-kg estimated-kg
                 :scheduled-date scheduled-date :hazard-flags (or hazard-flags #{})
                 :source src}
     ;; deliberately HIGH confidence even when unsourced? — proves the hard
     ;; source-provenance gate does not care about confidence at all.
     :confidence (if unsourced? 0.9 0.95)}))

(defn- propose-manifest
  "Post-collection manifest draft from collection-crew input."
  [_db {:keys [pickup-id actual-kg waste-class source unsourced?]}]
  (let [src (when-not unsourced? source)]
    {:summary   (str "manifest record: " pickup-id " = " actual-kg "kg")
     :rationale "収集後の実重量記録。出典は収集/施設側の計測。"
     :cites     [:pickup-id :actual-kg :waste-class]
     :source    src
     :effect    :manifest-record
     :value     {:pickup-id pickup-id :actual-kg actual-kg :waste-class waste-class :source src}
     :confidence (if unsourced? 0.9 0.95)}))

(defn- propose-disclosure
  "Client-report column-set proposal. `:greedy?` injects over-disclosure
  (pulls hazard-flags/source columns beyond a basic-tier contract) — the
  WasteDispatchGovernor's licensed-disclosure gate must reject the excess
  columns."
  [_db {:keys [greedy?]}]
  (let [base [:pickup-id :generator-id :facility-id :waste-class :scheduled-date]
        greedy-extra [:estimated-kg :actual-kg :hazard-flags :source]]
    {:summary   "開示列提案"
     :rationale (if greedy? "分析に有用そうな列を広めに含めた。" "契約 tier に必要な最小列のみ。")
     :cites     base
     :source    nil
     :effect    :disclosure-serve
     :columns   (if greedy? (into base greedy-extra) base)
     :confidence 0.9}))

(defn- propose-dispute
  "Generator/facility dispute resolution draft. This NEVER auto-applies —
  `wastecollect.policy` and `wastecollect.phase` both structurally force
  every `:dispute/request` to human review, independent of confidence."
  [_db {:keys [disputed-field claim]}]
  {:summary   (str "manifest の " disputed-field " について紛争解決案ドラフト")
   :rationale (str "申立て内容: " claim "。裏取りは人間レビューで行う。")
   :cites     [disputed-field]
   :source    nil
   :effect    :dispute-apply
   :value     {:patch {disputed-field claim}}
   :confidence 0.5})

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :pickup/schedule    (propose-schedule db request)
    :manifest/record    (propose-manifest db request)
    :report/query       (propose-disclosure db request)
    :dispute/request    (propose-dispute db request)
    {:summary "未対応の操作" :rationale (str op) :cites [] :source nil
     :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────
;; The advisor is injected into the OperationActor, so the contained
;; intelligence node is a swap: a deterministic mock for dev/tests, or a
;; real LLM in production. Either way its output is a PROPOSAL the
;; WasteDispatchGovernor still censors — the single invariant never
;; depends on which advisor ran.

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは非危険物廃棄物の収集配車アドバイザーです。"
       "与えられた事実のみに基づき、提案を1つだけ EDN マップで返します。"
       "説明や前置きは一切書かず、EDN だけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :source({:class .. :ref ..}か nil) "
       ":effect(:pickup-upsert|:manifest-record|:disclosure-serve|:dispute-apply) "
       ":value(該当マップ) :confidence(0..1)。\n"
       "重要: 危険物(電池・鋭利物・化学物質等)を含む収集は一切扱ってはいけません"
       "(このactorは非危険物収集専用です)。出典を伴わない分類根拠は絶対に"
       "提案してはいけません。施設の許可容量判断はあなたの責務ではありません"
       "(governor が判定します)。"))

(defn- facts-for [st {:keys [op subject facility-id]}]
  (case op
    :pickup/schedule {:facility (store/facility st facility-id)}
    {:pickup (store/pickup st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the WasteDispatchGovernor
  escalates/holds — an LLM hiccup can never auto-commit or auto-publish."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :source nil :effect :noop :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference). Pass
  `model/anthropic-model`, an OpenAI-compatible model (Ollama/vLLM/kotoba), or
  `model/mock-model` for offline tests. `gen-opts` is forwarded to -generate."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record — the LLM's interpretable rationale is a
  key asset (dispute appeals, audits). Persisted to the :audit channel."
  [request proposal]
  {:t          :wastecollectllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :source     (:source proposal)
   :confidence (:confidence proposal)})
