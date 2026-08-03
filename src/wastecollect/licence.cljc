(ns wastecollect.licence
  "Carrier-licence adapter over `cloud-itonami-licensed-operator`.

  This actor decides whether a *pickup* is safe. It has never decided
  whether the operator is **allowed to carry waste at all** — a question
  that is not about the pickup, and that no amount of hazard
  classification or capacity checking answers. That question is already
  modelled, with citations, in `cloud-itonami-licensed-operator`; this ns
  is the seam to it.

  Two properties this seam must preserve, both inherited from the gate:

    1. DENY BY DEFAULT. A jurisdiction that is absent from the catalog is
       *unresearched*, not *unregulated*. `nil` jurisdiction, unknown
       jurisdiction, and every non-`:admissible` verdict resolve to
       blocked.

    2. AN OPERATOR CANNOT ATTEST ITS WAY TO A LICENCE. `gate/plan` only
       lets an attestation unlock a `:conditional` route; it can never
       unlock `:prohibited`, `:unsettled` or `:uncovered`. We pass
       attestations through unchanged rather than reimplementing that
       rule — reimplementing it is how the two copies drift.

  We do not re-derive any legal conclusion here. If this ns starts making
  its own judgements, the citations stop matching the verdict."
  (:require [clojure.string :as str]
            [cloud-itonami.licensed-operator.gate :as gate]))

(def sector
  "産業廃棄物収集運搬。The pickup act this actor performs."
  :sector/industrial-waste-collection)

(def jurisdiction-ids
  "This store's `:jurisdiction` keywords → licensed-operator catalog ids.

  A CLOSED map on purpose. An unmapped keyword resolves to nil and the
  gate blocks — the alternative (`(str/upper-case (name k))`) would
  silently mint plausible-looking ids like \"FRA\" for jurisdictions
  nobody has researched, and a fabricated id that misses the catalog is
  indistinguishable from a real one that does."
  {:jpn "JPN" :usa "USA" :gbr "GBR" :deu "DEU"})

(defn jurisdiction-id
  "Resolve the catalog id for a matter.

  `context-override` lets a caller name a SUB-NATIONAL jurisdiction —
  waste-carrier licensing in Japan is prefectural, so the honest id for a
  Tokyo pickup is \"JPN-13\", which the generator record (`:jpn`) cannot
  express. The override is narrowing, not a bypass: whatever it names is
  still looked up in the same catalog and still fails closed if absent."
  [context-override generator]
  (or context-override (get jurisdiction-ids (:jurisdiction generator))))

(defn carrier-verdict
  "Can the operator lawfully carry in `:jurisdiction`?

  Returns `{:open? bool :route kw :reason s :next m :citations [...]}`.

  `:holder` is the licensed third party being stood up when the operator
  defers rather than holding the licence itself — `gate/plan` runs every
  `:licensee-requirements` check against that record, and a missing field
  fails closed. We hand it straight through; a deferral is only as good
  as the holder."
  [{:keys [jurisdiction licence-held? attestations holder matter]}]
  (if (nil? jurisdiction)
    {:open? false
     :route :blocked
     :reason (str "法域が指定されていない。収集運搬の許認可は法域ごとに決まるため、"
                  "法域不明のまま collection を成立させない(未調査であって"
                  "『規制が無い』ではない)。")}
    (let [p (gate/plan {:jurisdiction jurisdiction
                        :sector sector
                        :licence-held? (boolean licence-held?)
                        :attestations attestations
                        :holder holder
                        :matter matter})]
      {:open? (:open? p)
       :route (:route p)
       :reason (when-not (:open? p)
                 (str "収集運搬の経路が開いていない(" (pr-str jurisdiction) "): "
                      (->> (:blockers p) (map :detail) (remove nil?)
                           (str/join " / "))))
       :next (:next p)
       :licence (get-in p [:licence :licence/name])
       :citations (:citations p)})))

(defn open? [situation] (boolean (:open? (carrier-verdict situation))))
