package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Path

/**
 * An element in a POM, identified by the position the parser recorded for it (the end of its start tag).
 * [path] null means the POM the problem belongs to; [name] null matches any element; [Pos.NONE] is the root element.
 */
data class ElementRef(val path: Path?, val name: String?, val pos: Pos)

/** A change to one or more POMs. Fixes are data so they can be cached, tested and applied to editor documents. */
sealed interface QuickFix {
    val title: String
}

data class RemoveElement(override val title: String, val target: ElementRef) : QuickFix

/** Sets the text of child [child] of [target], creating it at its canonical position. A null [value] asks the user. */
data class SetChild(override val title: String, val target: ElementRef, val child: String, val value: String?) : QuickFix

/** Defines (or overwrites) `<properties><name>` in [pom]. A null [value] asks the user. */
data class DefineProperty(override val title: String, val pom: Path?, val name: String, val value: String?) : QuickFix

data class AddExclusion(override val title: String, val target: ElementRef, val groupId: String, val artifactId: String) : QuickFix

data class AddDependency(override val title: String, val pom: Path?, val groupId: String, val artifactId: String, val version: String) : QuickFix

data class CompositeFix(override val title: String, val parts: List<QuickFix>) : QuickFix

/** The question to ask the user before applying, or null when the fix is complete. */
val QuickFix.prompt: String?
    get() = when (this) {
        is SetChild -> if (value == null) "Value for <$child>" else null
        is DefineProperty -> if (value == null) "Value for property '$name'" else null
        is CompositeFix -> parts.firstNotNullOfOrNull { it.prompt }
        else -> null
    }

/** The same fix with "the reporting POM" ([ElementRef.path] / pom == null) replaced by [owner]. */
fun QuickFix.anchored(owner: Path): QuickFix = when (this) {
    is RemoveElement -> copy(target = target.copy(path = target.path ?: owner))
    is SetChild -> copy(target = target.copy(path = target.path ?: owner))
    is DefineProperty -> copy(pom = pom ?: owner)
    is AddExclusion -> copy(target = target.copy(path = target.path ?: owner))
    is AddDependency -> copy(pom = pom ?: owner)
    is CompositeFix -> copy(parts = parts.map { it.anchored(owner) })
}

/** A replacement of `[start, end)` in a document's text. */
data class TextEdit(val start: Int, val end: Int, val replacement: String)

class FixException(message: String) : Exception(message)

/**
 * Turns [QuickFix]es into text edits. Targets are located by re-parsing the current text, so the result only
 * depends on what is in the editor, and a POM that changed since the analysis is detected instead of corrupted.
 */
object FixApplier {
    private val PROJECT_ORDER = listOf(
        "modelVersion", "parent", "groupId", "artifactId", "version", "packaging", "name", "description", "url",
        "inceptionYear", "organization", "licenses", "developers", "contributors", "mailingLists", "prerequisites",
        "modules", "subprojects", "scm", "issueManagement", "ciManagement", "distributionManagement", "properties",
        "dependencyManagement", "dependencies", "repositories", "pluginRepositories", "build", "reporting", "profiles",
    )
    private val ORDER = mapOf(
        "project" to PROJECT_ORDER,
        "parent" to listOf("groupId", "artifactId", "version", "relativePath"),
        "dependency" to listOf("groupId", "artifactId", "version", "type", "classifier", "scope", "systemPath", "optional", "exclusions"),
        "exclusion" to listOf("groupId", "artifactId"),
    )

    /** All files [fix] touches; [owner] is the POM the fix was reported on. */
    fun files(fix: QuickFix, owner: Path): Set<Path> = leaves(fix).mapTo(LinkedHashSet()) { leafPath(it) ?: owner }

    /** Edits per file. [text] returns the current contents of a file; [input] answers [QuickFix.prompt]. */
    fun edits(fix: QuickFix, owner: Path, input: String? = null, text: (Path) -> String): Map<Path, List<TextEdit>> {
        if (fix.prompt != null && input == null) throw FixException("'${fix.title}' needs a value")
        val docs = LinkedHashMap<Path, Doc>()
        fun doc(path: Path) = docs.getOrPut(path) { Doc(text(path)) }
        for (leaf in leaves(fix)) {
            val d = doc(leafPath(leaf) ?: owner)
            when (leaf) {
                is RemoveElement -> d.remove(d.find(leaf.target))
                is SetChild -> d.setChild(d.find(leaf.target), leaf.child, leaf.value ?: input!!)
                is DefineProperty -> d.defineProperty(leaf.name, leaf.value ?: input!!)
                is AddExclusion -> {
                    val dep = d.find(leaf.target)
                    val existing = dep.child("exclusions")?.children("exclusion").orEmpty()
                    if (existing.none { it.childText("groupId") == leaf.groupId && it.childText("artifactId") == leaf.artifactId }) {
                        d.append(dep, "exclusions", "<exclusion><groupId>${esc(leaf.groupId)}</groupId><artifactId>${esc(leaf.artifactId)}</artifactId></exclusion>")
                    }
                }
                is AddDependency -> {
                    val deps = d.root.child("dependencies")?.children("dependency").orEmpty()
                    if (deps.none { it.childText("groupId") == leaf.groupId && it.childText("artifactId") == leaf.artifactId }) {
                        d.append(
                            d.root, "dependencies",
                            "<dependency><groupId>${esc(leaf.groupId)}</groupId><artifactId>${esc(leaf.artifactId)}</artifactId>" +
                                "<version>${esc(leaf.version)}</version></dependency>",
                        )
                    }
                }
                is CompositeFix -> error("flattened")
            }
        }
        return docs.mapValues { it.value.finish() }.filterValues { it.isNotEmpty() }
    }

    /** Applies [edits] (computed against [text]) and returns the new text. */
    fun apply(text: String, edits: List<TextEdit>): String {
        val sb = StringBuilder(text)
        inApplicationOrder(edits).forEach { sb.replace(it.start, it.end, it.replacement) }
        return sb.toString()
    }

    /** Back to front, so earlier offsets stay valid; insertions at the same offset keep their order. */
    fun inApplicationOrder(edits: List<TextEdit>): List<TextEdit> =
        edits.withIndex().sortedWith(compareByDescending<IndexedValue<TextEdit>> { it.value.start }.thenByDescending { it.index }).map { it.value }

    private fun leaves(fix: QuickFix): List<QuickFix> = if (fix is CompositeFix) fix.parts.flatMap { leaves(it) } else listOf(fix)

    private fun leafPath(fix: QuickFix): Path? = when (fix) {
        is RemoveElement -> fix.target.path
        is SetChild -> fix.target.path
        is DefineProperty -> fix.pom
        is AddExclusion -> fix.target.path
        is AddDependency -> fix.pom
        is CompositeFix -> null
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * One document being edited. Edits are computed against the original text; insertions into the same container
     * are merged so that several fixes can e.g. create one `<properties>` section together.
     */
    private class Doc(val text: String) {
        val root: XNode
        private val lineStarts = ArrayList<Int>()
        private val sep = if (text.contains("\r\n")) "\r\n" else "\n"
        private val unit: String
        private val edits = ArrayList<TextEdit>()
        private val replaced = HashSet<Pair<Int, Int>>()

        /** New children per container element; a container that does not exist yet is keyed by (parent, name). */
        private val appends = LinkedHashMap<Pair<XNode, String?>, MutableList<String>>()

        init {
            val parsed = PomParser.parse(text)
            if (!parsed.model.wellFormed || parsed.root == null) throw FixException("The POM is not well-formed XML; fix the syntax first")
            root = parsed.root
            lineStarts.add(0)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                if (c == '\n' || c == '\r') lineStarts.add(i + 1)
                i++
            }
            unit = root.children.firstOrNull()?.let { indentOf(it).removePrefix(indentOf(root)) }?.takeIf { it.isNotEmpty() } ?: "    "
        }

        fun find(ref: ElementRef): XNode {
            if (ref.pos == Pos.NONE) return root
            fun visit(n: XNode): XNode? =
                if (n.pos == ref.pos && (ref.name == null || ref.name == n.name)) n else n.children.firstNotNullOfOrNull { visit(it) }
            return visit(root) ?: throw FixException("The POM changed since it was analysed; refresh and try again")
        }

        private fun offset(p: Pos): Int = lineStarts[p.line - 1] + p.column - 1
        fun startTagEnd(n: XNode) = offset(n.pos)
        fun start(n: XNode) = text.lastIndexOf('<', startTagEnd(n) - 1)
        fun end(n: XNode) = offset(n.endPos)
        fun selfClosing(n: XNode) = n.endPos == n.pos
        fun endTagStart(n: XNode) = if (selfClosing(n)) end(n) else text.lastIndexOf("</", end(n) - 1)

        private fun lineStart(offset: Int) = lineStarts.last { it <= offset }

        fun indentOf(n: XNode): String {
            val s = start(n)
            val prefix = text.substring(lineStart(s), s)
            return if (prefix.isBlank()) prefix else ""
        }

        private fun childIndent(n: XNode) = n.children.firstOrNull()?.let { indentOf(it) }?.takeIf { it.isNotEmpty() } ?: (indentOf(n) + unit)

        private fun edit(start: Int, end: Int, replacement: String) {
            if (start != end && !replaced.add(start to end)) return
            edits += TextEdit(start, end, replacement)
        }

        fun remove(n: XNode) {
            if (n === root) throw FixException("Cannot remove the root element")
            var s = start(n)
            var e = end(n)
            val ls = lineStart(s)
            var le = e
            while (le < text.length && (text[le] == ' ' || text[le] == '\t')) le++
            if (text.substring(ls, s).isBlank() && (le == text.length || text[le] == '\n' || text[le] == '\r')) {
                s = ls
                e = when {
                    text.startsWith("\r\n", le) -> le + 2
                    le < text.length -> le + 1
                    else -> le
                }
            }
            edit(s, e, "")
        }

        fun setChild(n: XNode, child: String, value: String) {
            val existing = n.child(child)
            when {
                existing == null -> insertChild(n, child, if (value.isEmpty()) "<$child/>" else "<$child>${esc(value)}</$child>")
                value.isEmpty() -> edit(start(existing), end(existing), "<$child/>")
                selfClosing(existing) -> edit(start(existing), end(existing), "<$child>${esc(value)}</$child>")
                else -> edit(startTagEnd(existing), endTagStart(existing), esc(value))
            }
        }

        fun defineProperty(name: String, value: String) {
            val props = root.child("properties")
            val existing = props?.child(name)
            if (existing != null) setChild(props, name, value) else append(root, "properties", "<$name>${esc(value)}</$name>")
        }

        /** Adds [snippet] to container [container] under [parent], creating the container if needed. */
        fun append(parent: XNode, container: String, snippet: String) {
            val c = parent.child(container)
            appends.getOrPut(if (c != null) c to null else parent to container) { ArrayList() }.add(snippet)
        }

        private fun insertChild(n: XNode, child: String, snippet: String) {
            val indent = childIndent(n)
            val order = ORDER[n.name]
            val rank = order?.indexOf(child) ?: -1
            val before = if (rank < 0) null else n.children.lastOrNull { order!!.indexOf(it.name) in 0 until rank }
            when {
                before != null -> edit(end(before), end(before), "$sep$indent$snippet")
                rank >= 0 && n.children.isNotEmpty() -> {
                    val first = n.children.first()
                    edit(start(first), start(first), "$snippet$sep$indent")
                }
                else -> appendAtEnd(n, listOf(snippet))
            }
        }

        private fun appendAtEnd(n: XNode, snippets: List<String>) {
            val indent = childIndent(n)
            val body = snippets.joinToString("") { "$sep$indent$it" }
            if (selfClosing(n)) {
                edit(start(n), end(n), text.substring(start(n), end(n) - 2).trimEnd() + ">" + body + sep + indentOf(n) + "</${n.name}>")
                return
            }
            var at = endTagStart(n)
            while (at > startTagEnd(n) && text[at - 1].isWhitespace()) at--
            edit(at, at, body + if (n.children.isEmpty()) sep + indentOf(n) else "")
        }

        /** Pretty-prints a one-line snippet of nested elements using this document's indentation. */
        private fun format(snippet: String, indent: String): String {
            val out = StringBuilder()
            var depth = 0
            var i = 0
            while (i < snippet.length) {
                val close = snippet.indexOf('>', i)
                val tag = snippet.substring(i, close + 1)
                val textEnd = snippet.indexOf('<', close + 1)
                val hasText = textEnd > close + 1 || (!tag.startsWith("</") && snippet.startsWith("</", close + 1))
                when {
                    tag.startsWith("</") -> {
                        depth--
                        out.append(sep).append(indent).append(unit.repeat(depth)).append(tag)
                    }
                    hasText || tag.endsWith("/>") -> {
                        val endTag = snippet.indexOf('>', textEnd) + 1
                        out.append(sep).append(indent).append(unit.repeat(depth)).append(snippet, i, if (hasText) endTag else close + 1)
                        i = if (hasText) endTag else close + 1
                        continue
                    }
                    else -> {
                        out.append(sep).append(indent).append(unit.repeat(depth)).append(tag)
                        depth++
                    }
                }
                i = close + 1
            }
            return out.toString().removePrefix(sep).removePrefix(indent)
        }

        fun finish(): List<TextEdit> {
            for ((key, snippets) in appends) {
                val (node, newContainer) = key
                val indent = if (newContainer == null) childIndent(node) else childIndent(node) + unit
                val formatted = snippets.map { format(it, indent) }
                if (newContainer == null) {
                    appendAtEnd(node, formatted)
                } else {
                    val inner = childIndent(node)
                    insertChild(node, newContainer, "<$newContainer>" + formatted.joinToString("") { "$sep$indent$it" } + "$sep$inner</$newContainer>")
                }
            }
            appends.clear()
            return edits.toList()
        }
    }
}
