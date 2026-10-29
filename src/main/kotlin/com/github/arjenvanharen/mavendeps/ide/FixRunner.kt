package com.github.arjenvanharen.mavendeps.ide

import com.github.arjenvanharen.mavendeps.core.CompositeFix
import com.github.arjenvanharen.mavendeps.core.FixApplier
import com.github.arjenvanharen.mavendeps.core.FixException
import com.github.arjenvanharen.mavendeps.core.QuickFix
import com.github.arjenvanharen.mavendeps.core.RefreshMode
import com.github.arjenvanharen.mavendeps.core.anchored
import com.github.arjenvanharen.mavendeps.core.prompt
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import java.nio.file.Path

/**
 * Applies [QuickFix]es to editor documents as one undoable command and saves them, which triggers an incremental
 * re-analysis through [PomFileListener]. Must be called on the EDT.
 */
object FixRunner {
    fun apply(project: Project, owner: Path, fix: QuickFix) = applyAll(project, fix.title, listOf(owner to fix))

    fun applyAll(project: Project, title: String, fixes: List<Pair<Path, QuickFix>>) {
        if (fixes.isEmpty()) return
        val combined = CompositeFix(title, fixes.map { (owner, fix) -> fix.anchored(owner) })
        val input = combined.prompt?.let { Messages.showInputDialog(project, it, title, null)?.trim() ?: return }

        val fdm = FileDocumentManager.getInstance()
        val files = FixApplier.files(combined, fixes.first().first).associateWith {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)
        }
        val missing = files.filterValues { it == null }.keys
        if (missing.isNotEmpty()) return Messages.showErrorDialog(project, "File not found: ${missing.first()}", title)
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(files.values.filterNotNull()).hasReadonlyFiles()) return
        val docs: Map<Path, Document> = files.mapValues { fdm.getDocument(it.value!!) ?: return Messages.showErrorDialog(project, "Cannot open ${it.key}", title) }

        val edits = try {
            FixApplier.edits(combined, fixes.first().first, input) { docs.getValue(it).text }
        } catch (e: FixException) {
            MavenAnalysisService.getInstance(project).requestRefresh(RefreshMode.RESCAN)
            return Messages.showErrorDialog(project, e.message, title)
        }
        WriteCommandAction.writeCommandAction(project).withName(title).run<RuntimeException> {
            for ((path, list) in edits) {
                val doc = docs.getValue(path)
                FixApplier.inApplicationOrder(list).forEach { doc.replaceString(it.start, it.end, it.replacement) }
            }
        }
        edits.keys.forEach { fdm.saveDocument(docs.getValue(it)) }
    }
}
