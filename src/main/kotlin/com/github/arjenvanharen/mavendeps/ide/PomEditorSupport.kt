package com.github.arjenvanharen.mavendeps.ide

import com.github.arjenvanharen.mavendeps.core.Pos
import com.github.arjenvanharen.mavendeps.core.Problem
import com.github.arjenvanharen.mavendeps.core.QuickFix
import com.github.arjenvanharen.mavendeps.core.Severity
import com.github.arjenvanharen.mavendeps.core.VersionHints
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Finds the analysed POM behind an editor file. The analysis reads files from disk, so positions only match the
 * document while it has no unsaved changes and the analysis has seen the saved file; until then, nothing is shown.
 */
private fun analysedPom(file: PsiFile): Path? {
    if (file.name != "pom.xml") return null
    val vf = file.virtualFile ?: return null
    if (!vf.isInLocalFileSystem || FileDocumentManager.getInstance().isFileModified(vf)) return null
    val path = vf.toNioPath().toAbsolutePath().normalize()
    val entry = MavenAnalysisService.getInstance(file.project).lastResult?.poms?.get(path)?.entry ?: return null
    val current = try {
        Files.readAttributes(path, BasicFileAttributes::class.java)
    } catch (_: IOException) {
        return null
    }
    // Saved, but not analysed yet (debounce or analysis still running): the positions would be off.
    if (current.size() != entry.size || current.lastModifiedTime().toMillis() != entry.lastModified) return null
    return path
}

/** The document offset of an analysis position, or null when it is outside the document. */
private fun Document.offsetOf(pos: Pos): Int? {
    if (pos.line < 1 || pos.line > lineCount) return null
    val start = getLineStartOffset(pos.line - 1)
    return minOf(start + maxOf(pos.column - 1, 0), getLineEndOffset(pos.line - 1))
}

/** Shows the analysis problems of a `pom.xml` in its editor, with the quick fixes on Alt+Enter. */
class PomProblemAnnotator : ExternalAnnotator<PomProblemAnnotator.Info, PomProblemAnnotator.Info>(), DumbAware {
    class Info(val path: Path, val problems: List<Problem>)

    override fun collectInformation(file: PsiFile): Info? = collect(file)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Info? = collect(file)

    private fun collect(file: PsiFile): Info? {
        val path = analysedPom(file) ?: return null
        val problems = MavenAnalysisService.getInstance(file.project).lastResult?.poms?.get(path)?.problems
        return problems?.takeIf { it.isNotEmpty() }?.let { Info(path, it) }
    }

    override fun doAnnotate(info: Info): Info = info

    override fun apply(file: PsiFile, info: Info, holder: AnnotationHolder) {
        val doc = file.viewProvider.document ?: return
        val text = doc.charsSequence
        for (p in info.problems) {
            val severity = when (p.severity) {
                Severity.ERROR -> HighlightSeverity.ERROR
                Severity.WARNING -> HighlightSeverity.WARNING
                Severity.INFO -> HighlightSeverity.WEAK_WARNING
            }
            val builder = holder.newAnnotation(severity, p.message).tooltip("${p.message} [Maven Analyzer · ${p.category.displayName}]")
            val end = doc.offsetOf(p.pos)
            if (end == null) {
                builder.fileLevel()
            } else {
                // The position is the end of the element's start tag: highlight the start tag.
                val start = (end - 1 downTo maxOf(doc.getLineStartOffset(doc.getLineNumber(end)), 0)).firstOrNull { text[it] == '<' }
                builder.range(if (start != null && start < end) TextRange(start, end) else TextRange(end, minOf(end + 1, doc.textLength)))
            }
            p.fixes.forEach { builder.withFix(ApplyQuickFix(info.path, it)) }
            builder.create()
        }
    }
}

private class ApplyQuickFix(private val path: Path, private val fix: QuickFix) : IntentionAction {
    override fun getText() = fix.title

    override fun getFamilyName() = "Maven Analyzer quick fix"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = true

    // FixRunner runs its own undoable write command, may ask for input and saves the files it changed.
    override fun startInWriteAction() = false

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) = FixRunner.apply(project, path, fix)

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY
}

/** Shows the effective version of managed and `${property}` versions, and newer versions found in the local repository. */
class VersionInlayProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (file.name != "pom.xml") return null
        return object : OwnBypassCollector {
            override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
                val path = analysedPom(file) ?: return
                val service = MavenAnalysisService.getInstance(file.project)
                val hints = service.versionHints(path) ?: return
                val doc = editor.document
                val text = doc.charsSequence.toString()
                for ((h, label) in hints) {
                    val start = doc.offsetOf(h.dependency.pos) ?: continue
                    val tooltip = buildString {
                        append("${h.groupId}:${h.artifactId}")
                        h.version?.let { append(" resolves to $it") }
                        if (h.source == "managed") append(" through dependencyManagement") else if (h.source != "literal") append(" via ${h.source}")
                        h.newer?.let { append(". Newer in the local repository: $it (use Update Version… in the Maven Analyzer)") }
                    }
                    sink.addPresentation(InlineInlayPosition(VersionHints.anchor(text, start), true), emptyList(), tooltip, HintFormat.default) {
                        text(label)
                    }
                }
            }
        }
    }
}
