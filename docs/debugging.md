# Debugging Guide

## ACL Deny Filtering (`processedDenies`)

Solr responses include a `processedDenies` boolean flag. When `true`, ACL deny
filtering was applied (documents denied to the user are excluded). When `false`,
no deny filtering was applied for that request.

`processedDenies: false` is **normal** for internal Solr requests (health checks,
admin console queries) that don't go through the Alfresco Repository. For search
requests originating from the Alfresco UI, the flag should always be `true`.

### Enabling diagnostic logs

Uncomment the following lines in `search-services/packaging/src/main/resources/logs/log4j.properties`:

```properties
log4j.logger.org.alfresco.solr.query.AbstractQParser=TRACE
log4j.logger.org.alfresco.solr.component.SetProcessedDeniesComponent=TRACE
```

Then rebuild and redeploy. Search for `[ACL-DIAG]` in `solr.log`:

```bash
grep ACL-DIAG /opt/solr/server/logs/solr.log
```

### What the logs tell you

| Log message | Meaning |
|-------------|---------|
| `getString()='AUTHORITY_FILTER_FROM_JSON', json from context=present` | Alfresco sent the authority filter correctly |
| `processedDenies set to TRUE` | Deny clause was built and applied |
| `getString()='*', json from context=null` | Internal Solr query (no authority filter expected) |
| `JSONException during authority filter parsing` | JSON from Alfresco is malformed or missing expected keys |
| `anyDenyDenies=false in JSON` | Alfresco explicitly disabled deny filtering |
| `AUTHORITY_FILTER_FROM_JSON matched but authQuery is empty` | JSON was parsed but contained no authorities |

## Tracker startup issues

### The trackers re-index everything on every restart

**Symptom:** after restarting the trackers service, the logs show a full re-track
from the very first transaction (`Found N transactions after lastTxCommitTime <epoch>`,
walking from transaction `id=1`), preceded by:

```
MetadataTracker : No transactions found - no verification required
```

even though the Solr index is **not** empty (content and models are present).

**Cause:** the tracker resume point used to be read from dedicated state documents
queried by id `TRACKER!STATE!TX` / `TRACKER!STATE!ACLTX`, but the only writer persisted
a single document with id `TRACKER!STATE`. The ids never matched, so
`SolrJQueryService.getTrackerInitialState()` always returned `lastIndexedTxCommitTime = 0`,
which `MetadataTracker.checkRepoAndIndexConsistency()` interprets as an empty index and
re-tracks from the beginning.

**Fix:** the resume point is now derived directly from the transaction / ACL-changeset
documents actually in the index — `getTrackerInitialState()` reads the most recent
`DOC_TYPE:Tx` (max `S_TXCOMMITTIME`) and `DOC_TYPE:AclTx` (max `ACLTXCOMMITTIME`) document
(`SolrJQueryService.topDocByField`). This is self-healing: it reflects what is indexed
even if a state-doc write was missed.

**If you still see it:** the running `solr9-trackers` image predates the fix. Rebuild and
redeploy the trackers image — a plain container restart re-runs the old code.

### `NamespaceException: Namespace prefix '<x>' is not mapped to a namespace URI` at startup

**Symptom:** a stack trace logged by `ModelTracker` right after
`ModelTracker: ensuring first model sync`, e.g. for the `pk` (pristy-kafka) prefix:

```
ERROR ... ModelTracker : Model tracking failed for core: alfresco
org.alfresco.service.namespace.NamespaceException: Namespace prefix pk is not mapped ...
    at ... SOLRAPIClient.getModelsDiff(...)
```

**This is not a crash.** `ensureFirstModelSync()` catches and logs the exception, then
startup continues; the next scheduled `ModelTracker` run (cron `model`) succeeds.

**Cause:** the first model sync calls `getModelsDiff()`, which builds a `QName` for every
model already persisted in Solr (e.g. `pk:...`). The required namespace prefixes are
registered when the local dictionary is rehydrated from Solr — which previously ran
**after** the first sync. So the first sync after a restart could not resolve a non-built-in
prefix. Only restarts are affected (a first-ever boot has no persisted models to resolve).

**Fix:** the local dictionary is now rehydrated (registering namespaces) **before** the
first model sync in `TrackerBootstrap.initialise()`. As with the re-index issue above, if
the message persists the deployed trackers image predates the fix — rebuild and redeploy.
