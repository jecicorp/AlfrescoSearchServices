# Tracker Configuration & Tuning

This guide documents the configuration settings of the standalone **Alfresco
Indexing Trackers** service (`alfresco-indexing-trackers`, Spring Boot) and
explains how each one affects indexing latency, throughput, and resource usage.

## How configuration is bound

All tracker settings live under the Spring prefix **`alfresco.tracker.*`** and
are bound from `application.yml` (baked into the image) or overridden at runtime.

Because Spring Boot uses *relaxed binding*, every property can be set with an
environment variable by upper-casing it and replacing `.` / `-` with `_`:

| Property (`application.yml`)        | Environment variable                       |
|-------------------------------------|--------------------------------------------|
| `alfresco.tracker.cron.content`     | `ALFRESCO_TRACKER_CRON_CONTENT`            |
| `alfresco.tracker.commit-interval`  | `ALFRESCO_TRACKER_COMMIT_INTERVAL`         |
| `alfresco.tracker.batch-count`      | `ALFRESCO_TRACKER_BATCH_COUNT`             |

This means you can re-tune a running stack (e.g. `pristy-demo`) by adding
environment variables to the `trackers` service — **no image rebuild required**.

## The indexing pipeline (why full-text is slower than metadata)

Content is indexed in **two passes**, by two different trackers. This is the key
to understanding indexing latency:

1. **MetadataTracker** indexes the node's properties, paths and aspects. As soon
   as this pass commits, the document is findable *by name and metadata*. If the
   node has content, the tracker flags it as "content outdated"
   (`LAST_INCOMING_CONTENT_VERSION_ID = -10`).
2. **ContentTracker** runs on its own schedule. It queries Solr for documents
   carrying the "outdated" flag, fetches the extracted text from the Repository
   (via the transform service), and indexes it. Only after *this* pass commits is
   the document findable *by its full text*.
3. **CommitTracker** periodically commits pending changes and reopens the Solr
   searcher, which is what actually makes new documents visible to queries.

Worst-case **full-text latency** is therefore roughly:

```
metadata cron  +  content cron  +  text extraction time  +  commit interval  +  newSearcher interval
```

With the shipped defaults that is on the order of **25–30 s** (excluding the
transform time), whereas metadata-only search is visible in **~5–8 s**.

## Settings reference

### Cron schedules — `alfresco.tracker.cron.*`

These use the Quartz/Spring 6-field cron format (`sec min hour day month weekday`).
`0/N * * * * ?` means "every N seconds". A cron only controls *how often a
tracker wakes up*; it does not by itself guarantee a commit (see commit settings).

| Property  | Shipped default        | Tracker        | Impact |
|-----------|------------------------|----------------|--------|
| `cron.metadata` | `0/5 * * * * ?` (5 s)  | MetadataTracker | How fast new/changed node **metadata** appears. Lower = fresher metadata, more load on the Repository tracking API. |
| `cron.acl`      | `0/5 * * * * ?` (5 s)  | AclTracker      | How fast permission changes (readers/denied) are reflected in search filtering. |
| `cron.content`  | `0/20 * * * * ?` (20 s)| ContentTracker  | **Dominant lever for full-text latency.** How often outdated content is picked up for extraction. Lower = fresher full text, more frequent Solr queries + transform calls. |
| `cron.commit`   | `0/5 * * * * ?` (5 s)  | CommitTracker   | How often the commit logic is evaluated. Effective commit cadence is `max(cron.commit, commit-interval)`. |
| `cron.model`    | `0/10 * * * * ?` (10 s)| ModelTracker    | How fast new/changed content models propagate. Rarely needs tuning. |
| `cron.cascade`  | `0/10 * * * * ?` (10 s)| CascadeTracker  | How fast path updates propagate to descendants after a rename/move. |
| `cron.repair`   | `0 0/1 * * * ?` (1 min)| RepairTracker   | How often nodes flagged with `HAS_INDEXING_ERROR` are retried. |

### Commit & visibility

| Property | Default | Impact |
|----------|---------|--------|
| `alfresco.tracker.commit-interval` | `2000` ms | Minimum time between Solr hard commits. The CommitTracker only commits if at least this long has elapsed since the last commit. Lower = changes persisted sooner, but more frequent (costly) commits. |
| `alfresco.tracker.new-searcher-interval` | `3000` ms | Minimum time between Solr searcher reopens. A document is **not visible to queries** until a new searcher opens. Lower = results appear sooner, at the cost of cache churn. |

> The compiled-in fallbacks in `CommitTracker` are much larger (60 s / 120 s);
> the small values above are set explicitly by the trackers' configuration and
> are what govern the e2e "wait for indexing" behaviour.

### Throughput & batching

| Property | Default | Impact |
|----------|---------|--------|
| `alfresco.tracker.batch-count` | `5000` | Number of nodes/ACLs fetched per metadata/ACL tracking call to the Repository. Higher = fewer round-trips and faster bulk/initial indexing, but larger memory spikes and longer single transactions. |

### Other settings

| Property | Default | Impact |
|----------|---------|--------|
| `alfresco.tracker.cascade-tracking-enabled` | `true` | Enables propagation of path changes to descendants. Disabling speeds up renames/moves of large subtrees but leaves descendant paths stale until a full reindex. |
| `alfresco.tracker.repair-max-retries` | `10` | How many times the RepairTracker retries a failing node before marking it permanently failed. |
| `alfresco.tracker.solr-home` | `/opt/solr/data` | Local directory where the model dictionary is persisted. |
| `alfresco.tracker.solr.collections` | `alfresco,archive` | Cores/collections to track. Must match the cores created in the Solr image. |

### Internal content settings (not externally configurable today)

The ContentTracker reads two extra knobs, but they are **not currently wired to
Spring properties**, so they always use their compiled defaults. Changing them
requires a code change (adding them to `TrackerProperties` / `TrackerBootstrap`):

| Property (legacy `Properties` key) | Default | Impact |
|------------------------------------|---------|--------|
| `alfresco.contentUpdateBatchSize` | `2000` | Partition size for parallel content extraction within one cycle. |
| `alfresco.content.tracker.maxParallelism` | `8` | Size of the `ForkJoinPool` extracting content in parallel. Higher = faster bulk extraction, more concurrent load on the transform service. |

In addition, each ContentTracker cycle pulls at most **2000** outdated documents
from Solr (hardcoded in `SolrJQueryService#getDocsWithUncleanContent`). With a
large backlog, full re-indexing therefore progresses 2000 documents per
`cron.content` tick.

## Reducing full-text indexing delay

The single most effective change is to lower **`cron.content`**. For a demo or
low-volume stack, dropping it from 20 s to 5 s brings full-text latency close to
metadata latency:

```yaml
# compose.yml — trackers service
environment:
  ALFRESCO_TRACKER_CRON_CONTENT: "0/5 * * * * ?"     # was 0/20
```

To shave off the visibility tail as well, you can also lower the searcher reopen
interval:

```yaml
  ALFRESCO_TRACKER_NEW_SEARCHER_INTERVAL: "1500"      # was 3000 (ms)
```

**Trade-offs to keep in mind:**

- A faster content cron means more frequent Solr queries and more frequent calls
  to the transform service. On a busy repository with heavy documents this can
  saturate the transform engine — tune conservatively in production.
- A shorter `new-searcher-interval` and `commit-interval` increase commit/cache
  overhead. For high write throughput, keep them higher and accept some latency.
- Metadata/ACL crons are already at 5 s; lowering them further yields little
  benefit because content extraction dominates the perceived delay.

## Current shipped configuration

For reference, the values baked into the image
(`src/main/resources/application.yml`):

```yaml
alfresco:
  tracker:
    cron:
      metadata: "0/5 * * * * ?"
      acl:      "0/5 * * * * ?"
      content:  "0/20 * * * * ?"
      commit:   "0/5 * * * * ?"
      model:    "0/10 * * * * ?"
      cascade:  "0/10 * * * * ?"
    batch-count: 5000
    cascade-tracking-enabled: true
```

`commit-interval` (2000 ms), `new-searcher-interval` (3000 ms),
`cron.repair` (1 min) and `repair-max-retries` (10) are not listed in
`application.yml` and fall back to the defaults shown in the reference tables.
