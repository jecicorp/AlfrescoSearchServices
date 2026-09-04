# Releasing Pristy Search Services

Release engineering for this fork: branches, version numbers, what the pipeline
publishes, and the one-time setup the GitLab project needs.

## Conventions

- Long-lived branches: **`develop`** (active work) and **`stable`** (released code).
  A release is a merge `develop` → `stable`, then a tag cut from `stable`.
- Tags carry the **plain version number, no `v` prefix**: `1.0.0`, not `v1.0.0`.
- `develop` always carries a `-SNAPSHOT` version; `stable` carries the released one.

## One-time project setup

Do this before the first tag, otherwise the publication jobs fail.

1. **Docker Hub credentials.** In *Settings → CI/CD → Variables*, add
   `DOCKERHUB_USER` and `DOCKERHUB_TOKEN` (an access token, not the account
   password). Mark both **Masked** and **Protected** — the jobs that use them only
   run on `develop`, `stable` and tags, so protecting the variables keeps them out
   of merge requests coming from forks.
2. **Public Maven registry.** In *Settings → Packages and registries*, allow
   anonymous pulls from the Package Registry. Without it, `pristy-ecm` and outside
   integrators cannot resolve `fr.pristy:pristy-search-subsystem-solr9` without a
   token.
3. **Protected branches and tags.** Protect `stable` and the `*.*.*` tag pattern so
   only maintainers can trigger a release pipeline.

No other secret is needed: dependencies resolve from Maven Central and
artifacts.alfresco.com, and `mvn deploy` authenticates with the ephemeral
`CI_JOB_TOKEN` (see `ci_settings.xml`).

## Cutting a release

Example for `1.0.0`, starting from an up-to-date `develop`.

`CHANGELOG.md` is curated by hand, in Keep a Changelog form. `mise run
changelog:unreleased` renders the conventional commits since the last tag and is
meant as a *draft* to pick from — not as the final text. Do not run `mise run
changelog` before `1.0.0` is tagged: with no tag in the history it regenerates the
whole file from every commit of the fork and drops the curated entries.

```bash
# 1. Close the changelog: move the entries under a real version heading and date.
mise run changelog:unreleased    # draft, to be edited down
vim CHANGELOG.md

# 2. Merge into stable.
git switch stable            # first release: git switch -c stable
git merge --no-ff develop

# 3. Set the release version across every module.
mvn versions:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false
git commit -am "release: 1.0.0"

# 4. Tag and push. Pushing the tag is what publishes.
git tag 1.0.0
git push origin stable
git push origin 1.0.0

# 5. Reopen development on the next snapshot.
git switch develop
git merge --no-ff stable
mvn versions:set -DnewVersion=1.1.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "chore: open 1.1.0-SNAPSHOT"
git push origin develop
```

## What a tag pipeline publishes

| Job | Output |
|-----|--------|
| `maven-build` | Builds the whole reactor except `e2e-test`, runs the unit tests, surfaces them as JUnit reports |
| `sbom` | `bom-runtime.json` (compile scope) and `bom-full.json` (+ provided), CycloneDX |
| `dependency-scan` | Trivy over both SBOMs; **CRITICAL on the runtime SBOM fails the pipeline** |
| `docker-build-solr` | `jeci/pristy-search-services:1.0.0` and `:latest` |
| `docker-build-trackers` | `jeci/pristy-indexing-trackers:1.0.0` and `:latest` |
| `maven-deploy` | All module JARs to the project's GitLab Package Registry |

On `develop`, the same jobs run but the images are tagged `:develop` and the JARs
are published as snapshots. The `e2e-test` module never runs in CI — it needs a full
ACS + Solr + Postgres stack; run it by hand with `mise run e2e:test` before tagging.

## Consuming the artifacts

Until the Maven Central publication lands (planned for 1.1), consumers declare the
project registry:

```xml
<repositories>
  <repository>
    <id>pristy-search-services</id>
    <url>https://gitlab.com/api/v4/projects/85972034/packages/maven</url>
  </repository>
</repositories>
```

The Solr distribution ZIP and the Docker images need no repository declaration.

## Still to do for Maven Central (1.1)

The `fr.pristy` namespace is already verified on Central. What the build still lacks:

- `maven-source-plugin` and `maven-javadoc-plugin` executions producing the required
  `-sources.jar` and `-javadoc.jar`. Javadoc will need `-Xdoclint:none`: most of this
  codebase is inherited Alfresco source and does not pass doclint.
- `maven-gpg-plugin`, with the signing key and passphrase as protected CI variables.
- The `central-publishing-maven-plugin`, plus a Central Portal token.
- A decision on scope. Only the artifacts a third party would genuinely depend on are
  worth the irreversibility of Central — `pristy-search-subsystem-solr9` and
  `pristy-solrclient-lib`. The distribution ZIP and the Solr plugin JAR are
  deployment artifacts, better served by the images and the release assets.

Central releases are immutable: once `1.1.0` is published under a coordinate, that
coordinate can never be reused. Settle the naming before the first push.
