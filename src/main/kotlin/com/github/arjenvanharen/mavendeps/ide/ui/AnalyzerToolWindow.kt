package com.github.arjenvanharen.mavendeps.ide.ui

import com.github.arjenvanharen.mavendeps.core.AnalysisResult
import com.github.arjenvanharen.mavendeps.core.PomResult
import com.github.arjenvanharen.mavendeps.core.Category
import com.github.arjenvanharen.mavendeps.core.Coordinates
import com.github.arjenvanharen.mavendeps.core.Problem
import com.github.arjenvanharen.mavendeps.core.QuickFix
import com.github.arjenvanharen.mavendeps.core.RefreshMode
import com.github.arjenvanharen.mavendeps.core.Severity
import com.github.arjenvanharen.mavendeps.ide.MavenAnalysisListener
import com.github.arjenvanharen.mavendeps.ide.FixRunner
import com.github.arjenvanharen.mavendeps.ide.MavenAnalysisService
import com.github.arjenvanharen.mavendeps.ide.Navigation
import com.intellij.icons.AllIcons
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.PopupHandler
import com.intellij.ui.awt.RelativePoint
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
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
        val factory = ContentFactory.getInstance()
        val contents = toolWindow.contentManager
        val dependencies = DependencyPanel(project)
        val dependenciesContent = factory.createContent(dependencies, "Dependencies", false)
        // The explorer hands searches and updates to the Dependencies tab and brings it to the front.
        val actions = object : DependencyActions {
            override fun find(coordinates: Coordinates) {
                contents.setSelectedContent(dependenciesContent)
                dependencies.find(coordinates)
            }

            override fun updateVersion(coordinates: Coordinates) {
                contents.setSelectedContent(dependenciesContent)
                dependencies.updateVersion(coordinates)
            }
        }
        val problemsContent = factory.createContent(AnalyzerPanel(project, toolWindow.disposable), "Problems", false)
        contents.addContent(problemsContent)
        // The tab title counts errors and warnings, so they are visible from every tab.
        fun title(result: AnalysisResult) {
            val count = result.count(Severity.ERROR) + result.count(Severity.WARNING)
            problemsContent.displayName = if (count > 0) "Problems ($count)" else "Problems"
        }
        MavenAnalysisService.getInstance(project).lastResult?.let(::title)
        project.messageBus.connect(toolWindow.disposable).subscribe(
            MavenAnalysisListener.TOPIC,
            MavenAnalysisListener { result -> ApplicationManager.getApplication().invokeLater({ title(result) }, project.disposed) },
        )
        contents.addContent(factory.createContent(ExplorerPanel(project, toolWindow.disposable, actions), "Explorer", false))
        contents.addContent(factory.createContent(GraphPanel(project, toolWindow.disposable), "Graph", false))
        contents.addContent(dependenciesContent)
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
                if (e.keyCode == KeyEvent.VK_ENTER && e.isAltDown) {
                    showFixes()
                    e.consume()
                } else if (e.keyCode == KeyEvent.VK_ENTER && navigateToSelection()) {
                    e.consume()
                }
            }
        })
        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: java.awt.Component, x: Int, y: Int) {
                tree.getPathForLocation(x, y)?.let { tree.selectionPath = it }
                showFixes(RelativePoint(comp, java.awt.Point(x, y)))
            }
        })

        val content = JPanel(BorderLayout())
        content.add(InlineProgress(project, parent), BorderLayout.NORTH)
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
            add(object : DumbAwareAction("Apply Quick Fix", "Apply a fix for the selected problem (Alt+Enter)", AllIcons.Actions.IntentionBulb) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedProblem()?.problem?.fixes?.isNotEmpty() == true
                }
                override fun actionPerformed(e: AnActionEvent) = showFixes()
            })
            add(object : DumbAwareAction("Apply All Suggestions", "Apply every 'extract version property' suggestion", AllIcons.Actions.QuickfixBulb) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = suggestionFixes().isNotEmpty()
                }
                override fun actionPerformed(e: AnActionEvent) = FixRunner.applyAll(project, "Apply all suggestions", suggestionFixes())
            })
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
        val suggestions = result.poms.values.sumOf { r -> r.problems.count { it.category == Category.SUGGESTIONS } }
        status.text = "${s.total} POMs · ${s.parsed} parsed, ${s.reused} cached · ${s.revalidated} revalidated · ${s.durationMs} ms" +
            " — $errors errors, $warnings warnings, $suggestions suggestions"
        if (result.poms.isEmpty()) status.text = "No pom.xml found in the project root or content roots"
    }

    private fun selectedProblem(): ProblemNode? = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ProblemNode

    /** One fix per distinct suggestion; the same suggestion is reported on every declaration it touches. */
    private fun suggestionFixes(): List<Pair<Path, QuickFix>> {
        val result = MavenAnalysisService.getInstance(project).lastResult ?: return emptyList()
        return result.poms.values.flatMap { r -> r.problems.filter { it.category == Category.SUGGESTIONS }.flatMap { p -> p.fixes.take(1).map { r.path to it } } }
            .distinctBy { it.second }
    }

    private fun showFixes(at: RelativePoint? = null) {
        val node = selectedProblem() ?: return
        val fixes = node.problem.fixes
        if (fixes.isEmpty()) return
        val step = object : BaseListPopupStep<QuickFix>("Quick Fixes", fixes) {
            override fun getTextFor(value: QuickFix) = value.title
            override fun getIconFor(value: QuickFix) = AllIcons.Actions.IntentionBulb
            override fun onChosen(selectedValue: QuickFix, finalChoice: Boolean): PopupStep<*>? =
                doFinalStep { FixRunner.apply(project, node.path, selectedValue) }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        val where = at ?: tree.getPathBounds(tree.selectionPath)?.let { RelativePoint(tree, java.awt.Point(it.x, it.y + it.height)) }
        if (where != null) popup.show(where) else popup.showInFocusCenter()
    }

    private fun navigateToSelection(): Boolean {
        val data = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject ?: return false
        val (path, line, column) = when (data) {
            is ProblemNode -> Triple(data.path, data.problem.pos.line, data.problem.pos.column)
            is PomNode -> Triple(data.result.path, data.result.model.gavPos.line, 0)
            else -> return false
        }
        Navigation.open(project, path, line, column)
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
                    if (p.fixes.isNotEmpty()) append("  ⚡ quick fix", SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES)
                }
            }
        }
    }
}
