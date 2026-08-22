(ns loop-innen.cli
  "Command-line argument parsing for the tick and the ingest scripts.

  One parser, because there were four and the same defect was in all of them.

  `partition-all 2` reads the arguments as strict pairs, so a boolean flag
  consumes the NEXT FLAG'S NAME as its value:

      --no-push --root /p   =>  {:no-push \"--root\"}     ; and /p is dropped

  `--root` then fell back to its default without saying so. In production the
  default (`../../..` from `orgs/kotoba-lang/loop-innen`) happens to be the
  superproject, so argument ORDER silently changed behaviour and nothing ever
  went red. Measured 2026-08-22 while repairing the stalled residency: a tick
  invoked as `--no-push --root <abs>` ingested 0 municipalities and 0 legal
  entities from a root that did not exist, and reported it as a successful
  ingest of an empty workspace."
  (:require [clojure.string :as str]))

(defn flag? [t] (str/starts-with? (str t) "--"))

(defn args->map
  "Parse `--flag value` and bare `--flag` into a map.

  A flag's value is the next token ONLY when that token is not itself a flag.
  Bare flags are `true`. Non-flag tokens with no preceding flag are ignored
  rather than guessed at."
  [args]
  (loop [[t & more] (vec args) out {}]
    (cond
      (nil? t)     out
      (not (flag? t)) (recur more out)
      :else (let [k (keyword (subs (str t) 2))
                  v (first more)]
              (if (and (some? v) (not (flag? v)))
                (recur (rest more) (assoc out k v))
                (recur more (assoc out k true)))))))

(defn string-opt
  "A string-valued option, or `default`.

  Separate from `get` because a bare `--root` parses to `true`, and
  `(or (:root cli) default)` would then hand the string `true` to a filesystem
  path. Every valued option in these scripts goes through here."
  [cli k default]
  (let [v (get cli k)]
    (if (string? v) v default)))
