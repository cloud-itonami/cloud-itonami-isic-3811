(ns wastecollect.facts
  "R0 classification-basis catalog — the ONLY hazard-classification
  provenance classes the WasteDispatchGovernor will accept as a citation
  for a pickup's waste-stream classification (mirrors
  `cloud-itonami-isic-6311`'s `marketdata.facts` discipline: honesty over
  coverage). This actor collects, routes and reports on NON-HAZARDOUS
  waste streams only; the classification basis is what lets the
  hazard-misclassification-gate (`wastecollect.policy`) tell 'declared
  non-hazardous, on this basis' apart from 'the LLM guessed'.

  Also carries the real regulatory frameworks that define what counts as
  hazardous in the first place — R0 scope: 3 real, citable frameworks, not
  a claim of global coverage. Extend only by appending a real, citable
  framework or classification-basis class, never fabricate either.")

(def catalog
  "Each entry: {:id :name :class :jurisdiction :basis :url}. `:class` is
  the value that must appear in a pickup proposal's `:source :class` for
  the source-provenance-gate to accept it as grounded."
  [{:id :us-rcra-hazardous-waste-listing
    :name "US EPA Resource Conservation and Recovery Act (RCRA), 40 CFR Part 261 hazardous waste identification"
    :class :collector-visual-inspection :jurisdiction :usa
    :basis :regulatory-framework
    :url "https://www.epa.gov/hw/how-hazardous-waste-regulated"}
   {:id :eu-waste-framework-directive
    :name "EU Waste Framework Directive 2008/98/EC, Annex III hazardous properties (HP1-HP15)"
    :class :facility-intake-scan :jurisdiction :eu
    :basis :regulatory-framework
    :url "https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32008L0098"}
   {:id :jpn-waste-management-act
    :name "廃棄物の処理及び清掃に関する法律(廃棄物処理法)特別管理産業廃棄物の指定"
    :class :generator-self-declaration :jurisdiction :jpn
    :basis :regulatory-framework
    :url "https://www.env.go.jp/recycle/waste/"}])

(def allowed-source-classes
  "The set of `:source :class` values the source-provenance-gate will
  accept anywhere a hazard-classification claim is asserted. A closed set
  — a class not in `catalog` (e.g. :inference, :unverified-guess) must be
  rejected, not silently accepted because it looks like a keyword."
  (into #{} (map :class catalog)))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers — never
  overstate ('全世界の廃棄物規制' in prose, 3 frameworks in fact)."
  []
  {:framework-count (count catalog)
   :jurisdictions (into (sorted-set) (map :jurisdiction catalog))
   :note (str "R0 scope: 3 real regulatory frameworks (US RCRA, EU Waste "
              "Framework Directive, Japan 廃棄物処理法) grounding 3 "
              "classification-basis source classes. Extend only by "
              "appending a real, citable framework — never fabricate one.")})

(defn class-allowed? [source-class]
  (contains? allowed-source-classes source-class))
