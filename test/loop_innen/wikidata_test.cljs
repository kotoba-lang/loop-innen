(ns loop-innen.wikidata-test
  "Pure-function tests only -- no network. The API-touching parts
   (`resolve-seed`, `verify-properties!`, `fetch-entities`) are exercised by
   actually running `scripts/ingest_wikidata.cljs`, whose output lands in
   `corpus/` as checked-in evidence; mocking HTTP here would test the mock."
  (:require [cljs.test :refer [deftest is testing]]
            [loop-innen.wikidata :as wd]))

(deftest time-precision-is-preserved-not-widened-test
  (testing "day / month / year precision each map to a same-precision innen date"
    (is (= {:date "1602-03-20" :precision :day} (wd/wd-time->date {"time" "+1602-03-20T00:00:00Z" "precision" 11})))
    (is (= {:date "1602-03" :precision :month} (wd/wd-time->date {"time" "+1602-03-00T00:00:00Z" "precision" 10})))
    (is (= {:date "1602" :precision :year} (wd/wd-time->date {"time" "+1602-00-00T00:00:00Z" "precision" 9}))))
  (testing "BCE keeps its sign, which innen.time orders correctly"
    (is (= "-0221" (:date (wd/wd-time->date {"time" "-0221-00-00T00:00:00Z" "precision" 9})))))
  (testing "coarser than a year is REFUSED, not degraded -- 'the 1870s' is not 1870"
    (let [r (wd/wd-time->date {"time" "+1870-00-00T00:00:00Z" "precision" 8})]
      (is (nil? (:date r)))
      (is (some? (:reason r))))))

(deftest node-id-is-a-valid-keyword-test
  (is (= :node/dutch-east-india-company (wd/node-id "Dutch East India Company" "Q6669")))
  (testing "a digit-leading label gets an n prefix: EDN keywords may not start with a digit"
    (is (= :node/n1973-oil-crisis (wd/node-id "1973 oil crisis" "Q316817")))
    (testing "and the result really does read back"
      (is (= :node/n1973-oil-crisis (cljs.reader/read-string (pr-str (wd/node-id "1973 oil crisis" "Q316817")))))))
  (testing "a label with no usable characters falls back to the QID"
    (is (= :node/q42 (wd/node-id "!!!" "Q42")))
    (is (= :node/q42 (wd/node-id nil "Q42")))))

(deftest kind-inference-covers-the-classes-the-first-real-pass-refused-test
  (testing "classes present in the first pass"
    (is (= :organization (first (wd/infer-kind ["public company"]))))
    (is (= :polity (first (wd/infer-kind ["sovereign state"]))))
    (is (= :incident (first (wd/infer-kind ["nuclear disaster"]))))
    (is (= :person (first (wd/infer-kind ["human"])))))
  (testing "classes ADDED because the first real pass refused them"
    (is (= :contract (first (wd/infer-kind ["Act of the Parliament of Great Britain"]))))
    (is (= :organization (first (wd/infer-kind ["voorcompagnie"]))))
    (is (= :incident (first (wd/infer-kind ["megathrust earthquake" "tsunami"]))))
    (is (= :artifact (first (wd/infer-kind ["container ship"]))))
    (is (= :event (first (wd/infer-kind ["stock market crash"]))))
    (is (= :document (first (wd/infer-kind ["proposal" "memorandum" "plan"])))))
  (testing "no fallback kind: an unmatched class returns nil so the caller refuses the node"
    (is (nil? (wd/infer-kind ["error message"])))
    (is (nil? (wd/infer-kind ["ethnic minority group"])))
    (is (nil? (wd/infer-kind []))))
  (testing "the matched label comes back so the inference is checkable"
    (is (= "container ship" (second (wd/infer-kind ["container ship"]))))))

(deftest property-map-shape-test
  (testing "every mapping declares the label it will be verified against"
    (is (every? :expect-label (vals wd/property-map))))
  (testing "every mapping declares a kind and a necessity -- innen.schema rejects edges without them"
    (is (every? :kind (vals wd/property-map)))
    (is (every? :necessity (vals wd/property-map))))
  (testing "P1542 (has effect) is the one reversed mapping: it reads opposite to an innen edge"
    (is (true? (:reverse? (get wd/property-map "P1542"))))
    (is (not-any? :reverse? (vals (dissoc wd/property-map "P1542"))))))
