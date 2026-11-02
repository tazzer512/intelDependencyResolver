package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Path

/**
 * A short plain-text brief of the vulnerabilities to fix, written to be pasted into an AI assistant (or a ticket).
 * When code impact was checked, advisories that do not impact the code are left out and only counted.
 */
object FixReport {
    private const val MAX_REFERENCES = 5
    private const val MAX_SOURCES = 6

    /**
     * [impacts] is null when code impact was not checked. [path] formats a file (e.g. relative to the project root),
     * [module] names a module by its POM.
     */
    fun render(
        artifacts: List<VulnerableArtifact>,
        impacts: Map<Pair<ArtifactVersion, String>, CodeImpact>?,
        path: (Path) -> String,
        module: (Path) -> String,
    ): String = buildString {
        val skipped = ArrayList<String>()
        val items = artifacts.mapNotNull { a ->
            val (relevant, notAffected) = a.vulnerabilities.partition { impacts?.get(a.artifact to it.id)?.impact != Impact.NOT_AFFECTED }
            notAffected.forEach { skipped += "${a.artifact} ${it.cve ?: it.id}" }
            if (relevant.isEmpty()) null else a to relevant
        }

        appendLine("# Fix vulnerable Maven dependencies")
        appendLine()
        if (items.isEmpty()) {
            appendLine(if (impacts == null) "No known vulnerabilities." else "No known vulnerabilities impact the code.")
            appendSkipped(skipped)
            return@buildString
        }
        appendLine("Task: upgrade the Maven dependencies below in this multi-module project so the listed vulnerabilities are fixed.")
        appendLine("Rules:")
        appendLine("- Upgrade to at least the given fix version; never lower a version, except where a downgrade is given below")
        appendLine("  (no newer version is safe; the downgrade version has no known advisories).")
        appendLine("- If the declared version is a \${property}, change the property where it is defined.")
        appendLine("- A library that only comes in transitively: pin the fix version in <dependencyManagement> of the root POM,")
        appendLine("  or upgrade the direct dependency that brings it in to a version that uses a fixed version.")
        appendLine("- Check the listed code against the new version's API (breaking changes), then run `mvn verify`.")
        if (impacts == null) appendLine("- Code impact was not checked: every advisory is listed.")
        for ((i, entry) in items.withIndex()) {
            val (a, vulns) = entry
            val fix = vulns.mapNotNull { it.fixFor(a.artifact.ga, a.artifact.version) }.map(::MavenVersion).maxOrNull()?.text
            appendLine()
            append("## ${i + 1}. ${a.artifact}")
            appendLine(when {
                fix != null -> " → upgrade to $fix"
                a.saferOlderVersion != null -> " → no newer fix; downgrade to ${a.saferOlderVersion} (no known advisories)"
                else -> " → no fixed version known"
            })
            for (v in vulns) {
                val ids = (listOfNotNull(v.cve) + v.id).distinct().joinToString(" / ")
                val fixed = v.fixFor(a.artifact.ga, a.artifact.version)?.let { "fixed in $it" } ?: "no fix yet"
                append("- $ids (${v.severity.displayName}${v.score?.let { " $it" } ?: ""}, $fixed): ${v.summary.trim()}")
                if (impacts?.get(a.artifact to v.id)?.impact == Impact.UNKNOWN) append(" [impact on code unknown]")
                appendLine()
            }
            appendLine("- Comes in: " + sources(a, path, module))
            val refs = vulns.flatMap { impacts?.get(a.artifact to it.id)?.evidence.orEmpty() }.distinct()
            if (refs.isNotEmpty()) {
                appendLine("- Code using the vulnerable classes: " + refs.take(MAX_REFERENCES).joinToString("; ") +
                    if (refs.size > MAX_REFERENCES) "; … ${refs.size - MAX_REFERENCES} more" else "")
            }
            if (fix == null && a.saferOlderVersion != null) {
                appendLine("- Downgrade: check the code against ${a.saferOlderVersion}'s API (it may lack features the current version has).")
            } else if (fix == null) appendLine("- No fix: apply the workaround from ${vulns.first().url}, or remove the dependency if it is not needed.")
        }
        appendSkipped(skipped)
    }

    /** Where the artifact is declared, or which direct dependency brings it in, per declaring POM. */
    private fun sources(a: VulnerableArtifact, path: (Path) -> String, module: (Path) -> String): String {
        val bySource = LinkedHashMap<String, MutableSet<String>>()
        for (u in a.usages.sortedBy { it.node.depth }) {
            val d = u.node.direct.dependency
            val where = (d.declaredIn ?: u.module).let { p -> path(p) + if (d.pos.line > 0) ":${d.pos.line}" else "" }
            val key = if (u.node.depth == 1) "declared in $where" else "transitively via ${u.node.direct.label} (declared in $where)"
            bySource.getOrPut(key) { LinkedHashSet() }.add(module(u.module) + if (u.node.scope != "compile") " [${u.node.scope}]" else "")
        }
        val parts = bySource.entries.take(MAX_SOURCES).map { (k, mods) -> "$k, modules: ${mods.joinToString(", ")}" }
        return parts.joinToString("; ") + if (bySource.size > MAX_SOURCES) "; … ${bySource.size - MAX_SOURCES} more" else ""
    }

    private fun StringBuilder.appendSkipped(skipped: List<String>) {
        if (skipped.isEmpty()) return
        appendLine()
        appendLine("Left out (do not impact the code): " + skipped.joinToString(", "))
    }
}
