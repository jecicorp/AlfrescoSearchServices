# Tracker Admin Endpoints

Operational REST API of the standalone **Alfresco Indexing Trackers** service
(`alfresco-indexing-trackers`, Spring Boot). It serves the reporting and
maintenance actions that, in classic Alfresco, were exposed by Solr's core admin
handler (`SUMMARY`, `REPORT`, `NODEREPORT`, `REINDEX`, …). In this fork **Solr is
kept vanilla** and these actions live in the trackers service instead.

## Where it runs

- Base URL: `http://<trackers-host>:8085` (the trackers `server.port`, default `8085`).
- Two equivalent interfaces:
  - **Clean REST API** — idiomatic Spring routes under `/api/admin/*` (this document's primary form).
  - **Solr-compat alias** — `GET /solr/admin/cores?action=<ACTION>&…`, same response envelope as the old Solr admin, for tools/scripts that still target it.
- The actuator endpoint `GET /actuator/repairreport` complements these (see [RepairTracker](#error-nodes--repairtracker)).

> The Alfresco control-plane actions (`SUMMARY`, `REPORT`, `REINDEX`, …) are **not**
> served by Solr (`:8983`): Solr is vanilla and calling e.g. `action=SUMMARY` on it
> returns `Unsupported operation: SUMMARY`. The admin surface moved here. The one
> exception is `STATUS`, which is a *native* Solr action — the trackers still expose
> it (see below) but answer it by proxying to Solr's own CoreAdmin STATUS.

### The `core` parameter

Every action accepts an optional `core` (alias `coreName`) parameter. When
**omitted, the action applies to all tracked cores** (`alfresco`, `archive`, …)
and the response is keyed by core name. Pass `core=alfresco` to target one core.

### Response format

The clean API returns plain JSON keyed by core. The Solr-compat alias wraps it as
`{ "responseHeader": { "status", "QTime" }, "<key>": { … } }` where `<key>` is
`Summary` for `SUMMARY`, `status` for `STATUS`, `report` for the `*REPORT` actions,
and `action` otherwise.

## Reports (read-only, `GET`)

| Endpoint | Solr-compat `action` | Params | Returns |
|----------|----------------------|--------|---------|
| `/api/admin/summary` | `SUMMARY` | `core?`, `cores?`, `metrics?` | Per-core stats: index doc counts, `FTS` (content outdated/updated), tracker states `TX`/`AclTX` with `…Lag` and `…DurationLag` (repo vs index), `ModelErrors`, `TrackerStats`. Also emits **stock-Alfresco-compatible** aliases for tools expecting the classic `AlfrescoCoreAdminHandler` field names (`MetadataTracker Active`, `AclTracker Active`, `ContentTracker Active`, `Id for last TX in index`, `Approx transactions remaining`, `TX Lag`, `Approx transaction indexing time remaining`, `Approx change sets remaining`). `cores` is a comma-separated list of cores to display (defaults to all). `metrics` is a comma-separated filter on the displayed keys, matched case-insensitively as a **substring** (e.g. `tx` → TX/TXLag/AclTX…, `nodes` → the node counts, `trackerstats` → TrackerStats). |
| _(Solr-compat alias only — no clean `/api/admin` route)_ | `STATUS` | `core?` | Per-core **native Solr** index stats — `index.{numDocs, maxDoc, deletedDocs, sizeInBytes, indexHeapUsageBytes}`. STATUS is not an Alfresco control-plane action; the trackers **proxy it to Solr's own CoreAdmin STATUS** and return Solr's `status` object. Present so tools that call the classic `/solr/admin/cores?action=STATUS` (e.g. the OOTBee Solr Tracking page) keep working. |
| `/api/admin/report` | `REPORT` | `core?`, `fromTime?`, `toTime?` (epoch ms) | Index consistency: DB vs index transaction counts, leaf/aux/error/unindexed doc counts, and counts of **missing**, **duplicated**, and **in-index-but-not-DB** transactions (tx and acl-tx). |
| `/api/admin/node-report` | `NODEREPORT` | `nodeid`, `core?` | Status of one node by **DBID**: `dbNodeStatus`, `dbTx`, `indexLeafDoc`/`indexAuxDoc`, `indexLeafTx`/`indexAuxTx`, `indexedNodeDocCount`. The go-to for "is node X indexed?". |
| `/api/admin/tx-report` | `TXREPORT` | `txid`, `core?` | Per-transaction indexing detail. |
| `/api/admin/acl-report` | `ACLREPORT` | `aclid`, `core?` | Per-ACL indexing detail. |
| `/api/admin/acltx-report` | `ACLTXREPORT` | `acltxid`, `core?` | Per-ACL-changeset detail. |
| `/api/admin/check` | `CHECK` | `core?` | Schedules an index/DB consistency check on the core. |

```bash
# Overall index health for the live core
curl -s "http://localhost:8085/api/admin/report?core=alfresco" | python3 -m json.tool

# Per-core stats + tracking lag
curl -s "http://localhost:8085/api/admin/summary?core=alfresco" | python3 -m json.tool

# Only the alfresco + archive cores, only TX-lag and FTS metrics
curl -s "http://localhost:8085/api/admin/summary?cores=alfresco,archive&metrics=tx,fts" | python3 -m json.tool

# Is node 15695 indexed? (DBID)
curl -s "http://localhost:8085/api/admin/node-report?nodeid=15695&core=alfresco" | python3 -m json.tool

# Solr-compat equivalent
curl -s "http://localhost:8085/solr/admin/cores?action=NODEREPORT&nodeid=15695&core=alfresco" | python3 -m json.tool
```

## Maintenance (mutating, `POST`)

| Endpoint | Solr-compat `action` | Params | Effect |
|----------|----------------------|--------|--------|
| `/api/admin/reindex` | `REINDEX` | `nodeid?`, `txid?`, `acltxid?`, `aclid?`, `query?`, `core?` | Schedules reindexing of the given node / transaction / acl-changeset / acl, or every node matching an **AFTS `query`**. Purges then re-fetches from the repo. |
| `/api/admin/index` | `INDEX` | `nodeid?`, `txid?`, `acltxid?`, `aclid?`, `core?` | Indexes a node/tx/acl that was **never** indexed (no purge step). |
| `/api/admin/purge` | `PURGE` | `nodeid?`, `txid?`, `acltxid?`, `aclid?`, `core?` | Removes the given item(s) from the index. |
| `/api/admin/retry` | `RETRY` | `core?` | Re-schedules **all recorded error nodes** (`HAS_INDEXING_ERROR`) for reindexing. |
| `/api/admin/backup` | `BACKUP` | `core?` | Triggers a Solr backup of the core(s) via the ReplicationHandler, into the configured `location`, keeping `number-to-keep` snapshots. |
| `/api/admin/restore` | `RESTORE` | `core` (**required**), `location?`, `name?` | Restores ONE core from a snapshot (`location` omitted → the configured per-core location; `name` omitted → the latest snapshot at that location). Restore is destructive — it reverts the live index to the snapshot — so it never fans out to all cores. |

```bash
# Reindex a single node (by DBID)
curl -s -X POST "http://localhost:8085/api/admin/reindex?nodeid=15695&core=alfresco"

# Reindex a whole transaction
curl -s -X POST "http://localhost:8085/api/admin/reindex?txid=29200&core=alfresco"

# Reindex by AFTS query (e.g. one type or subtree)
curl -s -X POST "http://localhost:8085/api/admin/reindex?query=TYPE:%22cm:content%22&core=alfresco"

# Retry everything currently flagged as an error node
curl -s -X POST "http://localhost:8085/api/admin/retry?core=alfresco"

# On-demand backup of all cores into the configured location
curl -s -X POST "http://localhost:8085/api/admin/backup"

# Restore the alfresco core from the latest snapshot
curl -s -X POST "http://localhost:8085/api/admin/restore?core=alfresco"
```

Backup writes each core under `<location>/<core>` (e.g. `/backup/solr/alfresco`).
The backup runs on Solr's own filesystem through the ReplicationHandler, so the
`location` **must** be inside Solr's `solr.allowPaths`. After a `restore`, the
core swaps in the restored index and the tracker resumes from the last
transaction present in that index — only the delta since the backup is
re-indexed, not the whole repository.

`reindex` returns `{"<core>": {"status": "scheduled"}}` when the relevant tracker
is enabled (`notScheduled` otherwise). The work runs on the tracker's next cycle —
it is queued, not synchronous.

## Core management (mutating, `POST`)

| Endpoint | Solr-compat `action` | Params |
|----------|----------------------|--------|
| `/api/admin/new-core` | `NEWCORE` / `NEWINDEX` | `coreName`, `storeRef`, `template` |
| `/api/admin/new-default-index` | `NEWDEFAULTINDEX` / `NEWDEFAULTCORE` | `coreName`, `storeRef`, `template` |
| `/api/admin/update-core` | `UPDATECORE` / `UPDATEINDEX` | `coreName` |
| `/api/admin/update-shared` | `UPDATESHARED` | — |
| `/api/admin/remove-core` | `REMOVECORE` | `coreName`, `storeRef` |
| `/api/admin/log4j` | `LOG4J` | `resource?` |

## Error nodes & RepairTracker

Nodes that fail to index are (when recorded) flagged `HAS_INDEXING_ERROR` in the
index and picked up by the **RepairTracker**. Inspect them with:

```bash
curl -s "http://localhost:8085/actuator/repairreport" | python3 -m json.tool
```

`POST /api/admin/retry` re-queues those error nodes. See
[tracker-configuration.md](tracker-configuration.md) for the repair cron and
`repair-max-retries`.

> **Known limitation.** A node the repository reports as *unindexable* — e.g. a
> node whose type was removed from a content model
> (`… type {…}documentMarche … is not registered in DictionaryService`) — is
> currently **skipped with a WARN log only** (`SolrJIndexingService.indexNodes`),
> **not** recorded as an error node. Such nodes therefore do **not** appear in
> `report`/`repairreport` and are **not** picked up by `retry`; the only trace is
> the `Failed to index node <id> — skipping` line in the trackers log. Reindexing
> them explicitly will keep failing until the model/data is fixed (re-add the type
> as deprecated, or delete the orphaned nodes in the repository).

## Common recipes

```bash
# "Is my reindex caught up?" — watch TXLag / AclTXLag trend toward 0
curl -s "http://localhost:8085/api/admin/summary" | python3 -m json.tool

# "Did node X get indexed?" — dbNodeStatus vs indexLeafDoc
curl -s "http://localhost:8085/api/admin/node-report?nodeid=<DBID>&core=alfresco"

# "Reindex everything under a folder / of a type"
curl -s -X POST "http://localhost:8085/api/admin/reindex?query=<AFTS>&core=alfresco"

# "Find and re-run failed nodes"
curl -s "http://localhost:8085/actuator/repairreport"
curl -s -X POST "http://localhost:8085/api/admin/retry?core=alfresco"
```

## See also

- [solr9-admin-guide.md](solr9-admin-guide.md) — administrator overview of the Solr 9 search tier.
- [tracker-configuration.md](tracker-configuration.md) — tracker tuning, per-core overrides, repair cron.
