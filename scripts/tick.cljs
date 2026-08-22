(ns tick
  "One resident tick of the 因縁 record: ingest -> ingest -> cycle -> publish.

     nbb --classpath \"../innen/src:src:scripts\" scripts/tick.cljs \\
       [--depth 2] [--as-of YYYY-MM-DD] [--root <superproject>] [--no-push]

   Run by launchd through `tamaki exec`; the job is
   `scripts/com.kotoba.innen-tick.plist` (see ADR-2607258500). `tamaki exec`
   wraps this whole script as ONE AgentRun, so `tamaki status` shows one run per
   tick with its real exit code, while this repo's own
   `ledger/loop-innen-ledger.edn` keeps the per-cycle detail. Two records, each
   authoritative for its own thing.

   This paragraph used to claim the residency was `registered with tamaki and
   run by launchd`. Measured 2026-08-22, neither half was true: tamaki listed this
   loop only as an example line in its README, and no launchd job existed on the
   operator machine. The record had stopped on 2026-08-12 and the docstring went
   on describing a residency that was not there. A claim about the world that
   nothing checks decays into a claim about the past.

   Failure policy, deliberately not uniform:

   * an INGEST failure does not abort the tick. The corpus that failed keeps its
     previous content, the cycle still runs over what is there, and the step is
     reported as failed -- a transient Wikidata outage must not stop the record
     from reporting its own state.
   * a CYCLE failure DOES fail the tick (exit non-zero). If evaluate/report/ledger
     cannot run, there is nothing to publish and launchd should see red.
   * PUBLISH is skipped, not failed, when nothing changed -- an empty commit
     every 6 hours would bury the ticks that actually grew the record.

   And one guard that exists because it bit during development: a corpus file is
   a whole-file rewrite, so an ingest that returns FEWER entities than the file
   already holds would silently shrink the record. (Measured: re-running at
   `--depth 1` over a depth-2 corpus took it from 142 nodes / 124 edges back to
   74 / 42.) `ingest-guarded!` writes to a temp path, compares, and refuses to
   replace a corpus with a smaller one -- reporting the refusal instead of
   quietly regressing. A tick that runs unattended every 6 hours must not be
   able to lose ground."
  (:require [loop-innen.cli :refer [args->map string-opt]]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))


(def cli (args->map *command-line-args*))
(def as-of (string-opt cli :as-of (.slice (.toISOString (js/Date.)) 0 10)))
(def depth (string-opt cli :depth "2"))
(def superproject (string-opt cli :root "../../.."))
(def push? (not (:no-push cli)))
(def classpath "../innen/src:src:scripts")

(defn- run!
  "Run a step, return {:step :exit :ok?}. stdio is inherited so launchd's log
   holds the whole story."
  [label argv & [opts]]
  (println (str "\n[innen-tick] " label ": " (str/join " " argv)))
  (let [r (.spawnSync cp (first argv) (clj->js (vec (rest argv)))
                      (clj->js (merge {:stdio "inherit" :encoding "utf8"} opts)))
        exit (or (.-status r) 1)]
    {:step label :exit exit :ok? (zero? exit)}))

(defn- corpus-size
  "{:nodes n :edges n} for an existing corpus file, or nil when absent/unreadable."
  [path]
  (when (fs/existsSync path)
    (try
      (let [c (edn/read-string (str (fs/readFileSync path "utf8")))]
        {:nodes (count (:innen/nodes c)) :edges (count (:innen/edges c))})
      (catch :default _ nil))))

(defn- ingest-guarded!
  "Run an ingest into a temp path, then replace `out` only if the result is not
   smaller than what is already there. Returns the step map, with
   `:refused-regression` when it declined to shrink the record."
  [label argv out]
  (let [tmp (str out ".tick-tmp")
        before (corpus-size out)
        argv' (mapv #(if (= % out) tmp %) argv)
        step (run! label argv')
        after (corpus-size tmp)]
    (cond
      (not (:ok? step))
      (do (when (fs/existsSync tmp) (fs/unlinkSync tmp))
          (assoc step :kept (when before "previous corpus")))

      (nil? after)
      (do (when (fs/existsSync tmp) (fs/unlinkSync tmp))
          (assoc step :ok? false :exit 1 :note "ingest produced an unreadable corpus"))

      (and before (or (< (:nodes after) (:nodes before))
                      (< (:edges after) (:edges before))))
      (do (fs/unlinkSync tmp)
          (println (str "[innen-tick] " label ": REFUSED to replace "
                        (:nodes before) " nodes / " (:edges before) " edges with "
                        (:nodes after) " / " (:edges after) " — keeping the larger corpus"))
          (assoc step :refused-regression {:before before :after after}))

      :else
      (do (fs/renameSync tmp out)
          (assoc step :size after)))))

(defn- git [& args]
  (let [r (.spawnSync cp "git" (clj->js (vec args)) #js {:encoding "utf8"})]
    {:exit (or (.-status r) 1)
     :out (str/trim (str (.-stdout r)))}))

(defn- dirty-paths
  "Only the paths a tick is allowed to publish. Anything else a human left in the
   tree is none of this tick's business, and committing it would launder someone
   else's edit into an automated commit."
  []
  (->> (:out (git "status" "--porcelain" "--" "corpus" "ledger" "target"))
       str/split-lines
       (remove str/blank?)
       (map #(str/trim (subs % 2)))
       (remove #(str/starts-with? % "target/"))    ; report is regenerated, not tracked
       vec))

(defn -main []
  (println (str "[innen-tick] " (.toISOString (js/Date.)) " as-of=" as-of
                " depth=" depth " push=" push?))
  (let [steps
        [(ingest-guarded! "ingest:wikidata"
                          ["nbb" "--classpath" classpath "scripts/ingest_wikidata.cljs"
                           "--depth" depth "--as-of" as-of
                           "--out" (str "corpus/wikidata-" as-of ".edn")]
                          (str "corpus/wikidata-" as-of ".edn"))
         (ingest-guarded! "ingest:workspace"
                          ["nbb" "--classpath" classpath "scripts/ingest_workspace.cljs"
                           "--root" superproject "--as-of" as-of
                           "--merge-with" (str "corpus/wikidata-" as-of ".edn")
                           "--out" (str "corpus/workspace-" as-of ".edn")]
                          (str "corpus/workspace-" as-of ".edn"))]
        cycle-step (run! "cycle" ["nbb" "--classpath" classpath "bin/run.cljs"
                                  "--as-of" as-of])
        changed (dirty-paths)
        publish
        (cond
          (not push?) {:step "publish" :exit 0 :ok? true :skipped "--no-push"}

          (empty? changed)
          (do (println "\n[innen-tick] publish: nothing changed, no commit")
              {:step "publish" :exit 0 :ok? true :skipped "no change"})

          :else
          (let [msg (str "chore(innen): resident tick " as-of " — corpus + ledger\n\n"
                         "Automated tick (tamaki exec + launchd). Paths: "
                         (str/join ", " changed) "\n")]
            (println (str "\n[innen-tick] publish: " (count changed) " path(s) changed"))
            (let [add (git "add" "--" "corpus" "ledger")
                  commit (if (zero? (:exit add)) (git "commit" "-m" msg) add)
                  pull (if (zero? (:exit commit)) (git "pull" "--ff-only" "-q") commit)
                  push (if (zero? (:exit pull)) (git "push" "-q" "origin" "main") pull)]
              {:step "publish" :exit (:exit push) :ok? (zero? (:exit push))
               :paths changed})))
        all (conj (vec steps) cycle-step publish)]
    (println "\n[innen-tick] summary")
    (doseq [{:keys [step exit ok? skipped size refused-regression]} all]
      (println (str "  " (if ok? "ok  " "FAIL") " " step
                    " (exit " exit ")"
                    (when skipped (str " — skipped: " skipped))
                    (when size (str " — " (:nodes size) " nodes / " (:edges size) " edges"))
                    (when refused-regression
                      (str " — REFUSED regression "
                           (:nodes (:before refused-regression)) "/" (:edges (:before refused-regression))
                           " -> "
                           (:nodes (:after refused-regression)) "/" (:edges (:after refused-regression)))))))
    ;; Ingest failures are reported but do not fail the tick; a cycle or publish
    ;; failure does. See the namespace docstring.
    (let [fatal (remove :ok? [cycle-step publish])]
      (when (seq (remove :ok? steps))
        (println "[innen-tick] NOTE: an ingest step failed; the cycle ran over the previous corpus"))
      (js/process.exit (if (seq fatal) (:exit (first fatal)) 0)))))

(if (:parse-only cli) (js/process.exit 0) (-main))
