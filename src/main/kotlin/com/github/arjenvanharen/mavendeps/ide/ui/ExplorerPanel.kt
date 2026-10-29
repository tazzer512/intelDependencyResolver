package com.github.arjenvanharen.mavendeps.ide.ui

import com.github.arjenvanharen.mavendeps.core.MavenVersion
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.github.arjenvanharen.mavendeps.core.AnalysisResult
import com.github.arjenvanharen.mavendeps.core.Coordinates
import com.github.arjenvanharen.mavendeps.core.DependencyIndex
import com.github.arjenvanharen.mavendeps.core.DependencyNode
import com.github.arjenvanharen.mavendeps.core.DependencyResolver
import com.github.arjenvanharen.mavendeps.core.DependencyTree
import com.github.arjenvanharen.mavendeps.core.Library
import com.github.arjenvanharen.mavendeps.core.LibraryUsage
import com.github.arjenvanharen.mavendeps.core.NodeStatus
import com.github.arjenvanharen.mavendeps.core.PomResult
import com.github.arjenvanharen.mavendeps.core.Severity
import com.github.arjenvanharen.mavendeps.core.VersionAligner
import com.github.arjenvanharen.mavendeps.core.VersionConflict
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InlineBanner
import com.github.arjenvanharen.mavendeps.ide.MavenAnalysisListener
import com.github.arjenvanharen.mavendeps.ide.MavenAnalysisService
import com.github.arjenvanharen.mavendeps.ide.Navigation
import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.HyperlinkAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.HierarchyEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.HyperlinkEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** What the explorer needs from the Dependencies tab. */
internal interface DependencyActions {
    fun find(coordinates: Coordinates)

    fun updateVersion(coordinates: Coordinates)
}

private class ModuleItem(val result: PomResult, val tree: DependencyTree?) {
    override fun toString() = result.path.toString()
}

private class DepsFolder(val direct: Int, val total: Int) {
    override fun toString() = "dependencies"
}

private class DepItem(val module: Path, val node: DependencyNode) {
    override fun toString() = node.label + ":" + node.status
}

private class LibraryItem(val library: Library) {
    override fun toString() = library.ga
}

private class UsageItem(val usage: LibraryUsage) {
    override fun toString() = usage.module.toString() + ":" + usage.node.chain.joinToString { it.label }
}

private class Loaded(
    val result: AnalysisResult,
    val resolver: DependencyResolver,
    val trees: Map<Path, DependencyTree>,
    val libraries: Map<String, Library>,
    /** Versions in the local repository per groupId:artifactId, oldest first. */
    val local: Map<String, List<String>>,
) {
    /** Conflicts that are not already resolved to the newest version, by groupId:artifactId. */
    val conflicts: Map<String, VersionConflict> = VersionAligner.conflicts(libraries.values).associateBy { it.library.ga }

    /** The newest local version when it is newer than every version the workspace resolves. */
    fun newer(lib: Library): String? {
        val current = lib.versions.maxWithOrNull(MavenVersion.COMPARATOR) ?: return null
        val cur = MavenVersion(current)
        return local[lib.ga].orEmpty().lastOrNull { v -> MavenVersion(v).let { it > cur && (cur.isPreRelease || !it.isPreRelease) } }
    }
}

/**
 * Browse the module hierarchy and every module's resolved dependency tree, or turn it around and browse libraries
 * with the modules that use them. A details pane explains the selected node and links to its POMs.
 */
internal class ExplorerPanel(private val project: Project, parent: Disposable, private val actions: DependencyActions) :
    SimpleToolWindowPanel(true, true) {
    private enum class View { MODULES, LIBRARIES }

    private val service get() = MavenAnalysisService.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val details = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        border = JBUI.Borders.empty(8)
    }
    private val filter = SearchTextField(false)
    private val status = JBLabel()
    private val progress = InlineProgress(project, parent)
    private val filterAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
    private val conflictBanner = InlineBanner(EditorNotificationPanel.Status.Warning).apply {
        showCloseButton(false)
        addAction("Show conflicts") { showConflicts() }
        addAction("Resolve all to the newest version…") { resolveConflicts(loaded?.conflicts?.values?.toList().orEmpty()) }
        isVisible = false
    }

    private var view = View.MODULES
    private var showOmitted = true
    private var hideTestScopes = false
    private var conflictsOnly = false
    private var loaded: Loaded? = null
    private var stale = true
    private var loading = false
    private var everShown = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        TreeSpeedSearch.installOn(tree)
        tree.addTreeSelectionListener { showDetails() }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openDefault()
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && openDefault()) e.consume()
            }
        })
        PopupHandler.installPopupMenu(tree, contextActions(), "MavenDepsExplorerPopup")
        details.addHyperlinkListener(object : HyperlinkAdapter() {
            override fun hyperlinkActivated(e: HyperlinkEvent) = onLink(e.description)
        })

        filter.textEditor.emptyText.text = "Filter dependencies, e.g. jackson or :2.17"
        filter.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                filterAlarm.cancelAllRequests()
                filterAlarm.addRequest({ rebuild() }, 250)
            }
        })

        val left = JPanel(BorderLayout())
        left.add(JPanel(BorderLayout()).apply {
            add(conflictBanner.apply { border = JBUI.Borders.empty(4) }, BorderLayout.NORTH)
            add(filter, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        left.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        val splitter = OnePixelSplitter(false, 0.62f)
        splitter.firstComponent = left
        splitter.secondComponent = ScrollPaneFactory.createScrollPane(details, true)

        val content = JPanel(BorderLayout())
        content.add(progress, BorderLayout.NORTH)
        content.add(splitter, BorderLayout.CENTER)
        status.border = JBUI.Borders.empty(2, 6)
        content.add(status, BorderLayout.SOUTH)
        setContent(content)
        toolbar = createToolbar()
        details.text = hint("Select a module or dependency to see where it comes from.")

        project.messageBus.connect(parent).subscribe(
            MavenAnalysisListener.TOPIC,
            MavenAnalysisListener {
                ApplicationManager.getApplication().invokeLater({
                    stale = true
                    if (isShowing) load()
                }, project.disposed)
            },
        )
        // Resolving every module is the expensive part; only do it while the tab is visible.
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing && stale) load()
        }
    }

    private fun createToolbar(): javax.swing.JComponent {
        val expander = DefaultTreeExpander(tree)
        val group = DefaultActionGroup().apply {
            add(viewToggle("Modules", "Module hierarchy with each module's resolved dependency tree", AllIcons.Nodes.Module, View.MODULES))
            add(viewToggle("Libraries", "Every library with the modules that use it, conflicts first", AllIcons.Nodes.PpLibFolder, View.LIBRARIES))
            addSeparator()
            add(toggle("Show Omitted Dependencies", "Show duplicates and versions that lost mediation", AllIcons.Actions.Show, { showOmitted }) { showOmitted = it })
            add(toggle("Hide Test and Provided Scopes", "Hide test and provided dependencies", AllIcons.Nodes.Test, { hideTestScopes }) { hideTestScopes = it })
            add(toggle("Only Version Conflicts", "Show only libraries that resolve to more than one version", AllIcons.General.Warning, { conflictsOnly }) { conflictsOnly = it })
            add(object : DumbAwareAction(
                "Resolve All Version Conflicts…",
                "Align every conflicting library to the newest version found; review the changes before they are applied",
                AllIcons.Actions.QuickfixBulb,
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = loaded?.conflicts?.isNotEmpty() == true
                }
                override fun actionPerformed(e: AnActionEvent) = resolveConflicts(loaded?.conflicts?.values?.toList().orEmpty())
            })
            addSeparator()
            add(object : DumbAwareAction("Re-resolve", "Resolve all dependency trees again", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    stale = true
                    load()
                }
            })
            add(CommonActionsManager.getInstance().createExpandAllAction(expander, tree))
            add(CommonActionsManager.getInstance().createCollapseAllAction(expander, tree))
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("MavenDepsExplorer", group, true)
        toolbar.targetComponent = tree
        return toolbar.component
    }

    private fun viewToggle(text: String, description: String, icon: javax.swing.Icon, target: View) =
        toggle(text, description, icon, { view == target }) { if (it) view = target }

    private fun toggle(text: String, description: String, icon: javax.swing.Icon, get: () -> Boolean, set: (Boolean) -> Unit): AnAction =
        object : ToggleAction(text, description, icon), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = get()
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                set(state)
                rebuild()
            }
        }

    private fun contextActions(): ActionGroup = DefaultActionGroup(
        action("Open POM", "Open the POM of this module or artifact", AllIcons.Actions.MenuOpen) { openPom() },
        action("Go to Declaration", "Open the POM line that declares this dependency", AllIcons.Actions.EditSource) { openDeclaration() },
        Separator.getInstance(),
        action("Find Everywhere", "Search all POMs and transitive trees in the Dependencies tab", AllIcons.Actions.Find) { selectedCoordinates()?.let(actions::find) },
        action("Update Version…", "Update this dependency in every POM", AllIcons.Actions.Edit) { selectedCoordinates()?.let(actions::updateVersion) },
        action("Resolve Version Conflict", "Use the newest version of this library in every module", AllIcons.Actions.QuickfixBulb, { selectedConflict() != null }) {
            selectedConflict()?.let { resolveConflicts(listOf(it)) }
        },
        action("Copy as <dependency>", "Copy a POM snippet for this dependency", AllIcons.Actions.Copy) { copySnippet() },
    )

    private fun action(text: String, description: String, icon: javax.swing.Icon, enabled: () -> Boolean = { selected() != null }, run: () -> Unit): AnAction =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = enabled()
            }
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    private fun load() {
        val result = service.lastResult ?: return
        if (loading) return
        loading = true
        stale = false
        status.text = "Resolving dependency trees…"
        service.runQuery(
            "Resolving Maven dependency trees",
            { progress ->
                val resolver = service.resolver()
                val modules = result.poms.keys.toList()
                val trees = DependencyIndex.resolveAll(resolver, modules, progress) { done, total, module ->
                    progress.report(done.toDouble() / total, "Resolving ${result.poms[module]?.model?.effectiveGav?.artifactId ?: module.parent.fileName} ($done of $total)")
                }
                val libraries = DependencyIndex.libraries(trees)
                progress.report(1.0, "Reading versions from the local repository")
                val local = libraries.associate { it.ga to service.repository.versions(it.groupId, it.artifactId) }
                Loaded(result, resolver, trees, libraries.associateBy { it.ga }, local)
            },
            onProgress = { progress.job(it) },
            onFailure = {
                loading = false
                status.text = "Resolving failed: ${it.message}"
            },
        ) {
            loading = false
            loaded = it
            rebuild()
            if (stale) load()
        }
    }

    private fun passes(n: DependencyNode): Boolean =
        (showOmitted || n.status == NodeStatus.INCLUDED) &&
            (!hideTestScopes || n.scope !in setOf("test", "provided")) &&
            (!conflictsOnly || loaded?.conflicts?.containsKey("${n.groupId}:${n.artifactId}") == true)

    private fun matches(text: String): Boolean {
        val f = filter.text.trim()
        return f.isEmpty() || StringUtil.containsIgnoreCase(text, f)
    }

    private fun rebuild() {
        val data = loaded ?: return
        val state = TreeState.createOn(tree, root)
        root.removeAllChildren()
        var shown = 0
        val filtering = filter.text.isNotBlank()

        /** Adds [n] below [parent] when it, or something below it, passes; a match keeps its whole subtree. */
        fun addDep(parent: DefaultMutableTreeNode, module: Path, n: DependencyNode, force: Boolean): Boolean {
            if (!passes(n)) return false
            val self = force || matches(n.label)
            val child = DefaultMutableTreeNode(DepItem(module, n))
            var any = self
            for (c in n.children) if (addDep(child, module, c, self)) any = true
            if (!any) return false
            parent.add(child)
            shown++
            return true
        }

        when (view) {
            View.MODULES -> {
                val visited = HashSet<Path>()
                fun addModule(parent: DefaultMutableTreeNode, path: Path): Boolean {
                    val r = data.result.poms[path] ?: return false
                    if (!visited.add(path)) return false
                    val tree = data.trees[path]
                    val node = DefaultMutableTreeNode(ModuleItem(r, tree))
                    var any = !filtering && !conflictsOnly
                    if (tree != null && tree.roots.isNotEmpty()) {
                        val deps = DefaultMutableTreeNode(DepsFolder(tree.roots.size, tree.all().count { it.status == NodeStatus.INCLUDED }))
                        tree.roots.forEach { addDep(deps, path, it, false) }
                        if (deps.childCount > 0) {
                            node.add(deps)
                            any = true
                        }
                    }
                    for (m in r.modules) if (addModule(node, m)) any = true
                    if (any) parent.add(node)
                    return any
                }
                data.result.roots.forEach { addModule(root, it) }
            }
            View.LIBRARIES -> {
                val libs = data.libraries.values.filter { !conflictsOnly || it.ga in data.conflicts }.sortedWith(compareBy({ it.ga !in data.conflicts }, { it.ga }))
                for (lib in libs) {
                    val usages = lib.usages.filter { passes(it.node) }
                    if (usages.isEmpty()) continue
                    if (filtering && !matches(lib.ga + ":" + lib.requestedVersions.joinToString(":")) &&
                        usages.none { u -> matches(data.result.poms[u.module]?.model?.effectiveGav?.artifactId ?: "") }
                    ) continue
                    val node = DefaultMutableTreeNode(LibraryItem(lib))
                    usages.forEach { node.add(DefaultMutableTreeNode(UsageItem(it))) }
                    root.add(node)
                    shown++
                }
            }
        }
        model.reload()
        when {
            filtering -> TreeUtil.expandAll(tree)
            everShown -> state.applyTo(tree, root)
            else -> TreeUtil.expand(tree, 2)
        }
        everShown = true

        val libraries = data.libraries.values
        val open = data.conflicts.size
        conflictBanner.isVisible = open > 0
        if (open > 0) {
            conflictBanner.setMessage(
                "$open librar${if (open == 1) "y resolves" else "ies resolve"} to different versions in different modules. " +
                    "Mixed versions can cause runtime errors such as NoSuchMethodError.",
            )
        }
        val missing = data.trees.values.flatMapTo(HashSet()) { it.missingPoms }.size
        status.text = "${data.trees.size} modules · ${libraries.size} libraries · $open with version conflicts" +
            (if (missing > 0) " · $missing POMs missing from ${service.repository.root}" else "") +
            (if (filtering) " — $shown matching" else "")
        showDetails()
    }

    private fun selected(): Any? = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject

    private fun selectedNode(): Pair<Path, DependencyNode>? = when (val s = selected()) {
        is DepItem -> s.module to s.node
        is UsageItem -> s.usage.module to s.usage.node
        else -> null
    }

    private fun selectedCoordinates(): Coordinates? = when (val s = selected()) {
        is DepItem -> Coordinates(s.node.groupId, s.node.artifactId, s.node.version)
        is UsageItem -> Coordinates(s.usage.node.groupId, s.usage.node.artifactId, s.usage.node.version)
        is LibraryItem -> Coordinates(s.library.groupId, s.library.artifactId, null)
        is ModuleItem -> s.result.model.effectiveGav.let { Coordinates(it.groupId ?: "", it.artifactId ?: "", it.version) }
        else -> null
    }

    /** Double-click and Enter: a module opens its pom.xml, a direct dependency its declaration, any other its own POM. */
    private fun openDefault(): Boolean = when (val s = selected()) {
        is ModuleItem -> open(s.result.path, s.result.model.gavPos.line)
        is DepItem -> if (s.node.depth == 1) openDeclaration() else openPom()
        is UsageItem -> openDeclaration()
        is LibraryItem -> openPom()
        else -> false
    }

    /** The POM of the selected module or artifact: the workspace module that builds it, or the local repository copy. */
    private fun openPom(): Boolean {
        val s = selected()
        if (s is ModuleItem) return open(s.result.path, 1)
        val c = selectedCoordinates() ?: return false
        val version = c.version ?: (s as? LibraryItem)?.library?.versions?.lastOrNull() ?: return false
        val pom = artifactPom(c.groupId, c.artifactId, version)
        if (pom == null) {
            status.text = "Not in ${service.repository.root}: ${c.groupId}:${c.artifactId}:$version"
            return false
        }
        return open(pom, 1)
    }

    private fun artifactPom(groupId: String, artifactId: String, version: String): Path? =
        loaded?.resolver?.workspacePom(groupId, artifactId, version)
            ?: service.repository.pomPath(groupId, artifactId, version)?.takeIf { Files.isRegularFile(it) }

    /**
     * Where the selected dependency is declared: the module POM (or its parent) for a direct dependency, otherwise the
     * POM of the artifact that brings it in, at its `<artifactId>` when that POM declares it itself.
     */
    private fun openDeclaration(): Boolean {
        val (module, node) = selectedNode() ?: return (selected() as? ModuleItem)?.let { open(it.result.path, 1) } ?: false
        val parent = node.parent
        if (parent == null) {
            val d = node.dependency
            return open(d.declaredIn ?: module, d.pos.line, d.pos.column)
        }
        val pom = parent.version?.let { artifactPom(parent.groupId, parent.artifactId, it) } ?: return openPom()
        val artifactTag = Regex("<artifactId>\\s*${Regex.escape(node.artifactId)}\\s*</artifactId>")
        Navigation.open(project, pom, line = null) { text ->
            artifactTag.find(text)?.range?.first?.let { at -> text.substring(0, at).count { c -> c == '\n' } + 1 }
        }
        return true
    }

    private fun open(path: Path, line: Int, column: Int = 1): Boolean {
        Navigation.open(project, path, line, column)
        return true
    }

    private fun copySnippet() {
        val c = selectedCoordinates() ?: return
        val scope = selectedNode()?.second?.scope?.takeIf { it != "compile" }
        val xml = buildString {
            append("<dependency>\n    <groupId>${esc(c.groupId)}</groupId>\n    <artifactId>${esc(c.artifactId)}</artifactId>\n")
            c.version?.let { append("    <version>${esc(it)}</version>\n") }
            scope?.let { append("    <scope>${esc(it)}</scope>\n") }
            append("</dependency>")
        }
        CopyPasteManager.getInstance().setContents(StringSelection(xml))
        status.text = "Copied ${c.groupId}:${c.artifactId} as <dependency>"
    }

    private fun onLink(action: String) {
        when (action) {
            "pom" -> openPom()
            "declaration" -> openDeclaration()
            "find" -> selectedCoordinates()?.let(actions::find)
            "update" -> selectedCoordinates()?.let(actions::updateVersion)
            "resolve" -> selectedConflict()?.let { resolveConflicts(listOf(it)) }
            "copy" -> copySnippet()
            "conflicts" -> showConflicts()
        }
    }

    private fun showConflicts() {
        view = View.LIBRARIES
        conflictsOnly = true
        rebuild()
        TreeUtil.promiseSelectFirst(tree)
    }

    /** The open conflict of the selected library (or usage of a library). */
    private fun selectedConflict(): VersionConflict? {
        val ga = when (val s = selected()) {
            is LibraryItem -> s.library.ga
            is UsageItem -> "${s.usage.node.groupId}:${s.usage.node.artifactId}"
            is DepItem -> "${s.node.groupId}:${s.node.artifactId}"
            else -> return null
        }
        return loaded?.conflicts?.get(ga)
    }

    /** Plans aligning [conflicts] to their newest version, lets the user review the changes and applies them. */
    private fun resolveConflicts(conflicts: List<VersionConflict>) {
        if (conflicts.isEmpty()) {
            status.text = "No version conflicts to resolve"
            return
        }
        status.text = "Planning how to resolve ${conflicts.size} version conflict${if (conflicts.size == 1) "" else "s"}…"
        service.runQuery(
            "Planning version conflict resolution",
            { p ->
                val resolver = service.resolver()
                VersionAligner.plan(resolver.ws, resolver, conflicts.map { it.request() }, p)
            },
            onProgress = { progress.job(it) },
            onFailure = { status.text = "Planning failed: ${it.message}" },
        ) { items ->
            if (items.isEmpty()) {
                status.text = "Nothing to change: the declarations already use the newest versions"
                return@runQuery
            }
            val single = conflicts.singleOrNull()
            val title = if (single != null) "Resolve ${single.library.ga}: use ${single.target} everywhere" else "Resolve ${conflicts.size} version conflicts"
            val list = conflicts.take(12).joinToString("<br>") { c ->
                "&nbsp;&nbsp;• ${esc(c.library.ga)}: ${c.library.requestedVersions.joinToString(", ") { esc(it) }} → <b>${esc(c.target)}</b>"
            } + if (conflicts.size > 12) "<br>&nbsp;&nbsp;… and ${conflicts.size - 12} more" else ""
            val header = "<html>Each library is aligned to the newest version found in the workspace:<br>$list<br><br>" +
                "Declarations (or the property they use) are updated. Modules that only get an older version transitively get the " +
                "newest one pinned in &lt;dependencyManagement&gt; of their root POM. Versions are never lowered.</html>"
            val applied = PlanDialog.reviewAndApply(project, title, header, items)
            status.text = if (applied == null) "Resolving cancelled" else "Applied $applied change${if (applied == 1) "" else "s"}; the trees are resolved again after the analysis"
        }
    }

    // ---- details pane ----

    private fun hint(text: String) = "<html><body><span style='color:gray'>${esc(text)}</span></body></html>"

    private fun esc(s: String?) = StringUtil.escapeXmlEntities(s ?: "?")

    private fun rel(p: Path): String {
        val base = project.basePath?.let { Path.of(it) }
        return base?.takeIf { p.startsWith(it) }?.relativize(p)?.toString()?.ifEmpty { null } ?: p.toString()
    }

    private fun moduleName(p: Path) = loaded?.result?.poms?.get(p)?.model?.effectiveGav?.artifactId ?: p.parent.fileName.toString()

    private fun links(vararg items: Pair<String, String>) =
        "<p>" + items.joinToString(" &nbsp;·&nbsp; ") { (href, text) -> "<a href='$href'>$text</a>" } + "</p>"

    private fun row(name: String, value: String) = "<tr><td valign='top' style='color:gray'>$name</td><td>$value</td></tr>"

    private fun showDetails() {
        val data = loaded
        if (data == null) {
            details.text = hint(if (loading) "Resolving dependency trees…" else "Not resolved yet.")
            return
        }
        details.text = when (val s = selected()) {
            is ModuleItem -> moduleDetails(data, s)
            is DepsFolder -> hint("Double-click a dependency to open its declaration; right-click for more.")
            is DepItem -> nodeDetails(data, s.module, s.node)
            is UsageItem -> nodeDetails(data, s.usage.module, s.usage.node)
            is LibraryItem -> libraryDetails(data, s.library)
            else -> hint("Select a module or dependency to see where it comes from.")
        }
        details.caretPosition = 0
    }

    private fun moduleDetails(data: Loaded, item: ModuleItem): String {
        val r = item.result
        val gav = r.model.effectiveGav
        val tree = item.tree
        val conflicts = tree?.all()?.filter { "${it.groupId}:${it.artifactId}" in data.conflicts }?.map { it.artifactId }?.distinct()?.count() ?: 0
        val errors = r.problems.count { it.severity == Severity.ERROR }
        val warnings = r.problems.count { it.severity == Severity.WARNING }
        return buildString {
            append("<html><body><h3>${esc(gav.artifactId)}</h3><table>")
            append(row("Coordinates", esc("${gav.groupId}:${gav.artifactId}:${gav.version}")))
            append(row("Packaging", esc(r.model.effectivePackaging)))
            append(row("POM", esc(rel(r.path))))
            r.model.parent?.let { p -> append(row("Parent", esc("${p.gav.groupId}:${p.gav.artifactId}:${p.gav.version}") + if (r.parent == null) " <i>(outside the workspace)</i>" else "")) }
            if (r.modules.isNotEmpty()) append(row("Modules", r.modules.joinToString(", ") { esc(moduleName(it)) }))
            if (tree != null) {
                val included = tree.all().count { it.status == NodeStatus.INCLUDED }
                append(row("Dependencies", "${tree.roots.size} direct, ${included - tree.roots.size} transitive"))
                if (conflicts > 0) append(row("Conflicts", "<a href='conflicts'>$conflicts libraries with several versions</a>"))
                if (tree.missingPoms.isNotEmpty()) append(row("Missing POMs", tree.missingPoms.sorted().joinToString("<br>") { esc(it) }))
            }
            if (errors + warnings > 0) append(row("Problems", "$errors errors, $warnings warnings (see the Problems tab)"))
            append("</table>")
            append(links("pom" to "Open pom.xml", "copy" to "Copy as &lt;dependency&gt;", "find" to "Find usages"))
            append("</body></html>")
        }
    }

    private fun nodeDetails(data: Loaded, module: Path, n: DependencyNode): String {
        val lib = data.libraries["${n.groupId}:${n.artifactId}"]
        val status = when (n.status) {
            NodeStatus.INCLUDED -> if (n.pomFound) "included" else "<span style='color:orange'>included, but its POM is not in the local repository; its dependencies are unknown</span>"
            NodeStatus.OMITTED_DUPLICATE -> "omitted: already included elsewhere in this tree"
            NodeStatus.OMITTED_CONFLICT -> "<span style='color:red'>omitted: version ${esc(n.winnerVersion)} wins (nearest wins)</span>"
        }
        val chain = (listOf("<b>${esc(moduleName(module))}</b>") + n.chain.map { esc(it.label) }).joinToString(" → ")
        val declaredIn = if (n.parent == null) {
            val d = n.dependency
            esc(rel(d.declaredIn ?: module)) + " line ${d.pos.line}" + if (d.declaredIn != null && d.declaredIn != module) " <i>(inherited)</i>" else ""
        } else {
            "the POM of " + esc(n.parent!!.label)
        }
        val pom = n.version?.let { artifactPom(n.groupId, n.artifactId, it) }
        return buildString {
            append("<html><body><h3>${esc(n.artifactId)}</h3><table>")
            append(row("Coordinates", esc(n.label) + (n.dependency.classifier?.let { ":" + esc(it) } ?: "")))
            append(row("Scope", esc(n.scope) + if (n.dependency.optional) ", optional" else ""))
            append(row("Status", status))
            n.managedFrom?.let { append(row("Managed", "version forced by dependencyManagement (requested ${esc(it)})")) }
            append(row("Path", chain))
            append(row("Declared in", declaredIn))
            append(row("POM", pom?.let { esc(rel(it)) } ?: "<i>not available</i>"))
            if (n.children.isNotEmpty()) append(row("Brings in", "${n.children.size} dependencies"))
            if (lib != null) {
                val modules = lib.modules.size
                val versions = lib.versions.joinToString(", ") { esc(it) }
                append(row("In workspace", "$modules module${if (modules == 1) "" else "s"} · version${if (lib.versions.size == 1) "" else "s"} $versions" +
                    if (lib.ga in data.conflicts) " <a href='resolve'><span style='color:red'>(conflict — resolve)</span></a>" else ""))
            }
            append("</table>")
            append(links("declaration" to "Go to declaration", "pom" to "Open POM", "find" to "Find everywhere", "update" to "Update version…", "copy" to "Copy as &lt;dependency&gt;"))
            append("</body></html>")
        }
    }

    private fun libraryDetails(data: Loaded, lib: Library): String = buildString {
        append("<html><body><h3>${esc(lib.ga)}</h3>")
        if (lib.conflict) append(conflictSection(data, lib))
        append("<table>")
        append(row("Resolved versions", lib.versions.joinToString(", ") { esc(it) }.ifEmpty { "<i>none (all omitted)</i>" }))
        if (lib.requestedVersions.size > lib.versions.size) append(row("Also requested", (lib.requestedVersions - lib.versions.toSet()).joinToString(", ") { esc(it) }))
        append(row("Declared directly", if (lib.declared) "yes" else "no, only transitively"))
        val newer = data.newer(lib)
        data.local[lib.ga]?.takeIf { it.isNotEmpty() }?.let { local ->
            append(row("Local repository", local.takeLast(8).joinToString(", ") { v ->
                when {
                    v == newer -> "<b style='color:#${ColorUtil.toHex(NEWER_COLOR)}'>${esc(v)} ↑</b>"
                    v in lib.versions -> "<b>${esc(v)}</b>"
                    else -> esc(v)
                }
            } + if (local.size > 8) " <i>(${local.size - 8} older)</i>" else ""))
        }
        val byVersion = lib.usages.filter { it.node.status == NodeStatus.INCLUDED }.groupBy { it.node.version }
        for ((version, usages) in byVersion) {
            append(row(esc(version), usages.map { it.module }.distinct().joinToString(", ") { esc(moduleName(it)) }))
        }
        append("</table>")
        append(links("pom" to "Open POM", "find" to "Find everywhere", "update" to "Update version…", "copy" to "Copy as &lt;dependency&gt;"))
        append("</body></html>")
    }

    /** Explains a version conflict in plain words: who gets which version, and the one-click way to resolve it. */
    private fun conflictSection(data: Loaded, lib: Library): String = buildString {
        val conflict = data.conflicts[lib.ga]
        val newest = conflict?.target ?: VersionAligner.newest(lib.requestedVersions)
        val warn = ColorUtil.toHex(JBColor.RED)
        append("<table width='100%' cellpadding='6' style='border:1px solid #${ColorUtil.toHex(JBColor.border())}'><tr><td>")
        if (conflict == null) {
            append("<b>Several versions are requested, but this is already resolved.</b><br>")
            append("Every module uses ${esc(newest)}, the newest version; the older versions that other dependencies ask for are left out by Maven.")
        } else {
            append("<b style='color:#$warn'>⚠ Version conflict</b><br>")
            append("Not all modules use the same version of this library. A module can get an older version than its neighbours, ")
            append("and mixing versions can cause runtime errors such as NoSuchMethodError.<br><br>")
            append("<table cellpadding='2'>")
            val byVersion = lib.usages.filter { it.node.status == NodeStatus.INCLUDED && it.node.version != null }.groupBy { it.node.version!! }
            for ((version, usages) in byVersion.entries.sortedByDescending { MavenVersion(it.key) }) {
                val label = if (version == newest) "<b>${esc(version)}</b> (newest)" else "<span style='color:#$warn'>${esc(version)}</span>"
                val who = usages.distinctBy { it.module }.joinToString(", ") { u ->
                    esc(moduleName(u.module)) + if (u.node.depth == 1) " (declared)" else " (via ${esc(u.node.direct.artifactId)})"
                }
                append("<tr><td valign='top'>$label</td><td>$who</td></tr>")
            }
            append("</table><br>")
            append("<a href='resolve'><b>Use ${esc(newest)} everywhere</b></a> &nbsp;·&nbsp; <a href='update'>Choose another version…</a><br>")
            append("<span style='color:gray'>Updates the declarations of this library and pins ${esc(newest)} in &lt;dependencyManagement&gt; ")
            append("for modules that only get it transitively. You review every change before it is applied.</span>")
        }
        append("</td></tr></table><br>")
    }

    // ---- rendering ----

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val data = (value as? DefaultMutableTreeNode)?.userObject) {
                is ModuleItem -> {
                    val gav = data.result.model.effectiveGav
                    icon = if (data.result.model.effectivePackaging == "pom") AllIcons.Nodes.ModuleGroup else AllIcons.Nodes.Module
                    append(gav.artifactId ?: data.result.path.parent.fileName.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${gav.groupId ?: "?"}:${gav.version ?: "?"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    val errors = data.result.problems.count { it.severity == Severity.ERROR }
                    if (errors > 0) append("  $errors error${if (errors > 1) "s" else ""}", SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
                is DepsFolder -> {
                    icon = AllIcons.Nodes.PpLibFolder
                    append("Dependencies")
                    append("  ${data.direct} direct · ${data.total} total", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                is DepItem -> renderNode(data.node, null)
                is UsageItem -> renderNode(data.usage.node, data.usage.module)
                is LibraryItem -> {
                    val lib = data.library
                    icon = if (loaded?.conflicts?.containsKey(lib.ga) == true) AllIcons.General.Warning else AllIcons.Nodes.PpLib
                    append("${lib.groupId}:", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(lib.artifactId, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    val conflict = loaded?.conflicts?.get(lib.ga)
                    if (conflict != null) {
                        append("  ${lib.versions.size} versions: " + lib.versions.joinToString(", "), SimpleTextAttributes.ERROR_ATTRIBUTES)
                        append("  → use ${conflict.target}", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                    } else {
                        append("  " + lib.versions.joinToString(", ").ifEmpty { "omitted" }, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    val modules = lib.modules.size
                    append("  $modules module${if (modules == 1) "" else "s"}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    if (!lib.declared) append("  transitive only", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    loaded?.newer(lib)?.let { append("  ↑ $it in local repo", NEWER_ATTRIBUTES) }
                }
            }
        }

        private fun renderNode(n: DependencyNode, module: Path?) {
            val omitted = n.status != NodeStatus.INCLUDED
            val workspace = n.version?.let { loaded?.resolver?.workspacePom(n.groupId, n.artifactId, it) } != null
            icon = when {
                module != null -> AllIcons.Nodes.Module
                !n.pomFound -> AllIcons.General.Warning
                workspace -> AllIcons.Nodes.Module
                n.depth == 1 -> AllIcons.Nodes.PpLib
                else -> AllIcons.Nodes.PpLibFolder
            }
            val main = if (omitted) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
            if (module != null) {
                append(moduleName(module), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${n.version ?: "?"}", main)
            } else {
                append("${n.groupId}:", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(n.artifactId, if (omitted) main else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append(":${n.version ?: "?"}", main)
            }
            if (n.scope != "compile") append("  ${n.scope}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            if (n.dependency.optional) append("  optional", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            when (n.status) {
                NodeStatus.INCLUDED -> {}
                NodeStatus.OMITTED_DUPLICATE -> append("  duplicate", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                NodeStatus.OMITTED_CONFLICT -> append("  ✕ ${n.winnerVersion} wins", SimpleTextAttributes.ERROR_ATTRIBUTES)
            }
            n.managedFrom?.let { append("  managed (was $it)", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
            if (!n.pomFound) append("  POM missing", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            if (module != null) {
                if (n.depth == 1) append("  declared", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                else append("  via " + n.chain.dropLast(1).joinToString(" → ") { it.artifactId }, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            } else if (n.children.isNotEmpty() && !omitted) {
                append("  +${n.children.size}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }
}

private val NEWER_COLOR = JBColor(0x3C8C40, 0x6AAB73)
private val NEWER_ATTRIBUTES = SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, NEWER_COLOR)
