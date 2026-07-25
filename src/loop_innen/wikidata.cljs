(ns loop-innen.wikidata
  "Wikidata -> 因縁 edges. The `observe` source for the historical record.

   Three things here are load-bearing, and all three exist to stop a plausible
   guess from entering the record as a fact:

   1. **Seeds are labels, resolved by the API.** A QID typed from memory is a
      plausible-but-wrong identifier that would silently hang real dependency
      edges off the wrong entity. `resolve-seed` searches, then refuses the seed
      unless the returned description contains the caller's `:expect` string.

   2. **Property mappings are verified against live property labels.**
      `verify-properties!` fetches each mapped P-id's own label and drops the
      mapping when it does not match what this table claims it means. If
      Wikidata renames or this table remembers wrong, the mapping is refused
      rather than applied -- and the refusal is reported, not swallowed.

   3. **Statement qualifiers become the edge's validity interval.** P580/P582
      (start/end time) on a statement is exactly `:innen.edge/valid`, and
      Wikidata's own time precision maps onto `innen.time`'s: precision 11 ->
      day, 10 -> month, 9 -> year. Coarser than a year (decade, century) is NOT
      degraded into a year -- it is dropped with a note, because 'the 1870s'
      is not 1870.

   Confidence is `:attested`, never `:documented`, for everything ingested here:
   Wikidata is a secondary source that cites primaries. Upgrading an edge to
   `:documented` is a human act after reading the primary."
  (:require [clojure.string :as str]))

(def user-agent
  "Wikidata asks for a descriptive UA with contact info."
  "kotoba-lang-loop-innen/0.1 (https://github.com/kotoba-lang/loop-innen; jun784@gmail.com)")

(def api "https://www.wikidata.org/w/api.php")

(def property-map
  "Wikidata property -> innen edge. `:expect-label` is checked against the live
   property label before any mapping is applied.

   `:reverse? true` means the statement's subject is the DEPENDENCY and its
   object is the DEPENDENT (Wikidata 'has effect' reads the opposite way round
   from an innen edge, which always points dependent -> dependency).

   Deliberately excluded, with reasons, because the mapping would be an
   inference rather than a reading:
     P17  (country)      -> recorded as :innen.node/jurisdiction on the node.
                            'Registered in X' is not automatically 'X grants its
                            authority'.
     P361 (part of)       -> composition is not dependency.
     P463 (member of)     -> membership obligations vary too much per body.
     P155/P156 (follows)  -> temporal adjacency is not succession; P1365 is."
  ;; P749's live label is "parent organization or unit", not "parent
   ;; organization" -- this table said the latter and the verification step
   ;; refused the mapping until it was corrected. Left as a comment because it is
   ;; the clearest evidence that verify-properties! earns its keep.
  {"P749"  {:kind :control          :necessity :required     :expect-label "parent organization or unit"}
   "P127"  {:kind :ownership        :necessity :required     :expect-label "owned by"}
   "P1365" {:kind :succession       :necessity :incidental   :expect-label "replaces"}
   "P828"  {:kind :causation        :necessity :required     :expect-label "has cause"}
   "P1542" {:kind :causation        :necessity :required     :expect-label "has effect" :reverse? true}
   "P710"  {:kind :participation    :necessity :incidental   :expect-label "participant"}
   "P176"  {:kind :supply           :necessity :required     :expect-label "manufacturer"}
   "P88"   {:kind :funding          :necessity :required     :expect-label "commissioned by"}
   "P8324" {:kind :funding          :necessity :required     :expect-label "funder"}
   "P1547" {:kind :infrastructure   :necessity :required     :expect-label "depends on software"}})

(def node-property-map
  "Entity-level properties that become node attributes rather than edges."
  {"P571"  :inception
   "P576"  :dissolution
   "P569"  :birth
   "P570"  :death
   "P17"   :country
   "P1278" :lei
   "P31"   :instance-of})

(def exact-kind-label-rules
  "P31 labels matched by EXACT equality, checked before the substring rules.

   `:person` has to live here. As a substring rule, `\"human\"` matched
   `\"crime against humanity\"` — which is how The Holocaust came back from a real
   depth-2 pass classified as a person (and was then refused for having no death
   date, so the mis-typing showed up as the wrong refusal reason rather than as a
   wrong node). Personhood is the one inference where a substring is too loose."
  {"human" :person})

(def kind-label-rules
  "P31 (instance of) label -> innen node kind. Substring rules, applied in
   order, and the matched label is recorded on the node as
   `:innen.node/kind-basis` so a reader can check the inference instead of
   trusting it. An entity whose P31 labels match nothing is NOT given a
   fallback kind -- it is reported as unclassified and skipped, which shows up
   as a dangling-ref warning rather than as a confidently mis-typed node."
  [[["treaty" "agreement" "charter" "protocol" "concession" "convention" "accord" "contract" "pact"
     ;; statutes create obligations the same way an instrument does; added after
     ;; the first real pass refused "Tea Act 1773" (P31 "Act of the Parliament of
     ;; Great Britain") for want of any matching rule
     "act of" "statute" "legislation" "ordinance" "decree" "edict" "law of"] :contract]
   [["disaster" "accident" "spill" "outage" "explosion" "meltdown" "collapse" "breach" "vulnerability" "obstruction" "incident"
     ;; natural-hazard classes, added after the first pass refused the 2011
     ;; Tōhoku earthquake and tsunami -- the direct cause of a node already in
     ;; the record (Fukushima Daiichi), so the omission cost a real edge
     "earthquake" "tsunami" "flood" "hurricane" "typhoon" "eruption" "wildfire" "cyclone" "drought"
     ;; added after a depth-2 pass refused the British pet massacre
     "massacre"] :incident]
   [["war" "battle" "crisis" "depression" "recession" "revolution" "election" "conference" "siege" "famine" "pandemic" "epidemic" "event" "coup"
     "crash" "invasion"] :event]
   [["canal" "pipeline" "aqueduct" "railway" "railroad" "port" "factory" "plant" "cable" "bridge" "dam" "strait" "waterway" "network" "infrastructure" "reactor" "platform" "software" "library"
     ;; the Ever Given was refused as a "container ship" while the obstruction it
     ;; caused was already a node
     "ship" "vessel" "aircraft" "spacecraft" "satellite"] :artifact]
   [["standard" "specification" "protocol suite" "code" "format"] :standard]
   [["mineral" "commodity" "metal" "element" "crude oil" "resource" "ore"] :resource]
   [["archive" "document" "record" "filing" "manuscript" "plan" "proposal" "memorandum" "report"] :document]
   [["state" "country" "city" "municipality" "prefecture" "province" "empire" "dynasty" "kingdom" "republic" "government" "polity" "federation" "administrative"
     ;; "former district of Japan" — the wards Tokyo Metropolitan Government
     ;; succeeded, refused 6 times in one depth-2 pass
     "district"] :polity]
   [["company" "corporation" "enterprise" "business" "bank" "organization" "organisation" "institution" "agency" "union" "association" "cooperative" "consortium" "league" "cartel" "firm"
     ;; Dutch-language classes for the VOC's own predecessor companies, which the
     ;; first pass refused ("voorcompagnie", "colonial society") -- exactly the
     ;; upstream this record most wants
     "compagnie" "voorcompagnie" "society" "guild" "trading post"
     ;; depth-2 refusals: the New York Stock Exchange ("stock exchange") and the
     ;; Axis Powers ("military alliance") are both bodies entities depend on
     "exchange" "alliance"] :organization]])

;; ---------------------------------------------------------------- http

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn get-json
  "GET with the required UA. Retries transient failures a bounded number of
   times, then gives up and returns nil -- the caller reports the gap instead of
   pretending the data was absent upstream."
  ([url] (get-json url 3))
  ([url tries]
   (-> (js/fetch url (clj->js {:headers {"User-Agent" user-agent
                                         "Accept" "application/json"}}))
       (.then (fn [r]
                (if (.-ok r)
                  (.json r)
                  (if (pos? tries)
                    (.then (sleep 1500) #(get-json url (dec tries)))
                    (do (js/console.error (str "wikidata: giving up on " url " (HTTP " (.-status r) ")"))
                        nil)))))
       (.catch (fn [e]
                 (if (pos? tries)
                   (.then (sleep 1500) #(get-json url (dec tries)))
                   (do (js/console.error (str "wikidata: giving up on " url " (" (.-message e) ")"))
                       nil)))))))

(defn- qs [m]
  (->> m
       (map (fn [[k v]] (str (name k) "=" (js/encodeURIComponent (str v)))))
       (str/join "&")))

;; ---------------------------------------------------------------- seeds

(defn resolve-seed
  "Resolve one label to a QID, or return a refusal explaining why not.

   A blank `:expect` is itself a refusal. The first real pass allowed it, and
   the seed \"SWIFT\" resolved to the FAMILY NAME Swift (Q18331735), which then
   entered the graph as a node -- an unguarded seed is a wrong-entity bug
   waiting for a homonym."
  [{:keys [label expect] :as seed}]
  (if (str/blank? (str expect))
    (js/Promise.resolve
     {:innen/refused seed
      :innen/reason "seed has no :expect substring; refusing to accept whatever the search ranks first"})
    (-> (get-json (str api "?" (qs {:action "wbsearchentities" :search label :language "en"
                                    :uselang "en" :type "item" :limit 5 :format "json"})))
      (.then
       (fn [json]
         (let [hits (some-> json (js->clj :keywordize-keys true) :search)
               match (first (filter (fn [h]
                                      (str/includes? (str/lower-case (str (:description h)))
                                                     (str/lower-case expect)))
                                    hits))]
           (cond
             (nil? (seq hits))
             {:innen/refused seed :innen/reason "no search hit"}

             (nil? match)
             {:innen/refused seed
              :innen/reason (str "no hit whose description contains " (pr-str expect)
                                 "; saw: " (str/join " | " (map #(str (:label %) " — " (:description %)) hits)))}

             :else
             {:qid (:id match)
              :label (:label match)
              :description (:description match)
              :seed-label label})))))))

;; ---------------------------------------------------------------- entities

(defn fetch-entities
  "Batch-fetch up to 50 entities' labels + claims."
  [qids]
  (if (empty? qids)
    (js/Promise.resolve {})
    (-> (get-json (str api "?" (qs {:action "wbgetentities"
                                    :ids (str/join "|" qids)
                                    :props "labels|descriptions|claims"
                                    :languages "en|ja"
                                    :format "json"})))
        (.then (fn [json]
                 (or (some-> json (js->clj :keywordize-keys false) (get "entities")) {}))))))

(defn- snak-id [statement]
  (get-in statement ["mainsnak" "datavalue" "value" "id"]))

(defn- snak-string [statement]
  (get-in statement ["mainsnak" "datavalue" "value"]))

(defn wd-time->date
  "Wikidata time value -> an innen date string at the SAME precision, or nil
   with a reason when the precision is coarser than a year."
  [v]
  (when-let [t (get v "time")]
    (let [precision (get v "precision")
          bce? (str/starts-with? t "-")
          body (subs t 1)                        ; strip leading +/-
          [ymd _] (str/split body #"T")
          [y m d] (str/split ymd #"-")
          sign (if bce? "-" "")]
      (cond
        (>= precision 11) {:date (str sign y "-" m "-" d) :precision :day}
        (= precision 10) {:date (str sign y "-" m) :precision :month}
        (= precision 9) {:date (str sign y) :precision :year}
        :else {:date nil
               :reason (str "wikidata time precision " precision
                            " is coarser than a year; not degraded into one")}))))

(defn- qualifier-time [statement pid]
  (some-> (get-in statement ["qualifiers" pid])
          first
          (get-in ["datavalue" "value"])
          wd-time->date
          :date))

(defn- claim-time [entity pid]
  (some-> (get-in entity ["claims" pid])
          first
          (get-in ["mainsnak" "datavalue" "value"])
          wd-time->date
          :date))

(defn- labels-of [entity]
  {:en (get-in entity ["labels" "en" "value"])
   :ja (get-in entity ["labels" "ja" "value"])})

(defn instance-of-qids [entity]
  (keep snak-id (get-in entity ["claims" "P31"])))

(defn infer-kind
  "Infer a node kind from the labels of its P31 targets. Returns
   `[kind matched-label]`, or nil when nothing matched."
  [p31-labels]
  (let [lows (map (comp str/lower-case str) p31-labels)]
    (or (some (fn [l] (when-let [kind (get exact-kind-label-rules l)] [kind l])) lows)
        (some (fn [[needles kind]]
                (some (fn [l]
                        (when (some #(str/includes? l %) needles) [kind l]))
                      lows))
              kind-label-rules))))

(defn node-id
  "`:node/<slug>` from an English label, falling back to the QID. Keeping the
   human-readable form makes a corpus diff readable; the QID stays on the node
   as `:innen.node/wikidata` so identity never depends on the slug.

   A slug that would start with a digit gets an `n` prefix
   (`:node/n1973-oil-crisis`): EDN symbols -- and therefore keywords -- may not
   begin with a digit, so `:node/1973-oil-crisis` writes fine and then fails to
   read back. Found by the corpus round-trip check in
   `scripts/ingest_wikidata.cljs`, which exists for exactly this class of bug."
  [label qid]
  (let [slug (if (str/blank? (str label))
               (str/lower-case qid)
               (-> (str label)
                   str/lower-case
                   (str/replace #"[^a-z0-9]+" "-")
                   (str/replace #"^-|-$" "")
                   (as-> s (if (str/blank? s) (str/lower-case qid) s))))]
    (keyword "node" (if (re-find #"^[0-9]" slug) (str "n" slug) slug))))

(defn verify-properties!
  "Fetch the live label of every mapped property and return only the mappings
   whose label matches this table's claim, plus the refusals."
  []
  (-> (get-json (str api "?" (qs {:action "wbgetentities"
                                  :ids (str/join "|" (keys property-map))
                                  :props "labels" :languages "en" :format "json"})))
      (.then
       (fn [json]
         (let [ents (or (some-> json (js->clj :keywordize-keys false) (get "entities")) {})]
           (reduce (fn [acc [pid {:keys [expect-label] :as m}]]
                     (let [actual (get-in ents [pid "labels" "en" "value"])]
                       (if (= (str/lower-case (str actual)) (str/lower-case expect-label))
                         (assoc-in acc [:verified pid] (assoc m :label actual))
                         (update acc :refused conj
                                 {:innen/property pid
                                  :innen/expected expect-label
                                  :innen/actual actual
                                  :innen/reason "property label does not match this table's claim; mapping refused"}))))
                   {:verified {} :refused []}
                   property-map))))))
