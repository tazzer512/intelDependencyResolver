package com.github.arjenvanharen.mavendeps.ide.ui

import com.github.arjenvanharen.mavendeps.core.AnalysisResult
import com.github.arjenvanharen.mavendeps.core.DependencyIndex
import com.github.arjenvanharen.mavendeps.core.Library
import com.github.arjenvanharen.mavendeps.core.LibraryUsage
import com.github.arjenvanharen.mavendeps.core.ScanResult
import com.github.arjenvanharen.mavendeps.core.VersionAligner
import com.github.arjenvanharen.mavendeps.core.VulnSeverity
import com.github.arjenvanharen.mavendeps.core.VulnerabilityScanner
import com.github.arjenvanharen.mavendeps.core.Vulnerability
import com.github.arjenvanharen.mavendeps.core.VulnerableArtifact
import com.github.arjenvanharen.mavendeps.ide.MavenAnalysisListener
import com.github.arjenvanharen.mavendeps.ide.MavenAnalysisService
import com.github.arjenvanharen.mavendeps.ide.Navigation
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.HyperlinkAdapter
import com.intellij.ui.InlineBanner
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.HyperlinkEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

private class ArtifactItem(val artifact: VulnerableArtifact) {
    override fun toString() = artifact.artifact.toString()
}

private class AdvisoryItem(val vulnerability: Vulnerability, val artifact: VulnerableArtifact) {
    override fun toString() = vulnerability.id
}

private class UsedByItem(val usage: LibraryUsage) {
    override fun toString() = usage.module.toString() + ":" + usage.node.chain.joinToString { it.label }
}

private class Scan(val analysis: AnalysisResult, val result: ScanResult, val libraries: Map<String, Library>)

/**
 * Checks every resolved dependency of every module, direct and transitive, against the OSV vulnerability database
 * (GitHub Security Advisories, CVEs and more). Scans run only on request, because they send the coordinates of the
 * dependencies to osv.dev.
 */
internal class SecurityPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
    private val service get() = MavenAnalysisService.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val details = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        border = JBUI.Borders.empty(8)
    }
    private val status = JBLabel()
    private val progress = InlineProgress(project, parent)
    private val staleBanner = InlineBanner("The POMs changed since the last scan.", EditorNotificationPanel.Status.Info).apply {
        showCloseButton(false)
        addAction("Scan again") { scan() }
        isVisible = false
    }

    private var scan: Scan? = null
    private var scanning = false
    private var hideTestScopes = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        TreeSpeedSearch.installOn(tree)
        tree.addTreeSelectionListener { showDetails() }
        tree.emptyText.text = "Check all dependencies, direct and transitive, for known vulnerabilities"
        tree.emptyText.appendLine("Scan now", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { scan() }
        tree.emptyText.appendLine("Sends groupId:artifactId:version of each dependency to osv.dev; nothing else leaves the IDE.")
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openSelected()
        }.installOn(tree)
        details.addHyperlinkListener(object : HyperlinkAdapter() {
            override fun hyperlinkActivated(e: HyperlinkEvent) = onLink(e.description)
        })

        val splitter = OnePixelSplitter(false, 0.55f)
        splitter.firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
        splitter.secondComponent = ScrollPaneFactory.createScrollPane(details, true)
        val north = JPanel(BorderLayout())
        north.add(progress, BorderLayout.NORTH)
        north.add(staleBanner.apply { border = JBUI.Borders.empty(4) }, BorderLayout.SOUTH)

        val content = JPanel(BorderLayout())
        content.add(north, BorderLayout.NORTH)
        content.add(splitter, BorderLayout.CENTER)
        status.border = JBUI.Borders.empty(2, 6)
        content.add(status, BorderLayout.SOUTH)
        setContent(content)
        toolbar = createToolbar()
        details.text = hint("Click Scan to check the dependencies of all modules against osv.dev.")
        status.text = "Not scanned yet"

        project.messageBus.connect(parent).subscribe(
            MavenAnalysisListener.TOPIC,
            MavenAnalysisListener { result ->
                ApplicationManager.getApplication().invokeLater({ staleBanner.isVisible = scan != null && scan?.analysis !== result }, project.disposed)
            },
        )
    }

    private fun createToolbar(): javax.swing.JComponent {
        val expander = DefaultTreeExpander(tree)
        val group = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Scan for Vulnerabilities", "Check every resolved dependency against osv.dev", AllIcons.Actions.Execute) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !scanning
                }
                override fun actionPerformed(e: AnActionEvent) = scan()
            })
            add(object : DumbAwareAction(
                "Fix All Vulnerabilities…",
                "Upgrade every vulnerable library with a known fix to its fixed version; review the changes before they are applied",
                AllIcons.Actions.QuickfixBulb,
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = visibleArtifacts().any { it.fixVersion != null }
                }
                override fun actionPerformed(e: AnActionEvent) = fix(visibleArtifacts())
            })
            addSeparator()
            add(object : ToggleAction("Hide Test and Provided Scopes", "Hide vulnerabilities only reached through test or provided dependencies", AllIcons.Nodes.Test), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = hideTestScopes
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    hideTestScopes = state
                    rebuild()
                }
            })
            add(CommonActionsManager.getInstance().createExpandAllAction(expander, tree))
            add(CommonActionsManager.getInstance().createCollapseAllAction(expander, tree))
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("MavenDepsSecurity", group, true)
        toolbar.targetComponent = tree
        return toolbar.component
    }

    private fun scan() {
        val analysis = service.lastResult
        if (analysis == null) {
            status.text = "The POMs have not been analysed yet; try again in a moment"
            return
        }
        if (scanning) return
        scanning = true
        status.text = "Scanning…"
        service.runQuery(
            "Scanning Maven dependencies for vulnerabilities",
            { p ->
                val resolver = service.resolver()
                val modules = resolver.ws.entries.keys.toList()
                val trees = DependencyIndex.resolveAll(resolver, modules, p) { done, total, _ ->
                    p.report(0.3 * done / total, "Resolving dependency trees ($done of $total)")
                }
                val libraries = DependencyIndex.libraries(trees)
                val targets = VulnerabilityScanner.targets(libraries) { resolver.workspacePom(it.groupId, it.artifactId, it.version) != null }
                val result = VulnerabilityScanner.scan(service.osv, targets, p) { done, total, phase ->
                    p.report(0.3 + 0.7 * done / maxOf(total, 1), "$phase ($done of $total)")
                }
                Scan(analysis, result, libraries.associateBy { it.ga })
            },
            onProgress = { progress.job(it) },
            onFailure = {
                scanning = false
                status.text = "Scan failed: ${it.message ?: it.javaClass.simpleName} (is osv.dev reachable? Proxy settings: Settings | HTTP Proxy)"
            },
        ) {
            scanning = false
            scan = it
            staleBanner.isVisible = service.lastResult !== it.analysis
            rebuild()
        }
    }

    private fun passes(u: LibraryUsage) = !hideTestScopes || u.node.scope !in setOf("test", "provided")

    /** Vulnerable artifacts with the usages that pass the scope filter. */
    private fun visibleArtifacts(): List<VulnerableArtifact> =
        scan?.result?.vulnerable.orEmpty().mapNotNull { a ->
            if (!hideTestScopes) a else a.usages.filter(::passes).takeIf { it.isNotEmpty() }?.let { VulnerableArtifact(a.artifact, a.vulnerabilities, it) }
        }

    private fun rebuild() {
        val s = scan ?: return
        root.removeAllChildren()
        val artifacts = visibleArtifacts()
        for (a in artifacts) {
            val node = DefaultMutableTreeNode(ArtifactItem(a))
            a.vulnerabilities.forEach { node.add(DefaultMutableTreeNode(AdvisoryItem(it, a))) }
            a.usages.sortedBy { it.node.depth }.distinctBy { it.module to it.node.chain.map { n -> n.label } }.forEach { node.add(DefaultMutableTreeNode(UsedByItem(it))) }
            root.add(node)
        }
        model.reload()
        TreeUtil.expand(tree, 1)
        if (artifacts.isEmpty()) {
            tree.emptyText.text = "No known vulnerabilities in ${s.result.scanned} scanned artifact versions"
            details.text = hint("No known vulnerabilities. osv.dev was checked for ${s.result.scanned} artifact versions (direct and transitive).")
        } else {
            TreeUtil.promiseSelectFirst(tree)
        }
        val counts = VulnSeverity.entries.mapNotNull { sev ->
            artifacts.sumOf { a -> a.vulnerabilities.count { it.severity == sev } }.takeIf { it > 0 }?.let { "$it ${sev.displayName.lowercase()}" }
        }
        val transitive = artifacts.count { !it.direct }
        status.text = "${s.result.scanned} artifact versions scanned · ${artifacts.size} vulnerable ($transitive only transitive)" +
            (if (counts.isNotEmpty()) " · " + counts.joinToString(", ") else "") + " · data: osv.dev"
    }

    private fun selected(): Any? = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject

    private fun selectedArtifact(): VulnerableArtifact? = when (val s = selected()) {
        is ArtifactItem -> s.artifact
        is AdvisoryItem -> s.artifact
        is UsedByItem -> ((tree.selectionPath?.parentPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ArtifactItem)?.artifact
        else -> null
    }

    /** Opens the declaration that brings the artifact into the module (the direct dependency of its path). */
    private fun openSelected(): Boolean {
        val usage = (selected() as? UsedByItem)?.usage ?: return false
        val d = usage.node.direct.dependency
        Navigation.open(project, d.declaredIn ?: usage.module, d.pos.line, d.pos.column)
        return true
    }

    /** Plans upgrading [artifacts] to their fixed versions in every module, lets the user review and applies. */
    private fun fix(artifacts: List<VulnerableArtifact>) {
        val s = scan ?: return
        val requests = VulnerabilityScanner.fixRequests(artifacts, s.libraries)
        if (requests.isEmpty()) {
            status.text = "No fixed versions are known for these vulnerabilities"
            return
        }
        service.runQuery(
            "Planning vulnerability fixes",
            { p ->
                val resolver = service.resolver()
                VersionAligner.plan(resolver.ws, resolver, requests, p)
            },
            onProgress = { progress.job(it) },
            onFailure = { status.text = "Planning failed: ${it.message}" },
        ) { items ->
            if (items.isEmpty()) {
                status.text = "Nothing to change: the declarations already use the fixed versions"
                return@runQuery
            }
            val list = requests.take(12).joinToString("<br>") { "&nbsp;&nbsp;• ${esc(it.ga)} → <b>${esc(it.target)}</b>" } +
                if (requests.size > 12) "<br>&nbsp;&nbsp;… and ${requests.size - 12} more" else ""
            val header = "<html>Each vulnerable library is upgraded to the lowest version that fixes its known vulnerabilities:<br>$list<br><br>" +
                "Declarations (or the property they use) are updated. Modules that get the library only transitively get the fixed " +
                "version pinned in &lt;dependencyManagement&gt; of their root POM. Versions are never lowered. Run your tests afterwards: " +
                "a newer version can change behaviour.</html>"
            val title = if (requests.size == 1) "Upgrade ${requests[0].ga} to ${requests[0].target}" else "Fix ${requests.size} vulnerable libraries"
            val applied = PlanDialog.reviewAndApply(project, title, header, items)
            status.text = if (applied == null) "Fix cancelled" else "Applied $applied change${if (applied == 1) "" else "s"}; scan again after the analysis to confirm"
        }
    }

    private fun onLink(link: String) {
        when {
            link == "fix" -> selectedArtifact()?.let { fix(listOf(it)) }
            link.startsWith("http://") || link.startsWith("https://") -> BrowserUtil.browse(link)
        }
    }

    // ---- details pane ----

    private fun esc(s: String?) = StringUtil.escapeXmlEntities(s ?: "?")

    private fun hint(text: String) = "<html><body><span style='color:gray'>${esc(text)}</span></body></html>"

    private fun moduleName(p: Path) = service.lastResult?.poms?.get(p)?.model?.effectiveGav?.artifactId ?: p.parent.fileName.toString()

    private fun row(name: String, value: String) = "<tr><td valign='top' style='color:gray'>$name</td><td>$value</td></tr>"

    private fun severityHtml(v: Vulnerability) =
        "<b style='color:#${ColorUtil.toHex(color(v.severity))}'>${v.severity.displayName}</b>" + (v.score?.let { " ($it)" } ?: "")

    private fun showDetails() {
        details.text = when (val s = selected()) {
            is ArtifactItem -> artifactDetails(s.artifact)
            is AdvisoryItem -> advisoryDetails(s.vulnerability, s.artifact)
            is UsedByItem -> usageDetails(s.usage)
            else -> return
        }
        details.caretPosition = 0
    }

    private fun artifactDetails(a: VulnerableArtifact): String = buildString {
        append("<html><body><h3>${esc(a.artifact.toString())}</h3><table>")
        append(row("Vulnerabilities", a.vulnerabilities.joinToString("<br>") { v ->
            "${severityHtml(v)} <a href='${esc(v.url)}'>${esc(v.cve ?: v.id)}</a> ${esc(v.summary)}"
        }))
        append(row("Used", if (a.direct) "directly declared" + if (a.usages.any { it.node.depth > 1 }) " and transitively" else "" else "only transitively"))
        append(row("Modules", a.modules.joinToString(", ") { esc(moduleName(it)) }))
        val fix = a.fixVersion
        append(row("Fix", when {
            fix == null -> "<i>No fixed version is known yet. Check the advisories for workarounds, or exclude the library if it is not needed.</i>"
            a.unfixed > 0 -> "Upgrade to <b>${esc(fix)}</b> (fixes ${a.vulnerabilities.size - a.unfixed} of ${a.vulnerabilities.size}; the others have no fix yet)"
            else -> "Upgrade to <b>${esc(fix)}</b>"
        }))
        append("</table>")
        if (fix != null) {
            append("<p><a href='fix'><b>Upgrade to ${esc(fix)} in every module…</b></a><br><span style='color:gray'>")
            append(if (a.direct) "Updates the declaration" else "Pins the version in &lt;dependencyManagement&gt; of the root POM, because the library only comes in transitively")
            append(". You review every change before it is applied.</span></p>")
        }
        append("</body></html>")
    }

    private fun advisoryDetails(v: Vulnerability, a: VulnerableArtifact): String = buildString {
        append("<html><body><h3>${esc(v.id)}</h3><table>")
        if (v.aliases.isNotEmpty()) append(row("Also known as", v.aliases.joinToString(", ") { esc(it) }))
        append(row("Severity", severityHtml(v)))
        append(row("Summary", esc(v.summary)))
        append(row("Affects", esc(a.artifact.toString())))
        val fixes = v.fixedIn[a.artifact.ga].orEmpty()
        append(row("Fixed in", fixes.joinToString(", ") { esc(it) }.ifEmpty { "<i>no fixed version known</i>" }))
        v.published?.let { append(row("Published", esc(it.take(10)))) }
        append("</table><p><a href='${esc(v.url)}'>Open on osv.dev</a></p>")
        if (v.references.isNotEmpty()) {
            append("<p style='color:gray'>References</p><p>")
            append(v.references.take(10).joinToString("<br>") { "<a href='${esc(it)}'>${esc(it.take(90))}</a>" })
            append("</p>")
        }
        append("</body></html>")
    }

    private fun usageDetails(u: LibraryUsage): String = buildString {
        append("<html><body><h3>${esc(moduleName(u.module))}</h3><table>")
        append(row("Gets", esc(u.node.label) + " (${esc(u.node.scope)} scope)"))
        append(row("Path", (listOf("<b>${esc(moduleName(u.module))}</b>") + u.node.chain.map { esc(it.label) }).joinToString(" → ")))
        append(row("Comes in through", if (u.node.depth == 1) "a direct declaration" else "the direct dependency " + esc(u.node.direct.label)))
        append("</table><p style='color:gray'>Double-click to open the declaration that brings it in.</p></body></html>")
    }

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val data = (value as? DefaultMutableTreeNode)?.userObject) {
                is ArtifactItem -> {
                    val a = data.artifact
                    icon = severityIcon(a.severity)
                    append("${a.artifact.groupId}:", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(a.artifact.artifactId, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(":${a.artifact.version}")
                    val bySeverity = a.vulnerabilities.groupingBy { it.severity }.eachCount().toSortedMap()
                    for ((sev, n) in bySeverity) append("  $n ${sev.displayName.lowercase()}", attributes(sev))
                    append(if (a.direct) "  direct" else "  transitive", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    append("  ${a.modules.size} module${if (a.modules.size == 1) "" else "s"}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    a.fixVersion?.let { append("  fix: $it", FIX_ATTRIBUTES) } ?: append("  no fix yet", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                is AdvisoryItem -> {
                    val v = data.vulnerability
                    icon = AllIcons.Nodes.Annotationtype
                    append(v.cve ?: v.id, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (v.cve != null && v.cve != v.id) append("  ${v.id}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    append("  ${v.severity.displayName}" + (v.score?.let { " $it" } ?: ""), attributes(v.severity))
                    append("  ${v.summary}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    v.fixFor(data.artifact.artifact.ga, data.artifact.artifact.version)?.let { append("  fixed in $it", FIX_ATTRIBUTES) }
                }
                is UsedByItem -> {
                    val n = data.usage.node
                    icon = AllIcons.Nodes.Module
                    append(moduleName(data.usage.module), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (n.scope != "compile") append("  ${n.scope}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    append(if (n.depth == 1) "  declared" else "  via " + n.chain.dropLast(1).joinToString(" → ") { it.artifactId }, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
    }

    companion object {
        private val CRITICAL_COLOR: Color = JBColor(0xB71C1C, 0xFF6B6B)
        private val HIGH_COLOR: Color = JBColor(0xD84315, 0xF08A5D)
        private val MEDIUM_COLOR: Color = JBColor(0xB7791F, 0xE5C07B)
        private val LOW_COLOR: Color = JBColor(0x5C6B7A, 0x9AA7B0)
        private val FIX_ATTRIBUTES = SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor(0x3C8C40, 0x6AAB73))

        fun color(s: VulnSeverity): Color = when (s) {
            VulnSeverity.CRITICAL -> CRITICAL_COLOR
            VulnSeverity.HIGH -> HIGH_COLOR
            VulnSeverity.MEDIUM -> MEDIUM_COLOR
            VulnSeverity.LOW, VulnSeverity.UNKNOWN -> LOW_COLOR
        }

        private fun attributes(s: VulnSeverity) = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color(s))

        private fun severityIcon(s: VulnSeverity): Icon = when (s) {
            VulnSeverity.CRITICAL, VulnSeverity.HIGH -> AllIcons.General.Error
            VulnSeverity.MEDIUM -> AllIcons.General.Warning
            VulnSeverity.LOW, VulnSeverity.UNKNOWN -> AllIcons.General.Information
        }
    }
}
