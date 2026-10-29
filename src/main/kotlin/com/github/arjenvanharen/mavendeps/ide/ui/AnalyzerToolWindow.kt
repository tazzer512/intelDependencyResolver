package com.github.arjenvanharen.mavendeps.ide.ui

import com.github.arjenvanharen.mavendeps.core.AnalysisResult
import com.github.arjenvanharen.mavendeps.core.PomResult
import com.github.arjenvanharen.mavendeps.core.Problem
import com.github.arjenvanharen.mavendeps.core.RefreshMode
import com.github.arjenvanharen.mavendeps.core.Severity
import com.github.arjenvanharen.mavendeps.ide.MavenAnalysisListener
import com.github.arjenvanharen.mavendeps.ide.MavenAnalysisService
import com.intellij.icons.AllIcons
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class AnalyzerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AnalyzerPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
    }
}

private class PomNode(val result: PomResult, val base: Path?) {
    override fun toString() = result.path.toString()
}

private class ProblemNode(val path: Path, val problem: Problem) {
    override fun toString() = "${problem.pos.line}:${problem.pos.column}:${problem.message}"
}

private class AnalyzerPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val status = JBLabel()
    private var onlyProblems = false
    private var everShown = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigateToSelection()
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && navigateToSelection()) e.consume()
            }
        })

        val content = JPanel(BorderLayout())
        content.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        status.border = JBUI.Borders.empty(2, 6)
        content.add(status, BorderLayout.SOUTH)
        setContent(content)
        toolbar = createToolbar()

        project.messageBus.connect(parent).subscribe(
            MavenAnalysisListener.TOPIC,
            MavenAnalysisListener { result -> ApplicationManager.getApplication().invokeLater({ show(result) }, project.disposed) },
        )
        val service = MavenAnalysisService.getInstance(project)
        service.lastResult?.let { show(it) } ?: run {
            status.text = "Analyzing…"
            service.requestRefresh(RefreshMode.RESCAN)
        }
    }

    private fun createToolbar(): javax.swing.JComponent {
        val actions = ActionManager.getInstance()
        val expander = DefaultTreeExpander(tree)
        val group = DefaultActionGroup().apply {
            add(actions.getAction("MavenDeps.Refresh"))
            add(actions.getAction("MavenDeps.FullRefresh"))
            addSeparator()
            add(object : ToggleAction("Show Only POMs With Problems", null, AllIcons.General.Filter), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = onlyProblems
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    onlyProblems = state
                    MavenAnalysisService.getInstance(project).lastResult?.let { show(it) }
                }
            })
            add(com.intellij.ide.CommonActionsManager.getInstance().createExpandAllAction(expander, tree))
            add(com.intellij.ide.CommonActionsManager.getInstance().createCollapseAllAction(expander, tree))
        }
        val toolbar = actions.createActionToolbar("MavenDepsToolbar", group, true)
        toolbar.targetComponent = tree
        return toolbar.component
    }

    private fun show(result: AnalysisResult) {
        val state = TreeState.createOn(tree, root)
        root.removeAllChildren()
        val base = project.basePath?.let { Path.of(it) }
        val visited = HashSet<Path>()

        fun hasProblems(path: Path, seen: MutableSet<Path>): Boolean {
            if (!seen.add(path)) return false
            val r = result.poms[path] ?: return false
            return r.problems.isNotEmpty() || r.modules.any { hasProblems(it, seen) }
        }

        fun build(path: Path): DefaultMutableTreeNode? {
            val r = result.poms[path] ?: return null
            if (!visited.add(path)) return null
            if (onlyProblems && !hasProblems(path, HashSet())) return null
            val node = DefaultMutableTreeNode(PomNode(r, base))
            r.problems.forEach { node.add(DefaultMutableTreeNode(ProblemNode(path, it))) }
            r.modules.forEach { m -> build(m)?.let { node.add(it) } }
            return node
        }
        result.roots.forEach { r -> build(r)?.let { root.add(it) } }
        model.reload()
        if (everShown) state.applyTo(tree, root) else TreeUtil.expand(tree, 2)
        everShown = true

        val s = result.stats
        val errors = result.count(Severity.ERROR)
        val warnings = result.count(Severity.WARNING)
        status.text = "${s.total} POMs · ${s.parsed} parsed, ${s.reused} cached · ${s.revalidated} revalidated · ${s.durationMs} ms" +
            " — $errors errors, $warnings warnings"
        if (result.poms.isEmpty()) status.text = "No pom.xml found in the project root or content roots"
    }

    private fun navigateToSelection(): Boolean {
        val data = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject ?: return false
        val (path, line, column) = when (data) {
            is ProblemNode -> Triple(data.path, data.problem.pos.line, data.problem.pos.column)
            is PomNode -> Triple(data.result.path, data.result.model.gavPos.line, 0)
            else -> return false
        }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return false
        OpenFileDescriptor(project, file, maxOf(line - 1, 0), maxOf(column - 1, 0)).navigate(true)
        return true
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val data = (value as? DefaultMutableTreeNode)?.userObject) {
                is PomNode -> {
                    val r = data.result
                    val gav = r.model.effectiveGav
                    icon = AllIcons.Nodes.Module
                    append(gav.artifactId ?: r.path.parent.fileName.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${gav.groupId ?: "?"}:${gav.version ?: "?"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    val shown = data.base?.let { b -> if (r.path.startsWith(b)) b.relativize(r.path).toString() else null } ?: r.path.toString()
                    append("  $shown", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    val errors = r.problems.count { it.severity == Severity.ERROR }
                    val warnings = r.problems.count { it.severity == Severity.WARNING }
                    if (errors > 0) append("  $errors error${if (errors > 1) "s" else ""}", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    if (warnings > 0) append("  $warnings warning${if (warnings > 1) "s" else ""}", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                }
                is ProblemNode -> {
                    val p = data.problem
                    icon = when (p.severity) {
                        Severity.ERROR -> AllIcons.General.Error
                        Severity.WARNING -> AllIcons.General.Warning
                        Severity.INFO -> AllIcons.General.Information
                    }
                    append(p.message)
                    val where = if (p.pos.line > 0) " line ${p.pos.line}" else ""
                    append("  [${p.category.displayName}]$where", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }
}
