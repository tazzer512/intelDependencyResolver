package com.github.arjenvanharen.mavendeps.ide.ui

import com.github.arjenvanharen.mavendeps.core.Coordinates
import com.github.arjenvanharen.mavendeps.core.DeclaredHit
import com.github.arjenvanharen.mavendeps.core.DependencyQuery
import com.github.arjenvanharen.mavendeps.core.DependencySearch
import com.github.arjenvanharen.mavendeps.core.DependencySection
import com.github.arjenvanharen.mavendeps.core.DependencySnippet
import com.github.arjenvanharen.mavendeps.core.NodeStatus
import com.github.arjenvanharen.mavendeps.core.PlanItem
import com.github.arjenvanharen.mavendeps.core.Pos
import com.github.arjenvanharen.mavendeps.core.SearchResult
import com.github.arjenvanharen.mavendeps.core.TransitiveHit
import com.github.arjenvanharen.mavendeps.core.VersionUpdatePlanner
import com.github.arjenvanharen.mavendeps.ide.FixRunner
import com.github.arjenvanharen.mavendeps.ide.MavenAnalysisService
import com.github.arjenvanharen.mavendeps.ide.Navigation
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

private class GroupNode(val text: String, val kind: Kind) {
    enum class Kind { DECLARED, TRANSITIVE, MISSING }

    override fun toString() = text
}

private class ModuleNode(val path: Path, val label: String) {
    override fun toString() = path.toString()
}

private class DeclaredNode(val hit: DeclaredHit) {
    override fun toString() = "${hit.pom}:${hit.dependency.pos}"
}

private class TransitiveNode(val hit: TransitiveHit) {
    override fun toString() = "${hit.module}:${hit.node.chain.joinToString { it.label }}"
}

/** Finds a dependency in all POMs and resolved dependency trees, and updates its version everywhere. */
internal class DependencyPanel(private val project: Project) : SimpleToolWindowPanel(true, true), DependencyActions {
    private val groupField = JBTextField(18)
    private val artifactField = JBTextField(18)
    private val versionField = JBTextField(10)
    private val pasteArea = JBTextArea(4, 60)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val status = JBLabel("Enter groupId and artifactId (wildcards: *); the version is optional.")
    private val service get() = MavenAnalysisService.getInstance(project)

    init {
        groupField.emptyText.text = "groupId"
        artifactField.emptyText.text = "artifactId"
        versionField.emptyText.text = "version"
        val enter = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) search()
            }
        }
        listOf(groupField, artifactField, versionField).forEach { it.addKeyListener(enter) }

        val form = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        form.add(groupField)
        form.add(JBLabel(":"))
        form.add(artifactField)
        form.add(JBLabel(":"))
        form.add(versionField)
        form.add(JButton("Find", AllIcons.Actions.Find).apply { addActionListener { search() } })
        form.add(JButton("Update Version…", AllIcons.Actions.Edit).apply { addActionListener { updateVersion() } })

        pasteArea.emptyText.text = "Paste a <dependency> snippet, groupId:artifactId:version or a dependency:tree line to fill the fields"
        pasteArea.font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, pasteArea.font.size)
        pasteArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                fillFromPaste()
            }
        })
        val paste = JPanel(BorderLayout(4, 0))
        paste.border = JBUI.Borders.empty(0, 4, 4, 4)
        paste.add(ScrollPaneFactory.createScrollPane(pasteArea), BorderLayout.CENTER)
        val pasteButtons = JPanel(java.awt.GridLayout(0, 1, 0, 2))
        pasteButtons.add(JButton("Find", AllIcons.Actions.Find).apply {
            toolTipText = "Fill the fields from the pasted text and search"
            addActionListener { if (fillFromPaste()) search() }
        })
        pasteButtons.add(JButton("Clear", AllIcons.Actions.GC).apply { addActionListener { pasteArea.text = "" } })
        paste.add(JPanel(BorderLayout()).apply { add(pasteButtons, BorderLayout.NORTH) }, BorderLayout.EAST)
        val north = JPanel(BorderLayout())
        north.add(form, BorderLayout.NORTH)
        north.add(paste, BorderLayout.CENTER)

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigateToSelection()
        }.installOn(tree)

        val content = JPanel(BorderLayout())
        content.add(north, BorderLayout.NORTH)
        content.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        status.border = JBUI.Borders.empty(2, 6)
        content.add(status, BorderLayout.SOUTH)
        setContent(content)
    }

    override fun find(coordinates: Coordinates) {
        setFields(coordinates)
        search()
    }

    override fun updateVersion(coordinates: Coordinates) {
        setFields(coordinates)
        updateVersion()
    }

    private fun setFields(c: Coordinates) {
        groupField.text = c.groupId
        artifactField.text = c.artifactId
        versionField.text = c.version ?: ""
    }

    /**
     * Fills the fields from the pasted text. A `${property}` version is replaced by its value in the workspace; when
     * the modules disagree or nobody defines it, the version is left empty so every version is found.
     */
    private fun fillFromPaste(): Boolean {
        val text = pasteArea.text
        if (text.isBlank()) return false
        val found = DependencySnippet.parse(text)
        val first = found.firstOrNull()
        if (first == null) {
            status.text = "No groupId/artifactId found in the pasted text"
            return false
        }
        val raw = first.version
        val values = raw?.let { DependencySnippet.resolveVersion(service.workspace(), it) }.orEmpty()
        setFields(first.copy(version = values.singleOrNull()))
        val more = if (found.size > 1) " (first of ${found.size} dependencies)" else ""
        status.text = "Parsed ${first.groupId}:${first.artifactId}$more" + when {
            raw == null -> "; no version, so every version is searched"
            "\${" !in raw -> ""
            values.isEmpty() -> "; $raw is not defined in any POM, so every version is searched"
            values.size == 1 -> "; $raw = ${values.single()}"
            else -> "; $raw is ${values.joinToString(" / ")} in different modules, so every version is searched"
        }
        return true
    }

    private fun query(): DependencyQuery? {
        val q = DependencyQuery(groupField.text.trim(), artifactField.text.trim(), versionField.text.trim().ifEmpty { null })
        if (q.groupId.isEmpty() && q.artifactId.isEmpty()) {
            status.text = "Enter at least a groupId or an artifactId"
            return null
        }
        return q
    }

    private fun search() {
        val query = query() ?: return
        if (service.lastResult == null) {
            status.text = "The POMs have not been analysed yet; try again in a moment"
            return
        }
        status.text = "Searching $query…"
        service.runQuery("Searching for $query", { check ->
            val resolver = service.resolver()
            DependencySearch.search(resolver.ws, resolver, query, check)
        }) { show(it) }
    }

    private fun show(result: SearchResult) {
        root.removeAllChildren()
        val base = project.basePath?.let { Path.of(it) }
        fun rel(p: Path) = base?.takeIf { p.startsWith(it) }?.relativize(p)?.toString()?.ifEmpty { null } ?: p.toString()

        val declared = DefaultMutableTreeNode(GroupNode("Declared in POMs (${result.declared.size})", GroupNode.Kind.DECLARED))
        result.declared.groupBy { it.pom }.forEach { (pom, hits) ->
            val node = DefaultMutableTreeNode(ModuleNode(pom, rel(pom)))
            hits.forEach { node.add(DefaultMutableTreeNode(DeclaredNode(it))) }
            declared.add(node)
        }
        root.add(declared)

        val transitive = DefaultMutableTreeNode(GroupNode("Transitive (${result.transitive.size})", GroupNode.Kind.TRANSITIVE))
        result.transitive.groupBy { it.module }.forEach { (module, hits) ->
            val node = DefaultMutableTreeNode(ModuleNode(module, rel(module)))
            hits.forEach { node.add(DefaultMutableTreeNode(TransitiveNode(it))) }
            transitive.add(node)
        }
        root.add(transitive)

        if (result.missingPoms.isNotEmpty()) {
            val missing = DefaultMutableTreeNode(
                GroupNode("Not in the local repository (${result.missingPoms.size}) — their transitive dependencies are unknown", GroupNode.Kind.MISSING),
            )
            result.missingPoms.sorted().forEach { missing.add(DefaultMutableTreeNode(it)) }
            root.add(missing)
        }
        model.reload()
        TreeUtil.expand(tree, 2)
        status.text = "${result.query}: ${result.declared.size} declared, ${result.transitive.size} transitive" +
            if (result.missingPoms.isNotEmpty()) " · ${result.missingPoms.size} POMs missing from ${service.repository.root}" else ""
    }

    private fun updateVersion() {
        val query = query() ?: return
        if (query.groupId.isEmpty() || query.artifactId.isEmpty() || "*" in query.groupId || "*" in query.artifactId) {
            status.text = "Enter an exact groupId and artifactId to update"
            return
        }
        val from = query.version?.takeIf { "*" !in it }
        val to = Messages.showInputDialog(
            project,
            "New version for ${query.groupId}:${query.artifactId}" + (from?.let { " (currently $it)" } ?: " (all current versions)"),
            "Update Dependency Version", null,
        )?.trim()?.takeIf { it.isNotEmpty() } ?: return
        status.text = "Planning update to $to…"
        service.runQuery("Planning update of ${query.groupId}:${query.artifactId}", { check ->
            val resolver = service.resolver()
            VersionUpdatePlanner.plan(resolver.ws, resolver, query.groupId, query.artifactId, from, to, check)
        }) { items ->
            if (items.isEmpty()) {
                status.text = "Nothing to change: no declaration of ${query.groupId}:${query.artifactId}${from?.let { ":$it" } ?: ""} found"
                return@runQuery
            }
            val dialog = PlanDialog(project, "Update ${query.groupId}:${query.artifactId} to $to", items)
            if (dialog.showAndGet()) {
                val chosen = dialog.selected()
                FixRunner.applyAll(project, "Update ${query.groupId}:${query.artifactId} to $to", chosen.map { it.pom to it.fix })
                status.text = "Applied ${chosen.size} change${if (chosen.size == 1) "" else "s"}; search again to see the result"
            } else {
                status.text = "Update cancelled"
            }
        }
    }

    private fun navigateToSelection(): Boolean {
        val (path, pos) = when (val data = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
            is DeclaredNode -> data.hit.pom to data.hit.dependency.pos
            is TransitiveNode -> (data.hit.node.direct.dependency.declaredIn ?: data.hit.module) to data.hit.node.direct.dependency.pos
            is ModuleNode -> data.path to Pos(1, 1)
            else -> return false
        }
        Navigation.open(project, path, pos.line, pos.column)
        return true
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val data = (value as? DefaultMutableTreeNode)?.userObject) {
                is GroupNode -> {
                    icon = when (data.kind) {
                        GroupNode.Kind.DECLARED -> AllIcons.Nodes.PpLib
                        GroupNode.Kind.TRANSITIVE -> AllIcons.Hierarchy.Subtypes
                        GroupNode.Kind.MISSING -> AllIcons.General.Warning
                    }
                    append(data.text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
                is ModuleNode -> {
                    icon = AllIcons.Nodes.Module
                    append(data.label)
                }
                is DeclaredNode -> {
                    val h = data.hit
                    icon = AllIcons.Nodes.PpLib
                    append("${h.groupId}:${h.artifactId}:${h.resolvedVersion ?: "?"}")
                    val section = if (h.dependency.section == DependencySection.DEPENDENCY_MANAGEMENT) "dependencyManagement" else "dependencies"
                    val profile = h.dependency.profile?.let { ", profile $it" } ?: ""
                    append("  <$section>$profile", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (h.versionSource != "literal") append("  version: ${h.versionSource}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    h.dependency.scope?.let { append("  [$it]", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    append("  line ${h.dependency.pos.line}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                is TransitiveNode -> {
                    val n = data.hit.node
                    icon = AllIcons.Nodes.PpLibFolder
                    val omitted = n.status != NodeStatus.INCLUDED
                    append(n.label, if (omitted) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  [${n.scope}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    when (n.status) {
                        NodeStatus.INCLUDED -> {}
                        NodeStatus.OMITTED_DUPLICATE -> append("  omitted: duplicate", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        NodeStatus.OMITTED_CONFLICT -> append("  omitted: conflict with ${n.winnerVersion}", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    }
                    n.managedFrom?.let { append("  (managed from $it)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
                    append("  via " + n.chain.dropLast(1).joinToString(" → ") { it.label }, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                is String -> {
                    icon = AllIcons.Nodes.PpLib
                    append(data, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }
}

/** Lets the user review and deselect the changes of a version update before they are applied. */
private class PlanDialog(project: Project, title: String, private val items: List<PlanItem>) : DialogWrapper(project) {
    private val list = CheckBoxList<PlanItem>()

    init {
        this.title = title
        val base = project.basePath?.let { Path.of(it) }
        for (item in items) {
            val where = base?.takeIf { item.pom.startsWith(it) }?.relativize(item.pom)?.toString() ?: item.pom.toString()
            list.addItem(item, "${item.kind.displayName} — $where: ${item.description}", item.selected)
        }
        init()
    }

    fun selected(): List<PlanItem> = items.filter { list.isItemSelected(it) }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 6)).apply {
        add(JBLabel("Review the changes; transitive occurrences with another version are excluded at the dependency that brings them in."), BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list).apply { preferredSize = JBUI.size(900, 400) }, BorderLayout.CENTER)
    }
}
