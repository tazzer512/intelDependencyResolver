package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Path

/** Bring every module that uses `groupId:artifactId` (see [usages]) to at least [target]. */
data class AlignRequest(val groupId: String, val artifactId: String, val target: String, val usages: List<LibraryUsage>) {
    val ga: String get() = "$groupId:$artifactId"
}

/** A library that ends up in more than one version, and the version that resolves it. */
data class VersionConflict(val library: Library, val target: String) {
    /** Modules that resolve another version than [target], with the version they get now. */
    val outdated: Map<Path, String> = library.usages
        .filter { it.node.status == NodeStatus.INCLUDED && it.node.version != null && it.node.version != target }
        .associate { it.module to it.node.version!! }

    fun request() = AlignRequest(library.groupId, library.artifactId, target, library.usages)
}

/**
 * Aligns library versions over all modules, the way a Maven user would by hand:
 *
 * 1. Declarations of the library (in `<dependencies>` or `<dependencyManagement>`) are updated to the target,
 *    through their `${property}` when they use one.
 * 2. Modules that still get an older version only transitively get the target pinned in the
 *    `<dependencyManagement>` of their topmost workspace POM. Dependency management also applies to transitive
 *    dependencies, so this needs no exclusions and keeps the library on the classpath.
 *
 * Versions are never lowered: declarations newer than the target are left alone, and a pin uses the newest version
 * any module below that POM already resolves.
 */
object VersionAligner {
    /** Libraries with a version conflict that is not already resolved to the newest version, with that version. */
    fun conflicts(libraries: Collection<Library>): List<VersionConflict> = libraries.filter { it.conflict }.mapNotNull { lib ->
        newest(lib.requestedVersions)?.let { VersionConflict(lib, it) }?.takeIf { it.outdated.isNotEmpty() }
    }

    /** The newest release among [versions]; a pre-release only when there is nothing else. Ranges and expressions are ignored. */
    fun newest(versions: Collection<String>): String? {
        val usable = versions.filter { v -> v.isNotBlank() && "\${" !in v && v.none { it in "[](),"} }.map(::MavenVersion)
        return (usable.filter { !it.isPreRelease }.ifEmpty { usable }).maxOrNull()?.text
    }

    fun plan(ws: Workspace, resolver: DependencyResolver, requests: List<AlignRequest>, checkCanceled: () -> Unit = {}): List<PlanItem> {
        val items = ArrayList<PlanItem>()
        val propertyUpdates = HashSet<Pair<Path, String>>()
        for (r in requests) {
            checkCanceled()
            val target = MavenVersion(r.target)
            fun older(v: String?) = v != null && MavenVersion(v) < target

            val updatedManagementIn = HashSet<Path>()
            items += VersionUpdatePlanner.declarationItems(ws, resolver, r.groupId, r.artifactId, r.target, ::older, propertyUpdates, updatedManagementIn)

            // Modules that keep an older version after the declarations are updated, by their topmost workspace POM.
            val pins = LinkedHashMap<Path, MutableSet<Path>>()
            val newestBelow = HashMap<Path, MavenVersion>()
            for (u in r.usages) {
                if (u.node.status != NodeStatus.INCLUDED || ws.model(u.module) == null) continue
                val top = ws.ancestry(u.module).chain.lastOrNull() ?: u.module
                u.node.version?.let { v -> MavenVersion(v).let { mv -> newestBelow.merge(top, mv) { a, b -> maxOf(a, b) } } }
                if (!older(u.node.version)) continue
                // Step 1 already updated the declaration that decides this version: a direct dependency with its own
                // version, or dependency management in the workspace.
                if (u.node.depth == 1) {
                    val d = u.node.dependency
                    val declared = d.declaredIn?.let { p -> ws.model(p)?.dependencies?.firstOrNull { it.pos == d.pos } }
                    if (!declared?.version.isNullOrEmpty()) continue
                }
                if (resolver.effective(u.module).managedFor(r.groupId, r.artifactId)?.declaredIn != null) continue
                pins.getOrPut(top) { LinkedHashSet() }.add(u.module)
            }
            for ((pom, modules) in pins) {
                val version = maxOf(target, newestBelow[pom] ?: target).text
                val names = modules.map { ws.model(it)?.effectiveGav?.artifactId ?: it.parent.fileName.toString() }.sorted()
                items += PlanItem(
                    PlanItem.Kind.MANAGED, pom,
                    "Pin ${r.ga} to $version in <dependencyManagement> of ${ws.model(pom)!!.effectiveGav.ga}; " +
                        "${names.size} module${if (names.size == 1) "" else "s"} get an older version transitively (${names.joinToString(", ")})",
                    AddDependency("Manage ${r.ga} at $version", pom, r.groupId, r.artifactId, version, managed = true), true,
                )
            }
        }
        return items
    }
}
