package com.github.arjenvanharen.mavendeps.ide

import com.github.arjenvanharen.mavendeps.core.RefreshMode
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Loads the disk cache and re-validates it against the file system when a project opens. */
class AnalysisStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        MavenAnalysisService.getInstance(project).requestRefresh(RefreshMode.RESCAN)
    }
}
