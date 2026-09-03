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
externalized** into a standalone Spring Boot service (`pristy-indexing-trackers`)
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

### `Query.equals` must compare the class

Lucene 7.2 taught `BooleanQuery.rewrite` to short-circuit contradictions: if a query
appears both as `MUST`/`FILTER` and as `MUST_NOT`, the whole boolean is replaced by
`MatchNoDocsQuery("FILTER or MUST clause also in MUST_NOT")`. Lucene 6.6.5 had no such
rule, which is why the inherited ACL queries got away with an `equals`/`hashCode` that
compared only the authority string and ignored the concrete class:

```java
if (!(o instanceof AbstractAuthoritySetQuery)) return false;
return authorities.equals(that.authorities);        // hashCode() = authorities.hashCode()
```

The authority filter is built as `+(AUTHSET:<auths>) -(DENYSET:<auths>)` — the *same*
authority string on both sides — so `SolrAuthoritySetQuery` and `SolrDenySetQuery`
compared equal, and **every permission-filtered query returned zero documents**, with
status 200 and no exception. Fixed in `AbstractAuthoritySetQuery` and
`AbstractAuthorityQuery` with Lucene's own helpers, `sameClassAs(o)` in `equals` and
`classHash()` mixed into `hashCode`.

Only the in-filter path was affected: with `alfresco.postfilter=true` (the default,
per-core property) the filter is wrapped in a `PostFilterQuery` whose
`getFilterCollector` walks the query tree itself, so Lucene never rewrites the boolean.
`SolrAuthIT.testAuthInFilter` is the regression test; its sibling
`testAuthPostFilter` passed throughout.

### Solr 8 runtime / security

- Since Solr 8.6, **`-Dsolr.allowPaths` is only honoured when declared in `solr.xml`**.
  The inherited Solr 6 `solr.xml` lacked it, so core-admin actions failed with a
  path-not-allowed error. Added to `solr.xml` (and `solr.allowPaths` set in the
  Dockerfile).
- The shard URL allow-list (CVE-2017-3164) was renamed: `shardsWhitelist` →
  **`allowUrls`**, and `solr.disable.shardsWhitelist` → `solr.disable.allowUrls`.
  Like `allowPaths` it is read from `solr.xml` only (`AllowListUrlChecker.create(NodeConfig)`;
  nothing in `solr-core` reads the `solr.allowUrls` system property directly), so the
  placeholder had to be declared there too. Without it, any request carrying the `shards`
  parameter fails in standalone mode with *"solr.xml property 'allowUrls' not configured
  but required (in lieu of ZkController and ClusterState)"* — which affects sharded
  deployments, not just the distributed ITs. A sharded install must set
  `-Dsolr.allowUrls=host1:8983,host2:8983,…`; the checker keeps only `host:port`, so the
  core path in the URL is irrelevant.
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
- **Deprecated declarations Solr 9 ignores**, removed so the startup log stays readable:
  `<jmx />` (superseded by the `<metrics><reporter>` section of `solr.xml` since Solr 7)
  and the `enableRemoteStreaming` attribute of `<requestParsers>` (now driven only by the
  `solr.enableRemoteStreaming` system property, default `false`).
- **`luceneMatchVersion` is pinned to `9.12.3`** instead of `LATEST`. With `LATEST` the
  index-time analysis silently follows whatever Lucene the next Solr release embeds, which
  is exactly what back-compat is supposed to prevent. **Bump it together with the Solr
  version**, and only as a deliberate decision — it changes how text is indexed and queried,
  so it belongs with a re-index.

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
| `SOLR_MODE="${SOLR_MODE:-user-managed}"` | Not a Solr 9 default but a Solr **10** one: `bin/solr start` will switch to SolrCloud, and standalone (Solr's *user-managed*) will require `--user-managed`. Solr 9.10 already accepts the value, so setting it pins the mode and drops the forward-notice from every startup log. Written as a fallback: an env var, `ZK_HOST` or `-c` still overrides it. |

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
  they load the models directly from test resources: `dictionary → system → content →
  cmis` first, then every remaining model in `test-files/alfrescoModels` (`cmistest`,
  `acme`, `allfieldtypes`, `solrtest`), which import `d`/`sys`/`cm` only. Loading just
  the four socle models is not enough — `CMISQueryParser` then rejects the CMIS test
  queries with `Type is unsupported in query: cmistest:extendedContent`, since the
  type is simply absent from the dictionary.
- **JaCoCo**: exclude `org/alfresco/repo/search/impl/parsers/**`. The ANTLR-generated
  `FTSParser` DFA methods exceed the JVM 64 KB method limit once instrumented
  (`MethodTooLargeException`), which broke query parsing under coverage.
- **Solr 6 example-schema leftovers** in `test-files/{collection1,master,slave}/conf/schema-rerank.xml`
  (three identical copies): dropped `solr.GeoHashField` and its only field `point_hash`,
  `solr.LatLonType` (declared but unused — `solr.LatLonPointSpatialField` is the Solr 9 replacement)
  and the `solr.StandardFilterFactory` filter (a no-op since Lucene 4.7, removed in Lucene 7).
  `solr.PointType` and the `Trie*` types are still shipped by Solr 9, so they stay.
  A schema aborts on the **first** missing class, hiding the rest, so check statically instead of
  paying one run per class: extract every `class="solr.*"` outside XML comments from those configs and
  look the simple name up in `solr-core-9.10.1.jar`. **The class existing is not enough** — its
  arguments must still be accepted: `solr.ExternalFileField` no longer knows `valType` (Solr 9's
  `init()` consumes only `keyField` and `defVal`, and `FieldType.setArgs` rejects whatever is left),
  so the four declarations carrying it aborted the whole `schema-rerank.xml` with
  *"schema fieldtype file(org.apache.solr.schema.ExternalFileField) invalid arguments:{valType=float}"*
  and left `collection1` unavailable.
- **`TopDocs.totalHits` is a `TotalHits` object since Lucene 8**, not an `int`. Two test assertions
  still compared it to an expected count: `assertEquals(count, docs.totalHits)` binds to
  `assertEquals(Object, Object)`, so it fails even when the counts agree — the giveaway is
  `expected:<1> but was:<1 hits>`, `TotalHits.toString()`. Fixed with `.value`
  (`AbstractAlfrescoSolrIT.assertAQuery`, `AuthQueryIT.assertFTSQuery`); `src/main` had already
  been migrated.

- **The failsafe `argLine` in the root POM pins two system properties**; neither is optional.
  - `-Duser.language=en -Duser.country=US`. The assertions are written for English analysis and
    most requests carry **no** locale (`assertResponseCardinality` passes a null JSON), so the
    parser falls back to `I18NUtil.getLocale()`. Left unpinned, results depend on the developer's
    machine: on a `fr_FR` JVM the wildcard `?est` loses its stem (`est` is a French stop word, so
    the token vanishes and only the `?` survives the offset-based wildcard reconstruction),
    `[te to test]` matches everything, and `runner` stems onto one document too many.
    `AbstractAlfrescoDistributedIT` extends `SolrTestCaseJ4`, which randomises `Locale.setDefault`
    anyway, so the pin only makes deterministic the harnesses that do not.
  - `-Dtest.solr.allowed.securerandom=NativePRNG`.
    `SolrTestCaseJ4.assertNonBlockingRandomGeneratorAvailable` fails a whole class when the JVM
    picks a SecureRandom it deems potentially blocking, and which one it picks varies per run — so
    the check fires **at random**, before any test executes. The backing algorithm is irrelevant to
    what these tests do. Never read such a failure as a code regression. Passing the same property
    with `-D` on the command line is now redundant, and makes Maven warn that it is
    *"configured twice"* — harmless, but drop the flag.

## Tracker timing (post-externalization)

The externalized trackers no longer read `solrcore.properties`, so they started on
slow built-in defaults and exceeded test retry windows. Restore the upstream-equivalent
cadence (see [`tracker-configuration.md`](tracker-configuration.md) for full tuning):

- model cron `10s`, commit cron `5s`, `commitInterval 2000ms`, `newSearcherInterval 3000ms`.

Cascade re-indexing of descendants on rename/move was also repaired: query the
`long@s_@{sys}cascadeTx` field (not the `int@s_@cascade` flag) and set `maxResults(1)`
on the metadata lookup.

---

## Upgrade checklist

1. Build the distribution and image: `mise run install` then `mise run e2e:build-image`
   (or `mise run e2e:rebuild` for the full e2e stack).
2. Deploy Solr 9 with the runtime overrides above (the Dockerfile already sets them).
3. Start with **empty cores** — do not try to reuse Lucene 6/8 index data.
4. Start the externalized trackers and let them rebuild the index from the repository.
5. Verify: facet drill-down (APATH), highlighting (`hl.method=original`), and sorting
   on `text`/`mltext` fields (the `SORTED` docvalues fix).

### When bumping Solr later

`luceneMatchVersion` is **not** a version to keep in step with the Solr release. It states
which Lucene behaviour the index was built with, so it moves **with a re-index**, not with an
upgrade — a Solr patch or minor bump leaves it alone. Lucene keeps the constants of the
previous major (Lucene 9 still declares `LUCENE_8_*`), so today's `9.12.3` stays valid
through Solr 10 and only has to move at Solr 11 — which is a re-index anyway.

Two things to know when it does move: the value must exist as a Lucene `Version` constant
(check `javap -cp lucene-core-<v>.jar org.apache.lucene.util.Version`), and editing the two
shipped templates changes **new cores only** — an existing core carries its own copy in
`solrhome/<core>/conf/solrconfig.xml`.

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
