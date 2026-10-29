package com.github.arjenvanharen.mavendeps.ide.ui

import com.github.arjenvanharen.mavendeps.core.AnalysisResult
import com.github.arjenvanharen.mavendeps.core.EdgeKind
import com.github.arjenvanharen.mavendeps.core.GraphEdge
import com.github.arjenvanharen.mavendeps.core.LayeredLayout
import com.github.arjenvanharen.mavendeps.core.ModuleGraph
import com.github.arjenvanharen.mavendeps.core.Severity
import com.github.arjenvanharen.mavendeps.ide.MavenAnalysisListener
import com.github.arjenvanharen.mavendeps.ide.MavenAnalysisService
import com.github.arjenvanharen.mavendeps.ide.Navigation
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.CubicCurve2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private class GraphData(val result: AnalysisResult, val graph: ModuleGraph)

/**
 * The workspace modules as a graph in build order: whatever a module needs (its dependencies and its parent) is
 * drawn below it. Hovering a module highlights its connections, Focus shows only what it needs and what needs it.
 */
internal class GraphPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
    private val service get() = MavenAnalysisService.getInstance(project)
    private val canvas = Canvas()
    private val filter = SearchTextField(false)
    private val status = JBLabel()
    private val progress = InlineProgress(project, parent)

    private var data: GraphData? = null
    private var stale = true
    private var loading = false
    private val shownKinds = mutableSetOf(EdgeKind.DEPENDENCY, EdgeKind.PARENT)
    private var hideTestEdges = false
    private var focus = false

    init {
        filter.textEditor.emptyText.text = "Highlight modules, e.g. core"
        filter.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = canvas.repaint()
        })
        val top = JPanel(BorderLayout())
        top.add(progress, BorderLayout.NORTH)
        top.add(filter, BorderLayout.CENTER)
        val content = JPanel(BorderLayout())
        content.add(top, BorderLayout.NORTH)
        content.add(canvas, BorderLayout.CENTER)
        status.border = JBUI.Borders.empty(2, 6)
        content.add(status, BorderLayout.SOUTH)
        setContent(content)
        toolbar = createToolbar()
        PopupHandler.installPopupMenu(canvas, contextActions(), "MavenDepsGraphPopup")
        status.text = "Drag to pan, wheel to zoom, drag a module to move it, double-click to open its POM."

        project.messageBus.connect(parent).subscribe(
            MavenAnalysisListener.TOPIC,
            MavenAnalysisListener {
                ApplicationManager.getApplication().invokeLater({
                    stale = true
                    if (isShowing) load()
                }, project.disposed)
            },
        )
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing && stale) load()
        }
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(toggle("Show Parents", "Draw <parent> inheritance edges", AllIcons.Hierarchy.Supertypes, { EdgeKind.PARENT in shownKinds }) { kind(EdgeKind.PARENT, it) })
            add(toggle("Show Aggregation", "Draw <modules> aggregation edges", AllIcons.Nodes.ModuleGroup, { EdgeKind.MODULE in shownKinds }) { kind(EdgeKind.MODULE, it) })
            add(toggle("Hide Test-Scope Dependencies", "Hide dependencies with test or provided scope", AllIcons.Nodes.Test, { hideTestEdges }) { hideTestEdges = it })
            add(toggle("Focus on Selection", "Show only what the selected module needs and what needs it", AllIcons.General.Locate, { focus }) {
                focus = it
                canvas.relayout(fit = true)
            })
            addSeparator()
            add(button("Zoom In", AllIcons.General.ZoomIn) { canvas.zoom(1.25) })
            add(button("Zoom Out", AllIcons.General.ZoomOut) { canvas.zoom(0.8) })
            add(button("Fit to Window", AllIcons.General.FitContent) { canvas.fit() })
            add(button("Reset Layout", AllIcons.Actions.Refresh) {
                stale = true
                load()
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("MavenDepsGraph", group, true)
        toolbar.targetComponent = canvas
        return toolbar.component
    }

    private fun kind(kind: EdgeKind, on: Boolean) {
        if (on) shownKinds.add(kind) else shownKinds.remove(kind)
    }

    private fun toggle(text: String, description: String, icon: javax.swing.Icon, get: () -> Boolean, set: (Boolean) -> Unit): AnAction =
        object : ToggleAction(text, description, icon), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = get()
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                set(state)
                canvas.repaint()
            }
        }

    private fun button(text: String, icon: javax.swing.Icon, run: () -> Unit): AnAction = object : DumbAwareAction(text, null, icon) {
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    private fun contextActions() = DefaultActionGroup(
        selectionAction("Open POM", AllIcons.Actions.MenuOpen) { open(it) },
        selectionAction("Focus on This Module", AllIcons.General.Locate) {
            focus = true
            canvas.relayout(fit = true)
        },
        selectionAction("Copy Coordinates", AllIcons.Actions.Copy) { p ->
            val gav = data?.result?.poms?.get(p)?.model?.effectiveGav ?: return@selectionAction
            CopyPasteManager.getInstance().setContents(StringSelection("${gav.groupId}:${gav.artifactId}:${gav.version}"))
        },
    )

    private fun selectionAction(text: String, icon: javax.swing.Icon, run: (Path) -> Unit): AnAction = object : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = canvas.selected != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            canvas.selected?.let(run)
        }
    }

    private fun load() {
        val result = service.lastResult ?: return
        if (loading) return
        loading = true
        stale = false
        service.runQuery(
            "Building Maven module graph",
            { progress ->
                progress.report(null, "Building module graph")
                // One snapshot for both: an analysis finishing in between must not mix two workspaces.
                val resolver = service.resolver()
                GraphData(result, ModuleGraph.build(resolver.ws, resolver, progress))
            },
            onProgress = { progress.job(it) },
            onFailure = {
                loading = false
                status.text = "Building the graph failed: ${it.message}"
            },
        ) {
            loading = false
            data = it
            canvas.relayout(fit = true, keepMoved = false)
            describe(canvas.selected)
            if (stale) load()
        }
    }

    private fun open(path: Path) {
        val line = data?.result?.poms?.get(path)?.model?.gavPos?.line?.takeIf { it > 0 } ?: 1
        Navigation.open(project, path, line)
    }

    private fun name(path: Path): String = data?.result?.poms?.get(path)?.model?.effectiveGav?.artifactId ?: path.parent.fileName.toString()

    private fun describe(path: Path?) {
        val d = data ?: return
        if (path == null) {
            val deps = d.graph.edges.count { it.kind == EdgeKind.DEPENDENCY }
            status.text = "${d.graph.nodes.size} modules, $deps module dependencies. Lower modules are built first."
            return
        }
        fun list(edges: List<GraphEdge>, end: (GraphEdge) -> Path) =
            edges.map { e -> name(end(e)) + (e.scope?.takeIf { it != "compile" }?.let { " ($it)" } ?: "") }.distinct().joinToString(", ").ifEmpty { "nothing" }
        val g = d.graph
        val r = d.result.poms[path]
        val gav = r?.model?.effectiveGav
        val errors = r?.problems?.count { it.severity == Severity.ERROR } ?: 0
        val warnings = r?.problems?.count { it.severity == Severity.WARNING } ?: 0
        val parent = g.outgoing(path, setOf(EdgeKind.PARENT)).firstOrNull()?.let { name(it.to) }
        status.text = buildString {
            append("<html><b>${StringUtil.escapeXmlEntities(name(path))}</b> ${StringUtil.escapeXmlEntities("${gav?.groupId}:${gav?.version}")}")
            append(" · needs: ${StringUtil.escapeXmlEntities(list(g.outgoing(path, setOf(EdgeKind.DEPENDENCY))) { it.to })}")
            append(" · used by: ${StringUtil.escapeXmlEntities(list(g.incoming(path, setOf(EdgeKind.DEPENDENCY))) { it.from })}")
            parent?.let { append(" · parent: ${StringUtil.escapeXmlEntities(it)}") }
            if (errors + warnings > 0) append(" · <font color='#${ColorUtil.toHex(ERROR_COLOR)}'>$errors errors, $warnings warnings</font>")
            append("</html>")
        }
    }

    private inner class Canvas : JComponent() {
        private val boxes = LinkedHashMap<Path, Rectangle2D.Double>()
        private val moved = HashSet<Path>()
        private var scale = 1.0
        private var offset = Point2D.Double(0.0, 0.0)
        private var fitPending = false
        private var hovered: Path? = null
        var selected: Path? = null
            private set
        private var dragStart: Point? = null
        private var dragNode: Path? = null

        init {
            isOpaque = true
            background = JBColor.PanelBackground
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    dragStart = e.point
                    dragNode = nodeAt(e.point)
                    if (dragNode != selected && (dragNode != null || !e.isPopupTrigger)) select(dragNode)
                }

                override fun mouseReleased(e: MouseEvent) {
                    dragStart = null
                    dragNode = null
                    cursor = Cursor.getDefaultCursor()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) nodeAt(e.point)?.let { open(it) }
                }

                override fun mouseDragged(e: MouseEvent) {
                    val start = dragStart ?: return
                    val dx = (e.x - start.x).toDouble()
                    val dy = (e.y - start.y).toDouble()
                    val node = dragNode
                    if (node != null) {
                        boxes[node]?.let { it.x += dx / scale; it.y += dy / scale }
                        moved.add(node)
                        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    } else {
                        offset.x += dx
                        offset.y += dy
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    }
                    dragStart = e.point
                    repaint()
                }

                override fun mouseMoved(e: MouseEvent) {
                    val node = nodeAt(e.point)
                    if (node != hovered) {
                        hovered = node
                        toolTipText = node?.let { tooltip(it) }
                        repaint()
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    if (hovered != null) {
                        hovered = null
                        repaint()
                    }
                }

                override fun mouseWheelMoved(e: MouseWheelEvent) = zoom(1.1.pow(-e.preciseWheelRotation), e.point)
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
            addMouseWheelListener(mouse)
        }

        override fun getPreferredSize() = Dimension(JBUI.scale(400), JBUI.scale(300))

        private fun select(path: Path?) {
            selected = path
            describe(path)
            if (focus) relayout(fit = true) else repaint()
        }

        private fun tooltip(path: Path): String {
            val r = data?.result?.poms?.get(path) ?: return name(path)
            val problems = r.problems.filter { it.severity != Severity.INFO }.take(5).joinToString("") { "<br>• ${StringUtil.escapeXmlEntities(it.message)}" }
            return "<html><b>${StringUtil.escapeXmlEntities(name(path))}</b><br>${StringUtil.escapeXmlEntities(path.toString())}$problems</html>"
        }

        /** What the selected module needs (transitively) and what needs it, plus the module itself. */
        private fun neighbourhood(start: Path, graph: ModuleGraph): Set<Path> {
            val kinds = setOf(EdgeKind.DEPENDENCY, EdgeKind.PARENT)
            val out = linkedSetOf(start)
            for (down in listOf(true, false)) {
                val queue = ArrayDeque(listOf(start))
                val seen = hashSetOf(start)
                while (queue.isNotEmpty()) {
                    val n = queue.removeFirst()
                    val next = if (down) graph.outgoing(n, kinds).map { it.to } else graph.incoming(n, kinds).map { it.from }
                    next.filter { seen.add(it) }.forEach { queue.add(it); out.add(it) }
                }
            }
            return out
        }

        fun relayout(fit: Boolean, keepMoved: Boolean = true) {
            val d = data ?: return
            val focused = selected?.takeIf { focus && it in d.graph.nodes }
            val visible = focused?.let { neighbourhood(it, d.graph) }?.let { set -> d.graph.nodes.filter { it in set } } ?: d.graph.nodes
            val previous = if (keepMoved) boxes.filterKeys { it in moved }.mapValues { it.value.clone() as Rectangle2D.Double } else emptyMap()
            if (!keepMoved) moved.clear()
            boxes.clear()
            // Build order: a module sits above everything it needs, its parent included.
            val layers = LayeredLayout.layers(visible, d.graph.edges.filter { it.kind != EdgeKind.MODULE }.map { it.from to it.to })
                .reversed().flatMap { it.chunked(MAX_PER_ROW) }
            val fm = getFontMetrics(boldFont())
            val widths = layers.map { row -> row.map { min(max(fm.stringWidth(name(it)) + 40, 120), 260).toDouble() } }
            val rowWidths = widths.map { it.sum() + GAP * (it.size - 1) }
            val maxWidth = rowWidths.maxOrNull() ?: 0.0
            var y = 0.0
            for ((i, row) in layers.withIndex()) {
                var x = (maxWidth - rowWidths[i]) / 2
                for ((j, node) in row.withIndex()) {
                    boxes[node] = previous[node] ?: Rectangle2D.Double(x, y, widths[i][j], NODE_HEIGHT)
                    x += widths[i][j] + GAP
                }
                y += NODE_HEIGHT + ROW_GAP
            }
            if (fit) fitPending = true
            repaint()
        }

        fun zoom(factor: Double, around: Point = Point(width / 2, height / 2)) {
            val newScale = (scale * factor).coerceIn(0.15, 3.0)
            val f = newScale / scale
            offset.x = around.x - (around.x - offset.x) * f
            offset.y = around.y - (around.y - offset.y) * f
            scale = newScale
            repaint()
        }

        fun fit() {
            if (boxes.isEmpty() || width <= 0) return
            val bounds = boxes.values.reduce { a, b -> a.createUnion(b) as Rectangle2D.Double }
            val margin = 30.0
            scale = min(min((width - 2 * margin) / bounds.width, (height - 2 * margin - LEGEND_HEIGHT) / bounds.height), 1.4).coerceAtLeast(0.15)
            offset.x = (width - bounds.width * scale) / 2 - bounds.x * scale
            offset.y = margin - bounds.y * scale
            repaint()
        }

        private fun nodeAt(p: Point): Path? {
            val wx = (p.x - offset.x) / scale
            val wy = (p.y - offset.y) / scale
            return boxes.entries.lastOrNull { it.value.contains(wx, wy) }?.key
        }

        private fun boldFont(): Font = JBUI.Fonts.label().deriveFont(Font.BOLD)

        private fun visibleEdges(): List<GraphEdge> = data?.graph?.edges.orEmpty().filter { e ->
            e.kind in shownKinds || e.kind == EdgeKind.DEPENDENCY
        }.filter { e ->
            e.from in boxes && e.to in boxes && !(hideTestEdges && e.kind == EdgeKind.DEPENDENCY && e.scope in setOf("test", "provided"))
        }

        override fun paintComponent(g0: Graphics) {
            g0.color = background
            g0.fillRect(0, 0, width, height)
            val d = data
            if (d == null || boxes.isEmpty()) {
                g0.color = UIUtil.getContextHelpForeground()
                val text = if (loading || d == null) "Building module graph…" else "No modules"
                g0.drawString(text, (width - g0.fontMetrics.stringWidth(text)) / 2, height / 2)
                return
            }
            if (fitPending && width > 0) {
                fitPending = false
                fit()
            }
            val g = g0.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.translate(offset.x, offset.y)
            g.scale(scale, scale)

            val edges = visibleEdges()
            val active = hovered ?: selected
            val linked = active?.let { a -> edges.filter { it.from == a || it.to == a }.flatMapTo(hashSetOf(a)) { listOf(it.from, it.to) } }
            val query = filter.text.trim()
            for (e in edges) {
                val highlighted = active != null && (e.from == active || e.to == active)
                g.composite = AlphaComposite.SrcOver.derive(if (active == null || highlighted) 1f else 0.12f)
                drawEdge(g, e, highlighted)
            }
            for ((path, box) in boxes) {
                val matches = query.isNotEmpty() && StringUtil.containsIgnoreCase(name(path), query)
                val dim = (linked != null && path !in linked) || (query.isNotEmpty() && !matches)
                g.composite = AlphaComposite.SrcOver.derive(if (dim) 0.3f else 1f)
                drawNode(g, d, path, box, matches)
            }
            g.dispose()
            paintLegend(g0 as Graphics2D)
        }

        private fun drawNode(g: Graphics2D, d: GraphData, path: Path, box: Rectangle2D.Double, matches: Boolean) {
            val r = d.result.poms[path]
            val errors = r?.problems?.count { it.severity == Severity.ERROR } ?: 0
            val warnings = r?.problems?.count { it.severity == Severity.WARNING } ?: 0
            val shape = RoundRectangle2D.Double(box.x, box.y, box.width, box.height, 12.0, 12.0)
            g.color = if (r?.model?.effectivePackaging == "pom") POM_FILL else NODE_FILL
            g.fill(shape)
            val selectedNode = path == selected
            g.color = when {
                selectedNode || matches -> ACCENT
                errors > 0 -> ERROR_COLOR
                warnings > 0 -> WARNING_COLOR
                else -> NODE_BORDER
            }
            g.stroke = BasicStroke(if (selectedNode || matches || errors + warnings > 0) 2.2f else 1f)
            g.draw(shape)

            val bold = boldFont()
            val small = JBUI.Fonts.smallFont()
            g.font = bold
            g.color = UIUtil.getLabelForeground()
            val title = fit(name(path), bold, box.width - 16)
            g.drawString(title, (box.x + (box.width - g.getFontMetrics(bold).stringWidth(title)) / 2).toFloat(), (box.y + 19).toFloat())
            g.font = small
            g.color = UIUtil.getContextHelpForeground()
            val gav = r?.model?.effectiveGav
            val sub = fit(listOfNotNull(gav?.version, r?.model?.effectivePackaging).joinToString(" · "), small, box.width - 16)
            g.drawString(sub, (box.x + (box.width - g.getFontMetrics(small).stringWidth(sub)) / 2).toFloat(), (box.y + 34).toFloat())

            // Problem badge in the top right corner.
            val count = errors + warnings
            if (count > 0) {
                val text = count.toString()
                val w = max(16, g.getFontMetrics(small).stringWidth(text) + 8).toDouble()
                val badge = RoundRectangle2D.Double(box.maxX - w + 4, box.y - 7, w, 15.0, 15.0, 15.0)
                g.color = if (errors > 0) ERROR_COLOR else WARNING_COLOR
                g.fill(badge)
                g.color = Color.WHITE
                g.drawString(text, (badge.x + (w - g.getFontMetrics(small).stringWidth(text)) / 2).toFloat(), (badge.y + 11.5).toFloat())
            }
        }

        private fun fit(text: String, font: Font, width: Double): String {
            val fm = getFontMetrics(font)
            if (fm.stringWidth(text) <= width) return text
            var t = text
            while (t.length > 1 && fm.stringWidth("$t…") > width) t = t.dropLast(1)
            return "$t…"
        }

        private fun drawEdge(g: Graphics2D, e: GraphEdge, highlighted: Boolean) {
            val a = boxes.getValue(e.from)
            val b = boxes.getValue(e.to)
            // Parallel edges between the same two modules (parent and aggregation) are spread out sideways.
            val shift = when (e.kind) {
                EdgeKind.DEPENDENCY -> 0.0
                EdgeKind.PARENT -> 14.0
                EdgeKind.MODULE -> -14.0
            }
            val down = b.centerY > a.centerY + 1
            val up = b.centerY < a.centerY - 1
            val start: Point2D.Double
            val end: Point2D.Double
            val c1: Point2D.Double
            val c2: Point2D.Double
            when {
                down || up -> {
                    start = Point2D.Double(a.centerX + shift, if (down) a.maxY else a.y)
                    end = Point2D.Double(b.centerX + shift, if (down) b.y else b.maxY)
                    val bend = (end.y - start.y) * 0.5
                    c1 = Point2D.Double(start.x, start.y + bend)
                    c2 = Point2D.Double(end.x, end.y - bend)
                }
                else -> {
                    // Same row: arc over the top.
                    start = Point2D.Double(a.centerX + shift, a.y)
                    end = Point2D.Double(b.centerX + shift, b.y)
                    c1 = Point2D.Double(start.x, start.y - 45)
                    c2 = Point2D.Double(end.x, end.y - 45)
                }
            }
            val test = e.scope in setOf("test", "provided")
            g.color = if (highlighted) ACCENT else when (e.kind) {
                EdgeKind.DEPENDENCY -> if (e.scope == "import") IMPORT_EDGE else DEP_EDGE
                EdgeKind.PARENT -> PARENT_EDGE
                EdgeKind.MODULE -> MODULE_EDGE
            }
            val width = if (highlighted) 2.2f else 1.3f
            g.stroke = when {
                e.kind == EdgeKind.MODULE -> BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(2f, 4f), 0f)
                e.kind == EdgeKind.PARENT || test -> BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(6f, 4f), 0f)
                else -> BasicStroke(width)
            }
            g.draw(CubicCurve2D.Double(start.x, start.y, c1.x, c1.y, c2.x, c2.y, end.x, end.y))
            g.stroke = BasicStroke(width)
            when (e.kind) {
                EdgeKind.DEPENDENCY -> head(g, c2, end, filled = true, diamond = false)
                EdgeKind.PARENT -> head(g, c2, end, filled = false, diamond = false)
                // UML aggregation: a hollow diamond at the aggregating POM.
                EdgeKind.MODULE -> head(g, c1, start, filled = false, diamond = true)
            }
        }

        private fun head(g: Graphics2D, from: Point2D.Double, tip: Point2D.Double, filled: Boolean, diamond: Boolean) {
            val angle = atan2(tip.y - from.y, tip.x - from.x)
            val size = 9.0
            fun at(dist: Double, side: Double) = Point2D.Double(
                tip.x - dist * cos(angle) - side * sin(angle),
                tip.y - dist * sin(angle) + side * cos(angle),
            )
            val points = if (diamond) listOf(at(0.0, 0.0), at(size, size / 2), at(2 * size, 0.0), at(size, -size / 2))
            else listOf(at(0.0, 0.0), at(size, size / 2), at(size, -size / 2))
            val shape = Path2D.Double().apply {
                moveTo(points[0].x, points[0].y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                closePath()
            }
            if (filled) g.fill(shape) else {
                val c = g.color
                g.color = background
                g.fill(shape)
                g.color = c
                g.draw(shape)
            }
        }

        private fun paintLegend(g: Graphics2D) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.font = JBUI.Fonts.smallFont()
            val fm = g.fontMetrics
            var x = JBUI.scale(10).toDouble()
            val y = height - JBUI.scale(12).toDouble()
            val items = listOf(
                Triple("depends on", DEP_EDGE, BasicStroke(1.3f)),
                Triple("test / provided", DEP_EDGE, BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(6f, 4f), 0f)),
                Triple("BOM import", IMPORT_EDGE, BasicStroke(1.3f)),
                Triple("inherits from", PARENT_EDGE, BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(6f, 4f), 0f)),
                Triple("aggregates", MODULE_EDGE, BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(2f, 4f), 0f)),
            )
            for ((label, color, stroke) in items) {
                g.color = color
                g.stroke = stroke
                g.draw(java.awt.geom.Line2D.Double(x, y - 4, x + 22, y - 4))
                g.color = UIUtil.getContextHelpForeground()
                g.drawString(label, (x + 27).toFloat(), y.toFloat())
                x += 27 + fm.stringWidth(label) + JBUI.scale(14)
            }
        }
    }

    private companion object {
        const val NODE_HEIGHT = 44.0
        const val GAP = 28.0
        const val ROW_GAP = 70.0
        const val MAX_PER_ROW = 8
        const val LEGEND_HEIGHT = 24.0
        val NODE_FILL: Color = JBColor(0xFFFFFF, 0x3C3F41)
        val POM_FILL: Color = JBColor(0xEDF3FC, 0x2E3645)
        val NODE_BORDER: Color = JBColor(0xB4B9C0, 0x5E6266)
        val ERROR_COLOR: Color = JBColor(0xE55757, 0xDB5C5C)
        val WARNING_COLOR: Color = JBColor(0xE6A23C, 0xD9A343)
        val ACCENT: Color get() = JBUI.CurrentTheme.Focus.focusColor()
        val DEP_EDGE: Color = JBColor(0x6F7680, 0x9AA0A8)
        val PARENT_EDGE: Color = JBColor(0x3B7DDD, 0x5C9CE6)
        val MODULE_EDGE: Color = JBColor(0xA8AEB5, 0x6A6F75)
        val IMPORT_EDGE: Color = JBColor(0x9B59B6, 0xB07CC6)
    }
}
