# Alfresco Search Services — Documentation

Technical documentation for this fork of Alfresco Search Services (Solr + the
standalone indexing trackers) running on Alfresco Community Edition.

## Index

| Document | Description |
|----------|-------------|
| [solr9-admin-guide.md](solr9-admin-guide.md) | **For administrators.** What changes with the new Solr 9 search version in plain terms: the new two-service layout, the mandatory re-index, the new configuration options, full re-index strategies (in-place vs blue/green with SWAP), and what stays the same. |
| [solr6-to-solr9-migration.md](solr6-to-solr9-migration.md) | How the search tier was migrated from Solr 6.6.5 to Solr 9.10.1 / Lucene 9.12.3, in two phases (6→8, then 8→9). API, schema, solrconfig, runtime and test changes. |
| [tracker-configuration.md](tracker-configuration.md) | Configuration and tuning of the standalone `pristy-indexing-trackers` Spring Boot service (cron, commit interval, batch size, latency/throughput trade-offs), including **per-core overrides and store selection** for the `alfresco`/`archive` cores. |
| [tracker-admin-endpoints.md](tracker-admin-endpoints.md) | Operational REST API of the trackers service (`:8085`): index reports (`summary`, `report`, `node-report`), on-demand reindex of a node/transaction/query, error-node retry, and the Solr-compat `/solr/admin/cores?action=…` alias. |
| [debugging.md](debugging.md) | Debugging guide — ACL deny filtering (`processedDenies`) and how to enable the relevant diagnostic logs, plus tracker startup issues (full re-index on restart, `ModelTracker` namespace errors). |
| [secure-comms-https.md](secure-comms-https.md) | Operator guide for `secureComms=https` (mTLS): the four TLS links, certificate generation with `keystore/generate-keystores.sh`, exact env vars for Solr / trackers / Alfresco, and the Caddy `:8984` dev-proxy caveat. |

## About this fork

This fork of Alfresco Search Services was created and is maintained by
**[Jeci](https://jeci.fr)**.

Jeci is the company behind **[Pristy](https://pristy.net)**, an open-source
Enterprise Content Management (ECM) suite built on Alfresco Community Edition.
Jeci specializes in consulting, integration and support around Alfresco Community
and the Pristy ecosystem, and offers commercial support both on Alfresco Community
Edition and, more specifically, on this search module.

> This is an independent community project, **not affiliated with Hyland**, and it
> is **not** supported on Alfresco Enterprise. See the
> [project README](../README.md) for the full status and disclaimer.
