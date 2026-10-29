# intelDependencyResolver

**Maven Dependency Analyzer**, a plugin for IntelliJ IDEA 2026, analyses Maven POMs in single- and multi-module projects.
It has its own POM parser and resolver, so it works without a Maven import, stays fast on large projects, and never
downloads anything. Transitive dependencies are resolved from your local repository (`~/.m2`).

Open the **Maven Analyzer** tool window to get started.

## Features

### Problems
- Validates XML syntax, POM structure, multi-module consistency (modules, parents, relativePath, packaging,
  duplicate coordinates) and property references.
- Offers quick fixes for most problems: click the lightbulb, press Alt+Enter, or right-click a problem.
- Suggests extracting a version that repeats across declarations into a property. **Apply All Suggestions** does them
  all at once.
- Analysis is incremental: only changed POMs are re-parsed, and only the POMs they affect are re-validated. The
  result is cached on disk, so reopening a project is quick.
- The tab title shows the number of errors and warnings, for example `Problems (3)`.

### In the `pom.xml` editor
- Problems are highlighted in the editor, with the same quick fixes on Alt+Enter.
- Inlay hints show the effective version of managed and `${property}` versions, for example `3.0 · managed by root`
  or `= 1.2`.
- When a newer version is already in your local repository, the hint shows it, for example `↑ 1.5 in local repo`.
  Pre-releases are only suggested if you already use one.

### Explorer
- **Modules** view: the module hierarchy, with each module's resolved dependency tree. Maven's "nearest wins"
  mediation, exclusions, scopes and dependency management are applied.
- **Libraries** view: every library, with the modules that use it and how each module gets it.
- A details pane explains the selected node: where a dependency is declared, the path it comes in through, why a
  version was left out, and which versions are in your local repository.
- Open any POM, from the workspace or from `~/.m2`, or jump to the line that declares a dependency.

### Version conflicts
- A banner shows how many libraries resolve to different versions in different modules.
- Each conflicting library gets a plain-language explanation: which module gets which version, and through which
  dependency.
- **Use X everywhere** resolves one conflict. **Resolve All Version Conflicts…** aligns every conflicting library to
  the newest version found in the workspace.
- To resolve a conflict, the plugin updates declarations, or the property they use. Modules that only get the library
  transitively get the version pinned in `<dependencyManagement>` of their root POM. This keeps the library on the
  classpath, unlike exclusions.
- Versions are never lowered.
- You review every change before it is applied, and the whole change is one undoable step.

### Security
- Scans every dependency of every module, direct and transitive, for known vulnerabilities. It uses
  [osv.dev](https://osv.dev), which covers CVEs and GitHub Security Advisories.
- Results are sorted by severity (with CVSS v3 scores), and show the CVE id, the affected modules, and the path each
  vulnerable library comes in through.
- **Fix All Vulnerabilities…** upgrades every vulnerable library to the lowest version that fixes its known
  vulnerabilities. It works like conflict resolution, so transitive-only libraries are fixed too.
- **Privacy:** a scan runs only when you click **Scan**. It sends only `groupId:artifactId:version` of each
  dependency, using the IDE's proxy settings.

### Graph
- An interactive module graph laid out in build order: what a module needs is drawn below it.
- Edges show dependencies (test and provided scopes dashed), BOM imports, parents and aggregation.
- Hover a module to highlight its connections. **Focus** shows only what a module needs and what needs it.
- Drag modules to move them, drag the background to pan, and use the mouse wheel to zoom. There is also a filter and
  problem badges on modules.

### Dependencies
- Finds a dependency in all POMs (declared) and in all resolved trees (transitive). Wildcards (`*`) are supported.
- To fill in the search fields, paste a `<dependency>` snippet, `groupId:artifactId:version`, a Gradle notation or a
  `mvn dependency:tree` line.
- **Update Version…** shows the versions in your local repository, newest first. It updates every declaration, and
  you review the changes first.

## Build and run

Requires JDK 25.

| Task | Command |
| --- | --- |
| Unit tests | `./gradlew test` |
| Plugin zip | `./gradlew buildPlugin`, which writes `build/distributions/maven-dependency-analyzer-<version>.zip` |
| Sandbox IDE with the plugin | `./gradlew runIde` |

To install the plugin, open **Settings | Plugins**, click ⚙, choose **Install Plugin from Disk…** and select the zip.

## Limitations

- Version ranges, relocations and file-based profile activation are not evaluated. Profiles are activated by
  `activeByDefault`, OS, JDK and property conditions.
- Artifacts whose POM is not in your local repository are reported as missing, and their dependencies are unknown.
  Run a Maven build once to fill `~/.m2`.
- The analysis reads saved files, so editor highlights and hints appear once a POM is saved.

## License

GPL-3.0. See [LICENSE](LICENSE).
