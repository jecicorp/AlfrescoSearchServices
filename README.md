# Alfresco Search Services (Jeci Fork)

Community fork of **Alfresco Search Services**, modernized to run on **vanilla
Apache Solr 9.10.1 / Lucene 9.12.3** and **Java 17**. Enterprise components
(Insight Engine, Zeppelin, governance services) have been removed, and the indexing
trackers have been **externalized into a standalone service**.

Project home: **https://github.com/jecicorp/AlfrescoSearchServices**

> ## ⚠️ Status & disclaimer
>
> - **Beta — not production-ready.** This fork is under active development and
>   **must not be used in production for the time being.**
> - **Not affiliated with Hyland / Alfresco.** This is an independent community
>   fork. It is **not** supported on **Alfresco Enterprise** and has no
>   relationship with Hyland.
> - **Built for Alfresco Community.** It was developed as part of the
>   [Pristy](https://pristy.net) project and is **fully compatible with Alfresco
>   Community Edition**.
> - **Support available.** [Jeci](https://jeci.fr) offers commercial support on
>   Alfresco Community Edition, and more specifically on this search module if
>   needed.

## What's in this fork

- **Solr 9.10.1 / Lucene 9.12.3** (vanilla Apache, no Alfresco-patched build).
- **Java 17**, **ZooKeeper 3.6.3**, logging on **Log4j 2**.
- **Externalized indexing trackers** — a separate Spring Boot service
  (`alfresco-indexing-trackers`) reads from the repository and feeds Solr, instead
  of running inside the Solr webapp. Solr and the trackers are now two independently
  deployable, restartable and tunable services.
- Enterprise-only modules removed; the search API, AFTS query language and ACL
  permission filtering are unchanged.

The search tier therefore consists of **two services**:

| Service | Role |
|---------|------|
| **Solr** (`alfresco-search` + `packaging`) | Query serving and index storage. |
| **`alfresco-indexing-trackers`** | Reads nodes/ACLs/content from the Alfresco Repository and indexes them into Solr. Tuned via `ALFRESCO_TRACKER_*` environment variables. |

## Documentation

See the [`docs/`](docs/) folder:

- [docs/solr9-admin-guide.md](docs/solr9-admin-guide.md) — **administrator guide**:
  what changes with this version, the mandatory re-index, the new configuration
  options, and what stays the same.
- [docs/solr6-to-solr9-migration.md](docs/solr6-to-solr9-migration.md) — technical
  migration record (Solr 6 → 8 → 9).
- [docs/tracker-configuration.md](docs/tracker-configuration.md) — full tuning
  reference for the standalone trackers.
- [docs/debugging.md](docs/debugging.md) — ACL deny filtering diagnostics.

## Prerequisites

- Java 17 (Temurin)
- Maven 3.9+
- [mise](https://mise.jdx.dev/) (recommended, auto-configures Java and Maven)

```bash
mise install
```

## Build

```bash
# Full build without tests
mise run build

# Or directly with Maven
mvn package -Dmaven.test.skip=true
```

## Unit Tests

```bash
# Unit tests (alfresco-search + alfresco-solrclient-lib)
mise run test

# Or directly with Maven
mvn test -pl search-services/alfresco-solrclient-lib,search-services/alfresco-search -am
```

## End-to-End Tests

E2E tests require a full ACS + Solr stack via Docker Compose.

```bash
# 1. Install the project (builds the distribution ZIP)
mise run install

# 2. Build the local Docker image from the distribution
mise run e2e:build-image

# 3. Generate the docker-compose (uses the local image)
mise run e2e:generate

# 4. Start the stack
mise run e2e:up

# 5. Follow logs (wait until everything is ready)
mise run e2e:logs

# 6. Run the tests
mise run e2e:test

# 7. Stop the stack
mise run e2e:down
```

The generator is configurable via `e2e-test/python-generator/generator.py -h`.

## Indexing Trackers (standalone service)

```bash
# Build the trackers Spring Boot JAR
mise run trackers:build

# Run the trackers locally
mise run trackers:run
```

A local development stack (Alfresco + Solr + Trackers) is also available:

```bash
mise run dev:up      # start
mise run dev:logs    # follow logs
mise run dev:down    # stop
```

## Project Structure

```
.
├── pom.xml                         # Parent POM (alfresco-search-parent)
├── mise.toml                       # mise configuration (Java, Maven, tasks)
├── docs/                           # Technical documentation (see index)
├── search-services/
│   ├── pom.xml                     # search-services parent POM
│   ├── alfresco-solrclient-lib/    # Solr client for Alfresco
│   ├── alfresco-search/            # Solr search engine (main module)
│   ├── alfresco-indexing-trackers/ # Standalone indexing trackers (Spring Boot)
│   └── packaging/                  # Distribution assembly + Docker image
└── e2e-test/                       # End-to-end tests
    ├── python-generator/           # Docker Compose generator (Python)
    └── generator-alfresco-docker-compose/  # Docker Compose generator (Yeoman)
```

## Install to Local Repository

```bash
mise run install
```

## Resources

The distribution ZIP is available under `search-services/packaging/target` after
building. The Docker image source is in `search-services/packaging/src/docker`.

## Support & About

This fork was created and is maintained by **[Jeci](https://jeci.fr)**, the company
behind **[Pristy](https://pristy.net)**, an open-source ECM suite built on Alfresco
Community Edition. Jeci offers commercial support on Alfresco Community Edition and,
more specifically, on this search module.

## Contributing

Please use [this guide](CONTRIBUTING.md) to make a contribution to the project.

## License

GNU Lesser General Public License v3.0 (LGPL-3.0). See [LICENSE](LICENSE) and the
license headers in the source files.
