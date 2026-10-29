package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Path

/** Coordinates read from pasted text. */
data class Coordinates(val groupId: String, val artifactId: String, val version: String?, val scope: String? = null)

/**
 * Reads coordinates from text pasted by the user: POM `<dependency>` snippets (also incomplete ones, e.g. without the
 * closing `</dependencies>`), `g:a[:v]` coordinates, Gradle notation and `mvn dependency:tree` lines.
 */
object DependencySnippet {
    private val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val exclusions = Regex("<exclusions\\b.*?(</exclusions>|$)", RegexOption.DOT_MATCHES_ALL)
    private val dependency = Regex("<dependency\\b[^>]*>(.*?)(</dependency>|(?=<dependency\\b)|$)", RegexOption.DOT_MATCHES_ALL)
    private val coordinates = Regex("""[A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+(:[A-Za-z0-9_.\-${'$'}{}]+)*""")

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>\\s*(.*?)\\s*</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    /** Every dependency in [text], in order; empty when nothing recognisable was found. */
    fun parse(text: String): List<Coordinates> {
        val xml = exclusions.replace(comment.replace(text, ""), "")
        if ('<' in xml) {
            val blocks = dependency.findAll(xml).map { it.groupValues[1] }.toList().ifEmpty { listOf(xml) }
            val found = blocks.mapNotNull { b ->
                val g = tag(b, "groupId")
                val a = tag(b, "artifactId")
                if (g == null && a == null) null else Coordinates(g ?: "", a ?: "", tag(b, "version"), tag(b, "scope"))
            }
            if (found.isNotEmpty()) return found
        }
        return coordinates.findAll(text).mapNotNull { fromColons(it.value.split(':')) }.toList()
    }

    private fun fromColons(p: List<String>): Coordinates? = when (p.size) {
        2 -> Coordinates(p[0], p[1], null)
        3 -> Coordinates(p[0], p[1], p[2])
        // g:a:type:version
        4 -> Coordinates(p[0], p[1], p[3])
        // mvn dependency:tree: g:a:type:version:scope
        5 -> Coordinates(p[0], p[1], p[3], p[4])
        // g:a:type:classifier:version:scope
        6 -> Coordinates(p[0], p[1], p[4], p[5])
        else -> null
    }

    /**
     * The values a version with `${...}` placeholders takes in the workspace POMs; a plain version is returned as is.
     * Empty when no POM defines the properties.
     */
    fun resolveVersion(ws: Workspace, version: String): List<String> {
        if ("\${" !in version) return listOf(version)
        return ws.entries.keys.asSequence()
            .mapNotNull { p -> ws.effectiveProperties(p)?.let { Workspace.substitute(version, it) } }
            .filter { "\${" !in it }
            .distinct().sorted().toList()
    }
}

/** One occurrence of a library in a module's resolved dependency tree. */
data class LibraryUsage(val module: Path, val node: DependencyNode)

/** A groupId:artifactId with all its occurrences over all modules. */
class Library(val groupId: String, val artifactId: String, val usages: List<LibraryUsage>) {
    val ga: String get() = "$groupId:$artifactId"

    /** Versions that end up on a classpath, in the order first seen. */
    val versions: List<String> = usages.filter { it.node.status == NodeStatus.INCLUDED }.mapNotNull { it.node.version }.distinct()

    /** All versions requested anywhere, including the ones that lost mediation. */
    val requestedVersions: List<String> = usages.mapNotNull { it.node.version }.distinct()

    /** Modules get different versions, or a module had to choose between versions. */
    val conflict: Boolean get() = versions.size > 1 || usages.any { it.node.status == NodeStatus.OMITTED_CONFLICT }

    /** Declared directly by at least one module (rather than only reached transitively). */
    val declared: Boolean get() = usages.any { it.node.depth == 1 }

    val modules: Set<Path> get() = usages.mapTo(LinkedHashSet()) { it.module }
}

/** Resolves the dependency trees of all modules and indexes them by library, for the explorer. */
object DependencyIndex {
    fun resolveAll(
        resolver: DependencyResolver,
        modules: Collection<Path>,
        checkCanceled: () -> Unit = {},
        progress: (done: Int, total: Int, module: Path) -> Unit = { _, _, _ -> },
    ): Map<Path, DependencyTree> {
        val out = LinkedHashMap<Path, DependencyTree>()
        for ((i, module) in modules.withIndex()) {
            checkCanceled()
            progress(i, modules.size, module)
            if (resolver.ws.model(module)?.wellFormed != true) continue
            out[module] = resolver.tree(module)
        }
        return out
    }

    /** Every library over [trees], sorted by groupId:artifactId. */
    fun libraries(trees: Map<Path, DependencyTree>): List<Library> {
        val byGa = LinkedHashMap<String, MutableList<LibraryUsage>>()
        for ((module, tree) in trees) {
            for (node in tree.all()) byGa.getOrPut("${node.groupId}:${node.artifactId}") { ArrayList() }.add(LibraryUsage(module, node))
        }
        return byGa.entries.sortedBy { it.key }.map { (_, usages) ->
            val n = usages.first().node
            Library(n.groupId, n.artifactId, usages)
        }
    }
}
