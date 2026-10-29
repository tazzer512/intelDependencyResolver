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
