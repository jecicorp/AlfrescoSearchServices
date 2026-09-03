# Third-party licenses in the distribution

The distribution zip carries jars from three different places, and each set is inventoried
differently. `ThirdPartyLicensesIT` (in `search-services/packaging`) guards the seam between them.

| Inventory | Covers | Maintained by |
|-----------|--------|---------------|
| `solr/licenses/` | every jar Apache Solr itself ships (~1100 files, one LICENSE/NOTICE per jar) | Apache, upstream |
| `licenses/THIRD-PARTY.txt` | the Maven dependency tree of `pristy-search` | `license-maven-plugin`, generated at build time |
| `licenses/notice.txt` | the jars the `packaging` module copies in on its own | by hand |

Nothing is declared twice. The hand-maintained file used to list **every** jar in the zip, which is
why it drifted: it was still the Solr 6.6.5-patched inventory, and only 50 of its 190 entries matched
what the build actually shipped.

## What the test checks

Two assertions, both over `target/solr-libs/libs` — the jars the build adds to the Solr webapp, which
is what `WEB-INF/lib` overlays on top of Solr's own:

- **`everyShippedJarHasADeclaredLicense`** — every such jar appears in the generated inventory or in
  `notice.txt`. This is the guard: a new dependency cannot reach the distribution undeclared.
- **`noStaleHandDeclaration`** — every jar named in `notice.txt` is still shipped, so the hand-written
  half cannot rot the way it did before.

Matching is by `artifactId-version` **prefix**, not by file name, so a jar carrying a classifier
(`netty-transport-native-epoll-4.2.6.Final-linux-x86_64.jar`) still resolves to its artifact.

Solr's own tree is deliberately **not** checked: Apache maintains `solr/licenses/` and re-deriving it
here would only duplicate it, one Solr release behind.

## When the test fails

- *Jar found with no declared license, and it is a Maven dependency* — nothing to do by hand. The
  plugin regenerates `THIRD-PARTY.txt` on the next build; a red test here means the build did not run
  `generate-resources` on `pristy-search`, or the dependency arrived through a scope the inventory
  excludes.
- *Jar found with no declared license, and it is copied in by `packaging`* — the `copy` execution of
  `maven-dependency-plugin` in `search-services/packaging/pom.xml` adds jars outside any dependency
  tree. Declare those in `notice.txt`, under the right `=== License ===` section.
- *Stale hand declaration* — the artifact changed version or stopped shipping. Fix the line, or drop it.

## Inventory generation for `pristy-search`

The `third-party-licenses` execution is configured in `search-services/pristy-search/pom.xml`, and it
deviates from the inherited configuration on two points.

- **`excludedScopes` is `test`, not `provided,test`.** `copy-dependencies` populates the shipped libs
  with `includeScope=compile`, and Maven's scope filter reads that as compile **plus provided and
  system**. Roughly 100 of the shipped jars — the Solr and Lucene transitive tree, jersey, netty,
  zookeeper — are therefore `provided` yet distributed. Excluding that scope would leave them
  uninventoried while still shipping them.
- **`excludedGroups` is `org.alfresco|^findbugs`.** `findbugs:annotations:1.0.0` (2006) declares no
  license in its POM and none in its jar, and `packaging` already drops it from the distribution, so
  it is out of scope for an inventory of what ships. The leading anchor matters: the pattern is
  matched **partially**, so a bare `findbugs` also excludes `com.google.code.findbugs:jsr305`, which
  *is* shipped and *is* Apache 2.0.

`failOnMissing` stays `true`. With those two adjustments the whole shipped tree resolves, so the
build now fails if a dependency arrives with no license metadata — which is the point.

## What the distribution ships from Solr

The assembly excludes most of the Solr tree. Its exclusion list was written for the Solr 6 layout
(`contrib/`, `dist/`), names that no longer exist in Solr 9: the modules moved to `modules/` and two
new top-level directories appeared. All of it was shipping unnoticed — 346 jars, including Kafka,
Hadoop, Calcite, langchain4j, carrot2, the AWS SDK and Google Cloud Storage.

| Directory | Jars | Shipped |
|-----------|------|---------|
| `server/` | 139 | yes, required |
| `modules/analysis-extras` | 12 | yes — the Alfresco schema needs the ICU analysis chain |
| `modules/` (16 other modules) | 289 | no |
| `cross-dc-manager/` | 40 | no |
| `prometheus-exporter/` | 5 | no |

Only `analysis-extras` is ever loaded: the Dockerfile sets `SOLR_MODULES=analysis-extras`, and a Solr
9 module that is not named there is never on the classpath. The 16 others were dead weight and CVE
surface.

**The trade-off**: an operator can no longer enable `extraction`, `s3-repository`, `ltr` or any other
module by editing `SOLR_MODULES` alone — the jars are not in the image. Re-add the module's directory
to the assembly if one becomes needed.

Note the pattern anchoring, again: the exclusions are `modules/**`, root-anchored, **not**
`**/modules/**`. Solr keeps its Jetty module definitions in `server/modules/` — `ssl.mod` and
`https.mod` among them, which the `https` secure-comms mode needs — and an unanchored pattern takes
those with it.

## The licence statement carried by the distribution

Three files in the zip state, in prose, under what terms the distribution is offered:
`licenses/notice.txt`, `licenses/js-notice.txt` and the `README.MD` at the root. All three
inherited Alfresco's Enterprise boilerplate — *"made available under the terms of the Alfresco
agreement located at www.alfresco.com/legal/agreements/ or other commercial agreement"*, and the
`README.MD` added *"if the software was purchased under a paid Alfresco license, the terms of the paid
license agreement will prevail"*. Both statements contradicted the LGPL v3 grant made by the root
`LICENSE`, by `licenses/license.txt` sitting in the same directory, and by every source header — and
the `README.MD` is the most visible of the three to whoever unpacks the archive. They were replaced by
a notice that satisfies what LGPL v3 actually requires of a fork, through GPL v3 §4 and §5 as the LGPL
incorporates them:

| Requirement | In the file |
|-------------|-------------|
| §4 — keep intact all notices of the original copyright | the `2005-2023 Alfresco Software, Ltd.` line stays, verbatim |
| §5(a) — state that the work is modified, and give a date | the paragraph naming March 2026 and what changed |
| §5(b) — state the licence the whole carries | the LGPL v3-or-later grant, pointing at `licenses/license.txt` |
| §15/§16 — the warranty disclaimer | the `WITHOUT ANY WARRANTY` paragraph |
| §5 — our own copyright on the modifications | the `2026 Jeci SARL` line |

What was removed is not the attribution but Alfresco's **offer of licence**, which is not ours to
pass on. The trademark paragraph is not required by the licence; it is there because the fork keeps
the names "Alfresco" and "Apache Solr" throughout, and nominative use is safest when it says outright
that it implies no endorsement.

Do not shorten the prose on the grounds that it is not an inventory: `licenses/license.txt` is the bare
LGPL text, with no statement of who applies it to what. These three files are the only place the
distribution says it.

`README.MD` is filtered by the assembly, so `${project.version}`, `${solr.base.version}` and
`${project.timestamp}` resolve. `${project.revision.key}` did **not** — it is defined in no POM and
shipped literally on the build line for years. It was dropped rather than defined.

### What `js-notice.txt` inventories

Its `=== MIT ===` list covers the ten JavaScript files the `packaging` resources overlay onto the Solr
admin webapp (`webapp/libs`, plus a `webapp/js/lib/jquery-3.5.1.min.js` on the Solr 6 path Solr 9 no
longer uses). The list is exact as of Solr 9.10.1; `js/angular/app.js` is the fork's own and needs no
declaration.

Its second section used to enumerate Solr's *own* admin JavaScript, and was still the Solr 6 set —
`chosen.js`, `console.js`, `jquery.sammy.js`, `ZeroClipboard.js`, `linker.js` and ten more, none of
which exist in Solr 9. It was replaced by a pointer to `solr/LICENSE.txt`, `solr/NOTICE.txt` and
`solr/licenses/`, which the zip carries verbatim. Same reasoning as for the jars: re-deriving Apache's
inventory here only duplicates it, one Solr release behind. Do not re-enumerate it.

## Known gaps

- `org.restlet.ext.servlet` ships without the `org.restlet` core it depends on. Pre-existing; nothing
  in the fork appears to load it.
- The Solr per-jar licences ship **twice**: at `solr/licenses/` with the Solr tree, and again at
  `licenses/solr/` through the `copy-solr-licenses` execution. 1139 files each way.
- `WEB-INF/lib` merges Solr's own webapp libs with ours, and the two disagree on several versions
  (`zookeeper` 3.9.4 and 3.9.5, netty 4.1 and 4.2, jackson 2.18.0 and 2.18.8 all ship side by side).
  A `delete-duplicate-jars` antrun step exists but still targets Solr 6 file names.
