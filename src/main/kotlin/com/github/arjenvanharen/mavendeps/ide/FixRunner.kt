package com.github.arjenvanharen.mavendeps.ide

import com.github.arjenvanharen.mavendeps.core.CompositeFix
import com.github.arjenvanharen.mavendeps.core.FixApplier
import com.github.arjenvanharen.mavendeps.core.FixCheck
import com.github.arjenvanharen.mavendeps.core.FixException
import com.github.arjenvanharen.mavendeps.core.FixVerifier
import com.github.arjenvanharen.mavendeps.core.TextEdit
import com.intellij.openapi.util.text.StringUtil
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
 *
 * Before anything is written, the change is checked in the background with [FixVerifier]: when it would add problems
 * (anything the Problems tab shows) or make a library resolve to different versions in different modules, the user
 * sees them and decides whether to apply anyway.
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

        // Fixes find their elements by the positions of the last analysis. Unsaved edits, or a file changed since, could
        // put the change at another element with the same position (e.g. the next <dependency>): analyse first.
        val service = MavenAnalysisService.getInstance(project)
        val result = service.lastResult
        val outdated = docs.filter { (path, doc) -> fdm.isDocumentUnsaved(doc) || result?.isCurrent(path) != true }.keys
        if (outdated.isNotEmpty()) {
            outdated.forEach { fdm.saveDocument(docs.getValue(it)) }
            service.requestRefresh(RefreshMode.INCREMENTAL, outdated)
            return Messages.showInfoMessage(
                project,
                "${outdated.joinToString { it.parent.fileName.toString() + "/" + it.fileName }} changed since it was analysed. " +
                    "It was saved and is being analysed again; apply the change once the analysis has finished.",
                title,
            )
        }

        val edits = try {
            FixApplier.edits(combined, fixes.first().first, input) { docs.getValue(it).text }
        } catch (e: FixException) {
            MavenAnalysisService.getInstance(project).requestRefresh(RefreshMode.RESCAN)
            return Messages.showErrorDialog(project, e.message, title)
        }
        val texts = edits.mapValues { (path, list) -> FixApplier.applyTo(docs.getValue(path).text, list) }
        val stamps = docs.mapValues { it.value.modificationStamp }
        val ws = service.workspace()
        service.runQuery(
            "Checking the change for new problems",
            { p -> FixVerifier.check(ws, texts, service.repository, checkCanceled = p) },
            onFailure = { e ->
                if (e is QueryCancelledException) return@runQuery
                val ok = Messages.showYesNoDialog(
                    project, "The change could not be checked for new problems (${e.message}). Apply it anyway?", title,
                    "Apply", "Cancel", Messages.getWarningIcon(),
                )
                if (ok == Messages.YES) write(project, title, docs, edits, stamps)
            },
        ) { check ->
            if (!check.clean && !confirm(project, title, check)) return@runQuery
            write(project, title, docs, edits, stamps)
        }
    }

    private fun confirm(project: Project, title: String, check: FixCheck): Boolean {
        val esc = { s: String -> StringUtil.escapeXmlEntities(s) }
        val text = buildString {
            append("<html>This change would introduce new issues:<br><br>")
            if (check.newProblems.isNotEmpty()) {
                append("<b>${check.newProblems.size} new problem${if (check.newProblems.size == 1) "" else "s"}</b> in the Problems tab:<br>")
                check.newProblems.take(MAX_LISTED).forEach { (path, p) ->
                    append("&nbsp;&nbsp;• ${p.severity.name.lowercase()} in ${esc(path.parent.fileName.toString())}/pom.xml: ${esc(p.message)}<br>")
                }
                if (check.newProblems.size > MAX_LISTED) append("&nbsp;&nbsp;… and ${check.newProblems.size - MAX_LISTED} more<br>")
            }
            if (check.newConflicts.isNotEmpty()) {
                append("<br><b>Libraries that would resolve to different versions in different modules:</b><br>")
                check.newConflicts.take(MAX_LISTED).forEach { append("&nbsp;&nbsp;• ${esc(it)}<br>") }
                if (check.newConflicts.size > MAX_LISTED) append("&nbsp;&nbsp;… and ${check.newConflicts.size - MAX_LISTED} more<br>")
            }
            if (check.resolvedProblems > 0) append("<br>It also resolves ${check.resolvedProblems} existing problem${if (check.resolvedProblems == 1) "" else "s"}.")
            append("<br>Apply anyway?</html>")
        }
        return Messages.showYesNoDialog(project, text, title, "Apply Anyway", "Cancel", Messages.getWarningIcon()) == Messages.YES
    }

    private fun write(project: Project, title: String, docs: Map<Path, Document>, edits: Map<Path, List<TextEdit>>, stamps: Map<Path, Long>) {
        // The check ran in the background: the edits are only valid for the text they were computed from.
        if (docs.any { (path, doc) -> doc.modificationStamp != stamps[path] }) {
            return Messages.showInfoMessage(project, "A POM changed while the change was being checked; apply it again.", title)
        }
        val fdm = FileDocumentManager.getInstance()
        WriteCommandAction.writeCommandAction(project).withName(title).run<RuntimeException> {
            for ((path, list) in edits) {
                val doc = docs.getValue(path)
                FixApplier.inApplicationOrder(list).forEach { doc.replaceString(it.start, it.end, it.replacement) }
            }
        }
        edits.keys.forEach { fdm.saveDocument(docs.getValue(it)) }
    }

    private const val MAX_LISTED = 15
}
