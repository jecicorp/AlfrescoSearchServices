# alfresco-search-subsystem-solr9

A **resources-only** JAR installed into the Alfresco Repository webapp
(`WEB-INF/lib`). It registers a new **`solr9`** Search subsystem type so the
Repository works correctly against this fork's split search tier: **vanilla
Solr 9** for queries and a **standalone trackers service** for the admin
control plane.

## The problem it solves

In this fork Solr 9 is kept vanilla. It serves search queries and the *native*
Solr core-admin actions (e.g. `STATUS`), but **not** the Alfresco-specific
control-plane actions (`SUMMARY`, `REPORT`, `NODEREPORT`, `REINDEX`, …). Those
moved to the standalone **Alfresco Indexing Trackers** service, which exposes a
Solr-compat alias at `GET /solr/admin/cores?action=<ACTION>` on port `8085`.

The stock `solr6` subsystem wires **both** of Alfresco's HTTP clients — the query
client *and* the admin client — to the same `${solr.host}:${solr.port}`. So any
Alfresco feature that issues an admin action (for example the **OOTBEE Support
Tools → “Solr / Search Service Tracking”** page, which calls
`SolrAdminHTTPClient` with `action=SUMMARY`) hits vanilla Solr and fails:

```
QueryParserException: Request failed 400 /solr/admin/cores?action=SUMMARY&wt=json
```

surfacing as an HTTP 500 on the admin page.

## What it does

`solr9` is a near-copy of the stock `solr6` subsystem with a **single functional
change**: a dedicated `solrTrackerHttpClientFactory` points the admin HTTP client
at the trackers service. The three network paths separate cleanly:

| Path | Bean | Target |
|------|------|--------|
| Search queries | `search.solrQueryHTTPCLient` | Solr (`${solr.host}:${solr.port}`) |
| Health ping + core registration (`STATUS`, native to Solr 9) | `solrAdminClient` | Solr |
| Admin actions (`SUMMARY`/`REPORT`/…) | `search.solrAdminHTTPCLient` | Trackers (`${solr.tracker.host}:${solr.tracker.port}`) |

`baseUrl` for the admin client stays `${solr.baseUrl}` (`/solr`), so the request
path is exactly `/solr/admin/cores?action=…` — the trackers Solr-compat alias.

## Enabling it

Set the subsystem and point Alfresco at the trackers service:

```
-Dindex.subsystem.name=solr9
-Dsolr.host=solr            -Dsolr.port=8983     # vanilla Solr (queries)
-Dsolr.tracker.host=trackers -Dsolr.tracker.port=8085   # trackers (admin actions)
```

### Configuration properties

| Property | Default | Meaning |
|----------|---------|---------|
| `solr.tracker.host` | `localhost` | Trackers admin host |
| `solr.tracker.port` | `8085` | Trackers admin port |
| `solr.tracker.port.ssl` | `8085` | Trackers admin SSL port (https/mTLS mode) |
| `solr.tracker.secureComms` | `${solr.secureComms}` | `none` / `secret` / `https`. Defaults to the Solr channel's setting |
| `solr.tracker.sharedSecret` | `${solr.sharedSecret}` | Shared secret for the trackers admin server. Defaults to the Solr channel's secret |

By default the trackers channel mirrors the Solr channel's `secureComms` and
shared secret, so a single-secret deployment needs no extra configuration. In
`https`/mTLS mode the same keystore/truststore material is reused; override the
`solr.tracker.*` properties only if the trackers admin server (`:8085`) uses a
different secret or certificate.

## Packaging

No Java or JSP sources — only Spring context XML and `.properties` resources, so
the reactor's license header check has nothing to enforce here. The artifact is a
plain JAR that must land on the Repository classpath (`WEB-INF/lib`); the
`solr9` factory bean is picked up via `classpath*:alfresco/extension/*-context.xml`
and the subsystem type files via `classpath*:alfresco/subsystems/Search/solr9/*`.

## Local dev stack

`docker-compose.dev.yml` already sets `-Dindex.subsystem.name=solr9` and the
`solr.tracker.*` vars. Build and stage the JAR into the ACS image, then start:

```bash
mise run dev:acs-build   # builds this module and copies the JAR into acs/assets/
mise run dev:up          # (depends on dev:acs-build) builds the ACS image and starts the stack
```

Verify: open the OOTBEE **Solr Tracking** admin page, or call the Solr-compat
alias directly on the trackers service:

```bash
curl -s -H "X-Alfresco-Search-Secret: secret" \
  "http://localhost:8085/solr/admin/cores?action=SUMMARY&wt=json" | python3 -m json.tool
```

## Not included: Solr backups

The stock `solr6` subsystem ships a `solr-backup-context.xml` that schedules
repository-driven Solr backups. That mechanism is broken under Solr 9 (blocked by
`solr.allowPaths`) and is slated to move to the trackers service, so it is
deliberately **omitted** here rather than scheduled against vanilla Solr.

## See also

- `docs/tracker-admin-endpoints.md` — the trackers admin REST API and Solr-compat alias.
- `docs/solr9-admin-guide.md` — administrator overview of the Solr 9 search tier.
