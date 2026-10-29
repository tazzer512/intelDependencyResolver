package com.github.arjenvanharen.mavendeps.ide.ui

import com.github.arjenvanharen.mavendeps.core.PlanItem
import com.github.arjenvanharen.mavendeps.ide.FixRunner
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel

/** Lets the user review and deselect planned POM changes before they are applied. */
internal class PlanDialog(project: Project, title: String, private val items: List<PlanItem>, private val header: String) : DialogWrapper(project) {
    private val list = CheckBoxList<PlanItem>()

    init {
        this.title = title
        val base = project.basePath?.let { Path.of(it) }
        for (item in items) {
            val where = base?.takeIf { item.pom.startsWith(it) }?.relativize(item.pom)?.toString() ?: item.pom.toString()
            list.addItem(item, "${item.kind.displayName} — $where: ${item.description}", item.selected)
        }
        setOKButtonText("Apply")
        init()
    }

    fun selected(): List<PlanItem> = items.filter { list.isItemSelected(it) }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 6)).apply {
        add(JBLabel(header).apply { setAllowAutoWrapping(true) }, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list).apply { preferredSize = JBUI.size(900, 400) }, BorderLayout.CENTER)
        add(JBLabel("Every change is one undoable command (Edit | Undo). Uncheck what you do not want.").apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }, BorderLayout.SOUTH)
    }

    companion object {
        /** Shows the plan and applies the chosen items; returns how many were applied, or null when cancelled. */
        fun reviewAndApply(project: Project, title: String, header: String, items: List<PlanItem>): Int? {
            val dialog = PlanDialog(project, title, items, header)
            if (!dialog.showAndGet()) return null
            val chosen = dialog.selected()
            FixRunner.applyAll(project, title, chosen.map { it.pom to it.fix })
            return chosen.size
        }
    }
}
