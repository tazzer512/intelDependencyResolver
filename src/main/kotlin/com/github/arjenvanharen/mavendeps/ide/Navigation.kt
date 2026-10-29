package com.github.arjenvanharen.mavendeps.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

/** Opens files in the editor. Finding a file the IDE has not seen yet (e.g. in `~/.m2`) is slow, so it is done off the EDT. */
object Navigation {
    /** Opens [path] at [line] and [column] (1-based); [line] null asks [findLine] for it, given the file's text. */
    fun open(project: Project, path: Path, line: Int? = 1, column: Int = 1, findLine: ((String) -> Int?)? = null) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return@executeOnPooledThread
            val target = line ?: findLine?.let { f -> runCatching { f(Files.readString(path)) }.getOrNull() } ?: 1
            ApplicationManager.getApplication().invokeLater({
                OpenFileDescriptor(project, file, maxOf(target - 1, 0), maxOf(column - 1, 0)).navigate(true)
            }, ModalityState.nonModal(), project.disposed)
        }
    }
}
