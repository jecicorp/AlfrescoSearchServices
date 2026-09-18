# Large-folder indexing benchmark

Measures how the indexing trackers behave on the shape customers report as
problematic: a single folder holding tens of thousands of children, or a tree of
comparable size. It answers two questions with numbers rather than impressions —
*how fast does the index converge* and *what does it cost the trackers process* —
and it is built to be run twice, before and after a change, so the two runs can
be compared field by field.

It lives in `bench/large-folder/` and is **not** part of the test suites: it
creates real content in a real repository and takes tens of minutes.

## What it measures

Two phases, selectable with `--phases`:

| Phase | What happens | What it exercises |
|-------|--------------|-------------------|
| `a` | The tree is created over the public REST API while the trackers run | Steady-state ingestion: the repository emits transactions, the MetadataTracker follows |
| `b` | `scripts/purgeIndex.sh` empties the core and restarts the trackers | Cold full re-index of an already-large repository, from transaction 0 |

Each phase samples, every `--interval` seconds:

- Solr document counts per `DOC_TYPE` (`Node`, `Tx`, `ErrorNode`, …), read with
  `defType=lucene` — the default `afts` parser rejects `*:*`;
- the tracker state from `GET :8085/api/admin/summary` — last indexed transaction,
  transaction lag, approximate transactions remaining;
- per container: memory, CPU, restart count and the `OOMKilled` flag, via
  `docker stats` and `docker inspect`.

`report.py` reduces the samples to: time to reach the expected document count,
mean and peak indexing rate, peak tracker memory, whether a container was
OOM-killed or restarted, and the number of error nodes left behind.

## Tree profiles

`--profile` selects the shape; `--total` is the number of nodes created, folders
included. Profiles are defined in `bench/large-folder/profiles.py`.

| Profile | Shape at `--total 30000` | Why |
|---------|--------------------------|-----|
| `flat` | 1 folder, 30 000 direct children, depth 1 | Worst case for the per-node metadata fetch: the folder's own metadata carries 30 000 child associations |
| `deep` | 732 folders, depth 5, ≤46 children each | Same node count spread out — isolates the cost of depth and path handling from the cost of width |
| `mixed` | 448 folders, depth 7, one hot folder with ~8 900 children | Closer to a real deployment: one oversized folder inside an otherwise ordinary tree |

Check a shape without touching the repository:

```bash
mise run bench:plan -- --profile mixed --total 30000
```

## Running it

The dev stack must be up (`mise run dev:up`) and healthy.

```bash
# Full scenario, both phases, results under bench/large-folder/runs/before-fix/
mise run bench:scenario -- --label before-fix --profile flat --total 30000

# Ingestion only, smaller tree, more creation workers
mise run bench:scenario -- --label smoke --profile mixed --total 2000 --phases a --workers 16
```

A run directory holds `baseline.json` (index state before anything was created),
`manifest.json` (what was created and how fast), `phase-a.csv`, `phase-b.csv` and
`summary.md`.

Compare two runs:

```bash
mise run bench:report -- bench/large-folder/runs/before-fix bench/large-folder/runs/after-fix
```

One-off look at the current state, outside any run:

```bash
mise run bench:sample
```

### Practical notes

- **Creation is the slow part.** 30 000 nodes over the REST API takes on the order
  of 10–20 minutes at 8 workers, and the rate *drops as the folder fills* on the
  `flat` profile — that slowdown is a repository-side result in its own right and
  is recorded in `manifest.json` under `rate_samples`.
- **`--resume` is on** in `scenario.sh`: a run interrupted mid-creation can be
  relaunched with the same `--label` and will reuse the nodes already created.
- **`trackers` is capped at 512 MB** in `docker-compose.dev.yml` and Solr at 2 GB.
  A benchmark that ends with `*_oom_killed = yes` or a non-zero `*_restarts` has
  found something; raise the cap deliberately for a second run rather than
  treating the first as invalid.
- The sampler reaches Solr through Caddy on `:8984`, which injects
  `X-Alfresco-Search-Secret`. Going straight to `:8983` requires
  `--search-secret <value>`.
- `--stable-for` ends a phase when the document count has not moved for that long:
  without it, a stalled tracker would keep the run alive until `--timeout`.
- **Compare runs of equal rank on a stack.** A second purge-and-re-index on a stack
  that has already done one is consistently far slower — measured at ~27s then ~44s
  for the same 30 781 documents, on the same build. The effect is larger than most
  changes being measured, so comparing the first run of one build against the second
  run of another inverts the verdict. Either restart the stack between measurements,
  or take the same rank on both sides. The per-node and per-transaction figures in
  `SUMMARY` drift the same way: `MeanNodeIndexTimeMs` went 6.84ms to 14.49ms between
  two runs of one unchanged build.

## What the scenario has found

### The transaction cursor stall (fixed)

The first run of this benchmark found a complete, silent indexing stall. Creating
30 000 files produced 30 000 transactions in four minutes; the MetadataTracker
indexed **none** of them, and would not have recovered on its own under sustained
ingestion.

`txnsFound` is the tracker's read cursor — `getTxFromCommitTime` returns the commit
time of its last entry, falling back to `lastGoodTxCommitTimeInIndex`, which
`continueState` pins one hole retention hour before now. The tracker recorded a
transaction in `txnsFound` only *after* the `isTransactionToBeIndexed` filter, so a
fetch window filled entirely with already-indexed transactions left the cursor
untouched and the cycle broke out immediately. The same window was then read again
five seconds later, indefinitely.

**Trigger: more than `alfresco.metadata.tracker.maxNumberOfTransactions` (2000)
transactions committed inside the hole retention hour.** Roughly 2000 file
creations per hour — any bulk import clears it.

**Signature in the logs**: an identical `Found 2000 transactions after
lastTxCommitTime …, transactions from Transaction [id=1 …]` line every five
seconds, while `SUMMARY` shows `Approx transactions remaining` climbing. No error,
no exception, no warning.

Measured on a 30 000-child folder, before and after the fix: index never
converging → converging 14 s after the last node was created; peak tracker lag
422 s → 7 s; cold re-index 65 s → 35 s.

### Metadata fetched one node at a time (fixed)

`SolrJIndexingService.indexNodes` delegated per node, each issuing its own
`getNodesMetaData` call with `maxResults=1` and every `NodeMetaDataParameters`
default left at `true`. A 30 000-node import meant 30 000 repository round trips,
and indexing the large folder itself made the repository serialise its 30 000
child references into one response.

The batch is now fetched in a single call, with `includeChildIds` and
`includeChildAssociations` disabled — `SolrDocumentMapper.toNodeDoc` reads
neither. A failed batch call falls back to one call per node.

### Still open

- **Where the time goes is now instrumented.** `SUMMARY` reports
  `MeanNodeIndexTimeMs`, `MeanNodeElapsedIndexTimeMs` and `MeanTxElapsedIndexTimeMs`;
  the first two were broken until `0b00b424c` and the third did not exist. On a fresh
  stack a cold re-index of 30 781 documents splits into roughly 7s of node indexing
  and 3s of transaction documents, with no unexplained remainder — an earlier estimate
  of a large missing chunk turned out to be an artefact of measuring from the benchmark
  phase, which includes the tracker container restart.
- **Cascade is not exercised.** `SolrJQueryService.getDescendantNodeIds` queries
  Solr with `rows=Integer.MAX_VALUE` and materialises the whole descendant set at
  once. Reaching it needs a rename or a move of the large folder, which phases `a`
  and `b` do not trigger.
- **`flat` versus `deep` at equal node count** has not been run. It is what
  separates the cost of width from the cost of depth.

### Tuning sweep: parallelism and batch size do not move throughput

Measured on the `flat` 30 000 profile, 20 cold re-indexes of 30 781 documents,
each on a **virgin Solr index** (the `solr-data` and `solr-solrhome` volumes are
removed and the core recreated, which takes ~9s and leaves the repository tree
intact, so a sweep does not have to recreate the tree between points):

| `metadata-parallelism` | `node-batch-size` | mean | sd | `MeanNodeIndexTimeMs` |
|---|---|---|---|---|
| 32 | 50 | 13.17s | 0.41 | 4.74 |
| 16 | 50 | 12.62s | 0.37 | 2.61 |
| 8 | 50 | 12.88s | 0.26 | 1.27 |
| 4 | 50 | 12.85s | 0.50 | 0.52 |
| 16 | 100 | 12.92s | 0.32 | 2.10 |

All twenty runs: mean 12.89s, sd 0.38s, range 12.1–13.7s. The spread between
configuration means is the same size as the noise, over an eightfold change in
thread count. **Neither knob changes throughput on this profile.**

`MeanNodeIndexTimeMs` meanwhile scales almost exactly with `1/threads` — the
product stays near 0.15 ms across the whole range. It measures **queueing, not
work**: four threads already saturate whatever the real bottleneck is, and adding
twenty-eight more only makes each of them wait longer. Read it as a contention
indicator, never as a throughput one.

The practical consequence is not "tune it faster" but "tune it smaller": eight
threads index as fast as thirty-two while leaving CPU and heap to the other core.
That is why the shipped `archive` overrides cut the pools without costing archive
anything measurable.

A first sweep at 2s sampling resolution appeared to show a clear ranking, and was
wrong — the quantisation was ±17% of a 12s measurement. Sample at 0.5s or finer,
and report the spread, not a single run.

Findings are recorded per run in `summary.md`; the run directories themselves are
git-ignored.
