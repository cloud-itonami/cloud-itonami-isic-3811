(ns wastecollect.policy
  "WasteDispatchGovernor — the independent compliance layer that earns the
  WasteDispatch-LLM the right to schedule a pickup, record a manifest, or
  serve a client report. The LLM has no notion of hazard classification
  discipline, a facility's permitted intake capacity, or a client's
  disclosure entitlement, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD (schedule/record/disclose
  nothing) — this actor's analog of `cloud-itonami-isic-6311`'s
  MarketDataGovernor and `cloud-itonami-isic-7820`'s StaffingGovernor.

  Eight checks, in priority order. The first five are HARD violations: a
  human approver CANNOT override them. The last three are SOFT/always-
  escalate: they route to a human, who may approve.

    1. rbac                        — does actor-role have permission for op?
    2. hazard-misclassification-gate — does the proposed pickup carry ANY
                                      hazard flag at all? (this actor's
                                      domain-unique HARD check #1: it
                                      structurally covers non-hazardous
                                      collection ONLY — a hazard-flagged
                                      stream is never scheduled under this
                                      actor at any confidence, it must be
                                      routed to a licensed hazardous
                                      handler outside this actor's scope)
    3. facility-permit-capacity-gate — would this pickup push the target
                                      facility's cumulative same-day intake
                                      for this waste-class past its
                                      permitted daily capacity? (domain-
                                      unique HARD check #2 — a real
                                      environmental-permit limit, no analog
                                      in any sibling actor)
    4. source-provenance-gate       — does the pickup/manifest cite an
                                      allowed classification-basis source
                                      class (`wastecollect.facts/
                                      allowed-source-classes`)?
    5. licensed-disclosure          — is there an active client-billing
                                      contract, and does the requested
                                      report stay within its tier?
    6. carrier-licence-gate         — is the operator ALLOWED TO CARRY at
                                      all in this jurisdiction? (HARD #5)
                                      Everything above asks whether THIS
                                      pickup is safe; this asks whether the
                                      operator may perform the act. A
                                      perfectly-classified, in-capacity,
                                      well-cited pickup is still unlawful
                                      if nobody holds the carrier licence.
                                      Delegates to `wastecollect.licence`
                                      → `cloud-itonami-licensed-operator`.
    7. confidence floor             — LLM confidence below threshold →
                                      escalate.
    8. bulk-volume gate             — the pickup's estimated weight exceeds
                                      the bulk threshold → always escalate,
                                      regardless of confidence.
    9. dispute requests             — a generator/facility dispute NEVER
                                      auto-resolves, at any confidence, any
                                      phase."
  (:require [clojure.set :as set]
            [wastecollect.facts :as facts]
            [wastecollect.licence :as licence]
            [wastecollect.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def confidence-floor 0.6)

(def bulk-threshold-kg
  "A pickup at or above this estimated weight always escalates to a human,
  even when governor-clean and high-confidence — an unusually large/bulk
  pickup (industrial dumping, a route miscalculation) warrants a second
  look before dispatch commits capacity."
  1000M)

(def permissions
  "actor-role → set of operations it may perform."
  {:dispatch-coordinator #{:pickup/schedule}
   :collection-crew      #{:manifest/record}
   :dispute-officer       #{:dispute/request}
   :client-user           #{:report/query}})

(def tier-columns
  "For `:report/query` — the columns each licensed client-billing tier may
  see. Anything beyond this is over-disclosure (licensed-disclosure
  violation), the waste-collection analog of the sibling actors'
  disclosure-minimization tiers."
  (let [base #{:pickup-id :generator-id :facility-id :waste-class :scheduled-date}
        detailed-extra #{:estimated-kg :actual-kg}
        audit-extra #{:hazard-flags :source}]
    {:tier/basic    base
     :tier/detailed (into base detailed-extra)
     :tier/audit    (into base (into detailed-extra audit-extra))}))

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " は " op " の権限を持たない")}]))

(defn- hazard-misclassification-violations
  [{:keys [op]} proposal]
  (when (= op :pickup/schedule)
    (let [flags (get-in proposal [:value :hazard-flags])]
      (when (seq flags)
        [{:rule :hazard-misclassification-gate
          :detail (str "危険物フラグが立っている非危険物収集への計上は不可: " (vec flags))}]))))

(defn- facility-permit-capacity-violations
  "The facility's permitted daily intake capacity per waste-class.

  `estimated-kg` used to be defaulted with `(or estimated-kg 0M)`, which
  meant an unweighed pickup contributed ZERO to the running intake and
  therefore always passed the permit-capacity check -- fail-open on an
  environmental permit limit. A non-numeric weight was worse: it reached
  `+` and threw a ClassCastException out of the governor itself.

  A weight that is not a number cannot be added to the day's intake, so
  the capacity check cannot be performed, so it is a violation. Not a
  zero, and not a crash."
  [{:keys [op]} proposal st]
  (when (= op :pickup/schedule)
    (let [{:keys [facility-id waste-class estimated-kg]} (:value proposal)
          fac (store/facility st facility-id)
          cap (get-in fac [:daily-capacity-kg waste-class])]
      (cond
        (nil? cap)
        [{:rule :facility-permit-capacity-gate
          :detail (str "施設 " facility-id " は waste-class " waste-class " の許可を保有しない")}]

        (not (number? estimated-kg))
        [{:rule :facility-permit-capacity-gate
          :detail (str "estimated-kg が数値でない(" (pr-str estimated-kg) ") -- "
                       "許可容量に対する検算ができないため計上しない")}]

        (> (+ (store/facility-intake st facility-id waste-class) estimated-kg) cap)
        [{:rule :facility-permit-capacity-gate
          :detail (str "施設 " facility-id " の " waste-class " 許可容量超過: "
                       "current=" (store/facility-intake st facility-id waste-class)
                       " + new=" estimated-kg " > cap=" cap)}]

        :else nil))))

(defn- source-provenance-violations
  [{:keys [op]} proposal]
  (when (contains? #{:pickup/schedule :manifest/record} op)
    (let [src (:source proposal)]
      (when (or (nil? src) (not (facts/class-allowed? (:class src))))
        [{:rule :source-provenance-gate
          :detail (str "分類根拠の出典が無いか許可されたクラスでない: " (pr-str src))}]))))

(defn- licensed-disclosure-violations
  [{:keys [op]} {:keys [tenant]} proposal st]
  (when (= op :report/query)
    (let [c (when tenant (store/contract st tenant))]
      (if (or (nil? c) (not (:active? c)))
        [{:rule :licensed-disclosure :detail (str "有効な契約が無い: tenant=" tenant)}]
        (let [allowed (get tier-columns (:tier c) #{})
              cols    (set (:columns proposal))
              extra   (set/difference cols allowed)]
          (when (seq extra)
            [{:rule :licensed-disclosure
              :detail (str "契約 tier " (:tier c) " に対し過剰な列: " (vec extra))}]))))))

(defn- carrier-licence-violations
  "HARD. May the operator lawfully CARRY this pickup at all?

  Every other gate here asks whether *this pickup* is safe to schedule —
  right classification, within permit capacity, properly cited. None of
  them asks whether the operator is allowed to be a carrier in the first
  place. A pickup can pass all of them and still be an unlicensed
  waste-transport offence.

  The verdict comes from `cloud-itonami-licensed-operator`, which is a
  cited fact table, not a judgement call by this actor. Deny by default:
  an uncatalogued jurisdiction is *unresearched*, not *unregulated*, so it
  fails closed (`licence/carrier-verdict` returns `:blocked` for nil).

  A human approver CANNOT override this — that is the point. `:hard?`
  puts it beyond `signoff-ok?`, because a manager approving a pickup does
  not confer a licence the company does not hold.

  The jurisdiction is a fact about the MATTER, not about the actor, so it
  is read from the generator being collected from — `:jurisdiction` in
  the context only narrows it to a sub-national id (JPN → JPN-13). An
  actor cannot move a pickup into a friendlier jurisdiction by asserting
  one."
  [{:keys [op]} {:keys [jurisdiction licence-held? attestations]} proposal st]
  (when (= op :pickup/schedule)
    (let [gen (store/generator st (get-in proposal [:value :generator-id]))
          jid (licence/jurisdiction-id jurisdiction gen)
          holder (get-in proposal [:value :carrier])
          v (licence/carrier-verdict {:jurisdiction jid
                                      :licence-held? licence-held?
                                      :attestations attestations
                                      :holder holder})]
      (when-not (:open? v)
        [{:rule :carrier-licence-gate
          :detail (:reason v)
          :jurisdiction jid
          :route (:route v)
          :next (:next v)}]))))

(defn- bulk?
  "A `:pickup/schedule` proposal is treated as bulk -- and so reaches a
  human -- unless its `:estimated-kg` can be established to be BELOW
  `bulk-threshold-kg`.

  Note the direction. This used to be
  `(some-> (get-in proposal [:value :estimated-kg]) (>= bulk-threshold-kg))`,
  which read the weight out of the advisor's OWN proposal and, because
  `some->` short-circuits on nil, returned nil when the field was
  ABSENT -- so a pickup carrying no estimated weight at all was
  classified as non-bulk and skipped the bulk review entirely.

  There is nothing to recompute the estimate against at scheduling
  time: `wastecollect.store`'s manifest is the weight recorded once the
  waste has actually been collected, which is after this decision. A
  self-declared estimate therefore cannot be verified, and an
  unverifiable number is worthless as a DE-escalation signal -- it may
  raise the alarm, it must never silence it."
  [{:keys [op]} proposal]
  (when (= op :pickup/schedule)
    (let [kg (get-in proposal [:value :estimated-kg])]
      (or (not (number? kg))
          (>= kg bulk-threshold-kg)))))

(defn check
  "Censors a WasteDispatch-LLM proposal against the policy tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :bulk? bool
    :hard? bool :dispute? bool}.

   - :hard?       — at least one HARD violation (hazard-misclassification-
                    gate/facility-permit-capacity-gate/source-provenance-
                    gate/licensed-disclosure/carrier-licence-gate). Forces
                    HOLD; a human cannot override.
   - :escalate?   — soft: low confidence, bulk-volume pickup, OR a dispute
                    request. A human decides.
   - :ok?         — clean AND not escalating: safe to auto-commit/-serve."
  [request context proposal st]
  (let [hard    (into []
                      (concat (rbac-violations request context)
                              (hazard-misclassification-violations request proposal)
                              (facility-permit-capacity-violations request proposal st)
                              (source-provenance-violations request proposal)
                              (licensed-disclosure-violations request context proposal st)
                              (carrier-licence-violations request context proposal st)))
        conf     (:confidence proposal 0.0)
        low?     (< conf confidence-floor)
        bulk?    (boolean (bulk? request proposal))
        dispute? (= :dispute/request (:op request))
        hard?    (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not bulk?) (not dispute?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? bulk? dispute?))
     :bulk?        bulk?
     :dispute?     dispute?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
