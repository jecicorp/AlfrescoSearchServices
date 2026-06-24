# What's New for Administrators — Solr 9 Search

This guide is for **Alfresco administrators**. It explains, in plain terms, what
changes with the new search version, what you need to do when upgrading, and the new
configuration options now available. It does **not** require knowing the internals —
for that, see [solr6-to-solr9-migration.md](solr6-to-solr9-migration.md).

## In one paragraph

Search now runs on **vanilla Apache Solr 9.10.1** (instead of the old
Alfresco-patched Solr 6) on **Java 17**. The biggest operational change is that the
**indexing trackers now run as their own separate service/container**, no longer
inside Solr. Your search queries, permissions filtering and AFTS syntax behave the
same as before — but the deployment now has **two** components to run instead of one,
and a few configuration settings moved or were renamed.

## The two things you must know

### 1. A full re-index is required

You **cannot reuse the old index data**. The index format changed across major Solr
versions. On upgrade:

1. Start Solr 9 with **empty cores**.
2. Start the trackers service.
3. The trackers rebuild the whole index from the repository automatically.

Plan for a re-indexing window proportional to your repository size. Search results
will be incomplete until indexing catches up. Metadata becomes searchable first;
full-text search for a document appears a bit later (see the latency note below).

### 2. There are now two services, not one

| Before (Solr 6) | Now (Solr 9) |
|-----------------|--------------|
| Solr + trackers in one webapp | **Solr** (query + index storage) and **`alfresco-indexing-trackers`** (a Spring Boot service that reads from the repository and feeds Solr) run separately |
| Tracker tuning via `solrcore.properties` | Tracker tuning via **environment variables** on the trackers service |

This separation is good news operationally: you can **restart, scale, monitor and
re-tune indexing independently** of query serving, and re-tune a running stack with
no image rebuild.

## New / changed configuration options

### Indexing behaviour — tune it live, no rebuild

All tracker settings are environment variables on the **trackers** service (prefix
`ALFRESCO_TRACKER_*`). Add them to your compose/deployment and restart that service —
**no image rebuild needed**. The full reference is in
[tracker-configuration.md](tracker-configuration.md); the ones admins reach for most:

| Environment variable | Default | What it does |
|----------------------|---------|--------------|
| `ALFRESCO_TRACKER_CRON_CONTENT` | `0/20 * * * * ?` (20 s) | **Main lever for full-text freshness.** Lower it (e.g. `0/5 * * * * ?`) to make document text searchable sooner — at the cost of more load on the transform service. |
| `ALFRESCO_TRACKER_CRON_METADATA` | `0/5 * * * * ?` (5 s) | How fast new/changed metadata becomes searchable. |
| `ALFRESCO_TRACKER_CRON_ACL` | `0/5 * * * * ?` (5 s) | How fast permission changes are reflected in search filtering. |
| `ALFRESCO_TRACKER_COMMIT_INTERVAL` | `2000` ms | Minimum time between commits. |
| `ALFRESCO_TRACKER_NEW_SEARCHER_INTERVAL` | `3000` ms | Minimum delay before new documents become visible to queries. |
| `ALFRESCO_TRACKER_BATCH_COUNT` | `5000` | Nodes fetched per tracking call — higher speeds bulk/initial indexing but uses more memory. |
| `ALFRESCO_TRACKER_CASCADE_TRACKING_ENABLED` | `true` | Whether renames/moves propagate new paths to descendants. |
| `ALFRESCO_TRACKER_SOLR_COLLECTIONS` | `alfresco,archive` | Which cores to track (must match the cores in the Solr image). |

> **Tip — faster full-text on a demo/low-volume stack:**
> ```yaml
> environment:
>   ALFRESCO_TRACKER_CRON_CONTENT: "0/5 * * * * ?"   # was 0/20
>   ALFRESCO_TRACKER_NEW_SEARCHER_INTERVAL: "1500"    # was 3000
> ```
> On a busy production repository, keep these conservative — a faster content cron
> hits the transform engine harder.

### Per-core tuning (the `archive` core is secondary)

Each setting above can be overridden **per core** with the prefix
`ALFRESCO_TRACKER_CORES_<CORE>_…`. The `archive` core (the trashcan) is rarely
searched, so it is the obvious candidate to deprioritise — track it less often
and skip full-text extraction entirely:

```yaml
environment:
  ALFRESCO_TRACKER_CORES_ARCHIVE_TRANSFORM_CONTENT: "false"      # no text extraction for trash
  ALFRESCO_TRACKER_CORES_ARCHIVE_CRON_METADATA: "0 0/5 * * * ?"  # every 5 min, not every 5 s
  ALFRESCO_TRACKER_CORES_ARCHIVE_CRON_CONTENT: "0 0/30 * * * ?"
```

The **store** each core indexes is chosen automatically: a core named `archive`
indexes the archive (trashcan) store, every other core indexes the live
workspace store. No configuration is needed for the standard layout. See
[tracker-configuration.md](tracker-configuration.md#per-core-configuration--store-selection)
for the full per-core reference.

### Solr runtime — new secure-by-default settings

Solr 9 ships "secure by default", and a few of those defaults must be turned off for
Alfresco to work. **These are already set in the provided Solr image**, but you should
know about them if you build a custom image or override the startup:

| Setting (in `solr.in.sh`) | Value | Why it's needed |
|---------------------------|-------|-----------------|
| `SOLR_JETTY_HOST` | `0.0.0.0` | Solr 9 listens only on `127.0.0.1` by default, which makes the container unreachable. |
| `SOLR_SECURITY_MANAGER_ENABLED` | `false` | Solr 9 turns on the Java SecurityManager by default, which blocks the Alfresco plugins. |
| `SOLR_MODULES` | `analysis-extras` | The ICU text-analysis components used by the Alfresco schema moved into a module that is no longer loaded by default. |

`solr.allowPaths` (file-access allow-list) is also configured for you in the image.

### Things that moved or were renamed

If you previously customised Solr config files, note these:

- **Logging is now Log4j 2.** If you tuned log levels, the format changed from the old
  Log4j 1.x `log4j.properties` style. Adjust your log configuration accordingly.
- **Cache settings renamed in `solrconfig.xml`.** The old `solr.LRUCache` /
  `solr.FastLRUCache` no longer exist — all caches now use `solr.CaffeineCache`. If
  you had custom cache sizes, re-apply them with the new class name.
- **Highlighting** is pinned to the original (field-mapping) highlighter via
  `hl.method=original`, so snippet highlighting keeps working with Alfresco field
  names. No action needed; just don't override it.

## What stays the same

You do **not** need to relearn day-to-day search administration:

- The **AFTS / search query language** and the public search API are unchanged.
- **Permission (ACL) filtering** behaves the same — denied documents stay hidden.
- The default cores are still **`alfresco`** and **`archive`**.
- Highlighting, faceting and ancestor-path (APATH) drill-down work as before.

## After upgrading — quick verification

1. Both services are up: Solr responds, and the trackers service logs indexing cycles.
2. Cores exist (`alfresco`, `archive`) and document counts are climbing toward the
   repository node count.
3. A newly uploaded document becomes findable **by name** within a few seconds and
   **by its content** within ~25–30 s (default cadence).
4. Sorting on title/name, faceting, and highlighting all return results.

## Troubleshooting quick reference

| Symptom | Likely cause / action |
|---------|-----------------------|
| Solr unreachable from other containers | `SOLR_JETTY_HOST` not set to `0.0.0.0` in a custom image. |
| Plugins fail with security/access errors | `SOLR_SECURITY_MANAGER_ENABLED` not set to `false`. |
| Schema fails to load mentioning ICU / analysis | `SOLR_MODULES=analysis-extras` missing. |
| Documents searchable by name but not by content | Normal during the content-indexing lag; if persistent, lower/inspect `ALFRESCO_TRACKER_CRON_CONTENT` and check the transform service. |
| Index count stuck at 0 | Trackers service not running or not pointed at the right cores (`ALFRESCO_TRACKER_SOLR_COLLECTIONS`). |
| `alfresco` and `archive` cores show identical document counts | The `archive` core is indexing the live store instead of the trashcan. Check the trackers startup log for `[core 'archive'] store=archive://SpacesStore`; if it shows `workspace://…`, the store was misconfigured. Fixed in current versions (store resolved per core). After fixing, purge and rebuild the archive index (`./purgeIndex.sh` or delete its data dir). |
| ACL deny filtering questions | See [debugging.md](debugging.md) (`processedDenies`). |

## See also

- [tracker-configuration.md](tracker-configuration.md) — full tracker tuning reference.
- [solr6-to-solr9-migration.md](solr6-to-solr9-migration.md) — technical migration details.
- [debugging.md](debugging.md) — ACL deny filtering diagnostics.
