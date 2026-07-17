# Tracker Configuration & Tuning

This guide documents the configuration settings of the standalone **Alfresco
Indexing Trackers** service (`alfresco-indexing-trackers`, Spring Boot) and
explains how each one affects indexing latency, throughput, and resource usage.

## How configuration is bound

All tracker settings live under the Spring prefix **`alfresco.tracker.*`** and
are bound from `application.yml` (baked into the image) or overridden at runtime.

Because Spring Boot uses *relaxed binding*, every property can be set with an
environment variable by upper-casing it and replacing `.` / `-` with `_`:

| Property (`application.yml`)                    | Environment variable                                   |
|-------------------------------------------------|--------------------------------------------------------|
| `alfresco.tracker.cron.content`                 | `ALFRESCO_TRACKER_CRON_CONTENT`                        |
| `alfresco.tracker.commit-interval`              | `ALFRESCO_TRACKER_COMMIT_INTERVAL`                     |
| `alfresco.tracker.batch-count`                  | `ALFRESCO_TRACKER_BATCH_COUNT`                         |
| `alfresco.tracker.cores.archive.transform-content` | `ALFRESCO_TRACKER_CORES_ARCHIVE_TRANSFORM_CONTENT` |

This means you can re-tune a running stack (e.g. `pristy-demo`) by adding
environment variables to the `trackers` service — **no image rebuild required**.

> **Per-core settings** live under `alfresco.tracker.cores.<coreName>.*` and
> override the global value for that core only — see
> [Per-core configuration & store selection](#per-core-configuration--store-selection).

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
| `alfresco.tracker.transform-content` | `true` | Maps to the legacy `alfresco.index.transformContent`. When `false`, the tracker does **not** request text extraction for that core — useful for the `archive` core, where full-text search of trashed documents is rarely needed. |
| `alfresco.tracker.max-live-searchers` | `2` | Maximum number of concurrent live Solr searchers a tracker keeps open while indexing. |
| `alfresco.tracker.repair-max-retries` | `10` | How many times the RepairTracker retries a failing node before marking it permanently failed. |
| `alfresco.tracker.solr-home` | `/opt/solr/data` | Local directory where the model dictionary is persisted. |
| `alfresco.tracker.health.connect-timeout` | `5000` ms | Connection timeout for the repository health probe behind `/actuator/health` (`RepositoryHealthIndicator`). |
| `alfresco.tracker.health.read-timeout` | `5000` ms | Read timeout for the repository health probe. Raise both on a slow/loaded repository to avoid the health endpoint reporting `DOWN` under transient latency. |
| `alfresco.tracker.solr.collections` | `alfresco,archive` | Cores/collections to track. Must match the cores created in the Solr image. The **store** each core tracks is resolved separately — see [Per-core configuration & store selection](#per-core-configuration--store-selection). |

### Backup — `alfresco.tracker.backup.*`

The trackers can drive Solr index backup (and restore) through Solr's
ReplicationHandler on a cron schedule, one job per tracked core. The index is a
*derived* store — it can always be rebuilt from the repository — so a backup is
purely a **recovery-time** optimisation: after a crash or a lost data volume you
restore the last backup and the tracker re-indexes only the delta since, instead
of re-tracking the whole repository (which can take days on a large index).

| Property | Env var | Default | Impact |
|----------|---------|---------|--------|
| `alfresco.tracker.backup.enabled` | `ALFRESCO_TRACKER_BACKUP_ENABLED` | `false` | Opt-in. When `true`, one cron backup job is registered per enabled core at startup. |
| `alfresco.tracker.backup.cron` | `ALFRESCO_TRACKER_BACKUP_CRON` | `0 0 2 1 * ?` | Spring cron expression. Default: monthly, 1st of the month at 02:00. |
| `alfresco.tracker.backup.location` | `ALFRESCO_TRACKER_BACKUP_LOCATION` | `/backup/solr` | Backup root on **Solr's** filesystem. Each core is written under `<location>/<core>`. **Must** be inside Solr's `solr.allowPaths`, and for disaster recovery it should be a **dedicated volume separate from the index data dir** (so a full or lost data disk does not take the backup with it). |
| `alfresco.tracker.backup.number-to-keep` | `ALFRESCO_TRACKER_BACKUP_NUMBER_TO_KEEP` | `2` | Snapshots retained per core. Each snapshot is a full copy of the index when the backup volume is on a different filesystem (no hardlinks across filesystems), so size the volume as `index size × number-to-keep`. |

> **`solr.allowPaths` is a Solr-side setting**, not a tracker one: the Solr 9
> image lists the backup dir in `-Dsolr.allowPaths` (see the Solr image
> Dockerfile / `SOLR_BACKUP_DIR`). A `location` outside `allowPaths` makes Solr
> reject the backup with HTTP 400.

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

## Per-core configuration & store selection

When several cores are tracked (the default `alfresco,archive`), each one can be
configured **independently**. This matters because the cores have different
roles: `alfresco` indexes the live `workspace://SpacesStore`, while `archive`
indexes the trashcan `archive://SpacesStore`, whose updates are secondary and
can be tracked far less aggressively.

### Store selection

Each core tracks exactly one store, resolved in this order:

1. an explicit `alfresco.tracker.cores.<coreName>.store`, otherwise
2. **by convention**: the core named `archive` tracks `archive://SpacesStore`;
   every other core tracks `workspace://SpacesStore`.

Thanks to the convention, a standard `alfresco` + `archive` deployment needs
**no `cores` configuration at all** to track the correct stores.

> **Why this exists.** `AbstractTracker` falls back to `workspace://SpacesStore`
> when no store is set. Previously the configuration was shared across all cores
> and never carried a store, so *every* core defaulted to the workspace store and
> the `archive` core indexed the live nodes instead of the trashcan — two distinct
> indexes holding identical documents. The resolved store of every core is now
> logged at startup (`TrackerBootstrap`), e.g.
> `[core 'archive'] store=archive://SpacesStore …`.

### Global default vs. per-core override

Every setting below has a **global default** (the `alfresco.tracker.*` keys in
the reference tables above) and may be **overridden per core** under
`alfresco.tracker.cores.<coreName>.*`. A per-core key that is left unset
**inherits the global value**.

Overridable per core:

| Per-core key | Global counterpart |
|--------------|--------------------|
| `cores.<name>.store` | *(convention — see above)* |
| `cores.<name>.batch-count` | `batch-count` |
| `cores.<name>.max-live-searchers` | `max-live-searchers` |
| `cores.<name>.transform-content` | `transform-content` |
| `cores.<name>.cascade-tracking-enabled` | `cascade-tracking-enabled` |
| `cores.<name>.commit-interval` | `commit-interval` |
| `cores.<name>.new-searcher-interval` | `new-searcher-interval` |
| `cores.<name>.cron.{metadata,acl,content,commit,cascade,repair}` | `cron.*` |
| `cores.<name>.backup.{enabled,cron,location,number-to-keep}` | `backup.*` |

> `cron.model` is **not** per-core: the ModelTracker is a single repo-global
> instance (initialised on the first core), so the model schedule always comes
> from the global / first-core configuration.

### Example: a deprioritised `archive` core

```yaml
alfresco:
  tracker:
    solr:
      collections: [alfresco, archive]
    cores:
      archive:
        # store is "archive://SpacesStore" by convention — no need to set it
        transform-content: false        # skip full-text extraction for the trash
        max-live-searchers: 1
        batch-count: 1000
        commit-interval: 30000
        new-searcher-interval: 60000
        cascade-tracking-enabled: false
        cron:
          metadata: "0 0/5 * * * ?"      # every 5 min instead of every 5 s
          content:  "0 0/30 * * * ?"
          acl:      "0 0/5 * * * ?"
```

The equivalent with environment variables (no image rebuild):

```yaml
# compose.yml — trackers service
environment:
  ALFRESCO_TRACKER_CORES_ARCHIVE_TRANSFORM_CONTENT: "false"
  ALFRESCO_TRACKER_CORES_ARCHIVE_CRON_METADATA: "0 0/5 * * * ?"
  ALFRESCO_TRACKER_CORES_ARCHIVE_CRON_CONTENT: "0 0/30 * * * ?"
```

The implementation is described in
[`doc/architecture/trackers/00002-per-core-configuration.md`](../search-services/alfresco-search/doc/architecture/trackers/00002-per-core-configuration.md).

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
`transform-content` (`true`), `max-live-searchers` (2), `cron.repair` (1 min)
and `repair-max-retries` (10) are not listed in `application.yml` and fall back
to the defaults shown in the reference tables.

No `cores` block is shipped: both `alfresco` and `archive` track the correct
store by convention, and every core inherits the global tuning above. Add a
`cores.archive.*` section only when you want the `archive` core tracked
differently (see [Per-core configuration & store selection](#per-core-configuration--store-selection)).
