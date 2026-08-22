(ns loop-innen.cli-test
  "The parser bug that stalled nothing and corrupted quietly.

  Four scripts each carried their own `partition-all 2` parser, and all four
  had the same defect: a boolean flag swallowed the next flag's NAME as its
  value. `--no-push --root /p` became `{:no-push \"--root\"}`, `/p` vanished,
  and `--root` fell back to a default that in production happens to be correct.
  So argument ORDER decided whether the tick read the real workspace or an
  empty directory, and an ingest of a non-existent root reported success with
  zero entities. Measured 2026-08-22."
  (:require [cljs.test :refer [deftest is testing]]
            [loop-innen.cli :as cli]))

(deftest a-boolean-flag-does-not-eat-the-next-flags-name
  (testing "this is the exact invocation that read an empty workspace"
    (is (= {:no-push true :root "/abs/path"}
           (cli/args->map ["--no-push" "--root" "/abs/path"])))))

(deftest argument-order-does-not-change-the-result
  (let [a (cli/args->map ["--no-push" "--root" "/p" "--depth" "2"])
        b (cli/args->map ["--root" "/p" "--depth" "2" "--no-push"])
        c (cli/args->map ["--depth" "2" "--no-push" "--root" "/p"])]
    (is (= a b c))
    (is (= {:no-push true :root "/p" :depth "2"} a))))

(deftest a-trailing-boolean-flag-is-true
  (is (= {:root "/p" :no-push true} (cli/args->map ["--root" "/p" "--no-push"])))
  (is (= {:no-push true} (cli/args->map ["--no-push"]))))

(deftest consecutive-boolean-flags-are-all-true
  (is (= {:a true :b true :c true} (cli/args->map ["--a" "--b" "--c"]))))

(deftest no-arguments-is-an-empty-map
  (is (= {} (cli/args->map [])))
  (is (= {} (cli/args->map nil))))

(deftest a-stray-non-flag-token-is-ignored-not-guessed-at
  (is (= {:root "/p"} (cli/args->map ["junk" "--root" "/p"]))))

(deftest string-opt-refuses-a-bare-flag-as-a-path
  (testing "a bare --root parses to true; handing that to a filesystem path is
            how a wrong default becomes a wrong directory"
    (is (= "../../.." (cli/string-opt {:root true} :root "../../..")))
    (is (= "../../.." (cli/string-opt {} :root "../../..")))
    (is (= "/real" (cli/string-opt {:root "/real"} :root "../../..")))))

(deftest the-old-parser-is-shown-to-be-wrong
  (testing "a regression test that cannot fail on the old code proves nothing,
            so the old behaviour is reproduced here and asserted to differ"
    (let [old (fn [args]
                (into {} (for [[k v] (partition-all 2 args)
                               :when (and k (clojure.string/starts-with? k "--"))]
                           [(keyword (subs k 2)) (or v true)])))
          args ["--no-push" "--root" "/abs/path"]]
      (is (= {:no-push "--root"} (old args)) "this is what shipped")
      (is (not= (old args) (cli/args->map args))))))
