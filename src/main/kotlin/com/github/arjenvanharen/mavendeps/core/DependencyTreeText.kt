package com.github.arjenvanharen.mavendeps.core

/**
 * A resolved tree as text in the format of `mvn dependency:tree` (`groupId:artifactId:type[:classifier]:version:scope`
 * with `+-`, `\-` and `|` connectors), for pasting into tickets and reviews. [verbose] also lists omitted nodes, like
 * `-Dverbose`, with the reason.
 */
object DependencyTreeText {
    fun render(root: Gav, packaging: String, tree: DependencyTree, verbose: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("${root.groupId ?: "?"}:${root.artifactId ?: "?"}:$packaging:${root.version ?: "?"}\n")
        fun visit(nodes: List<DependencyNode>, prefix: String) {
            val shown = nodes.filter { verbose || it.status == NodeStatus.INCLUDED }
            shown.forEachIndexed { i, n ->
                val last = i == shown.lastIndex
                sb.append(prefix).append(if (last) "\\- " else "+- ").append(line(n)).append('\n')
                visit(n.children, prefix + if (last) "   " else "|  ")
            }
        }
        visit(tree.roots, "")
        return sb.toString()
    }

    private fun line(n: DependencyNode): String {
        val d = n.dependency
        val coords = "${d.groupId}:${d.artifactId}:${d.type}" + (d.classifier?.let { ":$it" } ?: "") + ":${n.version ?: "?"}:${n.scope}" +
            if (d.optional) " (optional)" else ""
        val notes = listOfNotNull(
            n.managedFrom?.let { "version managed from $it" },
            n.requestedRange?.let { "from range $it" },
            n.relocatedFrom?.let { "relocated from $it" },
            "POM missing".takeIf { !n.pomFound },
        )
        return when (n.status) {
            NodeStatus.INCLUDED -> coords + if (notes.isEmpty()) "" else " (${notes.joinToString("; ")})"
            NodeStatus.OMITTED_DUPLICATE -> "($coords - omitted for duplicate)"
            NodeStatus.OMITTED_CONFLICT -> "($coords - omitted for conflict with ${n.winnerVersion})"
        }
    }
}
