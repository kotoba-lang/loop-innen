(ns loop-innen.query
  "Datalog query layer over the 因縁 corpora.

     nbb bin/query.cljs stats
     nbb bin/query.cljs demo
     nbb bin/query.cljs deps node/tsmc
     nbb bin/query.cljs explain node/log4shell node/log4j
     nbb bin/query.cljs as-of 1700
     nbb bin/query.cljs q '[:find ?l :where [?e \"innen.node/kind\" \"incident\"] [?e \"innen.node/label\" ?l]]'

   Convention, matching `com-junkawasaki/root`'s `manifest/edn-query.cljs` and
   `loop-system-dynamics/src/loop_system_dynamics/query.cljs` exactly: attributes
   are bare strings (`\"innen.node/id\"`, no leading colon), keyword VALUES are
   stringified the same way (`:node/tsmc` -> `\"node/tsmc\"`). A query written for
   either of those tools runs here unchanged.

   Because there are no lookup refs, an edge joins to its endpoints by VALUE --
   `\"innen.edge/from-id\"` to `\"innen.node/id\"` -- which is exactly why
   `innen.tx` keeps those plain-keyword id attributes alongside the ref ones."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datalog.core :as dl]
            [datalog.index :as index]
            [innen.algo :as ia]
            [innen.core :as ic]
            [innen.tx :as itx]
            [loop-innen.core :as loop-innen]))

(defn- attr-name [k]
  (if (keyword? k)
    (if-let [ns* (namespace k)] (str ns* "/" (name k)) (name k))
    (str k)))

(defn- ->value [v]
  (cond
    (keyword? v) (attr-name v)
    (map? v) (pr-str v)
    (or (vector? v) (seq? v) (set? v)) (pr-str v)
    (nil? v) ""
    :else v))

(defn- parse-vector-query [query]
  (let [qvec (if (string? query) (edn/read-string query) query)
        idx-in (first (keep-indexed #(when (= %2 :in) %1) qvec))
        idx-where (or (first (keep-indexed #(when (= %2 :where) %1) qvec)) -1)
        find-end (or idx-in idx-where)
        find-syms (vec (subvec qvec 1 find-end))
        in-syms (when idx-in (vec (subvec qvec (inc idx-in) idx-where)))
        where-clauses (when (pos? idx-where) (vec (subvec qvec (inc idx-where))))]
    {:find find-syms :in in-syms :where where-clauses}))

(defn- norm-ground [x]
  (cond
    (symbol? x) x
    (keyword? x) (attr-name x)
    :else x))

(defn- norm-clause [clause]
  (mapv norm-ground clause))

(defn- records->db [records]
  (reduce (fn [db record]
            (let [subject (str (:db/id record))]
              (reduce (fn [acc [k v]]
                        (if (= k :db/id)
                          acc
                          (index/assert-quad acc
                                             {:s subject
                                              :p (attr-name k)
                                              :o (->value v)}
                                             (constantly false))))
                      db
                      record)))
          (index/empty-db)
          records))

(defn build
  "Observe every corpus, project it through `innen.tx/->flat-tx`, and load into
   datalog. Returns `{:db … :graph … :corpora … :skipped … :tx-count …}`."
  [dir]
  (let [{:keys [innen/graph innen/corpora innen/skipped]} (loop-innen/observe dir)
        tempid (atom 0)
        next-tempid! (fn [] (swap! tempid dec))
        tx (mapv (fn [e] (assoc e :db/id (next-tempid!)))
                 (itx/->flat-tx graph {:dataset "innen"}))
        db (records->db tx)]
    {:db db :graph graph :corpora corpora :skipped skipped :tx-count (count tx)}))

(defn q
  "`query` may be a datalog string (the convention shared with
   manifest/edn-query.cljs) or Clojure data, which is pr-str'd for you."
  [query db & inputs]
  (let [{:keys [find in where]} (parse-vector-query
                                 (if (string? query) query (pr-str query)))
        in-syms (vec (remove #{'$} (or in [])))
        _ (when (not= (count in-syms) (count inputs))
            (throw (ex-info "loop-innen.query: :in arity mismatch"
                            {:in in-syms :inputs inputs})))]
    (dl/q db {:find find :in in :where (mapv norm-clause where)}
          (constantly true)
          inputs)))

(def demo-queries
  "Queries that exercise the parts of this record that make it worth having: the
   value join through an edge, the BCE-safe integer date keys, and the
   `:company/lei` bridge into the unified plane's financial corpora."
  [{:label "nodes per kind"
    :query "[:find ?kind (count ?e) :where [?e \"innen.node/kind\" ?kind]]"}

   {:label "dependency edges, joined to both endpoints' labels"
    :query (str "[:find ?from ?kind ?to :where "
                "[?e \"innen.edge/kind\" ?kind] "
                "[?e \"innen.edge/from-id\" ?fid] [?e \"innen.edge/to-id\" ?tid] "
                "[?f \"innen.node/id\" ?fid] [?f \"innen.node/label\" ?from] "
                "[?t \"innen.node/id\" ?tid] [?t \"innen.node/label\" ?to]]")}

   {:label "relations that began before 1800 (integer date keys, not string compare)"
    :query (str "[:find ?from ?kind ?to ?vf :where "
                "[?e \"innen.edge/valid-from-key\" ?k] [(< ?k 18000000)] "
                "[?e \"innen.edge/valid-from\" ?vf] [?e \"innen.edge/kind\" ?kind] "
                "[?e \"innen.edge/from-id\" ?fid] [?e \"innen.edge/to-id\" ?tid] "
                "[?f \"innen.node/id\" ?fid] [?f \"innen.node/label\" ?from] "
                "[?t \"innen.node/id\" ?tid] [?t \"innen.node/label\" ?to]]")}

   {:label "entities carrying an LEI — the join key into market-intel / cloud-itonami-lei"
    :query "[:find ?lei ?label :where [?e \"company/lei\" ?lei] [?e \"innen.node/label\" ?label]]"}

   {:label "incidents and what they are recorded as depending on"
    :query (str "[:find ?incident ?kind ?cause :where "
                "[?i \"innen.node/kind\" \"incident\"] [?i \"innen.node/label\" ?incident] "
                "[?i \"innen.node/id\" ?iid] "
                "[?e \"innen.edge/from-id\" ?iid] [?e \"innen.edge/kind\" ?kind] "
                "[?e \"innen.edge/to-id\" ?tid] [?t \"innen.node/id\" ?tid] [?t \"innen.node/label\" ?cause]]")}

   {:label "causation edges with the basis that justifies calling them causal"
    :query (str "[:find ?from ?to ?basis :where "
                "[?e \"innen.edge/kind\" \"causation\"] [?e \"innen.edge/causal-basis\" ?basis] "
                "[?e \"innen.edge/from-id\" ?fid] [?e \"innen.edge/to-id\" ?tid] "
                "[?f \"innen.node/id\" ?fid] [?f \"innen.node/label\" ?from] "
                "[?t \"innen.node/id\" ?tid] [?t \"innen.node/label\" ?to]]")}

   {:label "polities and how many entities' legal authority the record derives from them"
    :query (str "[:find ?label (count ?e) :where "
                "[?e \"innen.edge/kind\" \"legal-authority\"] [?e \"innen.edge/to-id\" ?tid] "
                "[?t \"innen.node/id\" ?tid] [?t \"innen.node/label\" ?label]]")}])

(defn run-demo [dir]
  (let [{:keys [db corpora tx-count skipped]} (build dir)]
    (println (str "loop-innen query: " (count corpora) " corpora, " tx-count " entities transacted"
                  (when (seq skipped) (str ", " (count skipped) " file(s) skipped"))))
    (println)
    (doseq [{:keys [label query]} demo-queries]
      (let [rows (q query db)]
        (println (str "## " label " — " (count rows) " row(s)"))
        (doseq [r (take 12 (sort-by str rows))]
          (println (str "   " (str/join "  |  " (map pr-str r)))))
        (when (> (count rows) 12) (println (str "   … +" (- (count rows) 12) " more")))
        (println)))))

(defn run-stats [dir]
  (let [{:keys [graph corpora skipped tx-count]} (build dir)]
    (println (pr-str {:innen/corpora corpora
                      :innen/skipped (mapv :innen/file skipped)
                      :innen/datoms-entities tx-count
                      :innen/stats (ic/stats graph)}))))

(defn- ->node-kw [x] (keyword (str/replace (str x) #"^:" "")))

(defn run-deps [dir id]
  (let [{:keys [graph]} (build dir)
        nid (->node-kw id)
        direct (ic/dependency-edges graph nid)
        trans (ia/transitive-dependencies graph nid)]
    (if-not (ic/node graph nid)
      (println (str "no such node in the record: " nid))
      (do (println (str "== " nid " — " (:innen.node/label (ic/node graph nid))))
          (println (str "direct dependencies (" (count direct) "):"))
          (doseq [e direct]
            (println (str "  -" (name (:innen.edge/kind e)) "-> " (:innen.edge/to e)
                          " [" (name (:innen.edge/necessity e)) "/" (name (:innen.edge/confidence e)) "]"))
            (println (str "      source: " (:innen.edge/source e))))
          (println (str "transitive (" (:innen/count trans) "): " (pr-str (sort (:innen/dependencies trans)))))
          (println (str "dependents: " (pr-str (sort (ic/dependents graph nid)))))))))

(defn run-explain [dir from to]
  (let [{:keys [graph]} (build dir)
        x (ia/explain graph (->node-kw from) (->node-kw to))]
    (if x
      (do (println (str "== " (:innen/from x) " depends on " (:innen/to x)
                        " through " (:innen/hops x) " hop(s)"))
          (doseq [h (:innen/path x)]
            (println (str "  " (:innen.edge/from h) " -" (name (:innen.edge/kind h)) "-> " (:innen.edge/to h)
                          "   valid " (pr-str (:innen.edge/valid h))
                          " / " (name (:innen.edge/confidence h))))
            (println (str "      source: " (:innen.edge/source h)))))
      (println (str "no recorded path from " from " to " to
                    " — a statement about this record's coverage, not about the world")))))

(defn run-as-of [dir year]
  (let [{:keys [graph]} (build dir)
        s (ic/as-of graph (str year))]
    (println (str "== the record as it stood in " year ": "
                  (count (:innen/nodes s)) " nodes, " (count (:innen/edges s)) " edges"))
    (doseq [e (sort-by (comp str :innen.edge/from) (ic/edges s))]
      (println (str "  " (:innen.edge/from e) " -" (name (:innen.edge/kind e)) "-> " (:innen.edge/to e)
                    "  " (pr-str (:innen.edge/valid e)))))))
