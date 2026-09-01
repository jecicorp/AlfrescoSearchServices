## Per-core Tracker Configuration

![Completeness Badge](https://img.shields.io/badge/Document_Level-In_Progress-yellow.svg?style=flat-square)

### Purpose

The tracker subsystem can track several Solr cores at once (typically `alfresco`
for the live `workspace://SpacesStore` and `archive` for the trashcan
`archive://SpacesStore`). Each core must be able to:

- track a **different store**, and
- run with **different tuning** — a secondary core such as `archive` is updated
  far less often than the primary one and usually does not need full-text
  content extraction at all.

Configuration is sourced exclusively from Spring Boot
`TrackerProperties` (prefix `alfresco.tracker`). The legacy per-core
`solrcore.properties` file is **not** read by the tracker subsystem.

### Configuration model

Values are defined **globally** and may be **overridden per core** under
`alfresco.tracker.cores.<coreName>`. Any per-core field left unset inherits the
global default.

```yaml
alfresco:
  tracker:
    # --- global defaults ---
    batch-count: 5000
    commit-interval: 2000          # ms
    new-searcher-interval: 3000    # ms
    cascade-tracking-enabled: true
    max-live-searchers: 2
    transform-content: true        # alfresco.index.transformContent
    cron:
      metadata: "0/10 * * * * ?"
      acl:      "0/10 * * * * ?"
      content:  "0/10 * * * * ?"
      commit:   "0/5 * * * * ?"
      model:    "0/10 * * * * ?"
      cascade:  "0/10 * * * * ?"
      repair:   "0 0/1 * * * ?"
    solr:
      collections: [alfresco, archive]

    # --- per-core overrides ---
    cores:
      archive:
        store: "archive://SpacesStore"   # optional, see "Store resolution"
        transform-content: false         # skip content extraction for the trash
        max-live-searchers: 1
        batch-count: 1000
        commit-interval: 30000
        new-searcher-interval: 60000
        cascade-tracking-enabled: false
        cron:
          metadata: "0 0/5 * * * ?"      # every 5 min instead of every 10 s
          content:  "0 0/30 * * * ?"
```

The following keys are resolved per core (`TrackerProperties.resolvedCore`):
`store`, `batch-count`, `max-live-searchers`, `transform-content`,
`cascade-tracking-enabled`, `commit-interval`, `new-searcher-interval`, and all
`cron.*` schedules **except `cron.model`**: the `ModelTracker` is a single,
repo-global instance (initialised on the first core), so its schedule is taken
from the global / first-core configuration.

### Store resolution

The store tracked by a core is resolved in this order:

1. explicit `alfresco.tracker.cores.<coreName>.store`, otherwise
2. **convention**: the core literally named `archive` tracks
   `archive://SpacesStore`; every other core tracks `workspace://SpacesStore`.

Because of the convention default, a standard `alfresco` + `archive` deployment
needs **no** `cores` configuration at all to track the correct stores.

> **Regression note.** `AbstractTracker` falls back to
> `workspace://SpacesStore` when the `alfresco.stores` property is absent. A
> previous version of `TrackerBootstrap` built a single `Properties` object,
> shared across all cores, that never set `alfresco.stores`. Every core therefore
> defaulted to the workspace store, and the `archive` core indexed the live nodes
> instead of the trashcan — two physically distinct indexes holding identical
> documents. The per-core resolution above, plus the startup log of the resolved
> store for each core (see `TrackerBootstrap#logEffectiveConfiguration`), prevents
> this from recurring silently.

### Implementation

- `TrackerProperties` — global defaults, the `cores` override map
  (`CoreConfig` / `CronOverride`, all-nullable so unset means "inherit"), and
  `resolvedCore(String)` which produces a null-free `ResolvedCoreConfig`.
- `TrackerBootstrap#buildTrackerProperties(String coreName)` — bridges the
  resolved configuration into the flat `Properties` expected by the tracker
  constructors and the `TrackerScheduler`, once per core.
- `RepositoryClientConfig#repositoryProperties` — carries only the repository
  **connection** keys (host, port, secureComms, shared secret); it no longer
  duplicates tracker tuning.
