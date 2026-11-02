# Feature gap analysis (2026-11-02)

An analysis of the plugin at 0.2.0 plus the unreleased code impact work: what a Maven user would expect from it that
is missing or weaker than it could be. Each item says why it matters, how it fits the existing design, and whether it
is implemented in this round.

## What the plugin covers today

| Area | Coverage |
| --- | --- |
| Validation | XML syntax, POM structure (project, parent, dependency, exclusion tags), coordinates, modules, parents, packaging, duplicate coordinates, managed versions, property references, repeated-version suggestions |
| Resolution | Inheritance, interpolation, dependencyManagement with BOM imports, scopes, optional, exclusions, nearest-wins, profiles (activeByDefault/os/jdk/property) |
| Editor | Problems with quick fixes, effective-version inlay hints, newer local versions |
| Explorer / Graph | Module and library views, conflicts with one-step alignment, build-order module graph |
| Security | osv.dev scan, fix all, code impact check, AI fix report |
| Dependencies | Search declared/transitive, paste box, update version across POMs |

## Gaps found

### 1. Build plugins are invisible (high)

`<build><plugins>` and `<pluginManagement>` are not modelled at all. Maven itself warns about the most common plugin
mistakes, and the plugin cannot see them:

- **Plugin without a version** that no `<pluginManagement>` in the POM or its parents manages. Maven warns
  ("'build.plugins.plugin.version' for … is missing") because the build then depends on the Maven version.
- **The same plugin declared twice** in one `<plugins>` section (Maven warns; the second silently merges).

*Fit:* add `Plugin` to `PomModel` (parser + cache, bump `CacheStore.VERSION`), and a `PluginValidator`
`PomAnalyzer` that stays silent when a parent is outside the workspace (the existing rule for inherited checks).
Fixes: "Set <version>…" (asks for the value) and "Remove duplicate declaration".
**Implemented.**

### 2. Dependencies on workspace modules with the wrong version (high)

A module that depends on a sibling with a hard-coded version that no longer matches the sibling's version silently
resolves the old artifact from `~/.m2` instead of the reactor build, a classic multi-module bug after a version bump.

*Fit:* `PomAnalyzer` over the interpolated dependencies; WARNING with a fix that sets the version to
`${project.version}` when the depending POM shares the module's version, otherwise to the module's version.
**Implemented.**

### 3. Redundant versions next to dependencyManagement (medium)

A `<version>` in `<dependencies>` equal to the version that `<dependencyManagement>` (this POM, a parent or a
workspace BOM) already supplies is noise, and drifts the next time the managed version changes. IntelliJ's own Maven
support flags this.

*Fit:* reuse `DependencyVersionValidator`'s management lookup, now keeping the managed value. INFO (suggestion) with
"Remove <version>"; needs the position of the `<version>` element in `Dependency` (cache bump).
A version that *differs* from the managed one is deliberate more often than not, so it is not reported.
**Implemented.**

### 4. Release depending on SNAPSHOT (medium)

A project with a release version that depends on a `-SNAPSHOT` artifact cannot be released reproducibly
(`maven-release-plugin` refuses it).

*Fit:* small `PomAnalyzer`; WARNING without an automatic fix (the right release version is unknown).
**Implemented.**

### 5. Version ranges are not resolved (medium)

`[1.0,2.0)` in a dependency (own or transitive) currently leaves the node without a usable POM, so its subtree is
missing. Maven picks the highest version that satisfies the range; with a local-only resolver the equivalent is the
highest version in `~/.m2` that satisfies it.

*Fit:* `VersionRange` in `MavenVersion.kt` (parse/contains, unit-tested); `DependencyResolver.tree` resolves a range
before mediation. The node keeps the requested range for display. Listed under *Limitations* today.
**Implemented.**

### 6. Relocations are not followed (medium)

Artifacts like `mysql:mysql-connector-java` (now `com.mysql:mysql-connector-j`) publish a relocation POM. Maven follows
it; here the tree shows the empty relocation POM, the real artifact is missing, and conflicts/scans see the wrong
coordinates.

*Fit:* parse `<distributionManagement><relocation>` into `PomModel`; the resolver follows it (bounded hops, cycle
safe) when it creates a node, and records where it was relocated from.
**Implemented.**

### 7. No text export of a dependency tree (low, often asked for)

Sharing a resolved tree (ticket, review, chat) needs `mvn dependency:tree`. The Explorer could copy the same format.

*Fit:* `DependencyTreeText.render` in `core/` (tested), "Copy Dependency Tree" on a module in the Explorer.
**Implemented.**

### 8. Problems tab cannot hide suggestions (low)

Large projects get many INFO suggestions (repeated versions, now redundant versions) that bury errors.

*Fit:* a "Show Suggestions" toggle next to "Show Only POMs With Problems".
**Implemented.**

## Considered, not in this round

| Idea | Why not now |
| --- | --- |
| Unused / undeclared dependencies (`dependency:analyze`) | `ClassIndex` from the code impact check could do it, but runtime-only, annotation-processor and starter dependencies give many false positives without a proper reachability model. Worth its own design. |
| Exclusions that exclude nothing | Needs resolution in the validation suite, which must stay cheap; better as an Explorer annotation later. |
| Plugin versions in inlay hints / security scan | Builds on item 1; the vulnerability scan only covers runtime artifacts today. |
| Analysing unsaved editor content | Positions and fixes are tied to the file on disk by design (see AGENTS.md invariants). |
| File-based profile activation | Depends on the build directory state; rare in practice. |
| Settings page (repository path, osv endpoint) | Repository location already follows `maven.repo.local` and `settings.xml`. |
