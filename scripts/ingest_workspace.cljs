(ns ingest-workspace
  "Ingest the entities this workspace ALREADY records into the 因縁 graph.

     nbb --classpath \"../innen/src:src:scripts\" scripts/ingest_workspace.cljs \\
       --root <superproject-root> [--merge-with corpus/wikidata-YYYY-MM-DD.edn] [--out corpus/…]

   Two local corpora, both already sourced by whoever created them:

     orgs/cloud-itonami/cloud-itonami-municipality-*/organization.edn  (59 polities)
     orgs/cloud-itonami/cloud-itonami-lei-*/blueprint.edn              (161 legal entities)

   The point of pulling them in is the join: a `:company/lei` here is the same
   `:company/lei` the unified query plane already has SEC financials under
   (ADR-2607252000), so one query can ask what a company depends on AND what its
   revenue is.

   Where edges are and are NOT derived, and why:

   * LEI -> jurisdiction IS emitted as `:legal-authority`, `:documented`. GLEIF's
     field is literally the legal jurisdiction of registration -- the polity that
     grants the entity its legal personality. That is a reading of the source,
     not an inference.
   * municipality -> national government is NOT emitted. `organization.edn`
     records where the body sits (`:hq {:country \"gbr\"}`), not a grant of
     authority. The structural claim would be defensible in general and
     unsourced in particular, so the corpus records the omission in
     `:innen/not-derived` rather than fabricating 59 edges.

   Identity resolution across corpora uses only exact identifiers: an LEI match
   reuses the existing node id (recording that it did). Wikidata QIDs are copied
   through but NOT used to merge -- the workspace's own files show why, e.g.
   cloud-itonami-municipality-gbr-london records `:name-en \"Greater London
   Authority\"` against `:wikidata \"Q84\"`, which is London the city. Merging on
   that QID would silently fuse a municipal corporation with a settlement."
  (:require [loop-innen.cli :refer [args->map string-opt]]
            ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [innen.core :as ic]
            [innen.schema :as is]
            [loop-innen.wikidata :as wd]))


(def cli (args->map *command-line-args*))
(def root (string-opt cli :root "../../.."))
(def as-of (string-opt cli :as-of (.slice (.toISOString (js/Date.)) 0 10)))
(def out-file (string-opt cli :out (str "corpus/workspace-" as-of ".edn")))

(defn- read-edn [f]
  (try (edn/read-string (str (fs/readFileSync f "utf8")))
       (catch :default _ nil)))

(defn- exists? [f] (fs/existsSync f))

(defn- itonami-dirs [needle]
  (let [parent (path/join root "orgs" "cloud-itonami")]
    (if (exists? parent)
      (->> (fs/readdirSync parent)
           (filter #(str/includes? % needle))
           sort
           (map #(path/join parent %)))
      [])))

(defn jurisdiction-node [code src]
  {:innen.node/id (keyword "node" (str "jurisdiction-" (str/lower-case (str/replace code #"[^A-Za-z0-9]+" "-"))))
   :innen.node/kind :polity
   :innen.node/label code
   :innen.node/jurisdiction code
   :innen.node/note "Jurisdiction identified by its ISO 3166 code only; a human-readable name is not asserted because the source records the code, not the name."
   :innen.node/source src})

(defn municipality->node [dir]
  (let [f (path/join dir "organization.edn")
        o (read-edn f)]
    (when (and o (:name-en o))
      (let [rel (str/replace f (str root "/") "")]
        (cond-> {:innen.node/id (wd/node-id (:name-en o) (str (:wikidata o)))
                 :innen.node/kind :polity
                 :innen.node/polity-level :municipality
                 :innen.node/label (:name-en o)
                 :innen.node/source (str rel
                                         (when-let [s (:sourced-from o)] (str " — " s)))}
          (:name-local o) (assoc :innen.node/label-local (:name-local o))
          (:wikidata o) (assoc :innen.node/wikidata (:wikidata o))
          (get-in o [:hq :country]) (assoc :innen.node/jurisdiction (str/upper-case (get-in o [:hq :country])))
          ;; The London case: name-en and the recorded QID can denote different
          ;; things. Kept as a note on the node rather than resolved silently.
          (:wikidata o) (assoc :innen.node/note
                               (str "Wikidata QID as recorded by " rel
                                    "; not re-verified here, and deliberately not used as a merge key")))))))

(defn lei->node-and-edge [dir]
  (let [f (path/join dir "blueprint.edn")
        b (read-edn f)
        rel (str/replace f (str root "/") "")]
    (when (and b (:company/legal-name b))
      (let [lei (:company/lei b)
            juris (:company/jurisdiction b)
            id (wd/node-id (:company/legal-name b) (str lei))
            node (cond-> {:innen.node/id id
                          :innen.node/kind :organization
                          :innen.node/label (:company/legal-name b)
                          :innen.node/source (str rel " (cloud-itonami legal-entity blueprint, GLEIF-derived)")}
                   lei (assoc :company/lei lei)
                   juris (assoc :innen.node/jurisdiction juris))
            jnode (when juris (jurisdiction-node juris (str rel " :company/jurisdiction")))
            edge (when juris
                   {:innen.edge/from id
                    :innen.edge/to (:innen.node/id jnode)
                    :innen.edge/kind :legal-authority
                    :innen.edge/necessity :required
                    :innen.edge/confidence :documented
                    :innen.edge/as-of as-of
                    :innen.edge/source (str rel " :company/jurisdiction " (pr-str juris)
                                            " — GLEIF legal jurisdiction of registration")
                    :innen.edge/note "The polity of registration grants the entity its legal personality; this reads the blueprint's jurisdiction field rather than inferring a relation."})]
        {:node node :jurisdiction jnode :edge edge}))))

(defn -main []
  (let [merge-corpus (when-let [f (string-opt cli :merge-with nil)] (read-edn f))
        lei-index (into {} (keep (fn [n] (when-let [l (:company/lei n)] [l (:innen.node/id n)]))
                                 (:innen/nodes merge-corpus)))
        muni (vec (keep municipality->node (itonami-dirs "cloud-itonami-municipality-")))
        lei-raw (vec (keep lei->node-and-edge (itonami-dirs "cloud-itonami-lei-")))
        ;; LEI is an exact identifier: when an already-ingested node carries the
        ;; same one, reuse its id so the two corpora describe ONE entity.
        resolutions (atom [])
        resolve-id (fn [n]
                     (if-let [existing (get lei-index (:company/lei n))]
                       (do (when (not= existing (:innen.node/id n))
                             (swap! resolutions conj {:innen/lei (:company/lei n)
                                                      :innen/kept existing
                                                      :innen/instead-of (:innen.node/id n)}))
                           (assoc n :innen.node/id existing
                                  :innen.node/note (str "id reused from an earlier corpus via exact :company/lei match ("
                                                        (:company/lei n) ")")))
                       n))
        lei-nodes (mapv (comp resolve-id :node) lei-raw)
        id-remap (into {} (map (fn [n0 n1] [(:innen.node/id (:node n0)) (:innen.node/id n1)]) lei-raw lei-nodes))
        ;; Dedupe jurisdictions by ID, not by whole map: every blueprint cites a
        ;; different file, so `distinct` on the node maps kept 155 copies of ~20
        ;; real jurisdictions. The surviving node records how many blueprints
        ;; attest it instead of hiding the multiplicity.
        jurisdictions (->> (keep :jurisdiction lei-raw)
                           (group-by :innen.node/id)
                           (mapv (fn [[_id group]]
                                   (let [n (count group)]
                                     (cond-> (first group)
                                       (> n 1)
                                       (assoc :innen.node/note
                                              (str (:innen.node/note (first group))
                                                   " Attested by " n " cloud-itonami legal-entity blueprints; the citation names the first.")))))))
        edges (vec (keep (fn [{:keys [edge]}]
                           (when edge (update edge :innen.edge/from #(get id-remap % %))))
                         lei-raw))
        nodes (vec (concat muni lei-nodes jurisdictions))
        g (ic/graph* {:nodes nodes :edges edges :as-of as-of})
        errors (is/errors (:innen/problems g))
        corpus {:innen/as-of as-of
                :innen/dataset "innen-workspace"
                :innen/ingest {:script "scripts/ingest_workspace.cljs"
                               :root root
                               :municipalities (count muni)
                               :legal-entities (count lei-nodes)
                               :jurisdictions (count jurisdictions)
                               :lei-id-resolutions @resolutions}
                :innen/sources {:municipalities "orgs/cloud-itonami/cloud-itonami-municipality-*/organization.edn (each file carries its own :sourced-from provenance)"
                                :legal-entities "orgs/cloud-itonami/cloud-itonami-lei-*/blueprint.edn (GLEIF-derived legal-entity records)"}
                :innen/not-derived
                [{:innen/relation "municipality -> national government (:legal-authority)"
                  :innen/reason "organization.edn records where the body sits (:hq :country), not a grant of authority. Emitting 59 edges from a location field would be an inference presented as a reading."}
                 {:innen/relation "company -> company (:supply / :ownership)"
                  :innen/reason "the blueprints hold identity and jurisdiction, not commercial relationships. Supply edges need a source that states them."}]
                :innen/nodes (vec (sort-by (comp str :innen.node/id) (ic/nodes g)))
                :innen/edges (vec (sort-by (comp str :innen.edge/from) (ic/edges g)))
                :innen/refused {:nodes (vec (:nodes (:innen/rejected g)))
                                :edges (vec (:edges (:innen/rejected g)))}}]
    (fs/mkdirSync (path/dirname out-file) #js {:recursive true})
    (fs/writeFileSync out-file (with-out-str (binding [*print-namespace-maps* false] (pr corpus))))
    (let [round-trip (try (edn/read-string (str (fs/readFileSync out-file "utf8")))
                          (catch :default e {:innen/read-error (.-message e)}))]
      (when-let [err (:innen/read-error round-trip)]
        (println (str "  FATAL: " out-file " does not read back as EDN: " err))
        (js/process.exit 1)))
    (println (str "loop-innen ingest_workspace: root " root))
    (println (str "  municipalities: " (count muni)))
    (println (str "  legal entities: " (count lei-nodes) " (" (count @resolutions) " id(s) reused via LEI match)"))
    (println (str "  jurisdictions: " (count jurisdictions)))
    (println (str "  nodes: " (count (ic/nodes g)) "  edges: " (count (ic/edges g))))
    (when (seq errors)
      (println (str "  schema errors: " (count errors) " -- first: " (pr-str (first errors)))))
    (println (str "  wrote " out-file))))

(-main)
