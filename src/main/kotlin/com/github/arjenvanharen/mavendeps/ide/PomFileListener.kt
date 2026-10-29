package com.github.arjenvanharen.mavendeps.ide

import com.github.arjenvanharen.mavendeps.core.RefreshMode
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** Turns saved/created/deleted/moved POMs (and their directories) into incremental refresh requests. */
class PomFileListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val service = project.serviceIfCreated<MavenAnalysisService>() ?: return
        val changed = LinkedHashSet<Path>()
        for (event in events) {
            if (event.fileSystem !is LocalFileSystem) continue
            for (raw in pathsOf(event)) {
                val path = toPath(raw) ?: continue
                changed += service.pomsAffectedBy(path)
            }
        }
        if (changed.isNotEmpty()) service.requestRefresh(RefreshMode.INCREMENTAL, changed)
    }

    private fun pathsOf(event: VFileEvent): List<String> = when (event) {
        is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
        is VFilePropertyChangeEvent -> if (event.isRename) listOf(event.oldPath, event.newPath) else emptyList()
        else -> listOf(event.path)
    }

    private fun toPath(raw: String): Path? = try {
        Paths.get(raw).toAbsolutePath().normalize()
    } catch (_: InvalidPathException) {
        null
    }
}
