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
the same split as `loop-system-dynamics` ⊣ `dynamics`. Implements ADR-2607255500
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
- **163 nodes carry a `:company/lei`** — the join key into the unified query
  plane's SEC financials (ADR-2607252000).
- **The two corpora currently overlap in ZERO LEIs.** The 8 LEIs Wikidata
  supplied (Maersk, TSMC, ASML, CrowdStrike and holding companies) are disjoint
  from the 155 in `cloud-itonami-lei-*`. The join key exists on both sides and
  joins nothing yet — a measured gap, and the clearest target for the next
  ingest pass.
- **Only 1 edge states its own validity interval.** Wikidata statements in this
  sample rarely carry P580/P582 qualifiers, so the record's dated coverage is
  thin; the `window` basis (both endpoints dated) is what currently answers
  historical questions.

## Not here, on purpose

- **Scheduling** — one call is one cycle. Cron / routines / humans are callers.
- **Scoring truth** — `kotoba-lang/innen`.
- **Primary-source upgrades** — every Wikidata edge is `:attested`. Promoting an
  edge to `:documented` means a human read the primary source; the schema keeps
  that distinction rather than flattening it.

## Test

```bash
nbb --classpath "../innen/src:src:test" test/run_tests.cljs   # 9 tests, 56 assertions
```

Tests are hermetic (temp corpus dirs, no network). The API-touching paths are
exercised by running the ingest scripts, whose output lands in `corpus/` as
checked-in evidence — mocking the API here would test the mock.

## License

MIT — matching the rest of the kotoba-lang library/loop family (`dynamics`,
`loop-system-dynamics`, `loop-ux-kaizen`, `arrangement`).
