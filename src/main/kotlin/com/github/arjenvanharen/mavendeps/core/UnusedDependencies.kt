package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Path

/** What the code check concluded for one declared dependency in one module. */
enum class Usage { USED, NEEDED_BY_LIBRARY, LOADED_AT_RUNTIME, UNUSED, UNKNOWN }

/** The verdict for one direct dependency of one module, with what it is based on. */
class DependencyUsage(val module: Path, val node: DependencyNode, val usage: Usage, val evidence: List<String>)

/** Unused-dependency problems per declaring POM, for the analysis they were computed against. */
class UnusedDependencyResult(
    /** The content hash of every POM the result depends on; a POM that changed since makes its problems stale. */
    val hashes: Map<Path, String>,
    val problems: Map<Path, List<Problem>>,
    val verdicts: List<DependencyUsage>,
) {
    /** Problems still valid for [result]: only POMs whose content is unchanged (positions and fixes stay valid). */
    fun current(result: AnalysisResult): Map<Path, List<Problem>> =
        problems.filterKeys { p -> result.poms[p]?.entry?.hash == hashes[p] }
}

/**
 * Finds declared dependencies that the code does not need, like `mvn dependency:analyze`, and verifies each one
 * conservatively before it reports it. A dependency is reported only when, in **every** module that inherits the
 * declaration:
 *
 * - the module has code (sources or compiled classes) and all of it could be read;
 * - none of it (sources, `target/classes`, `target/test-classes`, configuration files such as Spring XML or
 *   `logback.xml`) references a class or package of the dependency, or of anything it brings in transitively;
 * - no other library or workspace module on the module's classpath references those classes (it may need them, e.g.
 *   as an optional dependency, a supertype or an annotation);
 * - none of those jars registers classes to be loaded at runtime (`META-INF/services`, Spring auto-configuration),
 *   which covers JDBC drivers, logging bindings, annotation processors and starters;
 * - every jar involved is in `~/.m2` and could be read completely.
 *
 * `runtime`, `system` and `import` scopes, non-jar types and workspace modules are not checked. Classes loaded only
 * by reflection from computed names are not seen, so the problem says what was checked.
 */
class UnusedDependencyAnalyzer(private val repo: LocalRepository, private val index: ClassIndex = ClassIndex()) {
    fun analyze(
        resolver: DependencyResolver,
        trees: Map<Path, DependencyTree>,
        checkCanceled: () -> Unit = {},
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): UnusedDependencyResult {
        val ws = resolver.ws
        val verdicts = ArrayList<DependencyUsage>()
        val modules = trees.keys.filter { ws.model(it)?.let { m -> m.wellFormed && m.effectivePackaging != "pom" } == true }
        for ((i, module) in modules.withIndex()) {
            checkCanceled()
            progress(i, modules.size)
            verdicts += moduleVerdicts(resolver, module, trees.getValue(module), checkCanceled)
        }
        progress(modules.size, modules.size)

        // A declaration (in the module or in a parent) is unused only when it is unused in every module that gets it.
        val byDeclaration = verdicts.groupBy { it.node.dependency.declaredIn!! to it.node.dependency.pos }
        val problems = LinkedHashMap<Path, MutableList<Problem>>()
        for ((key, list) in byDeclaration) {
            if (list.any { it.usage != Usage.UNUSED }) continue
            val (pom, pos) = key
            val d = list.first().node
            val names = list.map { moduleName(ws, it.module) }.distinct().sorted()
            val where = if (names.size == 1) "module ${names[0]}" else "modules ${names.joinToString(", ")}"
            val message = "${d.groupId}:${d.artifactId}${d.version?.let { ":$it" } ?: ""} is not used: no code or configuration of $where " +
                "references its classes or those it brings in, no other dependency needs them, and it registers nothing loaded at runtime"
            problems.getOrPut(pom) { ArrayList() } += Problem(
                Severity.WARNING, Category.DEPENDENCIES, message, pos,
                listOf(RemoveElement("Remove unused dependency ${d.groupId}:${d.artifactId}", ElementRef(null, "dependency", pos))),
            )
        }
        val hashes = ws.entries.mapValues { it.value.hash }
        return UnusedDependencyResult(hashes, problems, verdicts)
    }

    private fun moduleName(ws: Workspace, p: Path) = ws.model(p)?.effectiveGav?.artifactId ?: p.parent.fileName.toString()

    private fun isWorkspace(resolver: DependencyResolver, n: DependencyNode) = n.version != null && resolver.workspacePom(n.groupId, n.artifactId, n.version) != null

    private fun jarOf(n: DependencyNode): Path? {
        val d = n.dependency
        val version = n.version ?: return null
        return when (d.type) {
            "jar", "bundle", "ejb", "maven-plugin" -> repo.artifactPath(d.groupId, d.artifactId, version, d.classifier)
            "test-jar" -> repo.artifactPath(d.groupId, d.artifactId, version, d.classifier ?: "tests")
            else -> null
        }
    }

    private fun moduleVerdicts(resolver: DependencyResolver, module: Path, tree: DependencyTree, checkCanceled: () -> Unit): List<DependencyUsage> {
        val candidates = tree.roots.filter { n ->
            n.status == NodeStatus.INCLUDED && n.dependency.declaredIn != null && n.dependency.pos != Pos.NONE &&
                n.scope in CHECKED_SCOPES && n.dependency.type in CHECKED_TYPES && !isWorkspace(resolver, n)
        }
        if (candidates.isEmpty()) return emptyList()
        fun all(reason: Usage, why: String) = candidates.map { DependencyUsage(module, it, reason, listOf(why)) }

        val dir = module.parent
        val sources = index.sources(dir)
        val compiled = index.compiled(dir)
        if (!sources.complete || !compiled.complete) return all(Usage.UNKNOWN, "Not every source or class file of the module could be read")
        if (sources.files + compiled.files == 0) return all(Usage.UNKNOWN, "The module has no code to check")

        val included = tree.all().filter { it.status == NodeStatus.INCLUDED }.toList()
        val out = ArrayList<DependencyUsage>()
        for (d in candidates) {
            checkCanceled()
            out += verdict(resolver, module, d, sources, compiled, included)
        }
        return out
    }

    private fun verdict(
        resolver: DependencyResolver,
        module: Path,
        d: DependencyNode,
        sources: ClassIndex.Code,
        compiled: ClassIndex.Code,
        included: List<DependencyNode>,
    ): DependencyUsage {
        fun result(u: Usage, vararg why: String) = DependencyUsage(module, d, u, why.toList())

        // Removing the declaration removes it and what it brings in (as far as nothing else brings that in).
        val subtree = generateSequence(listOf(d)) { level -> level.flatMap { n -> n.children.filter { it.status == NodeStatus.INCLUDED } }.ifEmpty { null } }
            .flatten().toList()
        val subtreeSet = subtree.toSet()
        val defined = HashSet<String>()
        for (n in subtree) {
            if (isWorkspace(resolver, n)) return result(Usage.UNKNOWN, "It brings in workspace module ${n.artifactId}")
            val jar = jarOf(n) ?: if (n.dependency.type == "pom") continue else return result(Usage.UNKNOWN, "${n.label} is not a jar")
            val summary = index.summary(jar) ?: return result(Usage.UNKNOWN, "The jar of ${n.label} is not in the local repository or not readable")
            if (!summary.complete) return result(Usage.UNKNOWN, "The jar of ${n.label} could not be read completely")
            if (summary.runtimeHooks.isNotEmpty()) {
                return result(Usage.LOADED_AT_RUNTIME, "${n.label} registers ${summary.runtimeHooks.first()} (loaded at runtime without a reference in code)")
            }
            defined.addAll(summary.defined)
        }
        if (defined.isEmpty()) return result(Usage.UNKNOWN, "${d.label} and what it brings in contain no classes")

        // 1. The module's own code and configuration.
        val evidence = ArrayList<String>()
        for (code in listOf(sources, compiled)) {
            for ((name, where) in code.classes) if (name in defined) evidence += "$where → $name"
        }
        val packages = defined.mapTo(HashSet()) { it.substringBeforeLast('.', "") }
        for ((p, where) in sources.packages) if (p in packages) evidence += "$where → $p.*"
        if (evidence.isNotEmpty()) return DependencyUsage(module, d, Usage.USED, evidence.distinct().sorted().take(MAX_EVIDENCE))

        // 2. Everything else on the module's classpath that may need it.
        for (n in included) {
            if (n in subtreeSet) continue
            if (isWorkspace(resolver, n)) {
                val pom = resolver.workspacePom(n.groupId, n.artifactId, n.version!!) ?: continue
                val code = index.sources(pom.parent)
                val classes = index.compiled(pom.parent)
                if (!code.complete || !classes.complete) return result(Usage.UNKNOWN, "Not every file of workspace module ${n.artifactId} could be read")
                val hit = (code.classes.keys + classes.classes.keys).firstOrNull { it in defined } ?: code.packages.keys.firstOrNull { it in packages }
                if (hit != null) return result(Usage.NEEDED_BY_LIBRARY, "Workspace module ${n.artifactId} references $hit")
                continue
            }
            val jar = jarOf(n) ?: continue
            val summary = index.summary(jar) ?: return result(Usage.UNKNOWN, "The jar of ${n.label} is not in the local repository or not readable")
            if (!summary.complete) return result(Usage.UNKNOWN, "The jar of ${n.label} could not be read completely")
            val hit = if (summary.references.size < defined.size) summary.references.firstOrNull { it in defined } else defined.firstOrNull { it in summary.references }
            if (hit != null) return result(Usage.NEEDED_BY_LIBRARY, "${n.label} references $hit")
        }
        return result(Usage.UNUSED, "Checked ${sources.files} source files, ${compiled.files} class files and ${included.size - subtree.size} other dependencies")
    }

    companion object {
        private const val MAX_EVIDENCE = 20
        private val CHECKED_SCOPES = setOf("compile", "provided", "test")
        private val CHECKED_TYPES = setOf("jar", "test-jar", "bundle", "ejb")
    }
}
