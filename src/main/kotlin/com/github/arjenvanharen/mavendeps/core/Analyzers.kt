package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Path

/** Analysis of one POM in the context of the workspace. Only re-run for POMs affected by a change. */
fun interface PomAnalyzer {
    fun analyze(ws: Workspace, path: Path): List<Problem>
}

/** Analysis over the whole workspace. Must be cheap: it runs on every refresh. */
fun interface GlobalAnalyzer {
    fun analyze(ws: Workspace): Map<Path, List<Problem>>
}

/** A selectable set of analyses. Validation is the first; more (conflicts, unused, ...) can be added as suites. */
class AnalysisSuite(val name: String, val pomAnalyzers: List<PomAnalyzer>, val globalAnalyzers: List<GlobalAnalyzer>) {
    companion object {
        val VALIDATION = AnalysisSuite(
            "Validation",
            listOf(ModuleValidator, ParentValidator, DependencyVersionValidator, PropertyValidator),
            listOf(DuplicateCoordinatesValidator),
        )
    }
}

object ModuleValidator : PomAnalyzer {
    override fun analyze(ws: Workspace, path: Path): List<Problem> {
        val model = ws.model(path) ?: return emptyList()
        val out = ArrayList<Problem>()
        val seen = HashMap<Pair<Path, String?>, ModuleRef>()
        for (ref in model.modules) {
            if (ref.name.isEmpty()) continue
            val target = ws.moduleTarget(path, ref)
            val where = ref.profile?.let { " (profile '$it')" } ?: ""
            when {
                target == path -> out += Problem(Severity.ERROR, Category.MULTI_MODULE, "Module '${ref.name}' refers to this POM itself", ref.pos)
                target !in ws.entries -> out += Problem(
                    Severity.ERROR, Category.MULTI_MODULE, "Module '${ref.name}'$where does not exist: $target", ref.pos,
                )
            }
            if (seen.put(target to ref.profile, ref) != null) {
                out += Problem(Severity.ERROR, Category.MULTI_MODULE, "Module '${ref.name}' is listed more than once", ref.pos)
            }
        }
        return out
    }
}

object ParentValidator : PomAnalyzer {
    override fun analyze(ws: Workspace, path: Path): List<Problem> {
        val parent = ws.model(path)?.parent ?: return emptyList()
        val out = ArrayList<Problem>()
        ws.parentWarning(path)?.let { out += Problem(Severity.WARNING, Category.MULTI_MODULE, it, parent.pos) }
        if (ws.ancestry(path).cycle) {
            out += Problem(Severity.ERROR, Category.MULTI_MODULE, "The parent hierarchy of this POM contains a cycle", parent.pos)
        }
        val resolved = ws.resolveParent(path)
        if (resolved is ParentResolution.Local) {
            val parentModel = ws.model(resolved.path)!!
            if (parentModel.wellFormed && parentModel.effectivePackaging != "pom") {
                out += Problem(
                    Severity.ERROR, Category.MULTI_MODULE,
                    "Invalid packaging for parent POM ${parentModel.effectiveGav.ga}: must be 'pom' but is '${parentModel.effectivePackaging}'",
                    parent.pos,
                )
            }
        }
        return out
    }
}

/** Dependencies without a version must be managed by `<dependencyManagement>` in this POM, a parent, or an imported BOM. */
object DependencyVersionValidator : PomAnalyzer {
    override fun analyze(ws: Workspace, path: Path): List<Problem> {
        val model = ws.model(path) ?: return emptyList()
        val unversioned = model.dependencies.filter { it.section == DependencySection.DEPENDENCIES && it.version.isNullOrEmpty() }
        if (unversioned.isEmpty()) return emptyList()

        val managed = HashSet<String>()
        if (!collectManaged(ws, path, path, managed, HashSet())) return emptyList()
        return unversioned
            .filter { !it.groupId.isNullOrEmpty() && !it.artifactId.isNullOrEmpty() }
            .filter { it.managementKey { v -> ws.interpolate(path, v) } !in managed }
            .map {
                Problem(
                    Severity.ERROR, Category.STRUCTURE,
                    "'dependencies.dependency.version' for ${it.managementKey()} is missing and not managed by a parent or imported BOM",
                    it.pos,
                )
            }
    }

    /** Adds management keys visible from [pom]; returns false when some source is outside the workspace. */
    private fun collectManaged(ws: Workspace, pom: Path, interpolationContext: Path, out: MutableSet<String>, visited: MutableSet<Path>): Boolean {
        if (!visited.add(pom)) return true
        val ancestry = ws.ancestry(pom)
        if (!ancestry.complete) return false
        var complete = true
        for (p in listOf(pom) + ancestry.chain) {
            for (dep in ws.model(p)!!.dependencies) {
                if (dep.section != DependencySection.DEPENDENCY_MANAGEMENT) continue
                if (dep.scope == "import" && dep.type == "pom") {
                    val bom = findByGav(ws, dep, interpolationContext)
                    complete = complete && bom != null && collectManaged(ws, bom, bom, out, visited)
                } else {
                    out += dep.managementKey { ws.interpolate(interpolationContext, it) }
                }
            }
        }
        return complete
    }

    private fun findByGav(ws: Workspace, dep: Dependency, context: Path): Path? {
        val g = ws.interpolate(context, dep.groupId)
        val a = ws.interpolate(context, dep.artifactId)
        val v = ws.interpolate(context, dep.version)
        return ws.entries.keys.firstOrNull { p ->
            val gav = ws.model(p)!!.effectiveGav
            ws.interpolate(p, gav.groupId) == g && gav.artifactId == a && Workspace.versionsMatch(ws.interpolate(p, gav.version), v)
        }
    }
}

object PropertyValidator : PomAnalyzer {
    private val BUILTIN_PREFIXES = listOf(
        "project.", "pom.", "env.", "settings.", "session.", "maven.", "mojo.", "plugin.", "java.", "os.", "user.",
        "file.", "line.", "path.", "sun.", "jdk.",
    )
    private val BUILTINS = setOf("basedir", "localRepository", "reactorProjects", "executedProject", "mojoExecution", "settings", "session", "project")

    fun isBuiltin(name: String) = name in BUILTINS || BUILTIN_PREFIXES.any { name.startsWith(it) }

    override fun analyze(ws: Workspace, path: Path): List<Problem> {
        val model = ws.model(path) ?: return emptyList()
        if (model.propertyRefs.isEmpty()) return emptyList()
        // Inherited properties are unknown when a parent lives outside the workspace: stay silent rather than guess.
        val props = ws.effectiveProperties(path) ?: return emptyList()
        val profileProps = ws.profileProperties(path)
        // Inherited configuration is interpolated in the child's context, so a parent may use properties its children define.
        val descendantProps by lazy {
            ws.descendants(path).flatMapTo(HashSet()) { ws.model(it)!!.let { m -> m.properties.keys + m.profileProperties } }
        }
        return model.propertyRefs
            .filter { !isBuiltin(it.name) && it.name !in props && it.name !in profileProps && it.name !in descendantProps }
            .map {
                Problem(
                    if (it.critical) Severity.WARNING else Severity.INFO,
                    Category.PROPERTIES,
                    "Property '\${${it.name}}' is not defined in this POM or its parents" +
                        if (it.critical) "" else " (may be supplied with -D or by a plugin)",
                    it.pos,
                )
            }
    }
}

object DuplicateCoordinatesValidator : GlobalAnalyzer {
    override fun analyze(ws: Workspace): Map<Path, List<Problem>> {
        val byGav = HashMap<String, MutableList<Path>>()
        for ((path, entry) in ws.entries) {
            if (!entry.model.wellFormed) continue
            val gav = entry.model.effectiveGav
            val key = "${ws.interpolate(path, gav.groupId)}:${gav.artifactId}:${ws.interpolate(path, gav.version)}"
            if (gav.artifactId == null || key.contains("\${")) continue
            byGav.getOrPut(key) { ArrayList() }.add(path)
        }
        val out = HashMap<Path, MutableList<Problem>>()
        for ((gav, paths) in byGav) {
            if (paths.size < 2) continue
            for (p in paths) {
                val others = paths.filter { it != p }.joinToString { it.toString() }
                out.getOrPut(p) { ArrayList() } +=
                    Problem(Severity.ERROR, Category.MULTI_MODULE, "Project coordinates $gav are also used by $others", ws.model(p)!!.gavPos)
            }
        }
        return out
    }
}
