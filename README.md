# loop-innen — 因縁 observation loop

Continuously grows a **sourced dependency record over human history** — entities,
contracts, events, incidents, and the edges between them — and reports what it
can and cannot yet answer.

```text
observe (corpora)  ->  evaluate (kotoba-lang/innen scoring)  ->  decide (rank)
  ->  act (report)  ->  record-evidence (append-only ledger)
```

`loop-*` per `kotoba-lang/loop-ux-kaizen`'s `resources/repository-rules.edn`:
this repo owns the ordering, the ingest, and the evidence ledger. It owns **no
scoring truth** — criticality, cascade, concentration, cycles and historical
slicing all live in [`kotoba-lang/innen`](https://github.com/kotoba-lang/innen),
the same split as `loop-system-dynamics` ⊣ `dynamics`. Implements ADR-2607258500
(`com-junkawasaki/root`).

## Run it

```bash
# one cycle: observe every corpus -> score -> report -> append one ledger line
nbb --classpath "../innen/src:src:bin" bin/run.cljs

# query the record (DataScript; same datalog dialect as manifest/edn-query.cljs)
nbb --classpath "../innen/src:src:bin" bin/query.cljs demo
nbb --classpath "../innen/src:src:bin" bin/query.cljs deps node/maersk
nbb --classpath "../innen/src:src:bin" bin/query.cljs explain node/log4shell node/log4j
nbb --classpath "../innen/src:src:bin" bin/query.cljs as-of -0221
nbb --classpath "../innen/src:src:bin" bin/query.cljs q \
  '[:find ?l :where [?e "innen.node/kind" "incident"] [?e "innen.node/label" ?l]]'
```

## Grow it

Two ingest paths, both writing sourced corpus files into `corpus/`:

```bash
# external + historical: Wikidata statements -> edges (confidence :attested)
nbb --classpath "../innen/src:src:scripts" scripts/ingest_wikidata.cljs --depth 1

# internal: the entities this workspace already records -> nodes (+ jurisdiction edges)
nbb --classpath "../innen/src:src:scripts" scripts/ingest_workspace.cljs \
  --root <superproject-root> --merge-with corpus/wikidata-<date>.edn
```

Adding coverage means adding a seed to `resources/wikidata-seeds.edn` (a **label**
plus an `:expect` substring, never a QID) or dropping another corpus file into
`corpus/`. No code changes.

## Residency status — read this before trusting the dates

**The resident tick stopped on 2026-08-12 and was restarted by hand on
2026-08-22.** Ten days are missing from the corpus and from the ledger, and no
`chore(innen): resident tick` commit exists for them. The gap is real history
that was not observed, not history that did not happen.

Two things were wrong, and the second is the one worth reading:

1. **The tick could not run at all.** `(.toISOString.slice (js/Date.) 0 10)`
   appeared in four files. An older nbb resolved it; the current one does not,
   and every entry point died before doing anything. Fixed to
   `(.slice (.toISOString (js/Date.)) 0 10)`.

2. **Argument order silently changed what was ingested.** Each of the four
   scripts carried its own `partition-all 2` parser, and all four read a
   boolean flag and the NEXT FLAG'S NAME as one pair:

   ```text
   --no-push --root /abs/path   =>   {:no-push "--root"}    ; /abs/path dropped
   --root /abs/path --no-push   =>   {:root "/abs/path" …}  ; correct
   ```

   `--root` then fell back to `../../..`, which from `orgs/kotoba-lang/loop-innen`
   *is* the superproject — so in production it was right by position, and the
   defect never went red. Invoked the other way round the workspace ingest
   walked a directory that does not exist and reported **`ok ingest:workspace
   (exit 0) — 0 nodes / 0 edges`**: a successful ingest of an empty world.

   There is now one parser (`loop-innen.cli`), it is tested against the old
   behaviour rather than only against the new one, and every valued option goes
   through `string-opt` so a bare `--root` cannot hand `true` to a filesystem
   path.

**The residency is now registered** as `com.kotoba.innen-tick`
(`scripts/com.kotoba.innen-tick.plist`): launchd at 02:47, 08:47, 14:47 and
20:47, running the tick through `tamaki exec` so each tick is one `AgentRun`
with a real exit code. It commits and pushes when the record grows and skips
publication when it does not.

Until 2026-08-22 there was no such job. `scripts/tick.cljs` claimed the
residency was "registered with `tamaki` and run by launchd" and **neither half
was true** — tamaki listed this loop only as an example line in its README, and
`launchctl list` showed nothing while a dozen sibling residencies were loaded.
The docstring went on describing a residency that was not there for ten days.

Read `/tmp/innen-tick.err` before believing a quiet week.

```bash
nbb --classpath "../innen/src:src:scripts" scripts/tick.cljs \
  --root <superproject> --no-push
```

## What keeps this record honest

Every one of these came out of an actual failure in the first real pass, not from
a design document:

| Guard | Why it exists |
|---|---|
| Seeds are labels + a mandatory `:expect`, resolved through the API | With `:expect ""`, the seed `"SWIFT"` resolved to **Q18331735, the family name Swift**, and entered the graph as a node. A blank `:expect` is now itself a refusal. |
| Property mappings verified against live property labels | This repo's table claimed P749 meant `"parent organization"`. Its real label is `"parent organization or unit"`, and the mapping was refused until corrected. |
| No fallback node kind | 21 entities in the first pass matched no `P31` class rule. They were refused, which surfaced 6 real classes worth adding (`voorcompagnie` — the VOC's own predecessor companies; `Act of the Parliament`; `megathrust earthquake`; `container ship` — the Ever Given; `stock market crash`; `memorandum`). A default kind would have mis-typed them silently. |
| Corpus round-trip check | The first corpus written contained `:node/1973-oil-crisis` — printed fine, **unreadable as EDN** (keywords may not start with a digit). Ingest now reads back what it wrote and exits non-zero if it does not parse. |
| Duplicate-edge collapse | P828 (has cause) on the effect and P1542 (has effect) on the cause assert the SAME relation from both ends, which produced literal duplicate edges. Two statements are corroboration, not two dependencies; they now collapse into one edge carrying both citations. |
| Living persons excluded | A `:person` node is admissible only with a recorded death date. `George Kurtz` was refused for exactly this reason. |
| Three slice bases in every report | The permissive 1700 slice kept 303 nodes, including present-day Delaware registrations that state no founding date. Reporting only that number would have claimed 17th-century coverage the record does not have. |
| Skipped corpora reported, never dropped | A cycle that silently skipped a corpus would read as the world shrinking. |

## What the record holds today (cycle 1, 2026-07-25)

| corpus | nodes | edges | source |
|---|---|---|---|
| `innen-wikidata` | 74 | 42 | Wikidata statements, 39 verified seeds, depth 1 |
| `innen-workspace` | 261 | 155 | 59 `cloud-itonami-municipality-*` + 155 `cloud-itonami-lei-*` + 47 jurisdictions |
| **merged** | **334** | **197** | |

Real findings from that cycle, all reproducible from the corpus files:

- **`:node/jurisdiction-us-de` (Delaware) is the most critical node in the
  record**: 43 recorded legal entities lose their `:legal-authority` dependency
  if it fails. Not a surprise — but now it is a query result with 43 citations
  behind it rather than a thing everyone knows.
- **The BCE end works**: `as-of -0221` returns the Qin dynasty's succession
  edges, with Qin dated 905–221 BCE and Aqua Appia from 312 BCE.
- **163 nodes carry a `:company/lei`**, and the cross-repo join actually
  returns rows: loaded into the superproject's unified query plane
  (ADR-2607252000), **66 companies join a dependency edge to their SEC revenue
  in one query** — e.g. Walmart ($713B) → `:legal-authority` → Delaware.
  Dependency structure and financial scale became askable together:

  ```bash
  nbb --classpath ".:scripts/nbb_compat" manifest/edn-query.cljs q \
    '[:find ?label ?rev ?kind ?dep :where
      [?n "company/lei" ?lei] [?n "innen.node/label" ?label] [?n "innen.node/id" ?nid]
      [?f "company/lei" ?lei] [?f "source/dataset" "market-intel"] [?f "company/revenue-usd" ?rev]
      [?e "innen.edge/from-id" ?nid] [?e "innen.edge/kind" ?kind] [?e "innen.edge/to-id" ?dep]]'
  ```
- **The two ingest paths themselves overlap in ZERO LEIs.** The 8 LEIs Wikidata
  supplied (Maersk, TSMC, ASML, CrowdStrike and holding companies) are disjoint
  from the 155 in `cloud-itonami-lei-*`, so no entity is currently described by
  both — a measured gap, and the clearest target for the next ingest pass.
- **Only 1 edge states its own validity interval.** Wikidata statements in this
  sample rarely carry P580/P582 qualifiers, so the record's dated coverage is
  thin; the `window` basis (both endpoints dated) is what currently answers
  historical questions.

## Resident operation (tamaki + launchd)

The loop runs unattended every 6 hours, registered with
[`tamaki`](https://github.com/kotoba-lang/tamaki) so each tick is a durable,
queryable `AgentRun`:

```text
launchd com.kotoba-lang.innen-tick   (StartInterval 21600, RunAtLoad)
  └─ ~/.gftd/run-innen-tick.cljs
       └─ tamaki exec "innen record tick <date>" --project <repo> --
            nbb scripts/tick.cljs --depth 2
                 ├─ ingest:wikidata   (guarded)
                 ├─ ingest:workspace  (guarded)
                 ├─ cycle             (report + one ledger line)
                 └─ publish           (commit + push corpus/ + ledger/)
```

```sh
bin/tamaki status                     # every tick, with its real exit code
tail -f ~/.gftd/innen-tick.stdout.log # the tick's own output
launchctl kickstart -k gui/$UID/com.kotoba-lang.innen-tick   # run one now
```

Mode is `:external`: the tick is deterministic, so `tamaki exec` records the
actual argv and exit code rather than pretending `kotoba-code` ran it. Per-tick
detail stays in this repo's `ledger/loop-innen-ledger.edn` — two records, each
authoritative for its own thing.

Three failure policies, deliberately not uniform (`scripts/tick.cljs`):

- an **ingest** failure does not abort the tick — the previous corpus stands, the
  cycle still reports, and the step is marked failed. A transient Wikidata outage
  must not stop the record from reporting its own state.
- a **cycle** failure fails the tick, so launchd sees red.
- **publish** is skipped when nothing changed; an empty commit every 6 hours
  would bury the ticks that actually grew the record. Only `corpus/` and
  `ledger/` are ever staged — a human's unrelated edit is not laundered into an
  automated commit.

And one guard that exists because it bit during development: a corpus file is a
whole-file rewrite, so an ingest returning fewer entities than the file already
holds would silently shrink the record (**measured**: re-running at `--depth 1`
over a depth-2 corpus took it from 142 nodes / 124 edges back to 74 / 42).
`ingest-guarded!` writes to a temp path, compares, and refuses to replace a
corpus with a smaller one — reporting the refusal. A loop that runs unattended
must not be able to lose ground.

## Not here, on purpose

- **Scheduling policy** — `cycle!` is still one call = one cycle; the resident
  above is a caller, not a scheduler baked into the loop.
- **Scoring truth** — `kotoba-lang/innen`.
- **Primary-source upgrades** — every Wikidata edge is `:attested`. Promoting an
  edge to `:documented` means a human read the primary source; the schema keeps
  that distinction rather than flattening it.

## Test

```bash
npm test    # nbb via `clojure -A:test -Spath`; 10 tests, 63 assertions
```

Tests are hermetic (temp corpus dirs, no network). The API-touching paths are
exercised by running the ingest scripts, whose output lands in `corpus/` as
checked-in evidence — mocking the API here would test the mock.

## License

MIT — matching the rest of the kotoba-lang library/loop family (`dynamics`,
`loop-system-dynamics`, `loop-ux-kaizen`, `arrangement`).
