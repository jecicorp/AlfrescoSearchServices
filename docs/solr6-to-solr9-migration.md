# Migrating Alfresco Search Services from Solr 6 to Solr 9

This document describes how this fork of **Alfresco Search Services** was migrated
from the original Alfresco-patched **Solr 6.6.5** baseline to vanilla **Solr 9.10.1 /
Lucene 9.12.3**, in the context of **Alfresco Community Edition**. It is meant both as
a record of what changed and as a guide for anyone maintaining the search tier or
reproducing the upgrade.

> **Re-indexing is mandatory.** A full re-index is required after this upgrade.
> Lucene 9 cannot read a Lucene 6 (or even Lucene 8) index, and several Alfresco
> field encodings changed (stored fields, docvalues types, ancestor-path fields).
> Point the new Solr at empty cores and let the trackers rebuild from the repository.

## Why two hops, not one

Lucene only guarantees that it can read an index written by the **immediately
previous** major version. There is no supported jump from Lucene 6 to Lucene 9, and
the API surface changed too much to migrate the code in a single step. The work was
therefore done in two phases, each ending on a buildable, runnable state:

```
Solr 6.6.5-patched.22  ──▶  Solr 8.11.4 (vanilla)  ──▶  Solr 9.10.1
   Lucene 6.6.x              Lucene 8.11.x               Lucene 9.12.3
```

A second, structural change accompanied the upgrade: the **indexing trackers were
externalized** into a standalone Spring Boot service (`alfresco-indexing-trackers`)
instead of running inside the Solr webapp. Several migration fixes only make sense in
that light (model loading, commit cadence, cascade tracking) — see
[`tracker-configuration.md`](tracker-configuration.md).

## Version reference

| Component        | Before (Solr 6) | Intermediate (Solr 8) | After (Solr 9) |
|------------------|-----------------|-----------------------|----------------|
| Solr             | 6.6.5-patched.22 | 8.11.4 (vanilla)     | **9.10.1**     |
| Lucene           | 6.6.x           | 8.11.x                | **9.12.3**     |
| ZooKeeper        | 3.4.14          | 3.6.3                 | 3.6.3          |
| Java             | 11              | 17                    | **17**         |
| Servlet API      | javax.servlet   | javax.servlet         | javax.servlet (4.0.1) |
| Logging          | Log4j 1.x / reload4j | Log4j 2          | Log4j 2        |

> **No Jakarta migration yet.** Solr 9 still runs on Jetty 10 and the `javax.servlet`
> API; the move to `jakarta.servlet` only happens in Solr 10. The servlet API is
> pinned to `4.0.1`.

---

## Phase 1 — Solr 6.6.5 → Solr 8.11.4

The goal of this phase was to leave the Alfresco-patched Solr 6 distribution behind
and run on a **vanilla** Apache Solr 8, pulled straight from the Apache archive
instead of the Alfresco Nexus patched build.

### Dependencies & build

- `solr.base.version` `6.6.5` → `8.11.4`, patched suffix dropped.
- `solr.zip` now points at the Apache archive (no Alfresco patches).
- ZooKeeper `3.4.14` → `3.6.3`.
- Removed `solr-clustering` (not published for Solr 8).
- Removed `slf4j-reload4j` — Solr 8 uses **Log4j 2** natively.
- Removed the **`alfresco-xmlfactory`** dependency: it disabled XInclude in
  `schema.xml`, which prevented `generated_copy_fields.xml` from loading and broke the
  stored-field → indexing-field `copyField` directives. Solr 8 has built-in XXE
  protection, so the library is no longer needed.

### Lucene 6 → 8 API migration (~60 files)

The bulk of the code change. The notable signature/behaviour changes:

| Lucene 6 | Lucene 8 |
|----------|----------|
| `createWeight(IndexSearcher, boolean)` | `createWeight(IndexSearcher, ScoreMode, float)` |
| `LegacyNumericRangeQuery` | `LongPoint.newRangeQuery` |
| `NumericDocValues.get(int)` | `advanceExact(int)` + `longValue()` |
| `BinaryDocValues.get(int)` | `advanceExact(int)` + `binaryValue()` |
| `getSlowAtomicReader()` | iterate `LeafReaderContext` leaves |
| `Collector.needsScores()` | `scoreMode()` |
| `setScorer(Scorer)` | `setScorer(Scorable)` |
| `totalHits` (`long`) | `TotalHits` object (`.value`) |
| `HttpSolrClient` ctor | `HttpSolrClient.Builder` |
| `NodeConfigBuilder(SolrResourceLoader)` | `NodeConfigBuilder(Path)` |

Other notable points:
- `Scorer`: add `getMaxScore()`, drop `freq()`.
- `Weight`: add `extractTerms()` / `isCacheable()`, drop `normalize()`.
- `ConstantScoreWeight`/`Scorer`: now take `ScoreMode` and `boost`.
- `TokenStreamComponents` became `final` → **`MLAnalayser` rewritten** to initialize
  its delegate `TokenStream` in `reset()` instead of `initReader()`.
- `DocValuesCache` rewritten for the iterator-based `NumericDocValues`.

### Schema & solrconfig (Solr 8)

- **`mergePolicy` → `mergePolicyFactory`** (the old element was removed in Solr 7).
- Removed the clustering component and handler (`solr-clustering` absent from Solr 8).
- **Inlined the stored-field `dynamicField` and `copyField` definitions** into
  `schema.xml` (replacing the XInclude of `generated_copy_fields.xml`). XInclude is
  not supported in a Solr schema. All 75 stored-field patterns (`text@s`, `text@m`,
  `mltext@m`, `content@s`) and their flag combinations (tokenised, untokenised,
  cross-locale, sort, suggest) are now declared directly, with the matching
  `copyField` directives to populate the indexing fields.
- Added `_stored_` dynamic fields for `text`, `mltext` and `content` (stored fields
  enable highlighting), previously generated but missing from the schema.

### Lucene 8 stricter offset enforcement

Lucene 8 rejects **backwards token offsets**, which Lucene 6 tolerated. Three filters
had to clamp offsets to be non-decreasing:

- `PathTokenFilter` — `PATH` tokens emitted offset `0,0` after higher-offset tokens.
- `MLTokenDuplicator` — locale-prefixed duplicates with `positionIncrement=0`.
- `MonotonicOffsetFilter` (added in `AlfrescoAnalyzerWrapper.wrapComponents()`) — the
  definitive fix: because `getWrappedAnalyzer()` builds a new `MLAnalayser` per call,
  per-tokenizer offset state is lost between invocations; a wrapper-level filter
  persists across the whole token stream and catches every backwards offset.

### Solr 8 runtime / security

- Since Solr 8.6, **`-Dsolr.allowPaths` is only honoured when declared in `solr.xml`**.
  The inherited Solr 6 `solr.xml` lacked it, so core-admin actions failed with a
  path-not-allowed error. Added to `solr.xml` (and `solr.allowPaths` set in the
  Dockerfile).
- `getCoreProperties()` returns `null` in Solr 8 → use
  `getCore().getCoreDescriptor().getCoreProperty()` (auth queries/scorers).
- `SolrCachingPathQuery.createWeight` signature fixed Lucene 6 → 8.
- Dockerfile: add `procps-ng` (the Solr 8 `bin/solr` script needs `ps`).
- Trackers POM: exclude Jetty from SolrJ (conflicts with Spring Boot 3.4 / Jetty 12).

### Alfresco-specific indexing fixes uncovered in Solr 8

- **`SolrDocumentMapper`**: restore the `APATH`/`ANAME` ancestor-path fields that
  upstream `updatePathRelatedFields()` produced (depth-prefixed `APATH` values
  `0/a`, `1/a/b`, `F/a/b/c` plus suffix-based `ANAME`), otherwise APATH facet
  drill-down was broken.
- **Highlighting**: `DefaultSolrHighlighter` pre-fetched documents by field name, but
  Alfresco field names (e.g. `content@s_stored_t____@{ns}content`) contain `@` and
  `{}` that the field-list parser cannot handle. Override
  `getDocPrefetchFieldNames()` to return `null`.
- **AFTS parse errors**: a malformed query threw `FTSQueryException` (a
  `RuntimeException`) that bypassed the `ParseException` catch and surfaced as HTTP
  500. Caught in `AlfrescoFTSQParser.parse()` and wrapped in `SyntaxError` → HTTP 400.
- `BitsFilter` replaced with `BitSetQuery`; custom shard handlers removed.

---

## Phase 2 — Solr 8.11.4 → Solr 9.10.1

The Lucene 9 / Solr 9 step is more about **removed APIs and changed defaults** than
about volume of code.

### Dependencies & build

- `solr.base.version` `9.10.1`; **`lucene.version` decoupled** at `9.12.3`.
- `lucene-analyzers-common` → **`lucene-analysis-common`**.
- Added **`lucene-queries`** (the spans module moved here).
- Distribution URL is now a `.tgz` under `dist/solr/solr` (same layout otherwise, so
  the packaging assembly itself needed no change).

### Lucene 9 / Solr 9 package moves

- `org.apache.lucene.analysis.util.*Factory` → `org.apache.lucene.analysis.*`
- `org.apache.lucene.search.spans.*` → `org.apache.lucene.queries.spans.*`
- `org.apache.lucene.util.LuceneTestCase` →
  `org.apache.lucene.tests.util.LuceneTestCase` (test framework repackaged)
- `JettyConfig` / `JettySolrRunner` moved out of SolrJ into
  `org.apache.solr.embedded`
- `org.apache.solr.common.util.Base64` → `java.util.Base64`

### Removed / changed APIs

- **`Query.visit(QueryVisitor)`** must be implemented on every custom query.
  ACL/path filter queries (`AbstractAuthorityQuery`, `AbstractAuthoritySetQuery`,
  `BitSetQuery`, `SolrCachingPathQuery`, `SolrPathQuery`) report `visitLeaf`
  (no scoring terms); wrappers (`ContextAwareQuery`, `PostFilterQuery`,
  `ReRankQuery`) delegate to the wrapped query.
- **`Weight.extractTerms`** removed → empty overrides dropped.
- **`DocSet` is immutable** in Solr 9: scorers build a `FixedBitSet` then wrap it in
  `BitDocSet` (replacing `DocSet.add` / `BitDocSet.addUnique`); `SolrCachingPathQuery`
  uses `DocSet.makeQuery()` instead of the removed `getTopFilter()`.
- `SolrResourceLoader.locateSolrHome()` removed → resolve via the `solr.solr.home`
  system property; the `(Path, ClassLoader, Properties)` ctor dropped → 1-arg.
- `SolrQueryTimeoutImpl` removed — `timeAllowed` is enforced centrally via
  `QueryLimits`; the manual set/reset in `AlfrescoSearchHandler` was dropped.
- `PermissionNameProvider` is now abstract → implement `getPermissionName()` on the
  three request handlers.
- `FieldComparatorSource.newComparator(...)` gained a `Pruning` parameter.
- `DelegatingCollector.finish()` is now `final` → override `complete()` instead.
- `SolrSuggester.getStoreFile()` returns `Path`; `reload()` is now no-arg.
- `SecretSharedAuthPlugin.doAuthenticate` now takes `HttpServletRequest/Response`.
- `TermStates.build` / `termStatistics` signatures changed (the discarded stats
  priming in `AbstractAuthorityQueryWeight` was removed).

### Schema & solrconfig (Solr 9)

- **`ICUNormalizer2FilterFactory`**: the `name` parameter was renamed to **`form`**
  (in Lucene 9, `name` on a `<filter>` is reserved for SPI lookup).
- **Cache implementations removed**: `solr.LRUCache` / `solr.FastLRUCache` →
  **`solr.CaffeineCache`** for every cache (filter / queryResult / document /
  fieldValue / …).
- Removed the `XSLTResponseWriter` from `solrconfig` (removed from `solr-core`).
- Dropped the `solrconfig_insight.xml` XInclude (Insight Engine removed).

### Sort comparators — docvalues type became strict

`AlfrescoCollatableTextFieldType` and `AlfrescoCollatableMLTextFieldType` extend
`StrField`, which produces **`SORTED`** docvalues. Their custom sort comparators read
them as `BINARY` (`DocValues.getBinary` / `BinaryDocValues.binaryValue()`). Lucene 8
tolerated this; **Lucene 9 enforces the exact type** and throws:

```
IllegalStateException: unexpected docvalues type SORTED for field
  text@s__sort@{...}name (expected=BINARY)
```

Fix: read `SortedDocValues` instead — `DocValues.getSorted(...)` and
`docTerms.lookupOrd(docTerms.ordValue())`. The locale-prefix parsing is unchanged.
The unit tests were updated to mock `SortedDocValues` and stub `ordValue()` +
`lookupOrd(ord)` (the `@InjectMocks` mock injects by field type, so the old
`BinaryDocValues` mock was silently no longer wired in → NPE).

### Highlighting

Force **`hl.method=original`** so the Alfresco field-mapping highlighter is used
(rather than the Solr 9 default unified highlighter, which does not understand the
Alfresco field naming).

### Runtime defaults changed in Solr 9 (set in `solr.in.sh` via the Dockerfile)

Solr 9 ships with several **secure-by-default** settings that break the Alfresco
deployment and must be overridden:

| Setting | Why |
|---------|-----|
| `SOLR_MODULES=analysis-extras` | Solr 9 moved ICU analysis (`ICUTokenizerFactory`, `ICUNormalizer2FilterFactory`, used by the Alfresco schema) into a module that is **not** on the default classpath. |
| `SOLR_JETTY_HOST=0.0.0.0` | Solr 9 binds to `127.0.0.1` by default, leaving the container unreachable. |
| `SOLR_SECURITY_MANAGER_ENABLED=false` | Solr 9 enables the Java `SecurityManager` by default, which blocks the Alfresco plugins' file/network access. |

Also: the Solr log4j config was converted from **Log4j 1.x to Log4j 2** format.

---

## Test harness notes (embedded integration tests)

The embedded IT harness needed several adjustments to boot a Solr 9 core:

- Test Solr home must be **absolute** (Solr 9 asserts `instanceDir.isAbsolute()`).
- Force `NRTCachingDirectoryFactory` for tests — `RAMDirectoryFactory` needs the
  single lock and the mock FS factory needs a `RandomizedRunner` context the harness
  lacks.
- Add `jetty http2-client` / `http2-http-client-transport` (test scope) — the Solr 9
  embedded harness uses `Http2SolrClient`; the Jetty version is pinned to `10.0.26`.
- **`loadBootstrapModels()`**: since the trackers were externalized,
  `AlfrescoSolrDataModel` no longer loads any model on startup (the separate
  `ModelTracker` pushes them via `putModel()`). Embedded tests have no tracker, so
  they load the bootstrap models (`dictionary → system → content → cmis`) directly
  from test resources.
- **JaCoCo**: exclude `org/alfresco/repo/search/impl/parsers/**`. The ANTLR-generated
  `FTSParser` DFA methods exceed the JVM 64 KB method limit once instrumented
  (`MethodTooLargeException`), which broke query parsing under coverage.

## Tracker timing (post-externalization)

The externalized trackers no longer read `solrcore.properties`, so they started on
slow built-in defaults and exceeded test retry windows. Restore the upstream-equivalent
cadence (see [`tracker-configuration.md`](tracker-configuration.md) for full tuning):

- model cron `10s`, commit cron `5s`, `commitInterval 2000ms`, `newSearcherInterval 3000ms`.

Cascade re-indexing of descendants on rename/move was also repaired: query the
`long@s_@{sys}cascadeTx` field (not the `int@s_@cascade` flag) and set `maxResults(1)`
on the metadata lookup.

### Resource-bounded side-by-side rebuild

Solr 6 may remain active for production searches while a separate Solr 9 instance
builds new, empty cores in the background. Limit the standalone tracker container
with Docker CPU/memory controls and bound each content cycle with
`alfresco.tracker.content.batch-size`, `max-parallelism`, and
`max-documents-per-cycle`. Completed documents remain indexed; the remaining
outdated documents are picked up by later cron executions, including after a
tracker restart. See the
[resource-bounded migration example](tracker-configuration.md#resource-bounded-side-by-side-solr-6-to-solr-9-migration).

---

## Upgrade checklist

1. Build the distribution and image: `mise run install` then `mise run e2e:build-image`
   (or `mise run e2e:rebuild` for the full e2e stack).
2. Deploy Solr 9 with the runtime overrides above (the Dockerfile already sets them).
3. Start with **empty cores** — do not try to reuse Lucene 6/8 index data.
4. Start the externalized trackers and let them rebuild the index from the repository.
5. Verify: facet drill-down (APATH), highlighting (`hl.method=original`), and sorting
   on `text`/`mltext` fields (the `SORTED` docvalues fix).

## References

These commits implement the migration (oldest → newest):

- `3650ea27e` feat: migrate from Solr 6.6.5 to Solr 8.11.4 (vanilla)
- `b48a712bb` … `be057c73b` Solr 8 runtime, schema, analysis and indexing fixes
- `2a0b7b6e7` wip: begin Solr 9.10.1 migration (version bump, package moves)
- `3f3ef3b88` feat: make alfresco-search compile against Solr 9.10.1 / Lucene 9
- `f7b2cf0fa` feat: migrate Solr schema/solrconfig so the core boots under Solr 9
- `f51657530` feat: configure Solr 9 runtime in the search image
- `36c7210bd` / `2737b3cc6` solrconfig cleanups (XSLTResponseWriter, Insight)
- `50fa2ad57` / `16419b746` SORTED docvalues sort comparators (+ tests)
- `1e1a2161a` force `hl.method=original`
