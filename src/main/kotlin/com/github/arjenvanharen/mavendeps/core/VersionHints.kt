package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Path

/** What the editor shows next to a declared dependency: its effective version, where it comes from, and newer local versions. */
data class VersionHint(
    val dependency: Dependency,
    val groupId: String,
    val artifactId: String,
    /** The version after interpolation and dependency management; null when unknown. */
    val version: String?,
    /** `literal`, `managed` or the raw `${...}` expression. */
    val source: String,
    /** The POM whose dependencyManagement supplies the version; null for literal/property versions or management from outside the workspace. */
    val managedBy: Path?,
    /** The newest version in the local repository when it is newer than [version]. */
    val newer: String?,
) {
    /** The inlay text, or null when there is nothing to add to what the POM already says. */
    fun label(ws: Workspace): String? {
        val parts = ArrayList<String>()
        when {
            version == null -> {}
            source == "managed" -> parts += "$version · managed" + (managedBy?.let { " by ${ws.model(it)?.effectiveGav?.artifactId ?: it.parent.fileName}" } ?: "")
            source != "literal" -> parts += "= $version"
        }
        newer?.let { parts += "↑ $it in local repo" }
        return parts.joinToString("  ").ifEmpty { null }
    }
}

object VersionHints {
    fun forPom(ws: Workspace, resolver: DependencyResolver, repo: LocalRepository?, path: Path): List<VersionHint> =
        DependencySearch.declarations(ws, resolver, DependencyQuery("*", "*"), only = path).map { hit ->
            val managedBy = if (hit.versionSource == "managed") resolver.effective(path).managedFor(hit.groupId, hit.artifactId)?.declaredIn else null
            val newer = hit.resolvedVersion?.takeIf { repo != null && hit.dependency.scope != "import" }
                ?.let { repo!!.newerVersions(hit.groupId, hit.artifactId, it).lastOrNull() }
            VersionHint(hit.dependency, hit.groupId, hit.artifactId, hit.resolvedVersion, hit.versionSource, managedBy, newer)
        }

    /**
     * Where to show the hint of a dependency whose start tag ends at [startTagEnd]: after its `</version>`, else after
     * its `</artifactId>`, else after the start tag itself.
     */
    fun anchor(text: String, startTagEnd: Int): Int {
        val end = text.indexOf("</dependency>", startTagEnd).let { if (it < 0) text.length else it }
        val block = text.substring(startTagEnd, end)
        for (tag in listOf("</version>", "</artifactId>")) {
            val i = block.indexOf(tag)
            if (i >= 0) return startTagEnd + i + tag.length
        }
        return startTagEnd
    }
}
