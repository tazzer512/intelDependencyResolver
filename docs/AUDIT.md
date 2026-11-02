# Audit: bugs, security and performance (2026-11-02)

Scope: all of `src/main`, including the new code impact check and fix report, on top of commit `c61ab34`. Same
severity scale and threat model as the 2026-10-29 audit below. Every finding is fixed. Regression tests, marked with
the finding number: `AuditFixesTest` (S1–S3, B1, B3, B4, P1), `CodeImpactTest` (S4, B5) and `CoverageGapsTest` (S5).
The IDE-layer parts (B1's dialog, B2, P4) and the cache/parsing changes (P2, P3, P5, P6) have no unit test; they are
covered by the build and the existing tests passing. The suite runs with JaCoCo (`./gradlew test jacocoTestReport`):
`core/` line coverage went from 92% to 97% and branch coverage from 70% to 76%. What remains uncovered is mostly
defensive error handling (I/O failures in the middle of a read, size limits on jars and sources).

## Security

| # | Sev. | Issue | Fix |
|---|---|---|---|
| S1 | Medium | **Interpolation could allocate gigabytes.** The 64 KB cap on interpolated values was checked only after a whole `String.replace`. A value with thousands of `${big}` references to one large property built the full product first (5,000 × 60 KB = 300 MB) before the check could run. | Build each pass in a buffer that stops at the cap. |
| S2 | Medium | **Runaway discovery through symlinks.** A module folder linking back to an ancestor (`loop -> .`, `<module>loop</module>`) gives ever longer paths to the same `pom.xml`; each one was parsed as a new POM until the OS path limit. The number of POMs was also unbounded, and a file growing between its size check and the read could still be read whole. | Follow each real file once (`toRealPath`), stop at 20,000 POMs with an error on the root POM, and read at most `MAX_BYTES + 1`. |
| S3 | Medium | **Stack overflow through BOM imports.** Each import level recursed (`build` → `effectiveFromRepository` → `build`). A chain of a few thousand BOMs in `~/.m2` (or in the workspace) threw `StackOverflowError`. That is not an `Exception`, so `runQuery` did not report it: the Explorer, Graph or Security tab stayed "loading" until the IDE was restarted. | Ignore imports nested deeper than 32 levels (real BOM hierarchies nest 3–4). `runQuery` reports any `Throwable` to `onFailure`. A test confirms the overflow without the limit. |
| S4 | Low | **Code impact check followed symlinks.** A source file linked to a file outside the project was read and scanned for class names. | Walk sources and compiled classes with `NOFOLLOW_LINKS`. |
| S5 | Low | **OSV data was trusted.** Advisory ids from the API went into the request path unencoded (`../x`, spaces), and a server that kept returning `next_page_token` was paged forever. | Accept only ids that look like advisory ids; at most 100 extra pages per artifact. |

## Bugs

| # | Sev. | Issue | Fix |
|---|---|---|---|
| B1 | **High** | **Fixes could edit the wrong element.** Fixes find their target by the position recorded in the last analysis. The editor gutter hid problems while a POM had unsaved changes, but the Problems tab, the Explorer, Dependencies and Security plans, and Alt+Enter on a stale annotation still applied fixes. After inserting a few lines above, the position could match **another** `<dependency>` (their start tags all end in the same column), so the version or exclusion went to the wrong dependency. The previous audit's "refuse to run when the POM changed" only held when no element existed at the old position. | `AnalysisResult.isCurrent(path)` compares the file on disk with what the analysis read. `FixRunner` applies nothing while any target is unsaved or not current: it saves, re-analyses, and asks the user to apply again. The editor uses the same check. |
| B2 | Medium | **Cancelling a query blocked its tab.** Cancelling "Resolving dependency trees", "Building module graph" or a vulnerability scan from the progress indicator called neither `onDone` nor `onFailure`, so the tab's loading/scanning flag stayed set and it never ran again. | `onFailure` receives a `QueryCancelledException`; the tabs show "cancelled" and Explorer/Graph resolve again the next time they are shown. The Dependencies tab now handles failures too (its status stayed "Searching…"/"Planning…" forever). |
| B3 | Low | "Project uses its own coordinates as parent" was missed when the project inherits its `groupId` (the usual case). | Compare the effective groupId. |
| B4 | Low | The repeated-version suggestion could propose a property name that is not a valid XML element (an artifactId starting with a digit, e.g. `3d.version`); applying it then failed. | Skip such suggestions. |
| B5 | Low | The code impact check reported a corrupt jar as "not in the local repository". | Report it as "not a readable jar". |

## Performance

| # | Sev. | Issue | Fix |
|---|---|---|---|
| P1 | Medium | **Code impact check repeated work per module.** A workspace library's sources were walked once for every module that depends on it, and every classpath jar's references were intersected with the vulnerable classes again for each module (modules × jars × references). | Read each source tree and each jar once per check and keep only the vulnerable names it references. |
| P2 | Medium | Jar summaries for the code impact check built a per-class reference graph for every jar on the classpath, although only the vulnerable jars need one. A large classpath held hundreds of MB of transient sets. | Summaries keep only the union of references; the graph is read for vulnerable jars only. |
| P3 | Low | The `~/.m2` POM cache lived as long as the project and grew without bound. | Bounded to 20,000 POMs. |
| P4 | Low | UI-thread work: "Update Version…" listed the `~/.m2` folder on the EDT (slow on network homes); every keystroke in the paste box rebuilt the workspace index; opening a repository POM from the Explorer checked the file on the EDT. | Listing and the file check run in the background; the paste box reuses one workspace per analysis and skips it when the version has no `${…}`. |
| P5 | Low | `XNode.text` rebuilt and trimmed its string on every access; extraction and validation ask for it many times per element. | Computed once. |
| P6 | Low | Snippet parsing compiled a regex per tag per call. | Cached per tag name. |

## Checked and fine

- Quick fix values, Update Version input and property names are escaped or validated before they reach XML.
- POM- and advisory-derived text in HTML labels and the details panes is escaped; only `http(s)` links are opened.
- The code impact check reads jars with bounded entry counts and sizes and never runs or loads their classes.
- The fix report contains coordinates, advisory ids and summaries, and project-relative paths only.

# Audit: bugs, security and performance (2026-10-29)

Scope: all of `src/main` at commit `680d5e9` (core and IDE layer). Severity: **High** can break the plugin or leak
data from opening an untrusted project, **Medium** gives wrong results or noticeable slowness, **Low** is minor.

The plugin only reads files and never downloads anything. Edits happen only when the user applies a fix. So the main
security threat is an untrusted project, or an untrusted POM in `~/.m2`, crafted so that opening it does harm.

## Security

| # | Sev. | Issue | Fix |
|---|---|---|---|
| S1 | Medium | **Path traversal out of the local repository.** `LocalRepository.pom/versions/pomPath` build paths from groupId/artifactId/version taken from POMs. The only check is for `/`. A groupId `..` becomes `//` and resolves to `/`. An artifactId or version `..` (or `\` on Windows) escapes `~/.m2`. A crafted POM can make the plugin list directories and parse `.pom` files anywhere, and the Explorer's "Open POM" can open them. | Reject empty, `.`, `..`, `/`, `\` and `${` in the coordinate segments, and require that the resolved path stays under the repository root. |
| S2 | Medium | **HTML injection in Swing labels.** The Graph status line puts groupId and version into an `<html>` label without escaping them. Swing's HTML renderer loads `<img src=http://…>`, so selecting a module of a crafted project can make a network request. An Explorer status message also starts with a groupId, and a label is rendered as HTML whenever its text starts with `<html>`. | Escape every POM-derived value in HTML labels, and never start a plain label with POM text. |
| S3 | High | **Unbounded resource use on crafted input.** (a) A deeply nested POM could overflow the stack in the recursive visitors (`collectPropertyRefs`, `FixApplier.find`). `StackOverflowError` is not an `Exception`, so it would escape `runPending` and **end the analysis loop for the rest of the session**. JDK 25 already limits depth to 100 (`jdk.xml.maxElementDepth`), so on the current runtime the risk is lower than it looks; the plugin now enforces the same limit itself. (b) Every POM, in the workspace and in `~/.m2`, is read whole with `readAllBytes`, so a huge `.xml` file referenced as a module can cause an OOM. (c) A corrupt cache file with a huge list count allocates a huge `ArrayList`, which is an OOM and not caught either. | Limit element depth (fail as a syntax problem). Skip POMs over 16 MB with a problem. Bound the list counts and string lengths read from the cache. Keep the refresh loop alive when a refresh throws. |
| S4 | Low | **XML injection through property names.** `DefineProperty` inserts `<name>` verbatim. The name comes from a `${…}` reference, so `${a b}` or `${x><y}` in a POM produces malformed or injected XML when the fix is applied. | Offer "Define property" only for valid XML names; `FixApplier` rejects invalid names. |

## Bugs

| # | Sev. | Issue | Fix |
|---|---|---|---|
| B1 | Medium | The Graph tab builds with `service.workspace()` and `service.resolver()`, which are **two different snapshots**. If an analysis finishes between the two calls, `resolver.effective(p)` hits `model(p)!!` for a POM that no longer exists, an NPE. | Use `resolver.ws` for both. |
| B2 | Medium | Editor annotations and inlay hints check only that the document is saved. Right after a save (300 ms debounce plus the analysis), the last result still describes the old file, so highlights and hints land at **wrong positions**. | Show them only when the analysed file's size and mtime match the file on disk. |
| B3 | Low | "Copy as `<dependency>`" does not escape `&`, `<` and `>` in the coordinates, so the snippet can be malformed XML. | Escape the values. |
| B4 | Low | `DependencyVersionValidator.findByGav` compares the BOM's artifactId without interpolating it. | Compare interpolated coordinates (see P7). |

## Performance

| # | Sev. | Issue | Fix |
|---|---|---|---|
| P1 | Medium | After **every** analysis (every POM save), `DaemonCodeAnalyzer.restart()` re-highlights **all open editors**, including Java and Kotlin files. | Restart only the open `pom.xml` files. |
| P2 | Medium | The inlay provider calls `service.workspace()` on every highlighting pass, which builds a new `Workspace` (an O(N) index) only to name the managing POM. | Use the workspace that the cached hints were computed with. |
| P3 | Medium | `EffectiveModel.managedFor` scans all managed entries with a string prefix test. With a Spring Boot BOM that is about 1,500 entries for each declared dependency, in hints, search and the update planner. | Use a lazy groupId:artifactId index. |
| P4 | Low | `DependencyResolver.tree` builds the full `chain` list for every child edge to detect cycles (O(depth) allocations per edge, up to 50,000 nodes). | Walk the parent pointers without allocating. |
| P5 | Low | `ModuleGraph.outgoing/incoming` scan all edges; the Graph's Focus closure is O(V·E). | Use adjacency maps. |
| P6 | Low | Problems tab: the "Apply All Suggestions" `update()` recomputes and deep-hashes every suggestion fix on the EDT on each toolbar update (about twice a second). "Only POMs with problems" re-walks every subtree for each node (O(N²)). | Cache per analysis result; memoize the subtree check. |
| P7 | Low | `findByGav` scans and interpolates every workspace POM for each BOM import of each validated POM, O(N²) on a full refresh, and always a full scan for BOMs from outside the workspace. | A lazy per-`Workspace` index keyed by interpolated groupId:artifactId. |

## Checked and fine

- XML parsing has DTDs and external entities disabled (no XXE, no entity expansion).
- The cache is written atomically, and any read failure gives an empty cache.
- Fixes are recomputed against the current text and refuse to run when the POM changed (no silent corruption).
- Queries are cancellable and run off the EDT; `~/.m2` file lookups happen off the EDT.
- `Workspace` caches are not thread-safe, but every shared use goes through one synchronized `Insights` instance or a per-query snapshot.
