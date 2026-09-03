# Changelog

All notable changes to Pristy Search Services are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Version numbers are plain, without a `v` prefix, matching the Git tags.

## [Unreleased]

## [1.0.0] - unreleased

First release under the **Pristy Search Services** name. The project is a community
fork of Alfresco Search Services, rebuilt around vanilla Solr 9 with the indexing
trackers split out into their own service. A full re-index is required when coming
from any Alfresco Search Services release: Lucene 9 cannot read a Lucene 6 index.

### Changed — project identity

- Renamed to **pristy-search-services**, hosted at
  <https://gitlab.com/pristy-oss/pristy-search-services>.
- Maven coordinates moved to the `fr.pristy` group; every module artifact is now
  `pristy-*` (`pristy-search`, `pristy-solrclient-lib`, `pristy-indexing-trackers`,
  `pristy-search-subsystem-solr9`, `pristy-search-services`).
- Container install path is now `/opt/pristy-search-services`.
- Docker images published to Docker Hub as `jeci/pristy-search-services` and
  `jeci/pristy-indexing-trackers`.
- JARs published to the project's GitLab Package Registry, readable without
  authentication. Maven Central publication (namespace `fr.pristy`) is planned for 1.1.
- Java packages stay under `org.alfresco.*`: they are referenced by name from
  `schema.xml` and `solrconfig.xml`, and renaming them would break existing cores
  for no benefit.

### Changed — search tier

- **Solr 6.6.5 (Alfresco-patched) → Solr 9.10.1 / Lucene 9.12.3 (vanilla)**, via an
  intermediate Solr 8.11.4 step. No Alfresco-patched Solr build is used any more.
  See `docs/solr6-to-solr9-migration.md`.
- Java 11 → 17, Log4j 1.x → Log4j 2, ZooKeeper 3.4.14 → 3.6.3.
- Dropped the `alfresco-xmlfactory` dependency; Solr 8+ has built-in XXE protection
  and the library was blocking XInclude in `schema.xml`.

### Added

- **Standalone indexing trackers** (`pristy-indexing-trackers`): a Spring Boot 3.4
  service that polls the repository and indexes through SolrJ, instead of running
  inside the Solr webapp. Solr itself is now vanilla.
- **Admin endpoints on the trackers service** (`:8085`): `SUMMARY`, `REPORT`,
  `NODECHECK`, `PURGE`, `REINDEX` and friends, with a Solr-compatible alias so
  classic Alfresco tooling keeps working. `STATUS` is proxied to Solr's native
  CoreAdmin. See `docs/tracker-admin-endpoints.md`.
- **`solr9` Search subsystem** for the Alfresco repository
  (`pristy-search-subsystem-solr9`): routes admin actions to the trackers service
  while queries stay on Solr. Enable with `-Dindex.subsystem.name=solr9`.
- **RepairTracker**: nodes that fail to index are marked `HAS_INDEXING_ERROR` and
  retried by pluggable strategies, with a report at `/actuator/repairreport`.
- **Tracker-driven backup and restore**: per-core scheduled backups through the Solr
  ReplicationHandler, exposed as `BACKUP`/`RESTORE` admin actions. Replaces the
  repository-driven `SolrBackupJob`, which fails under Solr 9 (`solr.allowPaths`).
- **`https` secureComms mode**: mutual TLS, Jetty-native, PKCS12 keystores, each
  channel configurable independently. See `docs/secure-comms-https.md`.
- Per-core tracker configuration, with the tracked store resolved by convention and
  logged at startup. See `docs/tracker-configuration.md`.
- CI: unit tests, CycloneDX SBOMs and a Trivy dependency scan on every pipeline.

### Removed

- Enterprise-only components: Insight Engine, Zeppelin and governance services,
  along with the Alfresco enterprise Maven repositories.
- `Solr4X509ServletFilter` and the Solr 6 `HttpClientUtil` shared-secret
  interceptor, both obsolete now that TLS is handled by Jetty.
- `XSLTResponseWriter` from `solrconfig.xml` (removed from `solr-core` in Solr 9).

### Fixed

- Cross-locale content search: text is indexed without a locale prefix, and
  properties are written to both localised and non-localised fields.
- Sorting on `text`/`mltext` fields: read `SORTED` docvalues in the collatable
  comparators (Lucene 9 changed the docvalues type).
- Highlighting: forced `hl.method=original` so the Alfresco field-mapping
  highlighter is used.
- Cascade re-indexing of descendants on rename or move.
- Each core's tracked store is now resolved per core; previously a core with no
  explicit `alfresco.stores` silently indexed the live workspace, leaving the
  `alfresco` and `archive` cores with identical indexes.
- Node configuration (`solrhome/solr.xml`) is refreshed from the image at every
  container start. It sits inside the mounted `solrhome`, so a mount created by an
  earlier image kept its original copy through every upgrade — leaving the global
  `maxBooleanClauses` at Lucene's 1024 default and no `allowUrls` for distributed
  queries. Neither is fixable with a `-D` flag, since those properties are only
  substituted into that file. The previous copy is preserved as `solr.xml.bak`.

[Unreleased]: https://gitlab.com/pristy-oss/pristy-search-services/-/compare/1.0.0...develop
[1.0.0]: https://gitlab.com/pristy-oss/pristy-search-services/-/releases/1.0.0
