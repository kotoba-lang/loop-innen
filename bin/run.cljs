(ns run
  "One 因縁 cycle: observe every corpus -> evaluate via kotoba-lang/innen ->
   decide -> write the report -> append one ledger line.

     nbb --classpath \"../innen/src:src\" bin/run.cljs [--as-of YYYY-MM-DD] [--dir .]"
  (:require [clojure.string :as str]
            [loop-innen.core :as loop-innen]))

(defn- args->map [args]
  (into {} (for [[k v] (partition-all 2 args) :when (and k (str/starts-with? k "--"))]
             [(keyword (subs k 2)) v])))

(let [cli (args->map *command-line-args*)
      as-of (or (:as-of cli) (.toISOString.slice (js/Date.) 0 10))
      entry (loop-innen/cycle! {:dir (or (:dir cli) ".") :as-of as-of})]
  (println (str "loop-innen cycle " (:event/seq entry) " — " as-of))
  (println (str "  corpora: " (count (:innen/corpora entry))
                (when (seq (:innen/skipped entry)) (str " (skipped " (count (:innen/skipped entry)) ")"))))
  (doseq [c (:innen/corpora entry)]
    (println (str "    " (:innen/dataset c) ": " (:innen/nodes c) " nodes, " (:innen/edges c) " edges")))
  (println (str "  record: " (:innen/node-count (:innen/stats entry)) " nodes, "
                (:innen/edge-count (:innen/stats entry)) " edges"))
  (doseq [f (:innen/findings entry)]
    (println (str "  finding [" (name (:innen/kind f)) "] " (:innen/headline f))))
  (doseq [t (:innen/top-critical entry)]
    (println (str "    critical: " (:innen.node/id t) " -> " (:innen/dependents-lost t) " dependent(s) lost")))
  (println (str "  report: " (:innen/report entry))))
