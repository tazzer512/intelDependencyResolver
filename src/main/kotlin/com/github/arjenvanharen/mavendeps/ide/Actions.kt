package com.github.arjenvanharen.mavendeps.ide

import com.github.arjenvanharen.mavendeps.core.RefreshMode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

abstract class RefreshActionBase(private val mode: RefreshMode) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        MavenAnalysisService.getInstance(project).requestRefresh(mode)
    }
}

/** Re-checks every POM's size/timestamp and re-parses only what changed. */
class RefreshAction : RefreshActionBase(RefreshMode.RESCAN)

/** Discards the cache and re-parses every POM. */
class FullRefreshAction : RefreshActionBase(RefreshMode.FULL)
