package com.github.arjenvanharen.mavendeps.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

enum class RefreshMode {
    /** Only re-read files reported as changed; everything else is trusted from the cache. */
    INCREMENTAL,

    /** Stat every known POM and re-read those whose size/mtime changed (used after loading the disk cache). */
    RESCAN,

    /** Drop the cache and parse everything again. */
    FULL,
}

data class PomResult(val entry: PomEntry, val problems: List<Problem>, val modules: List<Path>, val parent: Path?) {
    val path: Path get() = entry.path
    val model: PomModel get() = entry.model
}

/** Progress of a refresh: POMs read so far while discovering the tree, then POMs validated. */
data class RefreshProgress(val phase: Phase, val done: Int, val total: Int) {
    enum class Phase(val displayName: String) { DISCOVERING("Reading POMs"), VALIDATING("Validating POMs") }

    /** Overall fraction in 0..1; reading counts for the first half, validating for the second. */
    val fraction: Double
        get() {
            val part = if (total == 0) 1.0 else done.toDouble() / total
            return if (phase == Phase.DISCOVERING) part / 2 else 0.5 + part / 2
        }
}

data class RefreshStats(val mode: RefreshMode, val total: Int, val parsed: Int, val reused: Int, val revalidated: Int, val durationMs: Long)

class AnalysisResult(val roots: List<Path>, val poms: Map<Path, PomResult>, val stats: RefreshStats) {
    fun count(severity: Severity) = poms.values.sumOf { r -> r.problems.count { it.severity == severity } }
}

/**
 * Discovers all POMs reachable from the root POMs (modules and parent relativePaths), parses only what changed and
 * re-runs cross-file analyzers only for POMs affected by a change. Not thread-safe; callers serialize access.
 */
class AnalysisEngine(private val suite: AnalysisSuite = AnalysisSuite.VALIDATION) {
    @Volatile
    private var entries: Map<Path, PomEntry> = emptyMap()
    private var workspace: Workspace? = null
    private val pomProblems = HashMap<Path, List<Problem>>()

    /** POMs currently part of the analysis; read from other threads to filter file events. */
    @Volatile
    var knownPaths: Set<Path> = emptySet()
        private set

    /** Referenced POMs that do not exist (yet); creating one of them requires a refresh. */
    @Volatile
    var missingPaths: Set<Path> = emptySet()
        private set

    /** Seeds the engine with entries from the disk cache. Use [RefreshMode.RESCAN] afterwards to validate them. */
    fun restore(cached: Collection<PomEntry>) {
        entries = cached.associateBy { it.path }
        workspace = null
        pomProblems.clear()
    }

    fun snapshot(): Collection<PomEntry> = entries.values

    /** A fresh workspace over the last refresh, safe to query from another thread while the engine keeps refreshing. */
    fun workspaceSnapshot(): Workspace = Workspace(entries)

    fun refresh(
        roots: Collection<Path>,
        mode: RefreshMode,
        changed: Collection<Path> = emptyList(),
        progress: (RefreshProgress) -> Unit = {},
    ): AnalysisResult {
        val start = System.nanoTime()
        val changedPaths = changed.mapTo(HashSet()) { it.toAbsolutePath().normalize() }
        val old = if (mode == RefreshMode.FULL) emptyMap() else entries
        if (mode == RefreshMode.FULL) pomProblems.clear()

        val counter = Counter()
        val next = LinkedHashMap<Path, PomEntry>()
        val missing = HashSet<Path>()
        val contentChanged = HashSet<Path>()
        val rootPaths = roots.map { Workspace.pomPath(it) }.distinct()
        val queue = ArrayDeque(rootPaths)
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (path in next || path in missing) continue
            val cached = old[path]
            val mustCheck = mode != RefreshMode.INCREMENTAL || cached == null || path in changedPaths
            val entry = if (mustCheck) load(path, cached, counter, force = path in changedPaths) else cached!!.also { counter.reused++ }
            if (entry == null) {
                missing.add(path)
                continue
            }
            if (cached == null || cached.hash != entry.hash) contentChanged.add(path)
            next[path] = entry
            // The total grows while modules and parents are discovered.
            progress(RefreshProgress(RefreshProgress.Phase.DISCOVERING, next.size, next.size + queue.size))
            for (module in entry.model.modules) {
                if (module.name.isNotEmpty()) queue.add(Workspace.pomPath(path.parent.resolve(module.name)))
            }
            val rel = entry.model.parent?.relativePath ?: if (entry.model.parent != null) "../pom.xml" else ""
            if (rel.isNotEmpty()) queue.add(Workspace.pomPath(path.parent.resolve(rel)))
        }

        val removed = old.keys - next.keys
        removed.forEach { pomProblems.remove(it) }
        val ws = Workspace(next)
        val previous = workspace
        val affected: Set<Path> =
            if (previous == null || mode == RefreshMode.FULL) next.keys
            else affectedBy(contentChanged + removed, (next.keys - old.keys) + removed, listOf(previous, ws)).filterTo(HashSet()) { it in next }

        for ((i, path) in affected.withIndex()) {
            progress(RefreshProgress(RefreshProgress.Phase.VALIDATING, i, affected.size))
            val entry = next.getValue(path)
            pomProblems[path] = if (entry.model.wellFormed) suite.pomAnalyzers.flatMap { it.analyze(ws, path) } else emptyList()
        }
        progress(RefreshProgress(RefreshProgress.Phase.VALIDATING, affected.size, affected.size))
        val global = HashMap<Path, MutableList<Problem>>()
        for (analyzer in suite.globalAnalyzers) {
            analyzer.analyze(ws).forEach { (p, problems) -> global.getOrPut(p) { ArrayList() } += problems }
        }

        entries = next
        workspace = ws
        knownPaths = next.keys.toSet()
        missingPaths = missing

        val results = LinkedHashMap<Path, PomResult>()
        for ((path, entry) in next) {
            val problems = (entry.localProblems + pomProblems[path].orEmpty() + global[path].orEmpty())
                .sortedWith(compareBy({ it.severity }, { it.pos.line }, { it.pos.column }))
            val modules = entry.model.modules.filter { it.name.isNotEmpty() }.map { ws.moduleTarget(path, it) }.filter { it in next }.distinct()
            val parent = (ws.resolveParent(path) as? ParentResolution.Local)?.path
            results[path] = PomResult(entry, problems, modules, parent)
        }
        val referencedAsModule = results.values.flatMapTo(HashSet()) { it.modules }
        val displayRoots = (rootPaths.filter { it in next } + next.keys).distinct().filter { it !in referencedAsModule }

        val stats = RefreshStats(mode, next.size, counter.parsed, counter.reused, affected.size, (System.nanoTime() - start) / 1_000_000)
        return AnalysisResult(displayRoots, results, stats)
    }

    /**
     * POMs whose cross-file results may depend on [seeds], over old and new workspace graphs. Inheritance (parents,
     * imported BOMs) propagates transitively; aggregators (module existence) and ancestors (property use) are added
     * without propagating further.
     */
    private fun affectedBy(seeds: Set<Path>, existenceChanged: Set<Path>, workspaces: List<Workspace>): Set<Path> {
        val byPath = HashMap<Path, MutableSet<Path>>()
        val aggregators = HashMap<Path, MutableSet<Path>>()
        val byGa = HashMap<String, MutableSet<Path>>()
        val gaOf = HashMap<Path, MutableSet<String>>()
        for (ws in workspaces) {
            for ((path, entry) in ws.entries) {
                val model = entry.model
                gaOf.getOrPut(path) { HashSet() } += model.effectiveGav.ga
                ws.parentCandidate(path)?.let { byPath.getOrPut(it) { HashSet() }.add(path) }
                (ws.resolveParent(path) as? ParentResolution.Local)?.let { byPath.getOrPut(it.path) { HashSet() }.add(path) }
                model.parent?.let { byGa.getOrPut(it.gav.ga) { HashSet() }.add(path) }
                for (module in model.modules) {
                    if (module.name.isNotEmpty()) aggregators.getOrPut(ws.moduleTarget(path, module)) { HashSet() }.add(path)
                }
                for (dep in model.dependencies) {
                    if (dep.scope == "import") byGa.getOrPut("${dep.groupId ?: "?"}:${dep.artifactId ?: "?"}") { HashSet() }.add(path)
                }
            }
        }
        val result = HashSet(seeds)
        val queue = ArrayDeque(seeds)
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            val dependents = byPath[p].orEmpty() + gaOf[p].orEmpty().flatMap { byGa[it].orEmpty() }
            for (d in dependents) if (result.add(d)) queue.add(d)
        }
        existenceChanged.forEach { result += aggregators[it].orEmpty() }
        // Ancestors may reference properties their descendants define (see PropertyValidator).
        seeds.forEach { seed -> workspaces.forEach { ws -> if (seed in ws.entries) result += ws.ancestry(seed).chain } }
        return result
    }

    private class Counter {
        var parsed = 0
        var reused = 0
    }

    /** [force] skips the size/mtime shortcut: a same-size edit within the mtime granularity would otherwise be missed. */
    private fun load(path: Path, cached: PomEntry?, counter: Counter, force: Boolean = false): PomEntry? {
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java)
        } catch (_: IOException) {
            return null
        }
        if (!attrs.isRegularFile) return null
        val size = attrs.size()
        val mtime = attrs.lastModifiedTime().toMillis()
        if (!force && cached != null && cached.size == size && cached.lastModified == mtime) {
            counter.reused++
            return cached
        }
        val bytes = try {
            Files.readAllBytes(path)
        } catch (e: IOException) {
            counter.parsed++
            val problem = Problem(Severity.ERROR, Category.XML_SYNTAX, "Cannot read file: ${e.message}", Pos.NONE)
            return PomEntry(path, size, mtime, "", PomModel.EMPTY, listOf(problem))
        }
        val hash = sha256(bytes)
        if (cached != null && cached.hash == hash) {
            counter.reused++
            return cached.copy(size = size, lastModified = mtime)
        }
        counter.parsed++
        val parsed = PomParser.parse(bytes)
        return PomEntry(path, size, mtime, hash, parsed.model, parsed.problems)
    }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
