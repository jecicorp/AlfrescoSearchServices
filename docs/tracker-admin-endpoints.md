# Tracker Admin Endpoints

Operational REST API of the standalone **Pristy Indexing Trackers** service
(`pristy-indexing-trackers`, Spring Boot). It serves the reporting and
maintenance actions that, in classic Alfresco, were exposed by Solr's core admin
handler (`SUMMARY`, `REPORT`, `NODEREPORT`, `REINDEX`, …). In this fork **Solr is
kept vanilla** and these actions live in the trackers service instead.

## Where it runs

- Base URL: `http://<trackers-host>:8085` (the trackers `server.port`, default `8085`).
- Three interfaces:
  - **Clean REST API** — idiomatic Spring routes under `/api/admin/*` (this document's primary form).
  - **Solr-compat alias** — `GET /solr/admin/cores?action=<ACTION>&…`, same response envelope as the old Solr admin, for tools/scripts that still target it.
  - **Pristy indexing API v1** — `/api/v1/*`, a new contract that is not a rewrite of the two above (see [below](#pristy-indexing-api-v1-read-only-get)). `GET /api/v1` advertises the capabilities of the running build; start there.
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

## Pristy indexing API v1 (read-only, `GET`)

### `GET /api/v1` — what this service is and what it can do

Ask here first. The answer says which capabilities the running build carries, so a client
never has to map a version number to a feature set:

```json
{
  "service": "pristy-indexing-trackers",
  "version": "1.2.0",
  "api": "1",
  "capabilities": {
    "admin.actions":         { "since": "1.0", "enabled": true  },
    "admin.backup":          { "since": "1.0", "enabled": false },
    "index.await":           { "since": "1.2", "enabled": true  },
    "index.diagnostic":      { "since": "1.2", "enabled": true  },
    "index.status":          { "since": "1.1", "enabled": true  },
    "index.unindexed-nodes": { "since": "1.1", "enabled": true  },
    "tracker.repair":        { "since": "1.0", "enabled": true  }
  }
}
```

- **`404` on this path means the trackers service predates the API** — that is the whole
  point of having it: it replaces guessing from a version string, and it frees the other
  endpoints to use `404` for its ordinary meaning.
- `since` is the service version a capability first shipped in; `version` is the running
  build, read from `build-info.properties` (`unknown` if the jar carries none).
- **`enabled` is not the same as present.** `admin.backup` above is compiled in but turned
  off by configuration; `index.unindexed-nodes` reflects `alfresco.tracker.record-unindexed-nodes`,
  so a client can tell whether the `unindexed` state can appear at all before relying on it.
- A capability is contributed as a Spring bean by the feature that serves it
  (`ApiCapabilities`), and each declaration takes that feature's service as a parameter —
  removing an implementation breaks the build instead of leaving the endpoint advertising
  something it no longer serves.

A second, additive surface under `/api/v1`. It is **not** an alias of the routes
above: `/api/admin/*` and the Solr-compat action names delegate to the same
`AdminService` and therefore carry the legacy contract unchanged, while `/api/v1`
answers a *verdict* computed from the index and the repository database at once.

### `GET /api/v1/index/node`

| Param | Required | Value |
|-------|----------|-------|
| `ref` | yes | a node DBID, a node UUID, or a full node reference (`workspace://SpacesStore/<uuid>`) |

A UUID or node reference is resolved to a DBID **inside the trackers service**, by
querying the indexed `LID` field — no round trip to the repository REST API. A bare
UUID is looked up in `workspace://SpacesStore/` then `archive://SpacesStore/`.

`ref` must match one of three shapes exactly: `\d{1,18}`, a UUID, or
`<protocol>://<store>/<uuid>` where the protocol and store are limited to
`[A-Za-z0-9_-]` and `[A-Za-z0-9_.-]`. Anything else is `400`, and the value that does
match is still escaped before it reaches the query — it is a caller-supplied string
interpolated into a Lucene query, so both guards stay.

```json
{
  "reference": { "input": "15695", "dbid": 15695, "resolvedBy": "DBID" },
  "database":  { "status": "updated", "tx": 4711 },
  "cores": {
    "alfresco": { "state": "indexed", "docType": "Node", "indexTx": 4711, "aclId": 42, "docCount": 1 },
    "archive":  { "state": "absent",  "docType": null,   "indexTx": null, "aclId": null, "docCount": 0 }
  },
  "verdict": "indexed"
}
```

Per-core `state`, read from the document's `DOC_TYPE`, its `INTXID` and its
`HAS_INDEXING_ERROR` flag:

| `state` | Meaning |
|---------|---------|
| `indexed` | a `Node` document exists and its `INTXID` matches the database transaction |
| `stale` | a `Node` document exists but lags the database transaction |
| `error` | indexing failed. `docType: ErrorNode` means nothing was indexed at all and the document carries `EXCEPTIONMESSAGE`/`EXCEPTIONSTACK`; `docType: Node` means the node is indexed but a property could not be resolved (`HAS_INDEXING_ERROR:true`), which is what the RepairTracker retries |
| `orphan` | the core holds a `Node` document the repository no longer backs — deleted, or unknown to the database |
| `unindexed` | the core holds an `UnindexedNode` document — the tracker deliberately did not index it (`cm:isIndexed=false`) |
| `unverified` | nothing could be compared: the repository was unreachable, or this core did not answer |
| `absent` | the core holds no document for this node |

An `ErrorNode` decides the state when a core holds one alongside a `Node`
document: the indexer drops it on every successful attempt, so its presence means
the last attempt failed.

`database.status` is `updated`, `deleted`, `unknown` (the repository answered and
holds no such node) or `unreachable` (the lookup itself failed — `MetadataTracker`
encodes that as `dbTx <= -2`, while `-1` is the `unknown` case). `verdict` is the
**worst** state any core reports, by the precedence
`error > orphan > stale > unverified > unindexed > indexed`, falling back to
`missing`, or `unresolved` when `ref` matched no node. Taking the worst rather
than the best is deliberate: a core lagging behind must not be hidden by another
core that is up to date.

**Status codes.**

| Code | When | Body |
|------|------|------|
| `400` | `ref` does not match one of the three accepted shapes | the reason, as text |
| `404` | `ref` is well-formed but resolves to no node (`verdict: "unresolved"`) | the full status document |
| `200` | anything else, **including a node the index does not hold** (`verdict: "missing"`) | the full status document |

`missing` is an answer, not an absent resource: the node resolved and the repository
knows it, so "known to the database, absent from the index" is precisely the diagnosis
being asked for. `unresolved` means the reference itself matched nothing, which is what
`404` is for — and the body travels with it, so the code carries no information the
caller has to trade the diagnosis for.

Distinguishing "no such node" from "no such API" no longer relies on this endpoint's
status code: a client asks `GET /api/v1` for that, where a `404` means the service
predates the API. That is the whole reason the descriptor exists.

```bash
# By DBID, UUID or noderef — same answer
curl -s "http://localhost:8085/api/v1/index/node?ref=15695" | python3 -m json.tool
curl -s "http://localhost:8085/api/v1/index/node?ref=3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b" | python3 -m json.tool
curl -s "http://localhost:8085/api/v1/index/node?ref=workspace://SpacesStore/3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b" | python3 -m json.tool
```

### Why not `NODEREPORT`

`NODEREPORT` returns `Node DBID`, `dbTx`, `dbNodeStatus`, `indexedNodeDocCount`
and four fields that are **always `null`** — `indexLeafDoc`, `indexAuxDoc`,
`indexLeafTx`, `indexAuxTx`. Those are Solr 4 leaf/aux leftovers declared in
`org.alfresco.solr.NodeReport`; their setters are called nowhere in this fork
**nor in upstream Alfresco**, whose `SolrInformationServer.addCommonNodeReportInfo`
is identical to this fork's and sets `indexedNodeDocCount` alone.

A consumer reading `indexLeafDoc` therefore gets `null`, and one that coerces it
to `0` concludes "absent from the index" for *every* node. `NODEREPORT` is left
exactly as it is — changing it would break the compatibility it exists to
provide — and callers wanting a usable answer should use `/api/v1/index/node`.

## Index diagnostic job (`/api/v1/index/diagnostic`)

`REPORT` compares every core with the repository database and can run for minutes on a
large index. The diagnostic job runs the same comparison **once, server-side**, reports
its progress while it runs and keeps its last result in the index: every client sees the
same running job and the same last result, across a page reload or a restart of this
service. Advertised by `GET /api/v1` as `index.diagnostic` (since `1.2`). `REPORT` and
`/actuator/repairreport` are unchanged and still answer synchronously.

| Method | Path | Answer |
|---|---|---|
| `POST` | `/api/v1/index/diagnostic?user=<id>` | `202` + snapshot. Starts a job, or joins the running one: a second `POST` never starts another. `user` is recorded as `startedBy`. |
| `DELETE` | `/api/v1/index/diagnostic` | `202` + snapshot while a job runs; `409` + snapshot when nothing runs. |
| `GET` | `/api/v1/index/diagnostic` | `200` + snapshot. |
| `GET` | `/api/v1/index/diagnostic/stream` | SSE: one `state` event per change, the first one on connection. |

```json
{
  "state": "running",
  "startedAt": "2026-09-25T15:02:11Z",
  "startedBy": "admin",
  "finishedAt": null,
  "step": 3,
  "steps": 9,
  "cores": {
    "alfresco": { "phase": "acl.db", "current": 41200, "target": 98000 },
    "archive":  { "phase": "pending", "current": null, "target": null }
  },
  "result": null,
  "error": null
}
```

- `state` is `idle` (never run, nothing stored — `cores` is then `{}` and `step`/`steps`
  are `null`), `running`, `done`, `failed` or `cancelled`. `error` carries the reason of a
  `failed` job.
- Each core goes through four phases, in order: `metadata.db`, `metadata.index`, `acl.db`,
  `acl.index`, then `done`; a core not reached yet is `pending`. The repair report follows
  the last core. `steps` is `4 × cores + 1` and `step` the 1-based index of the current
  one.
- `current` / `target` measure the current phase only. In `*.db` phases, `current` is the
  highest transaction (or ACL change set) id read from the database and `target` the last
  indexed one, `lastIndexedTxId` / `lastIndexedChangeSetId`, read when the phase starts:
  transactions committed while the diagnostic runs are outside the walk. A tracker that
  has no state yet has no bound, and `target` then follows `current`. In `*.index` phases,
  `current` is the first id of the facet batch being compared and `target` the highest id
  the walk found. There is no overall percentage: `alfresco` dwarfs `archive`, so any
  weighted figure would mislead.
- `result` has the shape of pristy-core's `GET diagnostic` answer: `report` keyed by core
  (the `REPORT` section of that core, with `error` when its report threw), `errorNodes`
  (the `/actuator/repairreport` content) and `partialFailures` (`["errorNodes"]` when the
  repair report could not be read). While a job runs, and after one fails or is cancelled,
  `result` is the **previous** successful result.
- Cores are the registered ones in name order, or the configured collections before the
  trackers have started.

### Cancelling

`DELETE` raises a flag the job checks between two batches: it stops at the next batch,
not instantly, and ends `cancelled`. A cancelled or failed job writes nothing, so the
stored result stays the last successful one.

### The stream

While a job runs, the stream sends at most one `state` event per 500 ms, the latest one;
any other state (`done`, `failed`, `cancelled`) is sent at once. A `:keepalive` comment
goes out every 30 s so an idle proxy keeps the connection open, even when no job runs.
Every event and keepalive is written by one dedicated thread, `diagnostic-stream`: the job
only flags that its state changed and never waits on a client, so a stalled connection
cannot slow the diagnostic down. It can still delay the events of the other connections
until its write fails and the broadcaster drops it.
The response carries `X-Accel-Buffering: no` and `Cache-Control: no-store`; configure an
nginx location as for `/api/v1/progress/stream` (see `indexing-progress.md`).

### Where the result is kept

Each successful job writes one document per core, id `DIAGNOSTIC!LAST`, `DOC_TYPE:
Diagnostic`, overwritten by the next success. It carries that core's `report` section,
the repair report, `partialFailures`, `startedAt`, `startedBy` and `finishedAt`, as JSON in
the stored-only dynamic field `text@s_stored___c__@diagnostic` (`localePrefixedField`,
`indexed="false"`, no `copyField`). That field exists in every Alfresco-derived schema, so
the document needs no schema change and works on cores created by older images, whose
`solrhome` volume keeps its original schema.

- The job never commits: the document becomes durable with the core's next tracker commit,
  like the `TRACKER!STATE` document. A commit issued by the job would also commit a
  tracker batch that `CommitTracker` may still roll back; the price is that a rollback
  happening before that commit drops the document (the result stays in memory until the
  next restart).
- At startup the service reads the documents back through real-time get (`/get`), which
  sees uncommitted documents too, and answers `done` with that result. When the cores hold
  documents of different runs (a core added since, or one whose write failed), only the
  run with the latest `finishedAt` is restored. A document this version cannot read is
  skipped with a warning.

### It moves no count

The document has no `TXID`, `ACLTXID`, `DBID`, `INTXID` or cascade flag, and a
`DOC_TYPE` no report reads: the `DOC_TYPE` facet of `SUMMARY`/`REPORT` looks counts up by
key, `setDuplicates` filters on a node `DOC_TYPE`, and every query that does not filter on
`DOC_TYPE` targets a field the document lacks. `DiagnosticDocumentNeutralityTest` runs the
reports before and after storing it twice and requires identical figures. The one number
it moves is Solr's own `STATUS` `numDocs`/`maxDoc`, by one per core, once — exactly as
`TRACKER!STATE` does.

### Configuration

Under `alfresco.tracker.diagnostic`, defaults in `TrackerProperties.DiagnosticConfig`.

| Key | Default | Effect |
|---|---|---|
| `min-event-interval-millis` | `500` | minimum interval between two running events on the stream |
| `keepalive-millis` | `30000` | interval of the `:keepalive` comment |
| `stream-timeout-millis` | `0` | SSE connection timeout; `0` means none |

```bash
curl -s -X POST "http://localhost:8085/api/v1/index/diagnostic?user=admin" | python3 -m json.tool
curl -s "http://localhost:8085/api/v1/index/diagnostic" | python3 -m json.tool
curl -N "http://localhost:8085/api/v1/index/diagnostic/stream"
curl -s -X DELETE "http://localhost:8085/api/v1/index/diagnostic" | python3 -m json.tool
```

## Waiting for nodes to become searchable (`POST /api/v1/index/await`)

A change in the repository reaches a search only once the metadata tracker indexed it and a
commit opened a searcher over it: about 11 s in the worst case with the shipped crons. This
endpoint holds a stream open until the given nodes are searchable, so a client waits for the
real answer instead of polling. Advertised by `GET /api/v1` as `index.await` (since `1.2`);
`enabled` follows `alfresco.tracker.await.enabled`, and the endpoint is not registered when it
is `false`.

```json
{ "dbids": [1234, 1235], "timeout": 20000 }
```

- `dbids`: positive DBIDs, duplicates collapsed, at most `max-batch` (1000) distinct ones.
- `timeout`: milliseconds, optional; missing means `min(20000, max-timeout)`, a larger value is
  capped at `max-timeout` (30000), not refused.

| Status | Body | When |
|---|---|---|
| `200` | `text/event-stream` | the request is accepted |
| `400` | `text/plain` reason | unreadable body, `dbids` missing or empty, a DBID not positive, more than `max-batch` DBIDs, `timeout` not positive |
| `503` | `text/plain` reason | the DBIDs would exceed `max-waiters`, or no tracked core indexes `workspace://SpacesStore` |

The endpoint never answers `404`. The stream carries `Cache-Control: no-store` and
`X-Accel-Buffering: no`, and starts with an SSE comment, `:open`, sent as soon as the request
is accepted: Spring MVC would otherwise hold the status line and headers until the first
event, and a caller would block on them. Each `data` is one line of JSON:

| Event | Data | Sent when |
|---|---|---|
| `searchable` | `{"dbid":1234}` | a `/query` request on the open searcher returns a `Node` document with `INTXID` at least the node's transaction in the repository, `HAS_INDEXING_ERROR` or not (one property failed, the node is searchable) |
| `error` | `{"dbid":1234,"verdict":"ERROR"}` | the node will not become searchable: `ERROR` (an `ErrorNode`), `UNINDEXED` (an `UnindexedNode`, `cm:isIndexed=false`), `ORPHAN` (no live node of that DBID in `workspace://SpacesStore`), `UNREACHABLE` (the repository could not be read); verdicts are upper-case enum names |
| `end` | `{"pending":[1235]}` | every DBID got its event (`pending` is `[]`), or the timeout expired; the stream then completes |

Each DBID gets at most one `searchable` or `error`, and a node already searchable gets its
event at once.

### How the wait is shortened

1. The service reads each node's transaction from the repository (`/api/solr/nodes`, one
   range request per 2000 consecutive DBIDs) and checks the index at once.
2. For the nodes still pending it fires the core's `MetadataTracker` job once through Quartz,
   `lag` (1000 ms) after the request, so the cycle does not defer the transaction as too
   recent. A trigger fired during a running cycle waits for it to end. While such a trigger is
   pending, later requests only register their nodes; one more cycle follows when such a request
   came after the cycle's cutoff (its start minus `lag`) and nodes are still pending. Fifty
   concurrent requests cost at most two extra cycles. Shortening the wait through `lag` assumes
   the trackers' and the repository's clocks are in sync (NTP).
3. When that cycle ends, the `CommitTracker` job is fired once. Its own guard still applies: no
   commit within `commit-interval` (2000 ms) of the previous one, and the next scheduled commit
   then does it.
4. After every commit of the core, the pending DBIDs are queried on the open searcher, 500 per
   request. Most commits return before their searcher opens (`waitSearcher` is only set once
   per `new-searcher-interval`), so nodes still pending are checked once more a second later,
   and again after each following commit.

Waiters live on the core tracking `workspace://SpacesStore` (`alfresco` in the shipped
configuration). A client that disconnects frees its waiters at the next event or at the request's
timeout, since Tomcat notices a disconnect only on write. The
connection itself is closed at the accepted wait plus 10 s. The events of every stream are
written by one sender thread: a client that stays connected without reading delays the other
streams until its write fails.

### Configuration

Under `alfresco.tracker.await`, defaults in `TrackerProperties.AwaitConfig`.

| Key | Default | Effect |
|---|---|---|
| `enabled` | `true` | registers the endpoint and turns the `index.await` capability on |
| `max-timeout` | `30000` | upper bound of `timeout`, in ms |
| `max-batch` | `1000` | most distinct DBIDs in one request |
| `max-waiters` | `10000` | nodes awaited at once across every request; beyond this, `503` |

```bash
curl -N -X POST -H 'Content-Type: application/json' \
  -d '{"dbids":[1234,1235],"timeout":20000}' \
  "http://localhost:8085/api/v1/index/await"
```

## Maintenance (mutating, `POST`)

| Endpoint | Solr-compat `action` | Params | Effect |
|----------|----------------------|--------|--------|
| `/api/admin/reindex` | `REINDEX` | `nodeid?`, `txid?`, `acltxid?`, `aclid?`, `query?`, `core?` | Schedules reindexing of the given node / transaction / acl-changeset / acl, or every node matching an **AFTS `query`**. Purges then re-fetches from the repo. |
| `/api/admin/index` | `INDEX` | `nodeid?`, `txid?`, `acltxid?`, `aclid?`, `core?` | Indexes a node/tx/acl that was **never** indexed (no purge step). |
| `/api/admin/purge` | `PURGE` | `nodeid?`, `txid?`, `acltxid?`, `aclid?`, `core?` | Removes the given item(s) from the index. |
| `/api/admin/retry` | `RETRY` | `core?` | Re-schedules **all recorded error nodes** (`HAS_INDEXING_ERROR`) for reindexing. |
| `/api/admin/backup` | `BACKUP` | `core?`, `location?`, `numberToKeep?` | Triggers a Solr backup of the core(s) via the ReplicationHandler. `location`/`numberToKeep` omitted → the configured per-core values. |
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

# Restore from an explicit location: pass the PARENT directory (the same value
# as the backup 'location'), NOT the per-core subdirectory. The service appends
# /<core> itself — the snapshot below is read from /mnt/adhoc/alfresco.
curl -s -X POST "http://localhost:8085/api/admin/restore?core=alfresco&location=/mnt/adhoc"
```

For both `backup` and `restore`, `location` is the **parent** directory: the
service appends `/<core>` itself, so each core ends up under `<location>/<core>`
(e.g. `location=/backup/solr` → snapshots of the `alfresco` core in
`/backup/solr/alfresco`). Never pass the per-core subdirectory as `location` —
`location=/backup/solr/alfresco` would be resolved to
`/backup/solr/alfresco/alfresco`. The backup runs on Solr's own filesystem
through the ReplicationHandler, so the `location` **must** be inside Solr's
`solr.allowPaths`. After a `restore`, the core swaps in the restored index and
the tracker resumes from the last transaction present in that index — only the
delta since the backup is re-indexed, not the whole repository.

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

A failed node is recorded in one of two ways, and the **RepairTracker** reads both:

| Trace | Written when | What the index holds |
|-------|--------------|----------------------|
| `HAS_INDEXING_ERROR:true` on the node document | the document was built and written, but a property could not be resolved | the node is indexed and searchable, minus that property |
| an `ErrorNode` document (`id = ERROR-<dbid>`) | building or fetching the node threw — including a type the repository reports as unindexable (`… is not registered in DictionaryService`), which fails the batch metadata call and then the per-node retry — or Solr answered `400`, refusing the document itself | nothing of the node at all; the error document carries `EXCEPTIONMESSAGE` and `EXCEPTIONSTACK` |

**An unreachable Solr is not an error node.** The two are decided apart on the write path:
a `400` means Solr will never accept this document, so retrying is pointless and the node
is recorded as an error node rather than stalling the core; anything else — a refused
connection, a timeout, a `5xx` — reaches the tracker, whose `onFail` raises the rollback
flag that `CommitTracker` turns into `infoSrv.rollback()`, and `continueState()` then
leaves the transaction cursor where it was. That is what makes the nodes get written on a
later cycle instead of leaving a permanent hole: before, every write failure was a `WARN`
and the cursor moved on regardless, so a few seconds of Solr downtime lost those nodes for
good, with nothing in `REPORT` to show it.

Inspect both with:

```bash
curl -s "http://localhost:8085/actuator/repairreport" | python3 -m json.tool
```

`POST /api/admin/retry` re-queues those error nodes. See
[tracker-configuration.md](tracker-configuration.md) for the repair cron and
`repair-max-retries`.

Two things follow from the second form having no node document:

- **Nothing is cleared on success.** Repairing it means re-indexing the node, and the
  indexer deletes the error document itself at the start of that attempt. The flag is
  never cleared for such a node — an atomic update on a document that does not exist
  would *create* one, holding nothing but an id and a flag.
- **Giving up is remembered in memory only.** An error document is not removed when
  `repair-max-retries` runs out, so the RepairTracker keeps a list of the nodes it has
  given up on (`permanentlyFailed` in the report) to stop retrying them every cycle.
  Restarting the trackers clears that list and retries everything, which is exactly
  what makes such a node repairable once a missing content model finally loads.

A node still in error after the retries therefore stays visible in `report`,
`repairreport` and `GET /api/v1/index/node` — the previous behaviour, where an
unindexable node left nothing but a `Failed to index node <id> — skipping` line in the
log, is gone. Fixing the underlying cause (re-adding the type as deprecated, or deleting
the orphaned nodes in the repository) then takes effect on the next restart or reindex.

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
