(ns loop-innen.publish
  "Committing and pushing a tick's output, from a west-managed checkout.

  Extracted from `scripts/tick.cljs` on 2026-08-22 because it was wrong in two
  ways that a resident job could not report, and because a script cannot be
  tested without running it.

  ## What was wrong

  **It pushed to `origin` and to a branch called `main`.** A west checkout has
  neither. west names the remote after the manifest entry (`kotoba-lang` here)
  and leaves the working tree on a DETACHED HEAD, so both halves of
  `git push origin main` were false: no such remote, and no local ref named
  `main` to push. The last successful tick was 2026-08-12, immediately before
  the commit named `rescue/west-detached-20260812`. Publication has been
  impossible ever since, and the ingest kept succeeding the whole time.

  **It discarded stderr.** The helper read `stdout` and the exit code and threw
  the error text away, so the residency reported `FAIL publish (exit 1)` with no
  reason — for a failure whose reason git had printed in full. Recording a
  status and dropping the body is the third of the five questions in
  ADR-2608136000, and this is what it costs: the cause was one line away and
  nobody could see it.

  ## What it does now

  Resolves the remote instead of assuming it, fast-forwards onto that remote's
  default branch, and pushes `HEAD:<branch>` so a detached checkout can publish.
  The push is not forced, so a non-fast-forward is refused by git rather than
  resolved by this code."
  (:require ["node:child_process" :as cp]
            [clojure.string :as str]))

(defn git
  "Run git in `dir`. Returns `{:exit :out :err}` — all three, always.

  `:err` exists because its absence is what made the previous failure
  unreadable."
  [dir & args]
  (let [r (.spawnSync cp "git" (clj->js (vec args))
                      #js {:encoding "utf8" :cwd dir})]
    {:exit (or (.-status r) 1)
     :out  (str/trim (str (.-stdout r)))
     :err  (str/trim (str (.-stderr r)))}))

(defn remote-name
  "The remote to publish to.

  Prefers `origin` when it exists, otherwise the single remote, otherwise nil.
  west names remotes after the manifest entry, so `origin` is exactly what a
  west checkout does NOT have — assuming it is how this broke."
  [dir]
  (let [remotes (->> (:out (git dir "remote"))
                     str/split-lines
                     (remove str/blank?)
                     (map str/trim)
                     vec)]
    (cond
      (some #{"origin"} remotes) "origin"
      (= 1 (count remotes))      (first remotes)
      :else                      nil)))

(defn default-branch
  "The remote's default branch, read from the remote HEAD ref, falling back to
  `main`. Read rather than assumed for the same reason as the remote."
  [dir remote]
  (let [r (git dir "symbolic-ref" "--quiet" (str "refs/remotes/" remote "/HEAD"))]
    (if (zero? (:exit r))
      (last (str/split (:out r) #"/"))
      "main")))

(defn dirty-paths
  "Only the paths a tick is allowed to publish. Anything else a human left in
  the tree is none of this tick's business, and committing it would launder
  someone else's edit into an automated commit."
  [dir]
  (->> (:out (git dir "status" "--porcelain" "--" "corpus" "ledger" "target"))
       str/split-lines
       (remove str/blank?)
       (map #(str/trim (subs % 2)))
       (remove #(str/starts-with? % "target/"))    ; the report is regenerated, not tracked
       vec))

(defn- step
  "Run one git step, short-circuiting on the first failure and KEEPING the
  error text. `prev` is the accumulated result."
  [prev dir label args]
  (if-not (:ok? prev)
    prev
    (let [r (apply git dir args)]
      (if (zero? (:exit r))
        (assoc prev :ok? true :trail (conj (:trail prev []) label))
        (assoc prev :ok? false :exit (:exit r) :failed-at label
                    :err (or (not-empty (:err r)) (not-empty (:out r))
                             "git failed and printed nothing")
                    :trail (conj (:trail prev []) label))))))

(defn publish!
  "Commit `paths` and push them, from a possibly detached checkout.

  Returns `{:ok? :exit :failed-at :err :trail :remote :branch}`. On failure
  `:err` carries what git actually said."
  [{:keys [dir message]}]
  (let [remote (remote-name dir)]
    (if-not remote
      {:ok? false :exit 1 :failed-at "remote-name"
       :err "no git remote to publish to; a checkout with several remotes and no `origin` is ambiguous and this refuses to guess"}
      (let [branch (default-branch dir remote)
            base   {:ok? true :exit 0 :remote remote :branch branch :trail []}]
        (-> base
            (step dir "add"    ["add" "--" "corpus" "ledger"])
            (step dir "commit" ["commit" "-m" message])
            (step dir "fetch"  ["fetch" "--quiet" remote])
            ;; --ff-only on a detached HEAD moves HEAD; it refuses rather than
            ;; merging, which is what this repo's Git policy requires.
            (step dir "ff"     ["merge" "--ff-only" (str remote "/" branch)])
            ;; HEAD:<branch> is what lets a DETACHED checkout publish at all.
            ;; Not forced: a non-fast-forward is git's refusal, not ours to fix.
            (step dir "push"   ["push" "--quiet" remote (str "HEAD:" branch)]))))))
