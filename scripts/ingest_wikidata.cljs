(ns ingest-wikidata
  "Ingest real, sourced dependency edges from Wikidata into a corpus file.

     nbb --classpath \"../innen/src:src\" scripts/ingest_wikidata.cljs \\
       [--depth 1] [--seeds resources/wikidata-seeds.edn] [--out corpus/…] [--as-of YYYY-MM-DD]

   Everything this script writes is checkable: every node carries its QID, every
   edge names the statement it came from, and every refusal (unresolvable seed,
   property whose label did not match, entity whose kind could not be inferred,
   date precision coarser than a year) is written into the corpus file itself
   under `:innen/refused` rather than dropped. A corpus that silently omits what
   it could not read would report growth it did not achieve.

   `--as-of` exists because Date.now() is unavailable in some of this
   workspace's runners; it defaults to the system date here, which is fine for a
   script a human invokes."
  (:require [loop-innen.cli :refer [args->map string-opt]]
            ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [innen.core :as ic]
            [innen.schema :as is]
            [loop-innen.wikidata :as wd]
            [promesa.core :as p]))


(def cli (args->map *command-line-args*))
(def as-of (string-opt cli :as-of (.slice (.toISOString (js/Date.)) 0 10)))
(def max-depth (js/parseInt (string-opt cli :depth "1") 10))
(def seeds-file (string-opt cli :seeds "resources/wikidata-seeds.edn"))
(def out-file (string-opt cli :out (str "corpus/wikidata-" as-of ".edn")))

(defn- read-edn [f] (edn/read-string (str (fs/readFileSync f "utf8"))))

(defn- batches [n coll] (partition-all n coll))

(defn fetch-all-entities
  "Fetch every QID in batches of 50, merging into one map."
  [qids]
  (p/loop [remaining (batches 50 (distinct qids))
           acc {}]
    (if (empty? remaining)
      acc
      (p/let [ents (wd/fetch-entities (first remaining))]
        (p/recur (rest remaining) (merge acc ents))))))

(defn fetch-labels
  "Labels only, for P31 targets / countries -- much cheaper than full claims."
  [qids]
  (p/loop [remaining (batches 50 (distinct (remove nil? qids)))
           acc {}]
    (if (empty? remaining)
      acc
      (p/let [json (wd/get-json (str wd/api "?"
                                     (str/join "&" [(str "action=wbgetentities")
                                                    (str "ids=" (str/join "%7C" (first remaining)))
                                                    "props=labels" "languages=en" "format=json"])))
              ents (or (some-> json (js->clj :keywordize-keys false) (get "entities")) {})]
        (p/recur (rest remaining)
                 (merge acc (into {} (map (fn [[qid e]] [qid (get-in e ["labels" "en" "value"])]) ents))))))))

(defn- statements [entity pid] (get-in entity ["claims" pid]))

(defn- snak-target [st] (get-in st ["mainsnak" "datavalue" "value" "id"]))

(defn- qualifier-date [st pid]
  (some-> (get-in st ["qualifiers" pid]) first (get-in ["datavalue" "value"])
          wd/wd-time->date :date))

(defn- entity-date [entity pid]
  (some-> (statements entity pid) first (get-in ["mainsnak" "datavalue" "value"])
          wd/wd-time->date :date))

(defn- lei-of [entity]
  (some-> (statements entity "P1278") first (get-in ["mainsnak" "datavalue" "value"])))

(defn entity->node
  "Build a node, or a refusal when the kind cannot be inferred from P31."
  [qid entity label-index]
  (let [{:keys [en ja]} {:en (get-in entity ["labels" "en" "value"])
                         :ja (get-in entity ["labels" "ja" "value"])}
        p31-labels (keep label-index (wd/instance-of-qids entity))
        [kind basis] (wd/infer-kind p31-labels)
        death (entity-date entity "P570")
        birth (entity-date entity "P569")
        inception (entity-date entity "P571")
        dissolution (entity-date entity "P576")
        existed (let [from (or inception birth) to (or dissolution death)]
                  (when (or from to) (cond-> {} from (assoc :from from) to (assoc :to to))))
        country (some-> (statements entity "P17") first snak-target)
        lei (lei-of entity)]
    (cond
      (nil? kind)
      {:innen/refused {:qid qid :label en :p31-labels (vec p31-labels)}
       :innen/reason "no P31 (instance of) label matched a node kind; not given a fallback kind"}

      ;; A :person node is admissible only as a documented historical actor.
      ;; No recorded death date -> treat as possibly living -> refuse.
      (and (= :person kind) (nil? death))
      {:innen/refused {:qid qid :label en}
       :innen/reason "person with no recorded death date; excluded rather than recorded as a historical actor"}

      :else
      (cond-> {:innen.node/id (wd/node-id en qid)
               :innen.node/kind kind
               :innen.node/label (or en qid)
               :innen.node/wikidata qid
               :innen.node/kind-basis (str "P31 label " (pr-str basis))
               :innen.node/source (str "Wikidata " qid
                                       " (labels/claims via action=wbgetentities), retrieved " as-of)}
        ja (assoc :innen.node/label-local ja)
        existed (assoc :innen.node/existed existed)
        (= :person kind) (assoc :innen.node/historical? true)
        lei (assoc :company/lei lei)
        country (assoc :innen.node/jurisdiction (or (label-index country) country))))))

(defn statement->edge
  [{:keys [qid node-id-of pid prop st]}]
  (let [target (snak-target st)
        target-id (node-id-of target)
        {:keys [kind necessity reverse? label]} prop
        [from to] (if reverse? [target-id (node-id-of qid)] [(node-id-of qid) target-id])
        valid (let [f (qualifier-date st "P580") t (qualifier-date st "P582")]
                (when (or f t) (cond-> {} f (assoc :from f) t (assoc :to t))))
        src (str "Wikidata statement on " qid ": " pid " (" label ") -> " target
                 ", retrieved " as-of)]
    (when (and from to)
      (cond-> {:innen.edge/from from
               :innen.edge/to to
               :innen.edge/kind kind
               :innen.edge/necessity necessity
               ;; Wikidata is a secondary source that cites primaries; upgrading
               ;; to :documented is a human act after reading the primary.
               :innen.edge/confidence :attested
               :innen.edge/as-of as-of
               :innen.edge/source src}
        valid (assoc :innen.edge/valid valid)
        (= :causation kind)
        (assoc :innen.edge/causal-basis
               (str "Wikidata " pid " (" label ") statement asserts the link: " qid " -> " target))))))

(defn -main []
  (p/let [{:keys [seeds]} (read-edn seeds-file)
          _ (println (str "loop-innen ingest_wikidata: " (count seeds) " seeds, depth " max-depth ", as-of " as-of))
          {:keys [verified refused]} (wd/verify-properties!)
          _ (println (str "  verified " (count verified) "/" (count wd/property-map) " property mappings"
                          (when (seq refused) (str "; REFUSED " (count refused)))))
          _ (doseq [r refused] (println (str "  REFUSED property " (:innen/property r)
                                             ": expected " (pr-str (:innen/expected r))
                                             " got " (pr-str (:innen/actual r)))))
          resolved (p/all (map wd/resolve-seed seeds))
          seed-refusals (filterv :innen/refused resolved)
          seed-hits (filterv :qid resolved)
          _ (println (str "  resolved " (count seed-hits) "/" (count seeds) " seeds"
                          (when (seq seed-refusals) (str "; REFUSED " (count seed-refusals)))))
          _ (doseq [r seed-refusals]
              (println (str "  REFUSED seed " (pr-str (:label (:innen/refused r))) ": " (:innen/reason r))))

          ;; ---- BFS over the mapped properties ----
          result
          (p/loop [depth 0
                   frontier (mapv :qid seed-hits)
                   seen #{}
                   entities {}]
            (if (or (> depth max-depth) (empty? frontier))
              {:entities entities :seen seen}
              (p/let [fresh (remove seen frontier)
                      ents (fetch-all-entities fresh)
                      entities' (merge entities ents)
                      seen' (into seen fresh)
                      next-qids (for [[_qid e] ents
                                      [pid _] verified
                                      st (statements e pid)
                                      :let [t (snak-target st)]
                                      :when t]
                                  t)]
                (p/recur (inc depth) (vec (distinct next-qids)) seen' entities'))))

          entities (:entities result)
          ;; P31 targets + countries need labels for kind inference / jurisdiction
          aux-qids (concat (mapcat (fn [[_ e]] (wd/instance-of-qids e)) entities)
                           (keep (fn [[_ e]] (some-> (statements e "P17") first snak-target)) entities))
          label-index (fetch-labels aux-qids)

          node-results (mapv (fn [[qid e]] [qid (entity->node qid e label-index)]) entities)
          nodes (vec (keep (fn [[_ n]] (when-not (:innen/refused n) n)) node-results))
          node-refusals (vec (keep (fn [[_ n]] (when (:innen/refused n) n)) node-results))
          qid->node-id (into {} (keep (fn [[qid n]] (when-not (:innen/refused n) [qid (:innen.node/id n)])) node-results))

          raw-edges (vec (for [[qid e] entities
                               [pid prop] verified
                               st (statements e pid)
                               :let [edge (statement->edge {:qid qid :prop (assoc prop :label (:label prop))
                                                            :pid pid :st st
                                                            :node-id-of qid->node-id})]
                               :when edge]
                           edge))
          ;; The same relation is often asserted from both ends -- P828 (has
          ;; cause) on the effect AND P1542 (has effect) on the cause -- which
          ;; produced literal duplicate edges in the first real pass. Two
          ;; independent statements are corroboration, not two dependencies, so
          ;; they collapse into one edge carrying both citations.
          edges (->> raw-edges
                     (group-by (juxt :innen.edge/from :innen.edge/kind :innen.edge/to
                                     #(get-in % [:innen.edge/valid :from])))
                     vals
                     (mapv (fn [group]
                             (let [srcs (distinct (map :innen.edge/source group))]
                               (cond-> (first group)
                                 (> (count srcs) 1)
                                 (assoc :innen.edge/source (str/join " ; also asserted by: " srcs)
                                        :innen.edge/note (str (count srcs) " independent Wikidata statements assert this relation")))))))
          g (ic/graph* {:nodes nodes :edges edges :as-of as-of})
          errors (is/errors (:innen/problems g))
          corpus {:innen/as-of as-of
                  :innen/dataset "innen-wikidata"
                  :innen/ingest {:script "scripts/ingest_wikidata.cljs"
                                 :seeds-file seeds-file
                                 :depth max-depth
                                 :entities-fetched (count entities)
                                 :property-mappings (into {} (map (fn [[pid m]] [pid (select-keys m [:kind :necessity :label :reverse?])])) verified)}
                  :innen/sources {:wikidata (str "Wikidata API " wd/api
                                                 " (action=wbsearchentities, action=wbgetentities props=labels|descriptions|claims), retrieved " as-of
                                                 ". Secondary source: every edge from this pass is :attested, not :documented.")}
                  :innen/nodes (vec (sort-by (comp str :innen.node/id) nodes))
                  :innen/edges (vec (sort-by (juxt (comp str :innen.edge/from) (comp str :innen.edge/kind) (comp str :innen.edge/to)) (:innen/edges g)))
                  :innen/refused {:properties (vec refused)
                                  :seeds (vec seed-refusals)
                                  :nodes node-refusals
                                  :edges (vec (:edges (:innen/rejected g)))}}]
    (fs/mkdirSync (path/dirname out-file) #js {:recursive true})
    (fs/writeFileSync out-file (with-out-str (binding [*print-namespace-maps* false] (pr corpus))))
    ;; Read back what we just wrote. A corpus that serialises but does not parse
    ;; is worse than no corpus -- every downstream reader fails on it, far from
    ;; the code that produced it. This check is why node ids no longer start with
    ;; a digit (EDN keywords may not), which printed fine and then would not read.
    (let [round-trip (try (edn/read-string (str (fs/readFileSync out-file "utf8")))
                          (catch :default e {:innen/read-error (.-message e)}))]
      (when-let [err (:innen/read-error round-trip)]
        (println (str "  FATAL: " out-file " does not read back as EDN: " err))
        (js/process.exit 1))
      (when-not (= (count (:innen/nodes corpus)) (count (:innen/nodes round-trip)))
        (println "  FATAL: node count changed across the round trip")
        (js/process.exit 1)))
    (println (str "  entities fetched: " (count entities)))
    (println (str "  nodes: " (count nodes) " (refused " (count node-refusals) ")"))
    (println (str "  edges: " (count (:innen/edges g)) " (rejected " (count (:edges (:innen/rejected g))) ")"))
    (when (seq errors)
      (println (str "  schema errors: " (count errors) " -- first: " (pr-str (first errors)))))
    (println (str "  wrote " out-file))))

(if (:parse-only cli) (js/process.exit 0) (-main))
