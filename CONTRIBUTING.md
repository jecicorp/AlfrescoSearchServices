# Contributing

Thanks for your interest in contributing to Pristy Search Services.

This is a community fork of Alfresco Search Services, maintained by
[Jeci](https://jeci.fr) as the search tier of Pristy ECM. It is **not** affiliated
with Hyland or Alfresco: please do not raise issues about this fork on Alfresco's
issue tracker, and do not expect Alfresco support to cover it.

## Raising issues

Use the issue tracker of this project:
<https://gitlab.com/pristy-oss/pristy-search-services/-/issues>.

A useful report includes the Solr and Alfresco versions, whether the trackers run in
`none` / `secret` / `https` secureComms mode, and the relevant excerpt of both the
Solr and the trackers logs. Query problems are almost always visible in the Solr log
even when the symptom appears elsewhere.

## Branches

- `develop` — active development. Target your merge requests here.
- `stable` — released code. Only release commits and hotfixes land on it.

Feature branches use a `feature/` or `fix/` prefix. Releases are cut from `stable`
and tagged with the plain version number, no `v` prefix — see
[docs/release.md](docs/release.md).

## Building and testing

```bash
mise run build     # build, no tests
mise run test      # unit tests: solrclient-lib, search, indexing-trackers
```

Or with Maven directly, excluding the end-to-end module:

```bash
mvn verify -pl '!e2e-test'
```

The **end-to-end tests** need a full ACS + Solr + Postgres stack and therefore do not
run in CI. Bring the stack up and run them locally before proposing anything that
touches indexing or query parsing:

```bash
mise run e2e:rebuild
mise run e2e:test
```

The CI pipeline runs the unit tests, produces two CycloneDX SBOMs and scans them with
Trivy. A CRITICAL vulnerability in a dependency we actually ship fails the pipeline.

## Coding conventions

- Match the surrounding code. Much of this codebase is inherited from Alfresco and
  does not follow a single style; consistency with the file you are editing beats
  consistency with any style guide. Avoid reformatting blocks unrelated to your change.
- **License headers are enforced at build time** by `license-maven-plugin`, and it
  only recognises headers framed by its own process tags. Never hand-write a plain
  `/* Copyright … */` header — the build will fail with `There are N file(s) with no
  header`. Files created in this fork carry the Pristy LGPL v3 header; files derived
  from Alfresco keep the Alfresco community header. When you add a new file, register
  it in the right `check-licenses-pristy` `<includes>` list and stamp it with:

  ```bash
  mvn license:update-file-header -Dlicense.update.dryrun=false
  ```

- Java packages stay under `org.alfresco.*`. They are referenced by name from
  `schema.xml` and `solrconfig.xml`, so renaming them breaks existing cores.

## Licensing of contributions

The project is distributed under the **LGPL v3**. By submitting a merge request you
agree that your contribution is licensed under the same terms.
