package com.github.arjenvanharen.mavendeps.ide.ui

import com.github.arjenvanharen.mavendeps.core.AnalysisResult
import com.github.arjenvanharen.mavendeps.core.ArtifactVersion
import com.github.arjenvanharen.mavendeps.core.ClassIndex
import com.github.arjenvanharen.mavendeps.core.CodeImpact
import com.github.arjenvanharen.mavendeps.core.CodeImpactAnalyzer
import com.github.arjenvanharen.mavendeps.core.Impact
import com.github.arjenvanharen.mavendeps.core.DependencyIndex
import com.github.arjenvanharen.mavendeps.core.FixReport
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
import com.github.arjenvanharen.mavendeps.ide.QueryCancelledException
import com.github.arjenvanharen.mavendeps.ide.Navigation
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.CheckboxAction
import com.intellij.openapi.ide.CopyPasteManager
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
import java.awt.datatransfer.StringSelection
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

private class Scan(val analysis: AnalysisResult, val result: ScanResult, val libraries: Map<String, Library>) {
    /** Whether the code reaches each advisory's vulnerable classes, by artifact and advisory id; null until checked. */
    var impacts: Map<Pair<ArtifactVersion, String>, CodeImpact>? = null
}

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

    /** Always-visible counterpart of the toolbar's "Fix All Vulnerabilities…", with the number it would fix. */
    private val fixAllButton = javax.swing.JButton("Fix All Issues…").apply {
        isEnabled = false
        toolTipText = "Upgrade every vulnerable library with a known fix (or downgrade to a safe older version when no newer " +
            "version is safe), in every module. The change is checked for new problems and version conflicts before it is applied."
        addActionListener { fix(visibleArtifacts()) }
    }

    private var scan: Scan? = null
    private var scanning = false
    private var hideTestScopes = false
    private val properties get() = PropertiesComponent.getInstance(project)

    /** "Code is affected by CVE": check whether the project's code reaches the vulnerable classes. */
    private var checkImpact: Boolean
        get() = properties.getBoolean(CHECK_IMPACT_KEY, false)
        set(value) = properties.setValue(CHECK_IMPACT_KEY, value, false)
    private var checkingImpact = false
    private val classIndex = ClassIndex()

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
        val south = JPanel(BorderLayout())
        south.add(status, BorderLayout.CENTER)
        south.add(fixAllButton, BorderLayout.EAST)
        south.border = JBUI.Borders.empty(2, 0, 2, 4)
        content.add(south, BorderLayout.SOUTH)
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
                "Upgrade every vulnerable library with a known fix to its fixed version (or, when no newer version is safe, " +
                    "downgrade it to the newest older version without known vulnerabilities); review the changes before they are applied",
                AllIcons.Actions.QuickfixBulb,
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = visibleArtifacts().any { it.target != null }
                }
                override fun actionPerformed(e: AnActionEvent) = fix(visibleArtifacts())
            })
            add(object : DumbAwareAction(
                "Copy Fix Report for AI",
                "Copy a short text report of the vulnerabilities to fix (only those that impact the code when " +
                    "'Code is affected by CVE' is on), ready to paste into an AI assistant",
                AllIcons.Actions.Copy,
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = scan != null && !(checkImpact && (checkingImpact || scan?.impacts == null) && visibleArtifacts().isNotEmpty())
                }
                override fun actionPerformed(e: AnActionEvent) = copyReport()
            })
            addSeparator()
            add(object : CheckboxAction(
                "Code is affected by CVE",
                "Check whether the project's code (and the libraries it uses) reaches the vulnerable classes of each advisory. " +
                    "Runs locally on the sources and the jars in ~/.m2; advisories that cannot be reached are flagged 'Does not impact code'.",
                null,
            ), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = checkImpact
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    checkImpact = state
                    if (state && scan?.impacts == null) analyzeImpact() else rebuild()
                }
            })
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
        updateFixAllButton()
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
                val result = VulnerabilityScanner.scan(service.osv, targets, p, service.safeVersions) { done, total, phase ->
                    p.report(0.3 + 0.7 * done / maxOf(total, 1), "$phase ($done of $total)")
                }
                Scan(analysis, result, libraries.associateBy { it.ga })
            },
            onProgress = { progress.job(it) },
            onFailure = {
                scanning = false
                updateFixAllButton()
                status.text = if (it is QueryCancelledException) "Scan cancelled"
                else "Scan failed: ${it.message ?: it.javaClass.simpleName} (is osv.dev reachable? Proxy settings: Settings | HTTP Proxy)"
            },
        ) {
            scanning = false
            scan = it
            staleBanner.isVisible = service.lastResult !== it.analysis
            rebuild()
            if (checkImpact) analyzeImpact()
        }
    }

    /** Checks, for every advisory of the current scan, whether the code reaches its vulnerable classes. Local only. */
    private fun analyzeImpact() {
        val s = scan ?: return
        if (checkingImpact || s.result.vulnerable.isEmpty()) return
        checkingImpact = true
        status.text = "Checking whether the code is affected…"
        service.runQuery(
            "Checking whether the code is affected by vulnerabilities",
            { p ->
                val resolver = service.resolver()
                CodeImpactAnalyzer(service.repository, classIndex).analyze(s.result.vulnerable, s.libraries.values, resolver::workspacePom, p) { done, total, phase ->
                    p.report(done.toDouble() / maxOf(total, 1), "$phase ($done of $total)")
                }
            },
            onProgress = { progress.job(it) },
            onFailure = {
                checkingImpact = false
                status.text = if (it is QueryCancelledException) "Code impact check cancelled" else "Code impact check failed: ${it.message ?: it.javaClass.simpleName}"
            },
        ) {
            checkingImpact = false
            s.impacts = it
            if (scan === s) rebuild()
        }
    }

    /** Copies a plain-text fix brief of the visible vulnerabilities to the clipboard. */
    private fun copyReport() {
        val base = project.basePath?.let { Path.of(it) }
        val impacts = if (checkImpact) scan?.impacts else null
        val report = FixReport.render(
            visibleArtifacts(),
            impacts,
            { p -> if (base != null && p.startsWith(base)) base.relativize(p).joinToString("/") else p.toString() },
            ::moduleName,
        )
        CopyPasteManager.getInstance().setContents(StringSelection(report))
        val listed = report.lines().count { it.startsWith("## ") }
        status.text = "Fix report copied: $listed librar${if (listed == 1) "y" else "ies"} to fix" +
            if (impacts != null) " (only advisories that impact the code)" else " (code impact not checked: all advisories)"
    }

    /** The code impact of [v] on [a], when the check is enabled and has run. */
    private fun impact(a: VulnerableArtifact, v: Vulnerability): CodeImpact? = if (checkImpact) scan?.impacts?.get(a.artifact to v.id) else null

    private fun passes(u: LibraryUsage) = !hideTestScopes || u.node.scope !in setOf("test", "provided")

    /** Vulnerable artifacts with the usages that pass the scope filter. */
    private fun visibleArtifacts(): List<VulnerableArtifact> =
        scan?.result?.vulnerable.orEmpty().mapNotNull { a ->
            if (!hideTestScopes) a else a.usages.filter(::passes).takeIf { it.isNotEmpty() }?.let { VulnerableArtifact(a.artifact, a.vulnerabilities, it) }
        }

    private fun updateFixAllButton() {
        val all = visibleArtifacts()
        val fixable = all.count { it.target != null }
        fixAllButton.text = when {
            all.isEmpty() -> "Fix All Issues…"
            fixable == all.size -> "Fix All Issues ($fixable)…"
            else -> "Fix All Issues ($fixable of ${all.size})…"
        }
        fixAllButton.isEnabled = fixable > 0 && !scanning
    }

    private fun rebuild() {
        updateFixAllButton()
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
        val impacts = artifacts.flatMap { a -> a.vulnerabilities.mapNotNull { impact(a, it) } }
        val impactText = when {
            !checkImpact || artifacts.isEmpty() -> ""
            checkingImpact || s.impacts == null -> " · checking code impact…"
            else -> " · " + listOfNotNull(
                impacts.count { it.impact == Impact.AFFECTED }.let { "$it impact code" },
                impacts.count { it.impact == Impact.NOT_AFFECTED }.let { "$it do not" },
                impacts.count { it.impact == Impact.UNKNOWN }.takeIf { it > 0 }?.let { "$it unknown" },
            ).joinToString(", ")
        }
        status.text = "${s.result.scanned} artifact versions scanned · ${artifacts.size} vulnerable ($transitive only transitive)" +
            (if (counts.isNotEmpty()) " · " + counts.joinToString(", ") else "") + impactText + " · data: osv.dev"
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
            val list = requests.take(12).joinToString("<br>") {
                "&nbsp;&nbsp;• ${esc(it.ga)} → <b>${esc(it.target)}</b>" + if (it.downgrade) " <i>(downgrade: no newer version is safe)</i>" else ""
            } +
                if (requests.size > 12) "<br>&nbsp;&nbsp;… and ${requests.size - 12} more" else ""
            val downgrades = requests.any { it.downgrade }
            val header = "<html>Each vulnerable library is upgraded to the lowest version that fixes its known vulnerabilities" +
                (if (downgrades) ", or, when no newer version fixes them, downgraded to the newest older version without known vulnerabilities" else "") +
                ":<br>$list<br><br>" +
                "Declarations (or the property they use) are updated. Modules that get the library only transitively get the fixed " +
                "version pinned in &lt;dependencyManagement&gt; of their root POM. " +
                (if (downgrades) "Only downgrades lower a version. Run your tests afterwards: an older version can lack API the code uses." +
                    "</html>" else "Versions are never lowered. Run your tests afterwards: a newer version can change behaviour.</html>")
            val single = requests.singleOrNull()
            val title = when {
                single == null -> "Fix ${requests.size} vulnerable libraries"
                single.downgrade -> "Downgrade ${single.ga} to ${single.target}"
                else -> "Upgrade ${single.ga} to ${single.target}"
            }
            val applied = PlanDialog.reviewAndApply(project, title, header, items)
            status.text = if (applied == null) "Fix cancelled" else "Checking $applied change${if (applied == 1) "" else "s"} for new problems, then applying; scan again after the analysis to confirm"
        }
    }

    private fun onLink(link: String) {
        when {
            link == "fix" -> selectedArtifact()?.let { fix(listOf(it)) }
            link == "recheck" -> scan?.let { it.impacts = null; analyzeImpact() }
            link.startsWith("http://") || link.startsWith("https://") -> BrowserUtil.browse(link)
        }
    }

    // ---- details pane ----

    private fun esc(s: String?) = StringUtil.escapeXmlEntities(s ?: "?")

    private fun majorOf(v: String) = v.substringBefore('.').substringBefore('-')

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
            "${severityHtml(v)} <a href='${esc(v.url)}'>${esc(v.cve ?: v.id)}</a> ${esc(v.summary)}" +
                (impact(a, v)?.let { " <i style='color:#${ColorUtil.toHex(impactColor(it.impact))}'>${impactLabel(it.impact)}</i>" } ?: "")
        }))
        append(row("Used", if (a.direct) "directly declared" + if (a.usages.any { it.node.depth > 1 }) " and transitively" else "" else "only transitively"))
        append(row("Modules", a.modules.joinToString(", ") { esc(moduleName(it)) }))
        val fix = a.fixVersion
        val older = a.saferOlderVersion
        val olderText = older?.let {
            "<br>No newer version fixes all of them; <b>${esc(it)}</b> is the newest older version without known vulnerabilities" +
                if (majorOf(it) != majorOf(a.artifact.version)) " (a different major version)" else ""
        } ?: ""
        append(row("Fix", when {
            fix == null && older != null -> "Downgrade to <b>${esc(older)}</b>: no newer version fixes these vulnerabilities, and osv.dev " +
                "knows none for ${esc(older)}" + if (majorOf(older) != majorOf(a.artifact.version)) " (a different major version)" else ""
            fix == null -> "<i>No fixed version is known yet, and no older version without known vulnerabilities was found. " +
                "Check the advisories for workarounds, or exclude the library if it is not needed.</i>"
            a.unfixed > 0 -> "Upgrade to <b>${esc(fix)}</b> (fixes ${a.vulnerabilities.size - a.unfixed} of ${a.vulnerabilities.size}; the others have no fix yet)$olderText"
            else -> "Upgrade to <b>${esc(fix)}</b>"
        } + when {
            fix == null -> ""
            a.fixStillVulnerable -> "<br><i>${esc(fix)} itself still has known vulnerabilities, and no newer version is known to fix them.</i>"
            fix != a.advisoryFixVersion -> "<br><span style='color:gray'>The advisories name ${esc(a.advisoryFixVersion)}, but that version has " +
                "vulnerabilities of its own; ${esc(fix)} has none known at osv.dev.</span>"
            else -> "<br><span style='color:gray'>Checked at osv.dev: ${esc(fix)} has no known vulnerabilities.</span>"
        }))
        append("</table>")
        if (fix == null && older != null) {
            append("<p><a href='fix'><b>Downgrade to ${esc(older)} in every module that uses ${esc(a.artifact.version)}…</b></a><br><span style='color:gray'>")
            append("An older version can lack API the code uses: run the tests afterwards. You review every change before it is applied.</span></p>")
        } else if (fix != null) {
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
        impact(a, v)?.let { append(row("Code", impactHtml(it, a))) }
        append("</table><p><a href='${esc(v.url)}'>Open on osv.dev</a></p>")
        if (v.references.isNotEmpty()) {
            append("<p style='color:gray'>References</p><p>")
            append(v.references.take(10).joinToString("<br>") { "<a href='${esc(it)}'>${esc(it.take(90))}</a>" })
            append("</p>")
        }
        append("</body></html>")
    }

    private fun impactHtml(i: CodeImpact, a: VulnerableArtifact): String = buildString {
        append("<b style='color:#${ColorUtil.toHex(impactColor(i.impact))}'>${impactLabel(i.impact)}</b><br>")
        val scope = if (i.namedClasses.isEmpty()) {
            "The advisory names no class of ${esc(a.artifact.toString())}, so all of its classes count as vulnerable."
        } else {
            "Vulnerable classes named by the advisory: " + i.namedClasses.take(10).joinToString(", ") { "<code>${esc(it.substringAfterLast('.'))}</code>" } +
                (if (i.namedClasses.size > 10) " …" else "") +
                " (${i.surfaceSize} class${if (i.surfaceSize == 1) "" else "es"} of the library lead to them)."
        }
        append(scope)
        when (i.impact) {
            Impact.AFFECTED -> {
                append("<br>Referenced from:<br>")
                append(i.evidence.joinToString("<br>") { "&nbsp;&nbsp;• ${esc(it)}" })
            }
            Impact.NOT_AFFECTED -> append(
                "<br>Neither the modules' code nor any library on their classpath references them. " +
                    "<span style='color:gray'>Classes loaded purely by reflection are not seen.</span>",
            )
            Impact.UNKNOWN -> {
                append("<br>Not referenced where it could be checked, but:<br>")
                append(i.reasons.joinToString("<br>") { "&nbsp;&nbsp;• ${esc(it)}" })
            }
        }
        append("<br><a href='recheck'>Check again</a> <span style='color:gray'>(after code changes)</span>")
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
                    val impacts = a.vulnerabilities.mapNotNull { impact(a, it) }
                    if (impacts.size == a.vulnerabilities.size) {
                        val affected = impacts.count { it.impact == Impact.AFFECTED }
                        when {
                            impacts.all { it.impact == Impact.NOT_AFFECTED } -> append("  ${impactLabel(Impact.NOT_AFFECTED)}", impactAttributes(Impact.NOT_AFFECTED))
                            affected > 0 -> append("  $affected of ${impacts.size} impact code", impactAttributes(Impact.AFFECTED))
                            else -> append("  ${impactLabel(Impact.UNKNOWN)}", impactAttributes(Impact.UNKNOWN))
                        }
                    }
                    append(if (a.direct) "  direct" else "  transitive", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    append("  ${a.modules.size} module${if (a.modules.size == 1) "" else "s"}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    when {
                        a.fixVersion != null && a.fixStillVulnerable ->
                            append("  best fix: ${a.fixVersion} (still has known vulnerabilities)", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        a.fixVersion != null -> append("  fix: ${a.fixVersion}", FIX_ATTRIBUTES)
                        a.saferOlderVersion != null -> append("  no newer fix; safe older: ${a.saferOlderVersion}", FIX_ATTRIBUTES)
                        else -> append("  no fix yet", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
                is AdvisoryItem -> {
                    val v = data.vulnerability
                    icon = AllIcons.Nodes.Annotationtype
                    append(v.cve ?: v.id, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (v.cve != null && v.cve != v.id) append("  ${v.id}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    append("  ${v.severity.displayName}" + (v.score?.let { " $it" } ?: ""), attributes(v.severity))
                    impact(data.artifact, v)?.let { append("  ${impactLabel(it.impact)}", impactAttributes(it.impact)) }
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
        private val NOT_AFFECTED_COLOR: Color = JBColor(0x3C8C40, 0x6AAB73)
        private const val CHECK_IMPACT_KEY = "mavendeps.security.checkCodeImpact"

        fun impactLabel(i: Impact) = when (i) {
            Impact.AFFECTED -> "Impacts code"
            Impact.NOT_AFFECTED -> "Does not impact code"
            Impact.UNKNOWN -> "Impact on code unknown"
        }

        fun impactColor(i: Impact): Color = when (i) {
            Impact.AFFECTED -> CRITICAL_COLOR
            Impact.NOT_AFFECTED -> NOT_AFFECTED_COLOR
            Impact.UNKNOWN -> LOW_COLOR
        }

        private fun impactAttributes(i: Impact) = SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, impactColor(i))

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
