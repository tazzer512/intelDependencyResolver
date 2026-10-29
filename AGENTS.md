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
  Fix it with `rm -rf build/classes/kotlin/test build/kotlin/compileTestKotlin`.

## Layout (`src/main/kotlin/com/github/arjenvanharen/mavendeps`)

`core/` is plain Kotlin with no IntelliJ APIs and is fully unit-tested. `ide/` is the thin IntelliJ layer.

| File | Responsibility |
| --- | --- |
| `core/Model.kt` | `Pos`, `Problem` (with `fixes`), `Gav`, `Dependency` (optional, exclusions), `PomModel` (incl. `profiles`/`Activation`), `PomEntry` |
| `core/PomParser.kt` | StAX parser to `XNode` tree with positions (`pos` = end of start tag, `endPos` = end of end tag) to `PomModel`. DTD/external entities disabled. `parse(bytes)` and `parse(text)` give identical positions. |
| `core/StructureValidator.kt` | Single-file checks (tags, coordinates, dependencies), each with quick fixes where possible |
| `core/Workspace.kt` | Immutable view over all parsed POMs: parent resolution (relativePath, then reactor GA lookup), ancestry, properties, interpolation |
| `core/Analyzers.kt` | `PomAnalyzer` (per POM, re-run only when affected) and `GlobalAnalyzer` (every refresh, must be cheap). Validation suite plus `RepeatedVersionAnalyzer` (suggestions) |
| `core/AnalysisEngine.kt` | Discovery, incremental refresh (`INCREMENTAL`/`RESCAN`/`FULL`), affected-set computation |
| `core/CacheStore.kt` | Binary gzip disk cache. **Bump `VERSION` whenever anything serialized changes** (model, problems, fixes). |
| `core/Fixes.kt` | `QuickFix` sealed types (data, cacheable) and `FixApplier`, which re-parses the current text, locates elements by `ElementRef` (path + name + pos) and produces `TextEdit`s that keep the document's indentation and line separator |
| `core/Resolution.kt` | `LocalRepository` (read-only `~/.m2`, never downloads), `DependencyResolver` (effective models, BOM imports, profiles via `ActivationContext`, nearest-wins trees) |
| `core/DependencyQueries.kt` | `DependencySearch` (declared vs transitive hits) and `VersionUpdatePlanner` (literal/property updates plus exclusions) |
| `ide/MavenAnalysisService.kt` | Project service: debounced, merged refresh requests; disk cache in `PathManager.getSystemDir()`; `runQuery` for background queries |
| `ide/PomFileListener.kt` | VFS events turned into incremental refreshes (saved files only) |
| `ide/FixRunner.kt` | Applies fixes to editor documents in one undoable `WriteCommandAction`, then saves (which triggers re-analysis) |
| `ide/ui/AnalyzerToolWindow.kt` | "Problems" tab: module tree, problems, quick fixes (lightbulb, Alt+Enter, right-click), "Apply All Suggestions" |
| `ide/ui/DependencyPanel.kt` | "Dependencies" tab: find a dependency (declared / transitive / missing POMs) and "Update Version…" with a review dialog |

## Invariants the tests lock in

- **Incremental refresh** re-parses only changed files and re-validates exactly the affected POMs: dependents via parent/BOM edges (transitive), aggregators of added/removed POMs (one hop), and ancestors of changed POMs, because parents may use properties their children define. `AnalysisEngineTest` asserts the parsed/reused/revalidated counts; update them deliberately.
- **External parents** (outside the workspace) silence inherited checks rather than guessing.
- **Fixes are data.** Every fix can be applied with `FixApplier` against plain text. `QuickFixFlowTest` checks that each fix makes its problem disappear on the next incremental refresh. A POM changed since analysis gives a `FixException` and is never corrupted.
- **Resolution matches Maven** for common cases. Mediation is nearest-wins, BFS, first declaration wins. test/provided/optional dependencies are not transitive. Exclusions use `*` wildcards. The module's dependencyManagement overrides transitive versions. Cycles are dropped. Profiles are activated by activeByDefault, os, jdk and property. Version ranges, relocations and file activation are not supported. This was verified identical to `mvn dependency:tree` on a real project.
- **Version update** never selects "declare directly" by default. Exclusions go on the direct dependency that brings the transitive copy in, which may be declared in a parent. Modules whose dependencyManagement will force the new version need no exclusions.

## Conventions

- Kotlin pitfall: `java.nio.file.Path` is `Iterable<Path>`. `set += path`, `list + path` and `setOf(...) + path` add path *segments*. Use `.add(path)` or `plusElement(path)`.
- Keep `core/` free of IntelliJ imports. Put logic there with a unit test, and keep `ide/` as glue.
- Tests are JUnit 4 and use `Fixture` (temp workspace plus a fake `.m2`). `Fixture.pom()` uses 4-space indentation.
- New problem types: give them `fixes` where an automatic fix is unambiguous. Use `SetChild(value = null)` or `DefineProperty(value = null)` to ask the user for a value.
- Edits only apply to saved content. The analysis reads files from disk, not unsaved editor documents.
