package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Path

sealed interface ParentResolution {
    data object None : ParentResolution

    data class Local(val path: Path) : ParentResolution

    /** Not part of the workspace (e.g. a parent from a repository); inherited data is unknown. */
    data object External : ParentResolution
}

/** Ancestors nearest-first. [complete] is false when the chain leaves the workspace or hits a broken POM. */
data class Ancestry(val chain: List<Path>, val complete: Boolean, val cycle: Boolean)

/**
 * Immutable view over all parsed POMs of one analysis run, with memoized parent resolution.
 * Paths are absolute and normalized.
 */
class Workspace(val entries: Map<Path, PomEntry>) {
    private val parentCache = HashMap<Path, ParentResolution>()
    private val parentWarnings = HashMap<Path, String>()
    private val ancestryCache = HashMap<Path, Ancestry>()
    private val propertiesCache = HashMap<Path, Map<String, String>?>()

    private val byGa: Map<String, List<Path>> = entries.values
        .filter { it.model.effectiveGav.artifactId != null }
        .groupBy({ localInterpolate(it, it.model.effectiveGav.ga) }, { it.path })

    fun model(path: Path): PomModel? = entries[path]?.model

    fun moduleTarget(owner: Path, ref: ModuleRef): Path = pomPath(owner.parent.resolve(ref.name))

    /** The file `<relativePath>` (or its default) points at, or null for `<relativePath/>`. */
    fun parentCandidate(path: Path): Path? {
        val parent = model(path)?.parent ?: return null
        val rel = parent.relativePath ?: "../pom.xml"
        if (rel.isEmpty()) return null
        return pomPath(path.parent.resolve(rel))
    }

    fun resolveParent(path: Path): ParentResolution = parentCache.getOrPut(path) { computeParent(path) }

    /** A diagnostic produced while resolving the parent of [path], e.g. a relativePath mismatch. */
    fun parentWarning(path: Path): String? {
        resolveParent(path)
        return parentWarnings[path]
    }

    private fun computeParent(path: Path): ParentResolution {
        val ref = model(path)?.parent ?: return ParentResolution.None
        val wantedGa = ref.gav.ga
        val candidate = parentCandidate(path)
        val candidateEntry = candidate?.let { entries[it] }
        if (candidateEntry != null) {
            val actual = Gav(
                candidateEntry.model.effectiveGav.groupId?.let { localInterpolate(candidateEntry, it) },
                candidateEntry.model.effectiveGav.artifactId,
                candidateEntry.model.effectiveGav.version?.let { localInterpolate(candidateEntry, it) },
            )
            if (actual.ga == wantedGa) {
                if (versionsMatch(ref.gav.version, actual.version)) return ParentResolution.Local(candidate)
                parentWarnings[path] = "Parent $wantedGa:${ref.gav.version} does not match version ${actual.version} " +
                    "of ${candidate.fileName} at relativePath; Maven will look for the parent in the repository"
                return ParentResolution.External
            }
            parentWarnings[path] = "relativePath points at $actual instead of $wantedGa, please verify your project structure " +
                "(use an empty <relativePath/> for parents outside the project)"
        }
        // Fall back to the reactor: a project in the workspace with the same coordinates.
        val byCoords = byGa[wantedGa].orEmpty().filter { it != path }
            .firstOrNull { versionsMatch(ref.gav.version, entries[it]?.let { e -> e.model.effectiveGav.version?.let { v -> localInterpolate(e, v) } }) }
        return if (byCoords != null) ParentResolution.Local(byCoords) else ParentResolution.External
    }

    fun ancestry(path: Path): Ancestry = ancestryCache.getOrPut(path) {
        val chain = ArrayList<Path>()
        val seen = hashSetOf(path)
        var current = path
        var complete = model(path)?.wellFormed == true
        var cycle = false
        while (true) {
            when (val res = resolveParent(current)) {
                ParentResolution.None -> break
                ParentResolution.External -> {
                    complete = false
                    break
                }
                is ParentResolution.Local -> {
                    if (!seen.add(res.path)) {
                        cycle = true
                        complete = false
                        break
                    }
                    chain.add(res.path)
                    if (model(res.path)?.wellFormed != true) complete = false
                    current = res.path
                }
            }
        }
        Ancestry(chain, complete, cycle)
    }

    /** All properties visible to [path] (inherited, own and `project.*`), or null when the parent chain is unknown. */
    fun effectiveProperties(path: Path): Map<String, String>? = propertiesCache.getOrPut(path) {
        val ancestry = ancestry(path)
        if (!ancestry.complete) return@getOrPut null
        val props = HashMap<String, String>()
        for (p in ancestry.chain.reversed().plusElement(path)) props.putAll(model(p)!!.properties)
        props.putAll(projectProperties(model(path)!!, path))
        props
    }

    private val childrenIndex: Map<Path, List<Path>> by lazy {
        entries.keys.mapNotNull { p -> (resolveParent(p) as? ParentResolution.Local)?.let { it.path to p } }
            .groupBy({ it.first }, { it.second })
    }

    private val byInterpolatedGa: Map<String, List<Path>> by lazy {
        entries.keys.groupBy { p -> model(p)!!.effectiveGav.let { "${interpolate(p, it.groupId)}:${interpolate(p, it.artifactId)}" } }
    }

    /** Workspace POMs whose fully interpolated coordinates are [groupId]:[artifactId]. */
    fun pomsWithGa(groupId: String?, artifactId: String?): List<Path> = byInterpolatedGa["$groupId:$artifactId"].orEmpty()

    /** POMs inheriting from [path], directly or indirectly. */
    fun descendants(path: Path): Set<Path> {
        val result = LinkedHashSet<Path>()
        val queue = ArrayDeque(childrenIndex[path].orEmpty())
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            if (p != path && result.add(p)) queue.addAll(childrenIndex[p].orEmpty())
        }
        return result
    }

    /** Names visible to [path] that are only defined inside profiles (they may or may not be active). */
    fun profileProperties(path: Path): Set<String> =
        ancestry(path).chain.plusElement(path).flatMapTo(HashSet()) { model(it)?.profileProperties ?: emptySet() }

    fun interpolate(path: Path, value: String?): String? {
        if (value == null || !value.contains("\${")) return value
        val props = effectiveProperties(path) ?: return value
        return substitute(value, props)
    }

    /** Interpolation that only uses the file's own properties; safe to call during parent resolution. */
    fun localInterpolate(entry: PomEntry, value: String): String {
        if (!value.contains("\${")) return value
        return substitute(value, entry.model.properties + projectProperties(entry.model, entry.path))
    }

    private fun projectProperties(model: PomModel, path: Path): Map<String, String> {
        val map = HashMap<String, String>()
        val gav = model.effectiveGav
        fun put(key: String, value: String?) {
            if (value != null) {
                map["project.$key"] = value
                map["pom.$key"] = value
            }
        }
        put("groupId", gav.groupId)
        put("artifactId", gav.artifactId)
        put("version", gav.version)
        put("packaging", model.effectivePackaging)
        put("parent.groupId", model.parent?.gav?.groupId)
        put("parent.artifactId", model.parent?.gav?.artifactId)
        put("parent.version", model.parent?.gav?.version)
        map["basedir"] = path.parent.toString()
        map["project.basedir"] = path.parent.toString()
        return map
    }

    companion object {
        private const val MAX_INTERPOLATED_LENGTH = 64 * 1024

        fun pomPath(p: Path): Path {
            val normalized = p.toAbsolutePath().normalize()
            return if (normalized.fileName?.toString()?.endsWith(".xml") == true) normalized else normalized.resolve("pom.xml")
        }

        fun substitute(value: String, props: Map<String, String>): String {
            var result = value
            repeat(10) {
                if (!result.contains("\${")) return result
                var changed = false
                for (name in PomParser.referencedProperties(result)) {
                    val replacement = props[name] ?: continue
                    result = result.replace("\${$name}", replacement)
                    changed = true
                    // Self-amplifying definitions (a = ${b}${b}, b = ${c}${c}, ...) must not exhaust memory.
                    if (result.length > MAX_INTERPOLATED_LENGTH) return result
                }
                if (!changed) return result
            }
            return result
        }

        /** Unresolved expressions (e.g. CI-friendly `${revision}`) are treated as compatible. */
        fun versionsMatch(a: String?, b: String?): Boolean =
            a == null || b == null || a.contains("\${") || b.contains("\${") || a == b
    }
}
