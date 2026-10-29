package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Path

/** Coordinates to look for. Blank or `*` matches anything; `*` may also be used as a wildcard inside a value. */
data class DependencyQuery(val groupId: String, val artifactId: String, val version: String? = null) {
    private fun pattern(s: String?): Regex? =
        s?.trim()?.takeIf { it.isNotEmpty() && it != "*" }?.let { Regex(it.split('*').joinToString(".*") { p -> Regex.escape(p) }) }

    private val g = pattern(groupId)
    private val a = pattern(artifactId)
    private val v = pattern(version)

    fun matches(groupId: String?, artifactId: String?): Boolean =
        (g == null || (groupId != null && g.matches(groupId))) && (a == null || (artifactId != null && a.matches(artifactId)))

    fun matchesVersion(version: String?): Boolean = v == null || (version != null && v.matches(version))

    override fun toString() = "$groupId:$artifactId" + (version?.takeIf { it.isNotBlank() }?.let { ":$it" } ?: "")
}

/** A declaration in a workspace POM (`<dependencies>` or `<dependencyManagement>`, possibly inside a profile). */
data class DeclaredHit(
    val pom: Path,
    val dependency: Dependency,
    val groupId: String,
    val artifactId: String,
    /** The version after interpolation and dependency management; null when unknown. */
    val resolvedVersion: String?,
    /** How the version is specified, e.g. `literal`, `${jackson.version}` or `managed`. */
    val versionSource: String,
)

/** An occurrence in a module's resolved dependency tree, reached through at least one other dependency. */
data class TransitiveHit(val module: Path, val node: DependencyNode)

class SearchResult(
    val query: DependencyQuery,
    val declared: List<DeclaredHit>,
    val transitive: List<TransitiveHit>,
    /** Artifacts whose POM is not in the local repository; their subtrees are unknown. */
    val missingPoms: Set<String>,
)

object DependencySearch {
    fun search(ws: Workspace, resolver: DependencyResolver, query: DependencyQuery, checkCanceled: () -> Unit = {}): SearchResult {
        val declared = declarations(ws, resolver, query).filter { query.matchesVersion(it.resolvedVersion) }
        val transitive = ArrayList<TransitiveHit>()
        val missing = LinkedHashSet<String>()
        for (module in modules(ws)) {
            checkCanceled()
            val tree = resolver.tree(module)
            missing += tree.missingPoms
            tree.all().filter { it.depth > 1 && query.matches(it.groupId, it.artifactId) && query.matchesVersion(it.version) }
                .forEach { transitive += TransitiveHit(module, it) }
        }
        return SearchResult(query, declared, transitive, missing)
    }

    /** Every matching declaration, regardless of version; only those in [only] when given. */
    fun declarations(ws: Workspace, resolver: DependencyResolver, query: DependencyQuery, only: Path? = null): List<DeclaredHit> {
        val out = ArrayList<DeclaredHit>()
        for ((path, entry) in ws.entries) {
            if (!entry.model.wellFormed || (only != null && path != only)) continue
            for (dep in entry.model.dependencies) {
                val g = ws.interpolate(path, dep.groupId) ?: continue
                val a = ws.interpolate(path, dep.artifactId) ?: continue
                if (!query.matches(g, a)) continue
                val raw = dep.version
                val (version, source) = when {
                    raw.isNullOrEmpty() -> resolver.effective(path).managedFor(g, a)?.version to "managed"
                    raw.contains("\${") -> ws.interpolate(path, raw)?.takeIf { !it.contains("\${") } to raw
                    else -> raw to "literal"
                }
                out += DeclaredHit(path, dep, g, a, version, source)
            }
        }
        return out
    }

    /** Workspace POMs that have dependencies of their own or inherited ones. */
    fun modules(ws: Workspace): List<Path> = ws.entries.filter { (path, e) ->
        e.model.wellFormed && (listOf(path) + ws.ancestry(path).chain).any { p ->
            ws.model(p)!!.dependencies.any { it.section == DependencySection.DEPENDENCIES && it.profile == null }
        }
    }.keys.toList()
}

/** One proposed change of a version update. */
data class PlanItem(val kind: Kind, val pom: Path, val description: String, val fix: QuickFix, val selected: Boolean) {
    enum class Kind(val displayName: String) {
        DECLARATION("Update declaration"),
        PROPERTY("Update property"),
        EXCLUSION("Exclude transitive version"),
        DIRECT_DEPENDENCY("Declare directly"),
    }
}

/**
 * Plans changing `groupId:artifactId` from [from] (any version when null) to [to] in every POM. Declarations are
 * updated in place, or through the property they use. Where a module still gets another version transitively
 * (and its dependency management does not force the new one), that path is excluded at the direct dependency
 * that brings it in; declaring the new version directly in such modules is offered but not selected by default.
 */
object VersionUpdatePlanner {
    fun plan(
        ws: Workspace,
        resolver: DependencyResolver,
        groupId: String,
        artifactId: String,
        from: String?,
        to: String,
        checkCanceled: () -> Unit = {},
    ): List<PlanItem> {
        val ga = "$groupId:$artifactId"
        val query = DependencyQuery(groupId, artifactId)
        val items = ArrayList<PlanItem>()
        val propertyUpdates = HashSet<Pair<Path, String>>()
        fun selected(version: String?) = from.isNullOrBlank() || version == from

        // Declarations in the workspace.
        val updatedManagementIn = HashSet<Path>()
        for (hit in DependencySearch.declarations(ws, resolver, query)) {
            val raw = hit.dependency.version
            if (raw.isNullOrEmpty() || !selected(hit.resolvedVersion) || hit.resolvedVersion == to) continue
            val depRef = ElementRef(hit.pom, "dependency", hit.dependency.pos)
            val section = if (hit.dependency.section == DependencySection.DEPENDENCY_MANAGEMENT) "dependencyManagement" else "dependencies"
            val property = Regex("""\$\{([^}]+)}""").matchEntire(raw.trim())?.groupValues?.get(1)
            if (property != null && !PropertyValidator.isBuiltin(property)) {
                val definer = (listOf(hit.pom) + ws.ancestry(hit.pom).chain).firstOrNull { property in ws.model(it)!!.properties } ?: hit.pom
                if (!propertyUpdates.add(definer to property)) continue
                val sharedWith = ws.entries.values.sumOf { e ->
                    e.model.dependencies.count { it.version?.trim() == raw.trim() && "${it.groupId}:${it.artifactId}" != ga }
                }
                val note = if (sharedWith > 0) " (also used by $sharedWith other declaration${if (sharedWith > 1) "s" else ""})" else ""
                items += PlanItem(
                    PlanItem.Kind.PROPERTY, definer, "Set property $property from ${hit.resolvedVersion} to $to$note",
                    DefineProperty("Set $property to $to", definer, property, to), true,
                )
            } else {
                items += PlanItem(
                    PlanItem.Kind.DECLARATION, hit.pom, "Change $ga in <$section> from ${hit.resolvedVersion ?: raw} to $to",
                    SetChild("Set version to $to", depRef, "version", to), true,
                )
            }
            if (hit.dependency.section == DependencySection.DEPENDENCY_MANAGEMENT) updatedManagementIn.add(hit.pom)
        }

        // Transitive occurrences with another version.
        val exclusions = HashSet<Pair<Path, Pos>>()
        for (module in DependencySearch.modules(ws)) {
            checkCanceled()
            val effective = resolver.effective(module)
            val managed = effective.managedFor(groupId, artifactId)
            // Dependency management forces the version of transitive dependencies too.
            val managedAfter = when {
                managed == null -> null
                managed.declaredIn != null && managed.declaredIn in updatedManagementIn -> to
                managed.declaredIn != null && selected(managed.version) && managed.version != null -> to
                else -> managed.version
            }
            if (managedAfter == to) continue
            val tree = resolver.tree(module)
            val transitive = tree.all().filter { it.depth > 1 && "${it.groupId}:${it.artifactId}" == ga && it.version != to }.toList()
            if (transitive.isEmpty()) continue
            for (node in transitive) {
                val direct = node.direct
                val declaredIn = direct.dependency.declaredIn ?: continue
                if (!exclusions.add(declaredIn to direct.dependency.pos)) continue
                val via = if (declaredIn == module) "" else " (declared in ${ws.model(declaredIn)!!.effectiveGav.ga}, inherited by its children)"
                items += PlanItem(
                    PlanItem.Kind.EXCLUSION, declaredIn,
                    "Exclude $ga:${node.version} brought in by ${direct.label} via ${node.chain.joinToString(" → ") { it.label }}$via",
                    AddExclusion("Exclude $ga from ${direct.label}", ElementRef(declaredIn, "dependency", direct.dependency.pos), groupId, artifactId),
                    true,
                )
            }
            val declaresDirectly = effective.dependencies.any { it.ga == ga }
            if (!declaresDirectly && transitive.any { it.status == NodeStatus.INCLUDED }) {
                items += PlanItem(
                    PlanItem.Kind.DIRECT_DEPENDENCY, module,
                    "Declare $ga:$to directly in ${ws.model(module)!!.effectiveGav.ga} so it stays on the classpath after the exclusion",
                    AddDependency("Add $ga:$to", module, groupId, artifactId, to), false,
                )
            }
        }
        return items
    }
}
