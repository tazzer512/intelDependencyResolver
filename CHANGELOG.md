# Changelog

All notable changes to this plugin. Versions follow [semantic versioning](https://semver.org).
The section of the current `pluginVersion` becomes the plugin's change notes.

## [Unreleased]

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
