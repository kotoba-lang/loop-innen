(ns loop-innen.core-test
  "Cycle tests against a temp corpus directory -- hermetic, no network, and no
   dependency on how big the checked-in corpus happens to be today."
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [cljs.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [loop-innen.core :as sut]))

(defn- tmp-dir []
  (let [d (fs/mkdtempSync (path/join (os/tmpdir) "loop-innen-test-"))]
    (fs/mkdirSync (path/join d "corpus") #js {:recursive true})
    d))

(defn- write-corpus! [dir name m]
  (fs/writeFileSync (path/join dir "corpus" name)
                    (with-out-str (binding [*print-namespace-maps* false] (pr m)))))

(def node-a
  {:innen.node/id :node/a :innen.node/kind :organization :innen.node/label "A"
   :innen.node/existed {:from "1600" :to "1700"}
   :innen.node/source "test fixture"})

(def node-b
  {:innen.node/id :node/b :innen.node/kind :polity :innen.node/label "B"
   :innen.node/existed {:from "1500"}
   :innen.node/source "test fixture"})

(def edge-ab
  {:innen.edge/from :node/a :innen.edge/to :node/b :innen.edge/kind :legal-authority
   :innen.edge/necessity :required :innen.edge/confidence :documented
   :innen.edge/as-of "2026-07-25" :innen.edge/valid {:from "1600" :to "1700"}
   :innen.edge/source "test fixture"})

(deftest observe-merges-corpora-test
  (let [d (tmp-dir)]
    (write-corpus! d "one.edn" {:innen/dataset "one" :innen/as-of "2026-07-25"
                                :innen/nodes [node-a] :innen/edges []})
    (write-corpus! d "two.edn" {:innen/dataset "two" :innen/as-of "2026-07-25"
                                :innen/nodes [node-b] :innen/edges [edge-ab]})
    (let [{:keys [innen/graph innen/corpora innen/skipped]} (sut/observe d)]
      (is (= 2 (count corpora)))
      (is (empty? skipped))
      (is (= 2 (count (:innen/nodes graph))))
      (is (= 1 (count (:innen/edges graph)))))))

(deftest observe-reports-what-it-could-not-read-test
  (let [d (tmp-dir)]
    (write-corpus! d "good.edn" {:innen/dataset "good" :innen/nodes [node-a] :innen/edges []})
    (fs/writeFileSync (path/join d "corpus" "broken.edn") "{:innen/nodes [{:innen.node/id :node/x")
    (fs/writeFileSync (path/join d "corpus" "empty.edn") "{:innen/nodes []}")
    (let [{:keys [innen/corpora innen/skipped]} (sut/observe d)]
      (is (= 1 (count corpora)))
      (testing "a corpus that fails to parse, and one with no nodes, are both reported -- a silent skip would look like the world shrank"
        (is (= 2 (count skipped)))
        (is (every? :innen/skipped skipped))))))

(deftest cycle-writes-report-and-appends-exactly-one-ledger-line-test
  (let [d (tmp-dir)
        ledger (path/join d "ledger" "loop-innen-ledger.edn")]
    (write-corpus! d "one.edn" {:innen/dataset "one" :innen/nodes [node-a node-b] :innen/edges [edge-ab]})
    (let [e1 (sut/cycle! {:dir d :as-of "2026-07-25"})
          e2 (sut/cycle! {:dir d :as-of "2026-07-26"})
          lines (remove str/blank? (str/split-lines (str (fs/readFileSync ledger "utf8"))))]
      (is (= 1 (:event/seq e1)))
      (testing "seq increments, so a cycle can never overwrite an earlier one"
        (is (= 2 (:event/seq e2))))
      (is (= 2 (count lines)))
      (testing "every line is readable EDN on its own -- the ledger is append-only, one form per line"
        (is (= [1 2] (mapv #(:event/seq (edn/read-string %)) lines))))
      (is (fs/existsSync (path/join d "target" "loop-innen-report.md")))
      (testing "the report names the corpora it read"
        (is (str/includes? (str (fs/readFileSync (path/join d "target" "loop-innen-report.md") "utf8"))
                           "one"))))))

(deftest evaluate-reports-all-three-slice-bases-test
  (let [d (tmp-dir)]
    (write-corpus! d "one.edn"
                   {:innen/dataset "one"
                    :innen/nodes [node-a node-b
                                  ;; undated node: survives :any, excluded from the others
                                  {:innen.node/id :node/c :innen.node/kind :organization
                                   :innen.node/label "C" :innen.node/source "test fixture"}]
                    :innen/edges [edge-ab
                                  ;; undated edge between two DATED nodes: :stated-or-endpoint keeps it
                                  {:innen.edge/from :node/b :innen.edge/to :node/a
                                   :innen.edge/kind :funding :innen.edge/necessity :substitutable
                                   :innen.edge/confidence :attested :innen.edge/as-of "2026-07-25"
                                   :innen.edge/source "test fixture"}]})
    (let [{:keys [innen/graph]} (sut/observe d)
          slices (:innen/slices (sut/evaluate graph))
          at (fn [y] (first (filter #(= y (:innen/year %)) slices)))]
      (testing "1650: all three bases agree the stated edge holds"
        (is (= 1 (:innen/dated-edges (at "1700"))))
        (is (= 2 (:innen/window-edges (at "1700"))))
        (is (= 2 (:innen/edges (at "1700")))))
      (testing "2026: A no longer existed, so NO basis keeps an edge touching it — endpoint existence binds even the permissive reading"
        (is (= 0 (:innen/dated-edges (at "2026"))))
        (is (= 0 (:innen/window-edges (at "2026"))))
        (is (= 0 (:innen/edges (at "2026"))))
        (testing "the undated node still shows up in the permissive node count"
          (is (= 2 (:innen/nodes (at "2026"))))
          (is (= 1 (:innen/dated-nodes (at "2026"))))))
      (testing "the undated node is only ever in the permissive count"
        (is (= 3 (:innen/nodes (at "1700"))))
        (is (= 2 (:innen/dated-nodes (at "1700"))))))))

(deftest decide-always-reports-the-ingest-frontier-test
  (let [d (tmp-dir)]
    (write-corpus! d "one.edn" {:innen/dataset "one" :innen/nodes [node-a node-b] :innen/edges [edge-ab]})
    (let [{:keys [innen/graph]} (sut/observe d)
          {:keys [innen/findings]} (sut/decide (sut/evaluate graph))
          kinds (set (map :innen/kind findings))]
      (is (contains? kinds :ingest-frontier))
      (testing "a leaf is described as a gap in the record, not as an entity depending on nothing"
        (let [f (first (filter #(= :ingest-frontier (:innen/kind %)) findings))]
          (is (str/includes? (:innen/note f) "gap in the record"))))
      (testing "critical nodes are reported with the basis their score was computed on"
        (let [f (first (filter #(= :critical-nodes (:innen/kind %)) findings))]
          (is (every? :innen/basis (:innen/items f))))))))
