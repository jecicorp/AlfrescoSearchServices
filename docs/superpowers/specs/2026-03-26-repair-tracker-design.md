# RepairTracker — Design Spec

## Problem

In the standalone tracker architecture, the fast trackers (MetadataTracker, ContentTracker, CascadeTracker) can encounter nodes they cannot index correctly. Examples:

- **UNRESOLVED_MODEL**: A custom model (e.g. `music:music`) is deployed dynamically but not yet loaded in the local dictionary when the MetadataTracker indexes nodes using it. Properties fall back to a generic `text@s__lt@` prefix — wrong field names, searches return empty.
- **EMPTY_NODE**: Repository returns a node with null/empty properties.
- **INVALID_ENCODING**: A metadata value contains non-UTF8 characters causing serialization failures.
- **MISSING_METADATA**: Repository returns no metadata for a DBID (deleted or inaccessible node).
- **CONTENT_EXTRACTION_FAILED**: ContentTracker fails to extract text content.
- **CASCADE_TIMEOUT**: CascadeTracker times out on deep/wide hierarchies (e.g. 200k folders).

Today, these errors are either silently degraded (wrong field prefix) or cause infinite retry loops. There is no unified error handling, no reporting, and no remediation path.

## Design

### Principle

Fast trackers must stay fast. When they encounter an anomaly, they **mark the node in error and move on**. A slow, dedicated **RepairTracker** picks up these nodes, diagnoses the problem, attempts remediation, and produces a report.

### Architecture: RepairTracker + pluggable strategies

```
MetadataTracker ──┐
ContentTracker  ──┤── mark HAS_INDEXING_ERROR:true ──→ Solr
CascadeTracker  ──┘
                                                        │
                          RepairTracker (slow cron) ◄───┘
                              │
                    ┌─────────┼──────────┐
                    ▼         ▼          ▼
          UnresolvedModel  EmptyNode   (future strategies)
                    │         │          │
                    ▼         ▼          ▼
              RepairReport (in-memory, exposed via Actuator)
```

## Solr Schema Change

Add to `schema.xml` (**both** rerank and noRerank templates):

```xml
<!-- Indexing error flag — set by trackers when a node cannot be indexed correctly.
     The RepairTracker queries this field to find nodes needing remediation. -->
<field name="HAS_INDEXING_ERROR" type="identifier" indexed="true" stored="true" default="false" />
```

- `indexed="true"`: RepairTracker queries `HAS_INDEXING_ERROR:true`
- `stored="true"`: value readable in returned documents
- `default="false"`: no impact on existing documents

## Error Marking

### SolrDocumentMapper (UNRESOLVED_MODEL)

In `toNodeDoc()`, the existing fallback block (line ~428) adds a tracking boolean:

```java
else
{
    LOGGER.warn("No property definition for {} — falling back", propQName);
    addPropertyValue(doc, propQName.toString(), value);
    hasUnresolvedProperty = true;
}
```

At the end of `toNodeDoc()`:

```java
if (hasUnresolvedProperty)
{
    doc.setField("HAS_INDEXING_ERROR", true);
}
```

No atomic update needed — the document is already being constructed.

### Other trackers (future)

Each tracker marks errors through the `InformationServer` abstraction:

```java
infoSrv.markIndexingError(dbId, tenant);
```

This method performs an atomic update on the `HAS_INDEXING_ERROR` field via `SolrJInformationServer`, keeping all Solr writes behind the same abstraction layer.

## RepairTracker

### Class hierarchy

`RepairTracker extends ActivatableTracker` in `org.alfresco.indexing.tracker`.

New `Tracker.Type.REPAIR` added to the enum.

### TrackerState

RepairTracker does not track repository transactions. It returns a minimal no-op `TrackerState` and `invalidateState()` is a no-op. It does not participate in the transaction-based consistency model.

### doTrack cycle

1. Acquire `MetadataTracker.getWriteLock()` to prevent concurrent writes on the same nodes
2. Query Solr: `HAS_INDEXING_ERROR:true AND DOC_TYPE:Node` (batch of 100)
3. For each doc, iterate registered `RepairStrategy` instances
4. First strategy returning `tryRepair(doc, docRef)` with a non-null result takes ownership
5. If repair succeeds: re-index the node, atomic update `HAS_INDEXING_ERROR → false`
6. If repair fails and `attempts < maxRetries` (default 10): node stays marked, cause recorded in `RepairReport`, retried next cycle
7. If repair fails and `attempts >= maxRetries`: mark as `PERMANENTLY_FAILED` in report, atomic update `HAS_INDEXING_ERROR → false` to stop retrying
8. If no strategy handles it: recorded as "UNKNOWN" in report
9. Release `MetadataTracker.getWriteLock()`
10. Issue a soft-commit to make changes visible

### Concurrency

RepairTracker acquires `MetadataTracker.getWriteLock()` before re-indexing to prevent race conditions where both trackers write the same node simultaneously. This ensures last-writer-wins conflicts cannot occur.

### Commit strategy

RepairTracker does **not** participate in the CommitTracker cycle. Instead, it issues its own soft-commit (`solrClient.commit(collection, false, false, true)`) after processing each batch. This is safe because:
- RepairTracker holds the MetadataTracker write lock during its batch
- Soft-commits only open a new searcher without flushing to disk
- The CommitTracker's periodic hard-commits will persist the changes

### Scheduling

- Cron property: `alfresco.repair.tracker.cron`
- Default: `0 0/1 * * * ?` (every 60 seconds — Quartz does not accept `0/60` in seconds)
- Added to `TrackerProperties.CronConfig` and `buildTrackerProperties()`

### Retry and backoff

- Max retry attempts per node: configurable via `alfresco.repair.tracker.maxRetries` (default: 10)
- After max retries, the node is marked `PERMANENTLY_FAILED` in the report and `HAS_INDEXING_ERROR` is cleared to stop retrying
- The attempt count is tracked in the in-memory `RepairReport` (resets on restart, but nodes in Solr are rediscovered and get a fresh counter)

## RepairStrategy Interface

Diagnosis and repair are combined in a single `tryRepair` method to avoid double repository fetches:

```java
public interface RepairStrategy
{
    /** Category identifier (e.g. "UNRESOLVED_MODEL") */
    String category();

    /**
     * Diagnose and attempt to repair the node in a single pass.
     * Returns a RepairResult if this strategy handles the error,
     * or null if this strategy does not apply to this node.
     */
    RepairResult tryRepair(SolrDocument doc, TenantDbId docRef);
}
```

### RepairResult

```java
public record RepairResult(long dbId, boolean success, String category, String message) {}
```

The `dbId` is included so the result is self-contained for report building.

## Package structure

All new repair classes go in `org.alfresco.indexing.tracker.repair`:
- `RepairTracker.java`
- `RepairStrategy.java`
- `RepairResult.java`
- `RepairReport.java`
- `UnresolvedModelStrategy.java`
- `EmptyNodeStrategy.java`

## V1 Strategies

### UnresolvedModelStrategy

- **tryRepair**: Fetch metadata from repository. Iterate properties, check if any property lacks a `PropertyDefinition` in the local dictionary. If unresolved properties exist but the model is now loaded → re-index the node via `infoSrv.indexNode()`, return `success`. If model still not loaded → return `success = false` with message describing the unresolved namespace. If all properties were already resolved (error was transient) → re-index anyway, return `success`. If this node's error is not model-related → return `null` (not handled).

### EmptyNodeStrategy

- **tryRepair**: Fetch metadata. If properties are null/empty, this strategy handles it. If node is legitimately empty in the repository, clear the error flag, return `success`. If node no longer exists in the repository, purge from Solr, return `success`. If properties are present → return `null` (not handled).

## REST Endpoint (Actuator)

### Endpoint

`GET /actuator/repair-report`

### Class

`RepairReportEndpoint` annotated `@Endpoint(id = "repair-report")` in `org.alfresco.indexing.config`. Receives a reference to `RepairTracker` to read the `RepairReport`.

### Response format

```json
{
  "lastCycleTimestamp": "2026-03-26T18:30:00Z",
  "totalErrorNodes": 42,
  "repairedThisCycle": 5,
  "pendingErrors": [
    {
      "dbId": 1088,
      "tenant": "",
      "category": "UNRESOLVED_MODEL",
      "message": "Model for namespace {music} still not loaded",
      "firstSeen": "2026-03-26T18:24:20Z",
      "attempts": 3
    }
  ],
  "recentRepairs": [
    {
      "dbId": 1092,
      "category": "UNRESOLVED_MODEL",
      "repairedAt": "2026-03-26T18:30:05Z",
      "message": "Re-indexed with resolved model music:music"
    }
  ],
  "summary": {
    "UNRESOLVED_MODEL": { "pending": 12, "repaired": 3 },
    "EMPTY_NODE": { "pending": 30, "repaired": 2 }
  }
}
```

### Retention

- `recentRepairs`: last 100 repairs (FIFO)
- `pendingErrors`: current state of nodes still in error (updated each cycle), capped at 500 entries. `totalErrorNodes` gives the real count.
- No disk persistence — report resets on tracker restart. Nodes in error remain marked in Solr and are rediscovered automatically.

## TrackerBootstrap Integration

Created after all other trackers:

```java
// 8. Create and schedule RepairTracker
List<RepairStrategy> strategies = List.of(
    new UnresolvedModelStrategy(repoClient, infoSrv, localDictionaryService),
    new EmptyNodeStrategy(repoClient, infoSrv)
);
RepairTracker repairTracker = new RepairTracker(
    trackerProps, repoClient, coreName, infoSrv, strategies, registry);
registry.register(coreName, repairTracker);
scheduler.schedule(repairTracker, coreName, trackerProps);
// Note: RepairTracker is NOT added to coreTrackers — it manages its own soft-commits
```

The `registry` reference is passed so RepairTracker can acquire `MetadataTracker.getWriteLock()` via `registry.getModelTracker()` / the registered trackers.

## Files to create/modify

### New files
- `RepairTracker.java` — the tracker
- `RepairStrategy.java` — strategy interface
- `RepairResult.java` — result record
- `RepairReport.java` — in-memory report model
- `UnresolvedModelStrategy.java` — V1 strategy
- `EmptyNodeStrategy.java` — V1 strategy
- `RepairReportEndpoint.java` — Actuator endpoint

### Modified files
- `Tracker.java` — add `REPAIR` to Type enum
- `SolrDocumentMapper.java` — add `hasUnresolvedProperty` flag + `HAS_INDEXING_ERROR` field
- `InformationServer.java` / `SolrJInformationServer.java` — add `markIndexingError(dbId, tenant)` method
- `TrackerBootstrap.java` — create and schedule RepairTracker
- `TrackerProperties.java` — add repair cron config + maxRetries config
- `schema.xml` (rerank **and** noRerank templates) — add `HAS_INDEXING_ERROR` field

## Known limitations

- **ActivatableTracker.isEnabled is static**: The `isEnabled` field in `ActivatableTracker` is `protected static AtomicBoolean`, meaning disabling any one `ActivatableTracker` disables all of them. This is a pre-existing bug, not introduced by RepairTracker, but worth noting. RepairTracker should not be disabled independently in production.
- **Retry counter resets on restart**: The attempt count for each node is stored in-memory. On tracker restart, nodes are rediscovered from Solr with a fresh counter. This means a node could exceed `maxRetries` across restarts. Acceptable for V1.

## Future extensions

- Additional strategies: `InvalidEncodingStrategy`, `CascadeTimeoutStrategy`, `ContentExtractionStrategy`
- Pristy/Alfresco UI consuming the `/actuator/repair-report` endpoint
- Manual retry action via `POST /actuator/repair-report/retry/{dbId}`
- Disk persistence of the report for cross-restart continuity
