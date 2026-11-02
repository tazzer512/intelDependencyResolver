package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Path

/**
 * Plugins in `<build><plugins>` need a version, either their own or one inherited from `<plugins>` or
 * `<pluginManagement>` in this POM or a parent; otherwise the build depends on the Maven version that runs it. A plugin
 * may also be declared only once per section. Silent when a parent is outside the workspace (its plugins are unknown).
 */
object PluginValidator : PomAnalyzer {
    override fun analyze(ws: Workspace, path: Path): List<Problem> {
        val model = ws.model(path) ?: return emptyList()
        if (model.plugins.isEmpty()) return emptyList()
        val out = ArrayList<Problem>()
        fun ga(p: Plugin) = "${ws.interpolate(path, p.effectiveGroupId)}:${ws.interpolate(path, p.artifactId)}"

        val seen = HashMap<Triple<String, Boolean, String?>, Plugin>()
        for (p in model.plugins) {
            if (p.artifactId.isNullOrEmpty()) continue
            if (seen.put(Triple(ga(p), p.managed, p.profile), p) != null) {
                val where = if (p.managed) "<pluginManagement>" else "<plugins>"
                out += Problem(
                    Severity.WARNING, Category.BUILD, "Plugin ${ga(p)} is declared more than once in $where", p.pos,
                    listOf(RemoveElement("Remove duplicate declaration", ElementRef(null, "plugin", p.pos))),
                )
            }
        }

        val unversioned = model.plugins.filter { !it.managed && it.version.isNullOrEmpty() && !it.artifactId.isNullOrEmpty() }
        if (unversioned.isEmpty()) return out
        val ancestry = ws.ancestry(path)
        if (!ancestry.complete) return out
        val versioned = HashSet<String>()
        for (p in listOf(path) + ancestry.chain) {
            for (plugin in ws.model(p)!!.plugins) {
                if (plugin.version.isNullOrEmpty() || plugin.artifactId == null) continue
                versioned += "${ws.interpolate(p, plugin.effectiveGroupId)}:${ws.interpolate(p, plugin.artifactId)}"
            }
        }
        for (p in unversioned) {
            if (ga(p) in versioned) continue
            out += Problem(
                Severity.WARNING, Category.BUILD,
                "Plugin ${ga(p)} has no version and none is managed in <pluginManagement>; the build depends on the Maven version",
                p.pos,
                listOf(SetChild("Set <version>…", ElementRef(null, "plugin", p.pos), "version", null)),
            )
        }
        return out
    }
}

/**
 * A `<version>` in `<dependencies>` that equals what `<dependencyManagement>` (this POM, a parent or a workspace BOM)
 * already supplies. Redundant, and it drifts the next time the managed version changes. A different version is often
 * deliberate, so it is not reported.
 */
object RedundantVersionAnalyzer : PomAnalyzer {
    override fun analyze(ws: Workspace, path: Path): List<Problem> {
        val model = ws.model(path) ?: return emptyList()
        val versioned = model.dependencies.filter { it.section == DependencySection.DEPENDENCIES && !it.version.isNullOrEmpty() && it.versionPos != Pos.NONE }
        if (versioned.isEmpty()) return emptyList()
        val managed = ManagedVersions.of(ws, path) ?: return emptyList()
        val out = ArrayList<Problem>()
        for (dep in versioned) {
            val version = ws.interpolate(path, dep.version) ?: continue
            val key = dep.managementKey { ws.interpolate(path, it) }
            val (managedVersion, by) = managed[key] ?: continue
            if ("\${" in version || version != managedVersion) continue
            val where = if (by == path) "in this POM" else "by ${ws.model(by)!!.effectiveGav.ga}"
            out += Problem(
                Severity.INFO, Category.DEPENDENCIES,
                "Version $version of ${key.substringBeforeLast(":jar")} is already managed $where; <version> can be removed",
                dep.pos,
                listOf(RemoveElement("Remove <version> (managed)", ElementRef(null, "version", dep.versionPos))),
            )
        }
        return out
    }
}

/** Managed versions visible to a workspace POM, keyed by management key, with the POM that manages them. */
internal object ManagedVersions {
    /** Null when some source is outside the workspace, so the result would be incomplete. */
    fun of(ws: Workspace, path: Path): Map<String, Pair<String, Path>>? {
        val out = HashMap<String, Pair<String, Path>>()
        return if (collect(ws, path, path, out, HashSet())) out else null
    }

    /** Own and inherited entries first, then imports, as in Maven. */
    private fun collect(ws: Workspace, pom: Path, context: Path, out: MutableMap<String, Pair<String, Path>>, visited: MutableSet<Path>): Boolean {
        if (!visited.add(pom)) return true
        val ancestry = ws.ancestry(pom)
        if (!ancestry.complete) return false
        val imports = ArrayList<Pair<Dependency, Path>>()
        for (p in listOf(pom) + ancestry.chain) {
            for (dep in ws.model(p)!!.dependencies) {
                if (dep.section != DependencySection.DEPENDENCY_MANAGEMENT) continue
                if (dep.scope == "import" && dep.type == "pom") imports += dep to p
                else {
                    val v = ws.interpolate(context, dep.version) ?: continue
                    out.putIfAbsent(dep.managementKey { ws.interpolate(context, it) }, v to p)
                }
            }
        }
        var complete = true
        for ((dep, _) in imports) {
            val g = ws.interpolate(context, dep.groupId)
            val a = ws.interpolate(context, dep.artifactId)
            val v = ws.interpolate(context, dep.version)
            val bom = ws.pomsWithGa(g, a).firstOrNull { p -> Workspace.versionsMatch(ws.interpolate(p, ws.model(p)!!.effectiveGav.version), v) }
            complete = complete && bom != null && collect(ws, bom, bom, out, visited)
        }
        return complete
    }
}

/**
 * A project with a release version that depends on a `-SNAPSHOT` (dependency, managed dependency, plugin or parent)
 * cannot be released reproducibly; the release plugin refuses it.
 */
object SnapshotDependencyValidator : PomAnalyzer {
    override fun analyze(ws: Workspace, path: Path): List<Problem> {
        val model = ws.model(path) ?: return emptyList()
        val own = ws.interpolate(path, model.effectiveGav.version) ?: return emptyList()
        if ("\${" in own || isSnapshot(own)) return emptyList()
        val out = ArrayList<Problem>()
        fun check(what: String, version: String?, pos: Pos) {
            val v = ws.interpolate(path, version) ?: return
            if (isSnapshot(v)) {
                out += Problem(Severity.WARNING, Category.DEPENDENCIES, "Release $own depends on snapshot $what:$v; the build is not reproducible", pos)
            }
        }
        model.parent?.let { check("parent ${it.gav.ga}", it.gav.version, it.pos) }
        for (d in model.dependencies) check("${ws.interpolate(path, d.groupId)}:${ws.interpolate(path, d.artifactId)}", d.version, d.pos)
        for (p in model.plugins) check("plugin ${ws.interpolate(path, p.effectiveGroupId)}:${ws.interpolate(path, p.artifactId)}", p.version, p.pos)
        return out
    }

    private fun isSnapshot(v: String) = v.endsWith("-SNAPSHOT") || v == "SNAPSHOT"
}

/**
 * A dependency on a module built in this workspace, with a version the module does not have: Maven takes the old
 * artifact from the repository instead of the reactor build, typically after a version bump. Global, because it
 * depends on POMs that are not ancestors (incremental refresh only re-validates along parent and BOM edges).
 */
object ModuleDependencyVersionValidator : GlobalAnalyzer {
    override fun analyze(ws: Workspace): Map<Path, List<Problem>> {
        val out = HashMap<Path, MutableList<Problem>>()
        for ((path, entry) in ws.entries) {
            val model = entry.model
            if (!model.wellFormed) continue
            for (dep in model.dependencies) {
                if (dep.version.isNullOrEmpty()) continue
                val g = ws.interpolate(path, dep.groupId) ?: continue
                val a = ws.interpolate(path, dep.artifactId) ?: continue
                val modules = ws.pomsWithGa(g, a).filter { it != path }
                if (modules.isEmpty()) continue
                val v = ws.interpolate(path, dep.version) ?: continue
                val moduleVersions = modules.mapNotNull { ws.interpolate(it, ws.model(it)!!.effectiveGav.version) }
                if ("\${" in v || moduleVersions.isEmpty() || moduleVersions.any { "\${" in it || Workspace.versionsMatch(it, v) }) continue
                val target = moduleVersions.distinct().singleOrNull()
                val own = ws.interpolate(path, model.effectiveGav.version)
                val fix = when {
                    target == null -> null
                    own == target -> SetChild("Use \${project.version}", ElementRef(null, "dependency", dep.pos), "version", "\${project.version}")
                    else -> SetChild("Use version $target", ElementRef(null, "dependency", dep.pos), "version", target)
                }
                out.getOrPut(path) { ArrayList() } += Problem(
                    Severity.WARNING, Category.MULTI_MODULE,
                    "$g:$a:$v is built in this workspace as version ${moduleVersions.distinct().joinToString()}; " +
                        "Maven takes $v from the repository instead of the module",
                    dep.pos, listOfNotNull(fix),
                )
            }
        }
        return out
    }
}
