# Alfresco Search Services (JECI Fork)

Community fork of Alfresco Search Services. Enterprise components (Insight Engine, Zeppelin, governance services) have been removed.

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
# 1. Generate the docker-compose
mise run e2e:generate

# 2. Start the stack
mise run e2e:up

# 3. Follow logs (wait until everything is ready)
mise run e2e:logs

# 4. Run the tests
mise run e2e:test

# 5. Stop the stack
mise run e2e:down
```

The generator is configurable via `e2e-test/python-generator/generator.py -h`.

## Project Structure

```
.
├── pom.xml                         # Parent POM (alfresco-search-parent)
├── mise.toml                       # mise configuration (Java, Maven, tasks)
├── search-services/
│   ├── pom.xml                     # search-services parent POM
│   ├── alfresco-solrclient-lib/    # Solr client for Alfresco
│   ├── alfresco-search/            # Solr search engine (main module)
│   └── packaging/                  # Distribution assembly
└── e2e-test/                       # End-to-end tests
    ├── python-generator/           # Docker Compose generator (Python)
    └── generator-alfresco-docker-compose/  # Docker Compose generator (Yeoman)
```

## Install to Local Repository

```bash
mise run install
```

## Resources

The distribution ZIP is available under `search-services/packaging/target` after building.

Docker image source is in `search-services/packaging/src/docker`.

## Contributing

Please use [this guide](CONTRIBUTING.md) to make a contribution to the project.
