(ns query
  "CLI for the DataScript query layer over the 因縁 corpora.

     nbb --classpath \"../innen/src:src:bin\" bin/query.cljs <mode> [args]

   modes: stats | demo | deps <node-id> | explain <from> <to> | as-of <year> | q '<datalog>'"
  (:require [loop-innen.query :as lq]))

(let [[mode a b] *command-line-args*
      dir "."]
  (case mode
    "stats" (lq/run-stats dir)
    "demo" (lq/run-demo dir)
    "deps" (lq/run-deps dir a)
    "explain" (lq/run-explain dir a b)
    "as-of" (lq/run-as-of dir a)
    "q" (let [{:keys [conn]} (lq/build dir)]
          (doseq [row (sort-by str (lq/q a conn))]
            (println (pr-str row))))
    (do (println "usage: nbb --classpath \"../innen/src:src:bin\" bin/query.cljs [stats | demo | deps <node-id> | explain <from> <to> | as-of <year> | q '<datalog>']")
        (js/process.exit 1))))
