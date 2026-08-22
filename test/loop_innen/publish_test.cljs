(ns loop-innen.publish-test
  "Real git repositories in a temp dir, arranged in the configuration that
  actually failed: a DETACHED HEAD and a remote that is not called `origin`.

  That is what a west checkout is, and `git push origin main` is false twice
  over in it. The residency reported `FAIL publish (exit 1)` for ten days
  without ever saying so, because the helper read stdout and the exit code and
  dropped stderr."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [loop-innen.publish :as pub]))

(defn- sh! [dir & args]
  (let [r (.spawnSync cp (first args) (clj->js (vec (rest args)))
                      #js {:encoding "utf8" :cwd dir})]
    (when-not (zero? (or (.-status r) 1))
      (throw (ex-info (str "fixture command failed: " (str/join " " args))
                      {:err (.-stderr r) :out (.-stdout r)})))
    (str/trim (str (.-stdout r)))))

(defn- fixture
  "A bare `remote` repo and a working checkout wired to it under `remote-name`,
  left on a DETACHED HEAD exactly as west leaves one."
  [remote-name]
  (let [root (fs/mkdtempSync (path/join (os/tmpdir) "innen-pub-"))
        bare (path/join root "remote.git")
        work (path/join root "work")]
    (fs/mkdirSync bare) (fs/mkdirSync work)
    (sh! bare "git" "init" "--bare" "--initial-branch=main" ".")
    (sh! work "git" "init" "--initial-branch=main" ".")
    (sh! work "git" "config" "user.email" "t@example.test")
    (sh! work "git" "config" "user.name" "t")
    (fs/mkdirSync (path/join work "corpus"))
    (fs/mkdirSync (path/join work "ledger"))
    (fs/writeFileSync (path/join work "corpus" "seed.edn") "{:seed 1}\n")
    (sh! work "git" "add" "-A")
    (sh! work "git" "commit" "-q" "-m" "genesis")
    (sh! work "git" "remote" "add" remote-name (str "file://" bare))
    (sh! work "git" "push" "-q" remote-name "main")
    (sh! work "git" "fetch" "-q" remote-name)
    ;; detach, the way west leaves a checkout
    (sh! work "git" "checkout" "-q" "--detach" "HEAD")
    {:root root :bare bare :work work}))

(defn- detached? [dir]
  (not (zero? (:exit (pub/git dir "symbolic-ref" "--quiet" "HEAD")))))

(defn- remote-head [bare]
  (:out (pub/git bare "rev-parse" "main")))

;; ── the helper that dropped the body ─────────────────────────────────────────

(deftest git-returns-the-error-text-not-only-the-status
  (let [{:keys [work]} (fixture "kotoba-lang")
        r (pub/git work "push" "origin" "main")]
    (is (pos? (:exit r)))
    (is (not (str/blank? (:err r)))
        "recording a status and dropping the body is what made this unreadable")
    (is (str/includes? (:err r) "origin"))))

;; ── resolving what was previously assumed ────────────────────────────────────

(deftest the-remote-is-resolved-not-assumed
  (is (= "kotoba-lang" (pub/remote-name (:work (fixture "kotoba-lang")))))
  (testing "origin still wins when it exists, so ordinary clones are unaffected"
    (is (= "origin" (pub/remote-name (:work (fixture "origin")))))))

(deftest no-remote-is-refused-with-a-reason
  (let [{:keys [work]} (fixture "kotoba-lang")]
    (sh! work "git" "remote" "remove" "kotoba-lang")
    (is (nil? (pub/remote-name work)))
    (let [r (pub/publish! {:dir work :message "m"})]
      (is (false? (:ok? r)))
      (is (= "remote-name" (:failed-at r)))
      (is (str/includes? (:err r) "no git remote")))))

;; ── the configuration that failed for ten days ───────────────────────────────

(deftest a-detached-checkout-with-a-non-origin-remote-publishes
  (let [{:keys [work bare]} (fixture "kotoba-lang")
        before (remote-head bare)]
    (is (detached? work) "the fixture must reproduce west's detached HEAD")
    (fs/writeFileSync (path/join work "corpus" "tick.edn") "{:tick 1}\n")
    (is (= ["corpus/tick.edn"] (pub/dirty-paths work)))
    (let [r (pub/publish! {:dir work :message "chore(innen): resident tick"})]
      (is (:ok? r) (str "publish failed at " (:failed-at r) ": " (:err r)))
      (is (= "kotoba-lang" (:remote r)))
      (is (= "main" (:branch r)))
      (is (= ["add" "commit" "fetch" "ff" "push"] (:trail r))))
    (is (not= before (remote-head bare)) "the remote's main must have moved")))

(deftest the-old-command-fails-on-the-same-fixture
  (testing "a regression test that cannot fail on the shipped code proves
            nothing, so the shipped command is run here and asserted to fail"
    (let [{:keys [work]} (fixture "kotoba-lang")]
      (fs/writeFileSync (path/join work "corpus" "tick.edn") "{:tick 1}\n")
      (pub/git work "add" "--" "corpus" "ledger")
      (pub/git work "commit" "-m" "m")
      (let [r (pub/git work "push" "-q" "origin" "main")]
        (is (pos? (:exit r)) "this is what shipped, on the tree west produces")))))

;; ── the refusal that must stay a refusal ─────────────────────────────────────

(deftest a-non-fast-forward-is-refused-by-git-not-resolved-here
  (let [{:keys [work bare]} (fixture "kotoba-lang")
        other (path/join (path/dirname work) "other")]
    ;; someone else advances the remote
    (fs/mkdirSync other)
    (sh! other "git" "clone" "-q" (str "file://" bare) ".")
    (sh! other "git" "config" "user.email" "o@example.test")
    (sh! other "git" "config" "user.name" "o")
    (fs/writeFileSync (path/join other "corpus" "theirs.edn") "{:theirs 1}\n")
    (sh! other "git" "add" "-A") (sh! other "git" "commit" "-q" "-m" "theirs")
    (sh! other "git" "push" "-q" "origin" "main")
    ;; and we have a divergent commit that cannot fast-forward
    (fs/writeFileSync (path/join work "corpus" "ours.edn") "{:ours 1}\n")
    (let [r (pub/publish! {:dir work :message "ours"})]
      (is (false? (:ok? r)))
      (is (= "ff" (:failed-at r))
          "the fast-forward must be where it stops; a merge here would be this
           code deciding something the Git policy says it may not")
      (is (not (str/blank? (:err r)))))))
