package com.github.arjenvanharen.mavendeps.ide.ui

import com.github.arjenvanharen.mavendeps.ide.MavenAnalysisProgressListener
import com.github.arjenvanharen.mavendeps.ide.ProgressState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JProgressBar

/**
 * A progress bar shown above a tab's content while the POM analysis or a job of the tab runs, and hidden otherwise.
 * The tab's own job wins over the analysis while both run.
 */
internal class InlineProgress(project: Project, parent: Disposable) : JPanel(BorderLayout(JBUI.scale(8), 0)) {
    private val bar = JProgressBar(0, 1000)
    private val label = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private var analysis: ProgressState? = null
    private var job: ProgressState? = null

    init {
        border = JBUI.Borders.empty(3, 6)
        add(label, BorderLayout.WEST)
        add(bar, BorderLayout.CENTER)
        isVisible = false
        project.messageBus.connect(parent).subscribe(
            MavenAnalysisProgressListener.TOPIC,
            MavenAnalysisProgressListener { state ->
                ApplicationManager.getApplication().invokeLater({
                    analysis = state
                    render()
                }, project.disposed)
            },
        )
    }

    /** Shows the progress of this tab's own job; null hides it again. Call on the EDT. */
    fun job(state: ProgressState?) {
        job = state
        render()
    }

    private fun render() {
        val state = job ?: analysis
        isVisible = state != null
        if (state == null) return
        bar.isIndeterminate = state.fraction == null
        state.fraction?.let { bar.value = (it * 1000).toInt() }
        label.text = state.text
    }
}
