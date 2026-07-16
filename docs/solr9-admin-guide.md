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

### Repository side: the `solr9` search subsystem

Because Solr is now vanilla and the admin control plane lives in the trackers, the
**Alfresco Repository** must use a matching search subsystem. This project ships a
`solr9` Search subsystem as the `alfresco-search-subsystem-solr9` module — a
resources-only JAR that just needs to be on the Repository classpath (`WEB-INF/lib`).
It behaves like the stock `solr6` subsystem except that Alfresco's **admin** HTTP
client (the `SUMMARY`/`REPORT`/`STATUS`/… actions) is pointed at the trackers service,
while search queries and the internal health ping stay on Solr.

Enable and configure it in `alfresco-global.properties` (or `-D`):

| Property | Value | Notes |
|----------|-------|-------|
| `index.subsystem.name` | `solr9` | Selects the subsystem. |
| `solr.host` / `solr.port` | Solr host / `8983` | Query + ping channel (unchanged). |
| `solr.tracker.host` | trackers host | **Required.** Defaults to `localhost`; if unset, admin calls fail with `Connection refused`. Must be reachable from the ACS container. |
| `solr.tracker.port` | `8085` | Trackers admin port (`none`/`secret` mode). |
| `solr.tracker.port.ssl` | `8085` | Trackers admin TLS port (`https`/mTLS mode). |

In `https`/mTLS mode the trackers admin server must have TLS enabled
(`TRACKER_SERVER_SSL_ENABLED=true`); `solr.tracker.secureComms` / `solr.tracker.sharedSecret`
default to the Solr channel's values, so a single-secret stack needs no extra config.

The **OOTBee Support Tools → "Solr Tracking"** admin page works against `solr9`
provided the addon recognises the `solr9` subsystem name (older builds only know
`solr`/`solr4`/`solr6`); the trackers serve `SUMMARY` (with stock-compatible field
names) and proxy `STATUS` to Solr.

> The `solr9` support for the addon is **not yet merged upstream**: build and deploy
> `ootbee-support-tools` from the **`feature/solr9`** branch (pull request pending on
> the upstream project). A stock/released OOTBee addon does not recognise `solr9`.

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
| `alfresco` and `archive` cores show identical document counts | The `archive` core is indexing the live store instead of the trashcan. Check the trackers startup log for `[core 'archive'] store=archive://SpacesStore`; if it shows `workspace://…`, the store was misconfigured. Fixed in current versions (store resolved per core). After fixing, rebuild the archive index in place (see the *Full re-index* section below). |
| ACL deny filtering questions | See [debugging.md](debugging.md) (`processedDenies`). |
| Admin page / admin action fails with `ConnectException: Connection refused` | `solr.tracker.host` not set (defaults to `localhost`) or the trackers admin port unreachable from ACS. In `https` mode also check `solr.tracker.port.ssl` and that the trackers admin server has TLS enabled. See *Repository side: the `solr9` search subsystem*. |
| OOTBee "Solr Tracking" page shows *Web Script Status 500* / `coreNames`/`… Active` null | Repository not on the `solr9` subsystem, or an `ootbee-support-tools` build that predates `solr9` support. Set `index.subsystem.name=solr9` and deploy an addon build with `solr9` support. |

## Operations: reports & on-demand reindex

Indexing reports and maintenance actions are served by the **trackers** service on
`:8085` (Solr stays vanilla — the old `/solr/admin/cores?action=…` handler now
lives here). The most useful:

```bash
# Is indexing caught up? (per-core stats + TXLag/AclTXLag)
curl -s "http://<trackers>:8085/api/admin/summary" | python3 -m json.tool

# Index consistency report (missing/duplicated/error docs)
curl -s "http://<trackers>:8085/api/admin/report?core=alfresco" | python3 -m json.tool

# Status of one node (by DBID)
curl -s "http://<trackers>:8085/api/admin/node-report?nodeid=<DBID>&core=alfresco"

# Reindex a node / transaction / AFTS query on demand
curl -s -X POST "http://<trackers>:8085/api/admin/reindex?nodeid=<DBID>&core=alfresco"
curl -s -X POST "http://<trackers>:8085/api/admin/reindex?txid=<TXID>&core=alfresco"

# Inspect and retry error nodes
curl -s  "http://<trackers>:8085/actuator/repairreport"
curl -s -X POST "http://<trackers>:8085/api/admin/retry?core=alfresco"
```

Full action list, parameters and the Solr-compat alias:
[tracker-admin-endpoints.md](tracker-admin-endpoints.md).

## Full re-index: in-place vs blue/green (near-zero downtime)

The on-demand actions above target a **single** node/transaction/query. Sometimes you
need to rebuild a **whole core** from scratch: after a content-model change that alters
how documents are indexed, after fixing a misconfiguration (e.g. the per-core store mix-up
above), to recover from index corruption, or on a major upgrade. There are two ways to do
it, with a clear trade-off between simplicity and search availability.

### Option A — in-place (simple, search degraded during the rebuild)

This is the same flow as the mandatory upgrade re-index: empty the core, let the trackers
rebuild it.

1. Stop the trackers service (so it does not re-commit while you empty the core).
2. Empty the core with a Solr delete-by-query and commit — a standard Solr command, no
   helper script:
   ```bash
   SECRET="<your X-Alfresco-Search-Secret>"
   curl -s -X POST "http://<solr>:8983/solr/alfresco/update?commit=true" \
     -H "Content-Type: application/json" \
     -H "X-Alfresco-Search-Secret: $SECRET" \
     -d '{"delete":{"query":"*:*"}}'
   ```
3. Start the trackers. The index is now empty, so the resume point is "nothing indexed" and
   the trackers rebuild from transaction 1. (Restarting is what forces them to re-read the
   resume point from the index — this relies on the resume point being derived from the
   index; see [debugging.md](debugging.md#the-trackers-re-index-everything-on-every-restart).)
4. Watch `GET /api/admin/summary` until `TXLag`/`AclTXLag` reach ~0.

While step 3 runs, **search results on that core are incomplete** — metadata reappears
first, full-text a bit later. There is no going back once step 2 is done.

### Option B — blue/green with a parallel core and SWAP (near-zero downtime)

Build a fresh index *next to* the live one, then swap them atomically. The live core keeps
serving complete results the entire time; users see the new index only at the instant of
the swap.

1. **Create the rebuild core** (its store is auto-selected by name — any name other than
   `archive` maps to the live workspace store, so no override is needed):
   ```bash
   SECRET="<your X-Alfresco-Search-Secret>"
   curl -s -X POST "http://<trackers>:8085/api/admin/new-core" \
     -H "X-Alfresco-Search-Secret: $SECRET" \
     -d "coreName=alfresco-rebuild" -d "template=rerank"
   ```
2. **Have the trackers index it in parallel.** Add the core to the tracked list and restart
   the trackers service:
   ```yaml
   environment:
     ALFRESCO_TRACKER_SOLR_COLLECTIONS: "alfresco,archive,alfresco-rebuild"
   ```
   The rebuild core starts from transaction 1 while `alfresco` stays current. Verify the
   store binding in the trackers startup log: `[core 'alfresco-rebuild'] store=workspace://SpacesStore`.
   To limit the extra load on the repository and transform engine, give the rebuild core
   slower crons via `ALFRESCO_TRACKER_CORES_ALFRESCOREBUILD_*` overrides during catch-up.
3. **Wait until it has caught up.** Compare it to the live core and confirm consistency:
   ```bash
   curl -s "http://<trackers>:8085/api/admin/summary" | python3 -m json.tool
   curl -s "http://<trackers>:8085/api/admin/report?core=alfresco-rebuild" | python3 -m json.tool
   ```
   Proceed only when `TXLag`/`AclTXLag` ≈ 0 **and** the report shows no missing/error docs.
   (Heed the silent-skip behaviour: trust `summary`/`report`, not the HTTP status code.)
4. **Swap atomically.** This is a *Solr* core action (`:8983`), not a trackers action — the
   trackers module does not wrap it, but vanilla Solr 9 standalone provides it:
   ```bash
   # briefly stop the trackers so no transaction lands mid-swap, then:
   curl -s "http://<solr>:8983/solr/admin/cores?action=SWAP&core=alfresco-rebuild&other=alfresco" \
     -H "X-Alfresco-Search-Secret: $SECRET"
   ```
   The name `alfresco` now serves the freshly built index. **Restart the trackers**: on
   restart they re-derive the resume point from the new `alfresco` index and continue from
   there — which only works if the trackers read their resume point from the index; see
   [debugging.md](debugging.md#the-trackers-re-index-everything-on-every-restart).
5. **Clean up.** Remove the rebuild core from `ALFRESCO_TRACKER_SOLR_COLLECTIONS`, then drop
   the now-stale old index (it is currently named `alfresco-rebuild` after the swap):
   ```bash
   curl -s -X POST "http://<trackers>:8085/api/admin/remove-core" \
     -H "X-Alfresco-Search-Secret: $SECRET" -d "coreName=alfresco-rebuild"
   ```

If the new index turns out to be wrong, you have **not lost anything**: SWAP again to put
the old index back under the `alfresco` name before you remove anything.

### Which to choose

| | Option A — in-place | Option B — blue/green (SWAP) |
|---|---------------------|------------------------------|
| **Search during rebuild** | Incomplete for the whole window | Complete on the old index until the swap instant |
| **Complexity** | Low — purge + restart | Higher — parallel core, monitor, swap, cleanup |
| **Disk** | One index | ~2× the core's size while both exist |
| **Repository load** | One tracking flow | ~2× polling during catch-up (throttle the rebuild core) |
| **Rollback** | None — once purged, the old index is gone | Old index kept until you remove it; swap back to undo |
| **Best for** | Maintenance windows, small repos, the mandatory upgrade re-index | Large/production repos where search must stay available |

Rule of thumb: use **in-place** when you already have a maintenance window or the repository
is small; use **blue/green** when search must remain fully available and you have the disk
headroom for a second copy of the core.

## See also

- [tracker-admin-endpoints.md](tracker-admin-endpoints.md) — reports & reindex REST API reference.
- [tracker-configuration.md](tracker-configuration.md) — full tracker tuning reference.
- [solr6-to-solr9-migration.md](solr6-to-solr9-migration.md) — technical migration details.
