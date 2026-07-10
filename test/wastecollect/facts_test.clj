(ns wastecollect.facts-test
  "The R0 classification-basis catalog is the whole ground truth for the
  source-provenance gate — these tests guard its own internal honesty
  (every class it advertises is actually backed by a catalog entry, no
  duplicate/aspirational entries)."
  (:require [clojure.test :refer [deftest is testing]]
            [wastecollect.facts :as facts]))

(deftest catalog-entries-are-well-formed
  (doseq [{:keys [id name class jurisdiction basis url]} facts/catalog]
    (testing (str id)
      (is (keyword? id))
      (is (string? name))
      (is (keyword? class))
      (is (keyword? jurisdiction))
      (is (keyword? basis))
      (is (string? url)))))

(deftest allowed-source-classes-matches-catalog
  (is (= (into #{} (map :class facts/catalog)) facts/allowed-source-classes)))

(deftest class-allowed?-rejects-unlisted-classes
  (is (facts/class-allowed? :generator-self-declaration))
  (is (facts/class-allowed? :collector-visual-inspection))
  (is (facts/class-allowed? :facility-intake-scan))
  (is (not (facts/class-allowed? :inference)))
  (is (not (facts/class-allowed? :unverified-guess)))
  (is (not (facts/class-allowed? nil))))

(deftest coverage-is-honest-not-aspirational
  (let [c (facts/coverage)]
    ;; the catalog is a handful of real regulatory frameworks, not "全世界
    ;; の廃棄物規制" — this test fails loudly if someone pads the catalog
    ;; with unverifiable entries.
    (is (= (count facts/catalog) (:framework-count c)))
    (is (<= (:framework-count c) 20) "R0 catalog should stay small and citable, not bulk-padded")
    (is (contains? (:jurisdictions c) :jpn))
    (is (contains? (:jurisdictions c) :usa))))
