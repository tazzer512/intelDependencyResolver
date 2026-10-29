# Maven Dependency Analyzer — agent guide

IntelliJ IDEA 2026 plugin (Kotlin) that analyses Maven POMs in single- and multi-module projects. It uses its own POM parser,
not IntelliJ's Maven plugin, so it works without a Maven import and stays fast.

## Build, test, run

| Task | Command |
| --- | --- |
| Unit tests (core, no IDE) | `./gradlew test` |
| One test class | `./gradlew test --tests '*FixApplierTest*'` |
| Plugin zip | `./gradlew buildPlugin` → `build/distributions/maven-dependency-analyzer-<version>.zip` |
| Sandbox IDE with the plugin | `./gradlew runIde` (log: `.intellijPlatform/sandbox/maven-dependency-analyzer/IU-2026.2.3/log_runIde/idea.log`) |

- Targets IntelliJ IDEA 2026.2.3 (`sinceBuild = 261`). Java **and** Kotlin target JVM 25; they must match or the build fails.
- A running `runIde` holds Gradle locks. Stop it before building if Gradle hangs on a lock.
- The IDE SDK download and extraction needs several GB of free disk space in `~/.gradle`.
- After changing constructor or default parameters, a stale incremental build can cause `NoSuchMethodError` in tests.
  Fix it with `./gradlew compileTestKotlin --rerun test --no-build-cache`. Deleting the class directories alone is not enough, because the build cache restores them.

## Layout (`src/main/kotlin/com/github/arjenvanharen/mavendeps`)

`core/` is plain Kotlin with no IntelliJ APIs and is fully unit-tested. `ide/` is the thin IntelliJ layer.

| File | Responsibility |
| --- | --- |
| `core/Model.kt` | `Pos`, `Problem` (with `fixes`), `Gav`, `Dependency` (optional, exclusions), `PomModel` (incl. `profiles`/`Activation`), `PomEntry` |
| `core/PomParser.kt` | StAX parser to `XNode` tree with positions (`pos` = end of start tag, `endPos` = end of end tag) to `PomModel`. DTD/external entities disabled. `parse(bytes)` and `parse(text)` give identical positions. |
| `core/StructureValidator.kt` | Single-file checks (tags, coordinates, dependencies), each with quick fixes where possible |
| `core/Workspace.kt` | Immutable view over all parsed POMs: parent resolution (relativePath, then reactor GA lookup), ancestry, properties, interpolation |
| `core/Analyzers.kt` | `PomAnalyzer` (per POM, re-run only when affected) and `GlobalAnalyzer` (every refresh, must be cheap). Validation suite plus `RepeatedVersionAnalyzer` (suggestions) |
| `core/AnalysisEngine.kt` | Discovery, incremental refresh (`INCREMENTAL`/`RESCAN`/`FULL`), affected-set computation, `RefreshProgress` callbacks (reading, then validating) |
| `core/CacheStore.kt` | Binary gzip disk cache. **Bump `VERSION` whenever anything serialized changes** (model, problems, fixes). |
| `core/Fixes.kt` | `QuickFix` sealed types (data, cacheable) and `FixApplier`, which re-parses the current text, locates elements by `ElementRef` (path + name + pos) and produces `TextEdit`s that keep the document's indentation and line separator |
| `core/Resolution.kt` | `LocalRepository` (read-only `~/.m2`, never downloads), `DependencyResolver` (effective models, BOM imports, profiles via `ActivationContext`, nearest-wins trees) |
| `core/DependencyQueries.kt` | `DependencySearch` (declared vs transitive hits) and `VersionUpdatePlanner` (literal/property updates plus exclusions) |
| `core/MavenVersion.kt` | Maven version ordering (`ComparableVersion` common cases: numeric items, alpha < beta < milestone < rc < snapshot < release < sp); `isPreRelease` |
| `core/ModuleGraph.kt` | `ModuleGraph` (dependency incl. BOM import, parent and aggregation edges between workspace POMs) and `LayeredLayout` (build-order layers, cycle-safe, barycenter ordering) |
| `core/VersionHints.kt` | Per declared dependency: effective version, its source (literal / `${prop}` / managed by which POM) and the newest newer version in `~/.m2`; `anchor()` places the editor hint |
| `core/VersionAligner.kt` | `VersionAligner`: version conflicts (`conflicts()`, newest release wins) and alignment plans (update declarations/properties, pin transitive-only modules in the root POM's `<dependencyManagement>`, never lower a version). Used by conflict resolution and vulnerability fixes. |
| `core/Vulnerabilities.kt` | `OsvClient` (osv.dev batch query plus cached advisory details through an `OsvTransport`), `VulnerabilityScanner` (targets: every included artifact version of every module, direct and transitive, minus workspace modules; `fixRequests`), `Cvss` v3 base scores |
| `core/Json.kt` | Minimal JSON reader/quoter so `core/` needs no library |
| `core/DependencyExplorer.kt` | `DependencySnippet` (parses pasted `<dependency>` XML, `g:a:v`, Gradle and `dependency:tree` lines; resolves `${prop}` versions against the workspace), `DependencyIndex` (resolves all modules, groups them into `Library` entries with conflict detection) |
| `ide/MavenAnalysisService.kt` | Project service: debounced, merged refresh requests; `versionHints(path)` cached per analysis; disk cache in `PathManager.getSystemDir()`; analysis progress goes to the IDE indicator and to `MavenAnalysisProgressListener.TOPIC`; `runQuery` runs background queries that take a `QueryProgress` and accept an `onProgress` callback for inline bars |
| `ide/PomEditorSupport.kt` | `PomProblemAnnotator` (analysis problems in the `pom.xml` editor, quick fixes as Alt+Enter intentions) and `VersionInlayProvider` (version inlay hints). Both show nothing while the document has unsaved changes, because positions come from the file on disk. |
| `ide/Navigation.kt` | Opens files off the EDT (VFS lookup of `~/.m2` files is a slow operation) and navigates on the EDT |
| `ide/PomFileListener.kt` | VFS events turned into incremental refreshes (saved files only) |
| `ide/FixRunner.kt` | Applies fixes to editor documents in one undoable `WriteCommandAction`, then saves (which triggers re-analysis) |
| `ide/ui/AnalyzerToolWindow.kt` | "Problems (N)" tab (N = errors + warnings): module tree, problems, quick fixes (lightbulb, Alt+Enter, right-click), "Apply All Suggestions" |
| `ide/ui/InlineProgress.kt` | Progress bar at the top of a tab, shown while the analysis or the tab's own job runs |
| `ide/ui/ExplorerPanel.kt` | "Explorer" tab: a Modules view (module hierarchy, then each module's resolved tree) or a Libraries view (library, then the modules that use it, conflicts first). Filter, omitted/test/conflict toggles, a conflict banner with "Resolve all to the newest version", a plain-language conflict section per library, details pane with links, open a POM (workspace or `~/.m2`) or a declaration. Libraries show "↑ v in local repo" when `~/.m2` has a newer version. Resolves only while the tab is visible. |
| `ide/ui/GraphPanel.kt` | "Graph" tab: custom-painted module graph in build order (what a module needs is below it). Hover highlights connections, Focus shows what a module needs and what needs it, drag nodes, pan/zoom/fit, filter, problem badges, legend. Resolves only while visible. |
| `ide/ui/SecurityPanel.kt` | "Security" tab: scans on request (it sends g:a:v to osv.dev), vulnerable artifacts by severity with advisories and the modules/paths that bring them in, "Fix All" / per-artifact upgrade through `VersionAligner`, a stale banner after POM changes |
| `ide/ui/PlanDialog.kt` | Review dialog for planned POM changes, plus `reviewAndApply` |
| `ide/ui/DependencyPanel.kt` | "Dependencies" tab: find a dependency (declared / transitive / missing POMs), a paste box that fills the fields (each in a titled border), and "Update Version…" (editable picker of local versions, newest first) with a review dialog. Implements `DependencyActions` for the Explorer. |

## Invariants the tests lock in

- **Incremental refresh** re-parses only changed files and re-validates exactly the affected POMs: dependents via parent/BOM edges (transitive), aggregators of added/removed POMs (one hop), and ancestors of changed POMs, because parents may use properties their children define. `AnalysisEngineTest` asserts the parsed/reused/revalidated counts; update them deliberately.
- **External parents** (outside the workspace) silence inherited checks rather than guessing.
- **Fixes are data.** Every fix can be applied with `FixApplier` against plain text. `QuickFixFlowTest` checks that each fix makes its problem disappear on the next incremental refresh. A POM changed since analysis gives a `FixException` and is never corrupted.
- **Resolution matches Maven** for common cases. Mediation is nearest-wins, BFS, first declaration wins. test/provided/optional dependencies are not transitive. Exclusions use `*` wildcards. The module's dependencyManagement overrides transitive versions. Cycles are dropped. Profiles are activated by activeByDefault, os, jdk and property. Version ranges, relocations and file activation are not supported. This was verified identical to `mvn dependency:tree` on a real project.
- **Pasted snippets**: the first dependency fills the fields. Comments and `<exclusions>` are ignored, and incomplete XML is accepted. A `${prop}` version is used only when every workspace POM resolves it to the same value; otherwise the version is left empty.
- **Local versions**: `LocalRepository.versions` lists only version directories with a POM (failed downloads are skipped). Newer versions skip pre-releases unless the current version is one.
- **Untrusted input** (`HardeningTest`): POMs and `~/.m2` content may be crafted. Repository paths are built only from safe segments and stay under the repository root (`LocalRepository.pomPath` returns null otherwise). POMs over `PomParser.MAX_BYTES` or nested deeper than `MAX_DEPTH` are reported, not parsed. Cache sizes are bounded. Interpolation output is capped. Property fixes require a valid element name. POM text in Swing `<html>` labels is always escaped.
- **Alignment** (`AlignAndSecurityTest`) never lowers a version. A transitive-only module is fixed by a `<dependencyManagement>` pin in its topmost workspace POM, never by exclusions. After resolving, `VersionAligner.conflicts` is empty.
- **Vulnerability scans** send only `groupId:artifactId` and version to osv.dev, and only when the user clicks Scan. The fix version is the lowest fixed version above the current one for each advisory, and the highest of those over all advisories.
- **Version update** never selects "declare directly" by default. Exclusions go on the direct dependency that brings the transitive copy in, which may be declared in a parent. Modules whose dependencyManagement will force the new version need no exclusions.

## Conventions

- Kotlin pitfall: `java.nio.file.Path` is `Iterable<Path>`. `set += path`, `list + path` and `setOf(...) + path` add path *segments*. Use `.add(path)` or `plusElement(path)`.
- User-visible features are described in `README.md` (by tab) and in the `<description>` of `plugin.xml`. Update both when a feature changes. Each tab is one row in the Layout table above.
- Keep `core/` free of IntelliJ imports. Put logic there with a unit test, and keep `ide/` as glue.
- Tests are JUnit 4 and use `Fixture` (temp workspace plus a fake `.m2`). `Fixture.pom()` uses 4-space indentation.
- New problem types: give them `fixes` where an automatic fix is unambiguous. Use `SetChild(value = null)` or `DefineProperty(value = null)` to ask the user for a value.
- Inside a Swing component, `ERROR`/`WARNING` and similar names resolve to `ImageObserver` int constants; give color constants distinct names.
- Editor annotations and hints are shown only when the analysed size/mtime match the file on disk. After analysis, only open `pom.xml` editors are restarted.
- Edits only apply to saved content. The analysis reads files from disk, not unsaved editor documents.
