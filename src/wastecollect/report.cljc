(ns wastecollect.report
  "Disclosure rendering — output as a GOVERNED read. The column set is not
  chosen here; it is whatever the WasteDispatchGovernor's
  licensed-disclosure gate approved for the caller's contract tier (see
  `:report/query`). This namespace only renders the approved columns, so a
  disclosure can never exceed the licensed tier."
  (:require [wastecollect.store :as store]))

(defn render-pickup
  "Render one pickup's record over exactly `columns` (already governor-
  approved). `:actual-kg` is only ever rendered when the caller's tier
  included it and a manifest exists."
  [db pickup-id columns]
  (let [p (store/pickup db pickup-id)
        m (store/manifest db pickup-id)
        cell (fn [col]
               (case col
                 :pickup-id     pickup-id
                 :generator-id  (:generator-id p)
                 :facility-id   (:facility-id p)
                 :waste-class   (:waste-class p)
                 :scheduled-date (:scheduled-date p)
                 :estimated-kg  (:estimated-kg p)
                 :actual-kg     (:actual-kg m)
                 :hazard-flags  (:hazard-flags p)
                 :source        (:source p)
                 nil))]
    (into {} (map (juxt identity cell)) columns)))
