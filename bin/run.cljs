(ns run
  "One 因縁 cycle: observe every corpus -> evaluate via kotoba-lang/innen ->
   decide -> write the report -> append one ledger line.

     nbb --classpath \"../innen/src:src\" bin/run.cljs [--as-of YYYY-MM-DD] [--dir .]"
  (:require [loop-innen.cli :refer [args->map string-opt]]
            [clojure.string :as str]
            [loop-innen.core :as loop-innen]))


(let [cli (args->map *command-line-args*)
      _ (when (:parse-only cli) (js/process.exit 0))
      as-of (string-opt cli :as-of (.slice (.toISOString (js/Date.)) 0 10))
      entry (loop-innen/cycle! {:dir (string-opt cli :dir ".") :as-of as-of})]
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
