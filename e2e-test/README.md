# Search Services E2E Tests

Automated tests for Alfresco Search Services requiring a running ACS + Solr stack.

## Prerequisites

- Java 17
- Maven 3.9+
- Docker and Docker Compose
- A running ACS + Search Services environment

## Quick Start with mise

```bash
# Build and install the project first
mise run install

# Build the local Docker image from the distribution ZIP
mise run e2e:build-image

# Generate and start the test stack (uses the local image)
mise run e2e:generate
mise run e2e:up
mise run e2e:logs        # Wait until all services are healthy

# Run the tests
mise run e2e:test

# Stop the stack
mise run e2e:down
```

## Manual Setup

### Build the Docker image

```bash
# Build the distribution ZIP first
mvn install -DskipTests -pl search-services/pristy-solrclient-lib,search-services/pristy-search,search-services/packaging -am

# Build the Docker image from the packaging output
docker build -t jeci/pristy-search-services:local search-services/packaging/target/docker-resources/
```

### Generate the Docker Compose stack

```bash
cd e2e-test/python-generator
python3 generator.py \
  --alfresco=alfresco/alfresco-content-repository-community:26.2.0 \
  --search=jeci/pristy-search-services:local \
  --postgres=postgres:16 \
  --transformer=AIOTransformers \
  --output=../../target/e2e-stack
```

See all options with `python3 generator.py -h`.

### Start the stack

```bash
docker compose -f target/e2e-stack/docker-compose.yml up --build -d
```

### Health checks

1. ACS Repo: ensure the admin console > Search Services shows tracking status.
2. Solr: check the admin console at `http://localhost:8983/solr/#`

### Run the tests

```bash
mvn test -pl e2e-test
```

### Run a specific test class

```bash
mvn test -pl e2e-test -Dtest=CustomModelTest
```

## Repository behaviour that the suite depends on

### `entry.search.score` is only returned when highlighting is requested (ACS 26+)

Up to ACS 25.3.0, `ResultMapper.toCollectionWithPagingInfo` set the `search` block —
which carries `score` and `highlight` — on every result row. ACS 26.2.0 wraps that
`setSearch` call in a `results.getHighlighting().isEmpty()` guard, so a query that asks
for no highlighting now comes back with no `search` block at all, score included.

The failure mode is not an assertion error: `ModelAssertion` calls `toString()` on the
missing field, so the test dies with `NullPointerException: Cannot invoke
"Object.toString()" because "this.fieldValue" is null`.

`SearchTest` is therefore split in two: `searchOnIndexedData` checks that the indexed
node is found, and `searchReportsRelevanceScore` asks for a `cm:content` highlight and
checks the score. Any new assertion on `entry.search` must request highlighting.
