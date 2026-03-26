# Debugging Guide

## ACL Deny Filtering (`processedDenies`)

Solr responses include a `processedDenies` boolean flag. When `true`, ACL deny
filtering was applied (documents denied to the user are excluded). When `false`,
no deny filtering was applied for that request.

`processedDenies: false` is **normal** for internal Solr requests (health checks,
admin console queries) that don't go through the Alfresco Repository. For search
requests originating from the Alfresco UI, the flag should always be `true`.

### Enabling diagnostic logs

Uncomment the following lines in `search-services/packaging/src/main/resources/logs/log4j.properties`:

```properties
log4j.logger.org.alfresco.solr.query.AbstractQParser=TRACE
log4j.logger.org.alfresco.solr.component.SetProcessedDeniesComponent=TRACE
```

Then rebuild and redeploy. Search for `[ACL-DIAG]` in `solr.log`:

```bash
grep ACL-DIAG /opt/solr/server/logs/solr.log
```

### What the logs tell you

| Log message | Meaning |
|-------------|---------|
| `getString()='AUTHORITY_FILTER_FROM_JSON', json from context=present` | Alfresco sent the authority filter correctly |
| `processedDenies set to TRUE` | Deny clause was built and applied |
| `getString()='*', json from context=null` | Internal Solr query (no authority filter expected) |
| `JSONException during authority filter parsing` | JSON from Alfresco is malformed or missing expected keys |
| `anyDenyDenies=false in JSON` | Alfresco explicitly disabled deny filtering |
| `AUTHORITY_FILTER_FROM_JSON matched but authQuery is empty` | JSON was parsed but contained no authorities |
