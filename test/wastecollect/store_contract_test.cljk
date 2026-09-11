(ns wastecollect.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic' a configuration change, not a
  rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [wastecollect.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "デモ商店会(架空)" (:name (store/generator s "gen-100"))))
      (is (= :general (:waste-class (store/pickup s "pk-100"))))
      (is (= "fac-100" (:facility-id (store/pickup s "pk-100"))))
      (is (= {:general 5000M :organic 2000M} (:daily-capacity-kg (store/facility s "fac-100"))))
      (is (= {:general 100M} (:daily-capacity-kg (store/facility s "fac-200"))))
      (is (= 0M (store/facility-intake s "fac-100" :general)))
      (is (= 1 (count (store/all-pickups s)))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "pickup upsert"
        (store/commit-record! s {:effect :pickup-upsert
                                 :value {:id "pk-999" :generator-id "gen-100" :facility-id "fac-100"
                                         :waste-class :general :estimated-kg 50M
                                         :scheduled-date "2026-07-11" :hazard-flags #{}
                                         :source {:class :generator-self-declaration :ref "demo"}}})
        (is (= 50M (:estimated-kg (store/pickup s "pk-999")))))
      (testing "manifest record also updates facility-intake"
        (store/commit-record! s {:effect :manifest-record
                                 :value {:pickup-id "pk-999" :actual-kg 55M :waste-class :general
                                         :source {:class :facility-intake-scan :ref "demo"}}})
        (is (= 55M (:actual-kg (store/manifest s "pk-999"))))
        (is (= 55M (store/facility-intake s "fac-100" :general))))
      (testing "dispute-apply patches the manifest"
        (store/commit-record! s {:effect :dispute-apply
                                 :value {:patch {:actual-kg 60M}}
                                 :path ["pk-999"]})
        (is (= 60M (:actual-kg (store/manifest s "pk-999")))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (take-last 2 (store/ledger s)))))))))

(deftest contract-lookup
  (doseq [[label s] (backends)]
    (testing label
      (is (= :tier/detailed (:tier (store/contract s "tenant-acme"))))
      (is (true? (:active? (store/contract s "tenant-acme"))))
      (is (nil? (store/contract s "tenant-ghost"))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/pickup s "nope")))
    (is (= [] (store/all-pickups s)))
    (is (= [] (store/ledger s)))
    (store/with-pickups s {"x" {:id "x" :generator-id "g" :facility-id "f" :waste-class :general
                                :estimated-kg 1M :scheduled-date "2026-01-01" :hazard-flags #{}
                                :source nil}})
    (is (= :general (:waste-class (store/pickup s "x"))))))
