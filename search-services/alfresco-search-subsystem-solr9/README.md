# alfresco-search-subsystem-solr9

A **resources-only** JAR installed into the Alfresco Repository webapp
(`WEB-INF/lib`). It registers a new **`solr9`** Search subsystem type so the
Repository works correctly against this fork's split search tier: **vanilla
Solr 9** for queries and a **standalone trackers service** for the admin
control plane.

## The problem it solves

In this fork Solr 9 is kept vanilla. It serves search queries and the *native*
Solr core-admin actions (`STATUS`, …), but **not** the Alfresco-specific
control-plane actions (`SUMMARY`, `REPORT`, `NODEREPORT`, `REINDEX`, …). Those
moved to the standalone **Alfresco Indexing Trackers** service, which exposes a
Solr-compat alias at `GET /solr/admin/cores?action=<ACTION>` on port `8085`.

The stock `solr6` subsystem wires **both** of Alfresco's HTTP clients — the query
client *and* the admin client — to the same `${solr.host}:${solr.port}`. So any
Alfresco feature that issues an admin action (for example the **OOTBee Support
Tools → “Solr Tracking”** page, which calls `SolrAdminHTTPClient` with
`action=SUMMARY`) hits vanilla Solr and fails:

```
QueryParserException: Request failed 400 /solr/admin/cores?action=SUMMARY&wt=json
```

surfacing as an HTTP 500 on the admin page.

## What it does

`solr9` is a near-copy of the stock `solr6` subsystem with a **single functional
change**: a dedicated `solrTrackerHttpClientFactory` points the admin HTTP client
(`search.solrAdminHTTPCLient`) at the trackers service instead of Solr. The
network paths separate cleanly:

| Path | Bean | Target |
|------|------|--------|
| Search queries | `search.solrQueryHTTPCLient` | Solr (`${solr.host}:${solr.port}`) |
| Internal health ping + core registration (`STATUS`) | `solrAdminClient` (store mappings) | Solr |
| Admin actions (`SUMMARY`/`REPORT`/`STATUS`/…) | `search.solrAdminHTTPCLient` | Trackers (`${solr.tracker.host}:${solr.tracker.port}`) |

`baseUrl` for the admin client stays `${solr.baseUrl}` (`/solr`), so the request
path is exactly `/solr/admin/cores?action=…` — the trackers Solr-compat alias.

**`STATUS` note.** `STATUS` is a *native* Solr action (per-core index doc counts
and size), so its data lives in Solr, not the trackers. The trackers Solr-compat
facade nevertheless answers `STATUS` by **proxying to Solr's native STATUS** (see
`AdminService.status()` in the trackers). This keeps the admin client generic: a
tool like the OOTBee page issues both `SUMMARY` and `STATUS` through the one admin
client, and the trackers serve `SUMMARY` locally while transparently forwarding
`STATUS` to Solr.

## Enabling it

Set the subsystem and point Alfresco at the trackers service (global properties /
`-D` / `alfresco-global.properties` — these override the subsystem defaults):

```
-Dindex.subsystem.name=solr9
-Dsolr.host=solr             -Dsolr.port=8983            # vanilla Solr (queries)
-Dsolr.tracker.host=trackers -Dsolr.tracker.port=8085    # trackers (admin actions)
```

> **Production gotcha.** `solr.tracker.host` defaults to `localhost` (see the table
> below). If you do not override it, Alfresco looks for the trackers on its own
> container and every admin call fails with `java.net.ConnectException: Connection
> refused`. Set it to the trackers host reachable from the ACS container.

### Configuration properties

| Property | Default | Meaning |
|----------|---------|---------|
| `solr.tracker.host` | `localhost` | Trackers admin host. **Must be set in prod** (see gotcha above). |
| `solr.tracker.port` | `8085` | Trackers admin port (used in `none`/`secret` mode). |
| `solr.tracker.port.ssl` | `8085` | Trackers admin SSL port — used in `https`/mTLS mode (Alfresco connects to `host:sslPort`). |
| `solr.tracker.secureComms` | `${solr.secureComms}` | `none` / `secret` / `https`. Defaults to the Solr channel's setting. |
| `solr.tracker.sharedSecret` | `${solr.sharedSecret}` | Shared secret for the trackers admin server. Defaults to the Solr channel's secret. |

By default the trackers channel mirrors the Solr channel's `secureComms` and
shared secret, so a single-secret deployment needs no extra configuration. In
`https`/mTLS mode the same keystore/truststore material is reused, and the trackers
admin server must have TLS enabled (`TRACKER_SERVER_SSL_ENABLED=true`) on
`solr.tracker.port.ssl`; override the `solr.tracker.*` properties only if the
trackers admin server uses a different secret or certificate.

### Consumer requirement — OOTBee Support Tools

The OOTBee “Solr Tracking” page only recognises the subsystem names it knows about
(`solr`, `solr4`, `solr6`). To use it with this subsystem the addon must be adapted
to recognise `solr9` (`solr-tracking.lib.js` regex) and to provide a
`solrAdminNativeClient` bean for the `solr9` subsystem context. An addon build
without `solr9` support renders the page as “disabled” (the FreeMarker template
fails on a missing `coreNames`).

## Design notes

- **Absolute `classpath:` imports.** The subsystem's context files import the shared
  `common-search-context.xml` / `common-opencmis-context.xml` with an **absolute**
  `classpath:` location, not a relative `../`. Those parents live in the Alfresco
  core jar, and a relative import inside this module's own jar cannot cross into
  another jar (`FileNotFoundException` at subsystem start).
- **Store mappings are NOT a composite property.** Unlike stock `solr6`, this
  subsystem does **not** declare `solr9.store.mappings` in `compositePropertyTypes`.
  A declared composite is rebuilt from *global* properties only and would override
  the inline `ListFactoryBean` sourceList with an empty list (→ `No solr query
  support for store workspace://SpacesStore`). The mappings (`workspace`+`archive`)
  are defined directly in the `solr9.store.mappings` `ListFactoryBean` in
  `solr-search-context.xml`, keeping the module self-contained.

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

Verify: open the OOTBee **Solr Tracking** admin page, or call the Solr-compat
alias directly on the trackers service:

```bash
curl -s -H "X-Alfresco-Search-Secret: secret" \
  "http://localhost:8085/solr/admin/cores?action=SUMMARY&wt=json" | python3 -m json.tool
```

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| `ConnectException: Connection refused` from `SolrAdminHTTPClient` | `solr.tracker.host` not set (defaults to `localhost`) or trackers admin port not reachable from ACS. In `https` mode also check the trackers TLS port (`solr.tracker.port.ssl`) and that the trackers admin server has TLS enabled. |
| `coreNames has evaluated to null` on the Solr Tracking page | OOTBee addon does not recognise `solr9` — use the patched `ootbee-support-tools`. |
| `No solr query support for store workspace://SpacesStore` | Store mappings empty — do not declare `solr9.store.mappings` as a composite (see Design notes). |
| `FileNotFoundException: … common-*.xml not found` at startup | A relative `../` import — use absolute `classpath:` (see Design notes). |

## Not included: Solr backups

The stock `solr6` subsystem ships a `solr-backup-context.xml` that schedules
repository-driven Solr backups. That mechanism is broken under Solr 9 (blocked by
`solr.allowPaths`) and is slated to move to the trackers service, so it is
deliberately **omitted** here rather than scheduled against vanilla Solr.

## See also

- `docs/tracker-admin-endpoints.md` — the trackers admin REST API and Solr-compat alias.
- `docs/solr9-admin-guide.md` — administrator overview of the Solr 9 search tier.
