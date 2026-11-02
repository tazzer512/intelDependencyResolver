# Changelog

All notable changes to this plugin. Versions follow [semantic versioning](https://semver.org).
The section of the current `pluginVersion` becomes the plugin's change notes.

## [Unreleased]

### Added
- Security tab: "Code is affected by CVE" checkbox. Checks locally, at class level, whether the project's code or the
  libraries it uses reach each advisory's vulnerable classes, and flags advisories that cannot be reached as
  "Does not impact code".
- Security tab: "Copy Fix Report for AI" copies a short text brief of the vulnerabilities to fix (CVEs, fix versions,
  declaring POMs, code that uses the vulnerable classes), ready to paste into an AI assistant.
- Build plugins are checked: a plugin without a version that no `<pluginManagement>` manages, and a plugin declared
  twice. Quick fixes set the version or remove the duplicate.
- A dependency on a workspace module with a version the module does not have (Maven would take the old artifact from
  the repository) is reported, with a fix to use `${project.version}` or the module's version.
- A `<version>` equal to the version `<dependencyManagement>` already supplies is shown as a hint, with a fix that
  removes it.
- A release that depends on a `-SNAPSHOT` dependency, plugin or parent is reported.
- Version ranges resolve to the highest matching version in the local repository, and relocated artifacts (e.g.
  `mysql:mysql-connector-java`) are followed to their new coordinates. The Explorer shows both.
- Explorer: "Copy Dependency Tree" copies a module's resolved tree in the format of `mvn dependency:tree`.
- Problems tab: "Show Hints" toggle to hide suggestions and other hints.

### Fixed
- Quick fixes and planned changes are no longer applied to a POM with unsaved changes or changed since the analysis;
  the edit could land on another element at the same position. The POM is saved and re-analysed first.
- Cancelling a background job (resolving, graph, scan, search) no longer leaves its tab unable to run again.
- "Project uses its own coordinates as parent" is also reported when the groupId is inherited.
- No version property is suggested under a name that is not a valid XML element.
- Faster code impact checks on large projects; no `~/.m2` access on the UI thread; less work while pasting.

### Security
- Bounded interpolation output, POM discovery (symlink loops, at most 20,000 POMs) and BOM import nesting against
  crafted projects and repositories.
- The code impact check does not follow symlinks; advisory ids from osv.dev are validated and paging is bounded.

## [0.2.0] - 2026-10-29

### Added
- Versioning: `pluginVersion` in `gradle.properties`, this changelog as the plugin's change notes, and the
  `bumpVersion` and `bup` Gradle tasks.
- Ready-built plugin zips per version in `bup/`.

## [0.1.0] - 2026-10-29

### Added
- Problems tab: validation of XML, POM structure, multi-module consistency and properties, with quick fixes and
  "Apply All Suggestions".
- Problems, quick fixes and version inlay hints in the `pom.xml` editor.
- Explorer tab: resolved dependency trees per module and per library, with a details pane.
- Version conflicts: plain-language explanation, "Use X everywhere" and "Resolve All Version Conflicts…".
- Security tab: scans direct and transitive dependencies against osv.dev, "Fix All Vulnerabilities…".
- Graph tab: interactive module graph in build order.
- Dependencies tab: find a dependency, paste a snippet, "Update Version…".
- Incremental analysis with a disk cache.

### Security
- Hardened against crafted POMs and `~/.m2` content (path traversal, nesting depth, file size, cache sizes).
