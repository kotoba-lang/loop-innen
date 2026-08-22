(ns loop-innen.scripts-load-test
  "The suite does not otherwise load the scripts, and that is how a syntax error
  shipped.

  On 2026-08-22 a docstring in `scripts/tick.cljs` was given a bare `\"` in the
  middle of it. The docstring ended there, the next word was read as a symbol,
  and every invocation died with `registered is not ISeqable`. The unit suite
  stayed green throughout, because it exercises `src/` and never opens a script.
  The failure surfaced only when launchd ran the residency for real.

  Note what did NOT work as a check: counting quotes. The broken line had two of
  them, so parity was preserved and the file still 'looked' balanced. The same
  trap CLAUDE.md records for heredoc EDN — an even number of quotes closes every
  form, and the reader returns a value without complaining.

  So this test loads each entry point for real, in a subprocess, with
  `--parse-only`: the flag is read after the whole file has been read and
  compiled, so reaching it is proof the file is well-formed, and the script
  exits before doing any work or any network I/O."
  (:require ["node:child_process" :as cp]
            [cljs.test :refer [deftest is testing]]))

(def entry-points
  ["scripts/tick.cljs"
   "scripts/ingest_wikidata.cljs"
   "scripts/ingest_workspace.cljs"
   "bin/run.cljs"])

(defn- load-script
  "Run one entry point with --parse-only. Returns {:exit :err}."
  [path]
  (let [r (cp/spawnSync "nbb"
                        (clj->js ["--classpath" "../innen/src:src:scripts" path "--parse-only"])
                        (clj->js {:encoding "utf8" :timeout 120000}))]
    {:exit (.-status r)
     :err  (str (.-stderr r) (.-stdout r))}))

(deftest every-entry-point-loads
  (doseq [p entry-points]
    (testing p
      (let [{:keys [exit err]} (load-script p)]
        (is (zero? exit)
            (str p " did not load; nbb said:\n" err))))))

(deftest the-guard-runs-no-work
  (testing "--parse-only must exit before the ingest, or this test becomes a
            four-way network call on every suite run"
    (let [{:keys [err]} (load-script "scripts/ingest_wikidata.cljs")]
      (is (not (re-find #"seeds, depth" err))
          "the ingest banner appeared, so --parse-only did not stop the work"))))
