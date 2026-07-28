(ns loop-innen.core
  "observe -> evaluate -> decide -> act -> record-evidence over the 因縁
   dependency record.

   `loop-*` per the workspace taxonomy in com-junkawasaki/root `manifest/repository-rules.edn`:
   this namespace owns the ORDER and the evidence ledger. It owns no scoring
   truth -- criticality, cascade, concentration, cycles and the historical slice
   all live in `kotoba-lang/innen`, the same way `loop-system-dynamics` defers to
   `kotoba-lang/dynamics`.

   Deliberately not a scheduler: one call = one cycle. Whatever runs it on a
   timer (cron, a routine, a human) is that caller's business."
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [innen.algo :as ia]
            [innen.core :as ic]
            [innen.schema :as is]))

(defn- read-edn [f]
  (try (edn/read-string (str (fs/readFileSync f "utf8")))
       (catch :default e {:innen/read-error (.-message e) :innen/file (str f)})))

(defn corpus-files
  "Every corpus file, in a stable order. `resources/*-corpus.edn` holds
   hand-curated, primary-sourced material; `corpus/*.edn` holds ingest output.
   Hand-curated files load FIRST so `merge-graphs` keeps their node definitions
   when an ingest describes the same entity differently."
  [dir]
  (let [ls (fn [sub pred]
             (let [d (path/join dir sub)]
               (if (fs/existsSync d)
                 (->> (fs/readdirSync d) sort (filter pred) (map #(path/join d %)))
                 [])))]
    (concat (ls "resources" #(str/ends-with? % "-corpus.edn"))
            (ls "corpus" #(str/ends-with? % ".edn")))))

(defn observe
  "Load every corpus into one graph. Files that fail to parse, or that parse to
   an unexpected shape, are REPORTED -- a cycle that silently skipped a corpus
   would show a coverage drop as if the world had changed."
  [dir]
  (let [files (corpus-files dir)
        loaded (for [f files
                     :let [c (read-edn f)]]
                 (cond
                   (:innen/read-error c)
                   {:innen/file f :innen/skipped (:innen/read-error c)}

                   (not (map? c))
                   {:innen/file f :innen/skipped "corpus is not a map"}

                   (empty? (:innen/nodes c))
                   {:innen/file f :innen/skipped "corpus has no :innen/nodes"}

                   :else
                   {:innen/file f
                    :innen/dataset (:innen/dataset c)
                    :innen/graph (ic/graph* {:nodes (:innen/nodes c)
                                             :edges (or (:innen/edges c) [])
                                             :as-of (:innen/as-of c)
                                             :source f})}))
        skipped (filterv :innen/skipped loaded)
        graphs (filterv :innen/graph loaded)
        merged (reduce (fn [acc {:keys [innen/graph]}]
                         (if acc (ic/merge-graphs acc graph) graph))
                       nil
                       graphs)]
    {:innen/graph (or merged (ic/graph* {:nodes [] :edges []}))
     :innen/corpora (mapv (fn [{:keys [innen/file innen/dataset innen/graph]}]
                            {:innen/file file
                             :innen/dataset dataset
                             :innen/nodes (count (:innen/nodes graph))
                             :innen/edges (count (:innen/edges graph))})
                          graphs)
     :innen/skipped skipped}))

(def slice-years
  "Historical slices reported every cycle. These are eras, not decoration: if the
   record only answers questions about the present, the slice counts say so."
  ["-0221" "0100" "1000" "1500" "1700" "1850" "1950" "2000" "2026"])

(defn evaluate
  "Everything scored, all of it delegated to `innen`."
  [g]
  (let [crit (ia/criticality g)
        cyc (ia/cycles g)
        conc (->> (ic/node-ids g)
                  (map #(ia/concentration g %))
                  (filter (fn [c] (some (fn [[_k v]] (:innen/single-source? v)) (:innen/per-kind c))))
                  vec)]
    {:innen/stats (ic/stats g)
     :innen/criticality crit
     :innen/cycles cyc
     :innen/single-source conc
     :innen/frontier (ia/frontier g)
     ;; Both readings, every cycle. The permissive slice keeps undated items, so
     ;; on a record where most entities state no founding date it reports coverage
     ;; the record does not have (measured: a permissive 1700 slice kept 303
     ;; nodes, including present-day Delaware registrations). The strict slice is
     ;; the honest answer to "what can this record say about that era".
     :innen/slices (mapv (fn [y]
                           (let [any (ic/as-of g y)
                                 window (ic/as-of g y {:basis :stated-or-endpoint})
                                 stated (ic/as-of g y {:basis :stated})]
                             {:innen/year y
                              :innen/nodes (count (:innen/nodes any))
                              :innen/edges (count (:innen/edges any))
                              :innen/window-nodes (count (:innen/nodes window))
                              :innen/window-edges (count (:innen/edges window))
                              :innen/dated-nodes (count (:innen/nodes stated))
                              :innen/dated-edges (count (:innen/edges stated))}))
                         slice-years)
     :innen/warnings (frequencies (map :innen/code (is/warnings (:innen/problems g))))}))

(defn decide
  "Rank what this cycle found. Ranking is ordering, not scoring -- the numbers
   come from `evaluate`, and each finding carries the basis its score was
   computed on so a thin-record zero is never read as a measured absence."
  [{:innen/keys [criticality cycles single-source frontier stats] :as _ev}]
  (let [top-crit (vec (take 10 (filter #(pos? (:innen/dependents-lost %)) criticality)))]
    {:innen/findings
     (cond-> []
       (seq top-crit)
       (conj {:innen/kind :critical-nodes
              :innen/headline (str (count top-crit) " node(s) whose failure propagates to at least one other")
              :innen/items (mapv #(select-keys % [:innen.node/id :innen.node/label :innen/dependents-lost :innen/direct-dependents :innen/basis]) top-crit)})

       (seq cycles)
       (conj {:innen/kind :mutual-dependency
              :innen/headline (str (count cycles) " mutual-dependency cluster(s)")
              :innen/items (mapv vec cycles)})

       (seq single-source)
       (conj {:innen/kind :single-source
              :innen/headline (str (count single-source) " node(s) with a single recorded source for some dependency kind")
              :innen/items (mapv (fn [c]
                                   {:innen.node/id (:innen.node/id c)
                                    :innen/kinds (vec (keep (fn [[k v]] (when (:innen/single-source? v) k)) (:innen/per-kind c)))})
                                 (take 25 single-source))})

       true
       (conj {:innen/kind :ingest-frontier
              :innen/headline (str (count frontier) " leaf node(s) with nothing recorded upstream")
              :innen/note "A leaf is a gap in the record, not an entity that depends on nothing. Ranked by how many dependents its missing upstream would reach."
              :innen/items (mapv #(select-keys % [:innen.node/id :innen.node/label :innen.node/kind :innen/dependents]) (take 15 frontier))}))
     :innen/coverage stats}))

(defn- fmt-table [rows headers]
  (str "| " (str/join " | " headers) " |\n"
       "|" (str/join "|" (repeat (count headers) "---")) "|\n"
       (str/join "\n" (map (fn [r] (str "| " (str/join " | " (map str r)) " |")) rows))
       "\n"))

(defn act
  "Write the cycle's report. Markdown, because a human reads it; every number in
   it is reproducible from the corpus files the header names."
  [{:keys [as-of observation evaluation decision out-file]}]
  (let [{:innen/keys [corpora skipped]} observation
        {:innen/keys [stats slices warnings]} evaluation
        body
        (str "# loop-innen — cycle report " as-of "\n\n"
             "Dependency record over human history: entities, contracts, events, incidents,\n"
             "and the sourced edges between them. Scoring truth: `kotoba-lang/innen`.\n\n"
             "## Corpora observed\n\n"
             (fmt-table (map (fn [c] [(:innen/dataset c) (:innen/nodes c) (:innen/edges c) (:innen/file c)]) corpora)
                        ["dataset" "nodes" "edges" "file"])
             (when (seq skipped)
               (str "\n**Skipped " (count skipped) " corpus file(s)** — reported rather than dropped:\n\n"
                    (str/join "\n" (map #(str "- `" (:innen/file %) "` — " (:innen/skipped %)) skipped))
                    "\n"))
             "\n## Record shape\n\n"
             "- nodes: **" (:innen/node-count stats) "**, edges: **" (:innen/edge-count stats) "**\n"
             "- by node kind: " (pr-str (:innen/by-node-kind stats)) "\n"
             "- by edge kind: " (pr-str (:innen/by-edge-kind stats)) "\n"
             "- by necessity: " (pr-str (:innen/by-necessity stats)) "\n"
             "- by confidence: " (pr-str (:innen/by-confidence stats)) "\n"
             "- earliest recorded validity start: **" (or (:innen/earliest-valid-from stats) "none") "**\n"
             "- schema warnings: " (pr-str warnings) "\n"
             "\n## Historical coverage\n\n"
             "What the record can answer, by era, under all three readings of a slice\n"
             "(`innen.core/as-of` `:basis`):\n\n"
             "- **any** — an item that states no interval is kept, because nothing can honestly\n"
             "  exclude it. Inflated wherever the record is undated.\n"
             "- **window** (`:stated-or-endpoint`) — an undated edge counts when both endpoints\n"
             "  are dated and existed then. Derived from stated facts, not guessed.\n"
             "- **stated** — only intervals the record actually states. The strictest measure.\n\n"
             "A `stated` count of 0 means the record cannot yet answer dependency questions\n"
             "about that era — not that nothing depended on anything then.\n\n"
             (fmt-table (map (fn [s] [(:innen/year s) (:innen/nodes s) (:innen/edges s)
                                      (:innen/window-nodes s) (:innen/window-edges s)
                                      (:innen/dated-nodes s) (:innen/dated-edges s)]) slices)
                        ["as-of" "nodes (any)" "edges (any)" "nodes (window)" "edges (window)" "nodes (stated)" "edges (stated)"])
             "\n## Findings\n\n"
             (str/join "\n"
                       (for [f (:innen/findings decision)]
                         (str "### " (name (:innen/kind f)) " — " (:innen/headline f) "\n\n"
                              (when-let [n (:innen/note f)] (str n "\n\n"))
                              (str/join "\n" (map #(str "- " (pr-str %)) (:innen/items f)))
                              "\n")))
             "\n---\n\n"
             "Regenerate: `nbb --classpath \"../innen/src:src\" bin/run.cljs`\n")]
    (fs/mkdirSync (path/dirname out-file) #js {:recursive true})
    (fs/writeFileSync out-file body)
    {:innen/report out-file :innen/bytes (count body)}))

(defn- next-seq [ledger-file]
  (if (fs/existsSync ledger-file)
    (->> (str/split-lines (str (fs/readFileSync ledger-file "utf8")))
         (remove str/blank?)
         (keep #(try (:event/seq (edn/read-string %)) (catch :default _ nil)))
         (reduce max 0)
         inc)
    1))

(defn record-evidence
  "Append exactly one line to the append-only ledger. Never rewrites: the
   ledger is the record of what each cycle actually observed, and editing it
   would destroy the only evidence that a number moved."
  [{:keys [as-of ledger-file observation evaluation decision report]}]
  (let [entry {:event/seq (next-seq ledger-file)
               :event/as-of as-of
               :event/kind :innen/cycle
               :innen/corpora (mapv #(select-keys % [:innen/dataset :innen/nodes :innen/edges]) (:innen/corpora observation))
               :innen/skipped (mapv #(select-keys % [:innen/file :innen/skipped]) (:innen/skipped observation))
               :innen/stats (:innen/stats evaluation)
               :innen/slices (:innen/slices evaluation)
               :innen/findings (mapv #(select-keys % [:innen/kind :innen/headline]) (:innen/findings decision))
               :innen/top-critical (mapv #(select-keys % [:innen.node/id :innen/dependents-lost])
                                         (take 5 (filter #(pos? (:innen/dependents-lost %)) (:innen/criticality evaluation))))
               :innen/report (:innen/report report)}]
    (fs/mkdirSync (path/dirname ledger-file) #js {:recursive true})
    (fs/appendFileSync ledger-file (str (binding [*print-namespace-maps* false] (pr-str entry)) "\n"))
    entry))

(defn cycle!
  "One full cycle. Returns the ledger entry."
  [{:keys [dir as-of report-file ledger-file]}]
  (let [dir (or dir ".")
        report-file (or report-file (path/join dir "target" "loop-innen-report.md"))
        ledger-file (or ledger-file (path/join dir "ledger" "loop-innen-ledger.edn"))
        observation (observe dir)
        g (:innen/graph observation)
        evaluation (evaluate g)
        decision (decide evaluation)
        report (act {:as-of as-of :observation observation :evaluation evaluation
                     :decision decision :out-file report-file})]
    (record-evidence {:as-of as-of :ledger-file ledger-file :observation observation
                      :evaluation evaluation :decision decision :report report})))
