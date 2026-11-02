package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Path

/** What a change to POMs would do to the Problems tab and to version consistency, before it is written. */
class FixCheck(
    /** Problems the change would add (the same problem elsewhere in the file counts as existing). */
    val newProblems: List<Pair<Path, Problem>>,
    /** How many existing problems the change would resolve. */
    val resolvedProblems: Int,
    /** Libraries that would end up in more versions over the modules than before, e.g. `org:x: 1.0 and 2.0`. */
    val newConflicts: List<String>,
    /** Libraries whose version conflict over the modules the change would resolve. */
    val resolvedConflicts: Int,
) {
    val clean: Boolean get() = newProblems.isEmpty() && newConflicts.isEmpty()
}

/**
 * Checks a change before it is applied: the changed POM texts are parsed into a copy of the workspace, and the
 * validation suite (everything the Problems tab shows from the analysis) and dependency resolution run on both the
 * current and the changed workspace. Problems are compared by POM, severity, category and message, so positions
 * that move with the edit do not count as new.
 */
object FixVerifier {
    fun check(
        ws: Workspace,
        changed: Map<Path, String>,
        repo: LocalRepository?,
        suite: AnalysisSuite = AnalysisSuite.VALIDATION,
        checkCanceled: () -> Unit = {},
    ): FixCheck {
        val entries = LinkedHashMap(ws.entries)
        for ((path, text) in changed) {
            val old = entries[path]
            val parsed = PomParser.parse(text)
            entries[path] = PomEntry(path, text.length.toLong(), old?.lastModified ?: 0, "changed:${text.hashCode()}", parsed.model, parsed.problems)
        }
        val after = Workspace(entries)

        val before = problems(ws, suite, checkCanceled)
        val now = problems(after, suite, checkCanceled)
        val newProblems = ArrayList<Pair<Path, Problem>>()
        var resolved = 0
        for (path in (before.keys + now.keys)) {
            val old = before[path].orEmpty().groupingBy(::key).eachCount()
            val new = now[path].orEmpty()
            val counts = HashMap(old)
            for (p in new) {
                val left = counts[key(p)] ?: 0
                if (left > 0) counts[key(p)] = left - 1 else newProblems += path to p
            }
            resolved += counts.values.sum()
        }

        val newConflicts = ArrayList<String>()
        var resolvedConflicts = 0
        if (repo != null) {
            val oldVersions = versions(DependencyResolver(ws, repo), checkCanceled)
            val newVersions = versions(DependencyResolver(after, repo), checkCanceled)
            for ((ga, vs) in newVersions) {
                val was = oldVersions[ga].orEmpty()
                if (vs.size > 1 && (was.size <= 1 || !was.containsAll(vs))) newConflicts += "$ga: ${vs.sortedWith(MavenVersion.COMPARATOR).joinToString(" and ")}"
            }
            resolvedConflicts = oldVersions.count { (ga, vs) -> vs.size > 1 && newVersions[ga].orEmpty().size <= 1 }
        }
        return FixCheck(newProblems.sortedWith(compareBy({ it.second.severity }, { it.first.toString() })), resolved, newConflicts.sorted(), resolvedConflicts)
    }

    private fun key(p: Problem) = Triple(p.severity, p.category, p.message)

    /** Every problem of every POM, as the analysis reports it. */
    fun problems(ws: Workspace, suite: AnalysisSuite = AnalysisSuite.VALIDATION, checkCanceled: () -> Unit = {}): Map<Path, List<Problem>> {
        val out = LinkedHashMap<Path, MutableList<Problem>>()
        for ((path, entry) in ws.entries) {
            checkCanceled()
            val list = ArrayList(entry.localProblems)
            if (entry.model.wellFormed) suite.pomAnalyzers.forEach { list += it.analyze(ws, path) }
            out[path] = list
        }
        for (analyzer in suite.globalAnalyzers) analyzer.analyze(ws).forEach { (p, problems) -> out.getOrPut(p) { ArrayList() } += problems }
        return out
    }

    /** The versions each library resolves to over all modules. */
    private fun versions(resolver: DependencyResolver, checkCanceled: () -> Unit): Map<String, Set<String>> =
        DependencyIndex.libraries(DependencyIndex.resolveAll(resolver, resolver.ws.entries.keys, checkCanceled))
            .associate { it.ga to it.versions.toSet() }
}
