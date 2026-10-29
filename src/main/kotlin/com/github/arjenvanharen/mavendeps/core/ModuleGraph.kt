package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Path

enum class EdgeKind(val displayName: String) {
    /** A dependency on another workspace module (`scope` says which), or a BOM import (`scope` = import). */
    DEPENDENCY("depends on"),
    PARENT("inherits from"),
    MODULE("aggregates"),
}

data class GraphEdge(val from: Path, val to: Path, val kind: EdgeKind, val scope: String? = null)

/** How the workspace POMs relate: module dependencies, parents and aggregation. */
class ModuleGraph(val nodes: List<Path>, val edges: List<GraphEdge>) {
    fun outgoing(node: Path, kinds: Set<EdgeKind> = EdgeKind.entries.toSet()) = edges.filter { it.from == node && it.kind in kinds }

    fun incoming(node: Path, kinds: Set<EdgeKind> = EdgeKind.entries.toSet()) = edges.filter { it.to == node && it.kind in kinds }

    companion object {
        /** Dependency edges use the effective model, so inherited dependencies and managed versions count. */
        fun build(ws: Workspace, resolver: DependencyResolver, checkCanceled: () -> Unit = {}): ModuleGraph {
            val nodes = ws.entries.filterValues { it.model.wellFormed }.keys.sortedBy { it.toString() }
            val byGa = HashMap<String, MutableList<Path>>()
            for (p in nodes) {
                val gav = ws.model(p)!!.effectiveGav
                byGa.getOrPut("${ws.interpolate(p, gav.groupId)}:${gav.artifactId}") { ArrayList() }.add(p)
            }
            fun target(g: String?, a: String?, v: String?): Path? =
                resolver.workspacePom(g ?: return null, a ?: return null, v) ?: byGa["$g:$a"]?.singleOrNull()

            val edges = LinkedHashSet<GraphEdge>()
            for (p in nodes) {
                checkCanceled()
                val model = ws.model(p)!!
                (ws.resolveParent(p) as? ParentResolution.Local)?.let { edges += GraphEdge(p, it.path, EdgeKind.PARENT) }
                model.modules.filter { it.profile == null }.forEach { m ->
                    ws.moduleTarget(p, m).takeIf { it in ws.entries && it != p }?.let { edges += GraphEdge(p, it, EdgeKind.MODULE) }
                }
                for (d in resolver.effective(p).dependencies) {
                    target(d.groupId, d.artifactId, d.version)?.takeIf { it != p }?.let { edges += GraphEdge(p, it, EdgeKind.DEPENDENCY, d.scope) }
                }
                for (d in model.dependencies) {
                    if (d.section != DependencySection.DEPENDENCY_MANAGEMENT || ws.interpolate(p, d.scope) != "import") continue
                    target(ws.interpolate(p, d.groupId), ws.interpolate(p, d.artifactId), ws.interpolate(p, d.version))
                        ?.takeIf { it != p }?.let { edges += GraphEdge(p, it, EdgeKind.DEPENDENCY, "import") }
                }
            }
            return ModuleGraph(nodes, edges.toList())
        }
    }
}

/**
 * Sugiyama-style layering: a node sits one layer above the highest node it points to, so everything a node depends
 * on is below it. Back edges of cycles are ignored. Within a layer, nodes are ordered by the average position of
 * their neighbours (a few barycenter sweeps), which removes most edge crossings in module graphs.
 */
object LayeredLayout {
    /** Layers from the bottom (nodes without outgoing edges) to the top. */
    fun <T> layers(nodes: List<T>, edges: List<Pair<T, T>>): List<List<T>> {
        val known = nodes.toSet()
        val out = HashMap<T, MutableList<T>>()
        val neighbours = HashMap<T, MutableList<T>>()
        for ((a, b) in edges) {
            if (a !in known || b !in known || a == b) continue
            out.getOrPut(a) { ArrayList() }.add(b)
            neighbours.getOrPut(a) { ArrayList() }.add(b)
            neighbours.getOrPut(b) { ArrayList() }.add(a)
        }
        val rank = HashMap<T, Int>()
        val visiting = HashSet<T>()
        fun rankOf(n: T): Int {
            rank[n]?.let { return it }
            if (!visiting.add(n)) return -1 // back edge of a cycle
            val r = (out[n].orEmpty().maxOfOrNull { rankOf(it) } ?: -1) + 1
            visiting.remove(n)
            rank[n] = r
            return r
        }
        nodes.forEach { rankOf(it) }
        val layers = MutableList((rank.values.maxOrNull() ?: -1) + 1) { ArrayList<T>() }
        nodes.forEach { layers[rank.getValue(it)].add(it) }

        val position = HashMap<T, Double>()
        fun index() = layers.forEach { l -> l.forEachIndexed { i, n -> position[n] = i.toDouble() / maxOf(l.size, 1) } }
        index()
        repeat(4) { sweep ->
            val order = if (sweep % 2 == 0) layers.indices.reversed() else layers.indices
            for (i in order) {
                layers[i].sortBy { n -> neighbours[n]?.mapNotNull { position[it] }?.average()?.takeIf { !it.isNaN() } ?: position.getValue(n) }
                index()
            }
        }
        return layers
    }
}
