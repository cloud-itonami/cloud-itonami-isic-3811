(ns wastecollect.store
  "SSoT for the waste-collection dispatch actor, behind a `Store` protocol
  so the backend is a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. The deterministic default
                       for dev/tests/demo (no deps).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible EAV
                       store. Pure `.cljc`, so it runs offline AND can be
                       pointed at a real Datomic Local or a kotoba-server pod
                       by swapping `langchain.db`'s `:db-api`.

  Both implement the same protocol and pass the same contract
  (test/wastecollect/store_contract_test.clj) — the actor, the
  WasteDispatchGovernor and the audit ledger never know which SSoT they
  run on.

  Entity shapes: a waste generator (customer), a receiving facility (with a
  permitted daily intake capacity per waste-class — the environmental
  permit limit the facility-permit-capacity-gate enforces), a scheduled
  pickup, a manifest (the actual recorded weight once collected), and a
  client-billing contract. There is NO field anywhere in this schema for
  hazardous-waste handling, storage or transport — this actor structurally
  covers non-hazardous collection only (ADR-2607113200 §1).

  The ledger stays append-only on every backend — 'who scheduled/recorded
  what, on what facility capacity/classification basis' is always a query
  over an immutable log."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.db :as d]))

(defprotocol Store
  (generator [s id])
  (facility [s id])
  (pickup [s id])
  (all-pickups [s])
  (manifest [s pickup-id])
  (facility-intake [s facility-id waste-class] "cumulative kg already recorded today at this facility for this waste-class")
  (contract [s tenant])
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision/disclosure fact")
  (with-generators [s generators] "replace/seed generators (map id→generator)")
  (with-facilities [s facilities] "replace/seed facilities (map id→facility)")
  (with-pickups [s pickups]       "replace/seed pickups (map id→pickup)")
  (with-contracts [s contracts]   "replace/seed client-billing contracts (map tenant→contract)"))

;; ───────────────────────── demo data (fictitious, non-real entities) ─────

(defn demo-data
  "A small, entirely fictitious dataset so the actor + tests run offline
  and no real generator/facility is ever named in this repository.
  `fac-200` carries a tight `:daily-capacity-kg` for `:general` purely to
  exercise the facility-permit-capacity-gate — it is not a claim about any
  real permit."
  []
  {:generators
   {"gen-100" {:id "gen-100" :name "デモ商店会(架空)" :jurisdiction :jpn}
    "gen-200" {:id "gen-200" :name "Demo Office Park (fictitious)" :jurisdiction :usa}}
   :facilities
   {"fac-100" {:id "fac-100" :name "デモ中間処理施設(架空)" :jurisdiction :jpn
               :daily-capacity-kg {:general 5000M :organic 2000M}}
    "fac-200" {:id "fac-200" :name "Demo Transfer Station (fictitious)" :jurisdiction :usa
               :daily-capacity-kg {:general 100M}}}
   :pickups
   {"pk-100" {:id "pk-100" :generator-id "gen-100" :facility-id "fac-100"
              :waste-class :general :estimated-kg 300M :scheduled-date "2026-07-10"
              :hazard-flags #{}
              :source {:class :generator-self-declaration :ref "jpn-waste-management-act:gen-100"}}}
   :contracts
   {"tenant-acme"  {:tenant "tenant-acme" :tier :tier/detailed :active? true :purpose :municipal-billing}
    "tenant-basic" {:tenant "tenant-basic" :tier :tier/basic :active? true :purpose :generator-portal}}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (generator [_ id] (get-in @a [:generators id]))
  (facility [_ id] (get-in @a [:facilities id]))
  (pickup [_ id] (get-in @a [:pickups id]))
  (all-pickups [_] (sort-by :id (vals (:pickups @a))))
  (manifest [_ pickup-id] (get-in @a [:manifests pickup-id]))
  (facility-intake [_ facility-id waste-class]
    (get-in @a [:facility-intake facility-id waste-class] 0M))
  (contract [_ tenant] (get-in @a [:contracts tenant]))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :pickup-upsert
      (swap! a assoc-in [:pickups (:id value)] value)

      :manifest-record
      (let [{:keys [pickup-id actual-kg]} value
            fid (:facility-id (pickup s pickup-id))
            wc  (:waste-class (pickup s pickup-id))]
        (swap! a assoc-in [:manifests pickup-id] value)
        (swap! a update-in [:facility-intake fid wc] (fnil + 0M) actual-kg))

      :dispute-apply
      (swap! a update-in [:manifests (first path)] merge (:patch value))

      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-generators [s gs] (when (seq gs) (swap! a assoc :generators gs)) s)
  (with-facilities [s fs] (when (seq fs) (swap! a assoc :facilities fs)) s)
  (with-pickups [s ps]    (when (seq ps) (swap! a assoc :pickups ps)) s)
  (with-contracts [s cts] (when (seq cts) (swap! a assoc :contracts cts)) s))

(defn seed-db
  "A MemStore seeded with the demo data. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :manifests {} :facility-intake {} :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (source citations, hazard-flag sets) are stored as
  EDN strings so `langchain.db` doesn't expand them into sub-entities."
  {:generator/id     {:db/unique :db.unique/identity}
   :facility/id      {:db/unique :db.unique/identity}
   :pickup/id        {:db/unique :db.unique/identity}
   :manifest/pickup-id {:db/unique :db.unique/identity}
   :contract/tenant  {:db/unique :db.unique/identity}
   :intake/key       {:db/unique :db.unique/identity}
   :ledger/seq       {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- generator->tx [{:keys [id name jurisdiction]}]
  (cond-> {:generator/id id}
    name         (assoc :generator/name name)
    jurisdiction (assoc :generator/jurisdiction jurisdiction)))

(defn- pull->generator [m]
  (when (:generator/id m)
    {:id (:generator/id m) :name (:generator/name m) :jurisdiction (:generator/jurisdiction m)}))

(def ^:private generator-pull [:generator/id :generator/name :generator/jurisdiction])

(defn- facility->tx [{:keys [id name jurisdiction daily-capacity-kg]}]
  (cond-> {:facility/id id}
    name               (assoc :facility/name name)
    jurisdiction       (assoc :facility/jurisdiction jurisdiction)
    true               (assoc :facility/daily-capacity-kg (enc (or daily-capacity-kg {})))))

(defn- pull->facility [m]
  (when (:facility/id m)
    {:id (:facility/id m) :name (:facility/name m) :jurisdiction (:facility/jurisdiction m)
     :daily-capacity-kg (or (dec* (:facility/daily-capacity-kg m)) {})}))

(def ^:private facility-pull
  [:facility/id :facility/name :facility/jurisdiction :facility/daily-capacity-kg])

(defn- pickup->tx [{:keys [id generator-id facility-id waste-class estimated-kg scheduled-date hazard-flags source]}]
  {:pickup/id id :pickup/generator-id generator-id :pickup/facility-id facility-id
   :pickup/waste-class waste-class :pickup/estimated-kg (enc estimated-kg)
   :pickup/scheduled-date scheduled-date :pickup/hazard-flags (enc (or hazard-flags #{}))
   :pickup/source (enc source)})

(defn- pull->pickup [m]
  (when (:pickup/id m)
    {:id (:pickup/id m) :generator-id (:pickup/generator-id m) :facility-id (:pickup/facility-id m)
     :waste-class (:pickup/waste-class m) :estimated-kg (dec* (:pickup/estimated-kg m))
     :scheduled-date (:pickup/scheduled-date m) :hazard-flags (or (dec* (:pickup/hazard-flags m)) #{})
     :source (dec* (:pickup/source m))}))

(def ^:private pickup-pull
  [:pickup/id :pickup/generator-id :pickup/facility-id :pickup/waste-class :pickup/estimated-kg
   :pickup/scheduled-date :pickup/hazard-flags :pickup/source])

(defn- manifest->tx [{:keys [pickup-id actual-kg waste-class source]}]
  {:manifest/pickup-id pickup-id :manifest/actual-kg (enc actual-kg)
   :manifest/waste-class waste-class :manifest/source (enc source)})

(defn- pull->manifest [m]
  (when (:manifest/pickup-id m)
    {:pickup-id (:manifest/pickup-id m) :actual-kg (dec* (:manifest/actual-kg m))
     :waste-class (:manifest/waste-class m) :source (dec* (:manifest/source m))}))

(def ^:private manifest-pull
  [:manifest/pickup-id :manifest/actual-kg :manifest/waste-class :manifest/source])

(defn- contract->tx [{:keys [tenant tier active? purpose]}]
  {:contract/tenant tenant :contract/tier tier :contract/active active? :contract/purpose purpose})

(defn- pull->contract [m]
  (when (:contract/tenant m)
    {:tenant (:contract/tenant m) :tier (:contract/tier m)
     :active? (:contract/active m) :purpose (:contract/purpose m)}))

(def ^:private contract-pull
  [:contract/tenant :contract/tier :contract/active :contract/purpose])

(defn- intake-key [facility-id waste-class] (str facility-id "|" (name waste-class)))

(defrecord DatomicStore [conn]
  Store
  (generator [_ id] (pull->generator (d/pull (d/db conn) generator-pull [:generator/id id])))
  (facility [_ id] (pull->facility (d/pull (d/db conn) facility-pull [:facility/id id])))
  (pickup [_ id] (pull->pickup (d/pull (d/db conn) pickup-pull [:pickup/id id])))
  (all-pickups [_]
    (->> (d/q '[:find [?id ...] :where [?e :pickup/id ?id]] (d/db conn))
         (map #(pull->pickup (d/pull (d/db conn) pickup-pull [:pickup/id %])))
         (sort-by :id)))
  (manifest [_ pickup-id] (pull->manifest (d/pull (d/db conn) manifest-pull [:manifest/pickup-id pickup-id])))
  (facility-intake [_ facility-id waste-class]
    (let [m (d/pull (d/db conn) [:intake/key :intake/kg] [:intake/key (intake-key facility-id waste-class)])]
      (or (dec* (:intake/kg m)) 0M)))
  (contract [_ tenant] (pull->contract (d/pull (d/db conn) contract-pull [:contract/tenant tenant])))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :pickup-upsert (d/transact! conn [(pickup->tx value)])

      :manifest-record
      (let [{:keys [pickup-id actual-kg]} value
              fid (:facility-id (pickup s pickup-id))
              wc  (:waste-class (pickup s pickup-id))
              prior (facility-intake s fid wc)]
          (d/transact! conn [(manifest->tx value)
                              {:intake/key (intake-key fid wc) :intake/kg (enc (+ prior actual-kg))}]))

      :dispute-apply
      (d/transact! conn [(manifest->tx (merge (manifest s (first path)) (:patch value)))])

      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-generators [s gs] (when (seq gs) (d/transact! conn (mapv generator->tx (vals gs)))) s)
  (with-facilities [s fs] (when (seq fs) (d/transact! conn (mapv facility->tx (vals fs)))) s)
  (with-pickups [s ps]    (when (seq ps) (d/transact! conn (mapv pickup->tx (vals ps)))) s)
  (with-contracts [s cts] (when (seq cts) (d/transact! conn (mapv contract->tx (vals cts)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`; empty when
  omitted."
  ([] (datomic-store {}))
  ([{:keys [generators facilities pickups contracts]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-generators generators) (with-facilities facilities)
         (with-pickups pickups) (with-contracts contracts)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo data — the Datomic-backed analog of
  `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  "Human-readable one-liner for a ledger fact (used by the demo)."
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "subject=" subject)
             (str "basis=" (pr-str basis))]))
