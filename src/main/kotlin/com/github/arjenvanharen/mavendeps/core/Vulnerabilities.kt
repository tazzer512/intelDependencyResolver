package com.github.arjenvanharen.mavendeps.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

enum class VulnSeverity(val displayName: String) { CRITICAL("Critical"), HIGH("High"), MEDIUM("Medium"), LOW("Low"), UNKNOWN("Unknown") }

/** One advisory from OSV (GitHub Security Advisories, CVEs, ...). */
data class Vulnerability(
    val id: String,
    /** Other ids of the same advisory, e.g. its CVE. */
    val aliases: List<String>,
    val summary: String,
    val severity: VulnSeverity,
    /** CVSS v3 base score when the advisory has a v3 vector. */
    val score: Double?,
    /** Versions that fix it, per `groupId:artifactId`. */
    val fixedIn: Map<String, List<String>>,
    val published: String?,
    val modified: String?,
    val references: List<String>,
) {
    val cve: String? get() = (listOf(id) + aliases).firstOrNull { it.startsWith("CVE-") }
    val url: String get() = "https://osv.dev/vulnerability/$id"

    /** The lowest version newer than [version] that fixes this advisory for [ga], or null when none is known. */
    fun fixFor(ga: String, version: String): String? {
        val current = MavenVersion(version)
        return fixedIn[ga].orEmpty().map(::MavenVersion).filter { it > current }.minOrNull()?.text
    }
}

data class ArtifactVersion(val groupId: String, val artifactId: String, val version: String) {
    val ga: String get() = "$groupId:$artifactId"
    override fun toString() = "$groupId:$artifactId:$version"
}

/** A resolved artifact version with known vulnerabilities, and where the modules get it from. */
class VulnerableArtifact(val artifact: ArtifactVersion, val vulnerabilities: List<Vulnerability>, val usages: List<LibraryUsage>) {
    val severity: VulnSeverity = vulnerabilities.minOfOrNull { it.severity } ?: VulnSeverity.UNKNOWN

    /** The lowest version that fixes every advisory with a known fix; null when none has one. */
    val fixVersion: String? = vulnerabilities.mapNotNull { it.fixFor(artifact.ga, artifact.version) }.map(::MavenVersion).maxOrNull()?.text

    /** Advisories without a known fixed version newer than the current one. */
    val unfixed: Int = vulnerabilities.count { it.fixFor(artifact.ga, artifact.version) == null }

    val direct: Boolean get() = usages.any { it.node.depth == 1 }
    val modules: Set<java.nio.file.Path> get() = usages.mapTo(LinkedHashSet()) { it.module }
}

class ScanResult(val scanned: Int, val vulnerable: List<VulnerableArtifact>) {
    fun count(severity: VulnSeverity) = vulnerable.sumOf { a -> a.vulnerabilities.count { it.severity == severity } }
}

/** HTTP access for [OsvClient]; the IDE supplies one that honours its proxy settings. */
interface OsvTransport {
    fun post(url: String, json: String): String

    fun get(url: String): String
}

/**
 * Client for the OSV API (https://osv.dev). Only `groupId:artifactId` and version of each artifact are sent.
 * Advisory details are cached by id and `modified` stamp across scans.
 */
class OsvClient(private val transport: OsvTransport, private val baseUrl: String = "https://api.osv.dev/v1") {
    private val details = ConcurrentHashMap<String, Vulnerability>()

    /** The advisories affecting each artifact; artifacts without any are left out. */
    fun query(
        artifacts: Collection<ArtifactVersion>,
        checkCanceled: () -> Unit = {},
        progress: (done: Int, total: Int, phase: String) -> Unit = { _, _, _ -> },
    ): Map<ArtifactVersion, List<Vulnerability>> {
        val ids = LinkedHashMap<ArtifactVersion, MutableMap<String, String?>>()
        val list = artifacts.toList()
        for ((chunkIndex, chunk) in list.chunked(BATCH).withIndex()) {
            checkCanceled()
            progress(chunkIndex * BATCH, list.size, "Querying osv.dev")
            val body = "{\"queries\":[" + chunk.joinToString(",") { query(it, null) } + "]}"
            val results = Json.parse(transport.post("$baseUrl/querybatch", body)).arr("results")
            for ((i, r) in results.withIndex()) {
                val artifact = chunk.getOrNull(i) ?: break
                collect(r, ids.getOrPut(artifact) { LinkedHashMap() })
                // Rare: more advisories than fit one page.
                var token = r.str("next_page_token")
                while (token != null) {
                    checkCanceled()
                    val page = Json.parse(transport.post("$baseUrl/query", query(artifact, token)))
                    collect(page, ids.getValue(artifact))
                    token = page.str("next_page_token")
                }
            }
        }
        val wanted = ids.values.flatMap { it.entries }.associate { it.key to it.value }
        val missing = wanted.filter { (id, modified) -> details[id]?.let { it.modified != modified || modified == null } ?: true }.keys.toList()
        fetch(missing, checkCanceled) { done -> progress(done, missing.size, "Reading advisories") }
        return ids.mapValues { (_, v) -> v.keys.mapNotNull { details[it] } }.filterValues { it.isNotEmpty() }
    }

    private fun query(a: ArtifactVersion, pageToken: String?) =
        "{\"package\":{\"ecosystem\":\"Maven\",\"name\":${Json.quote(a.ga)}},\"version\":${Json.quote(a.version)}" +
            (pageToken?.let { ",\"page_token\":${Json.quote(it)}" } ?: "") + "}"

    private fun collect(result: Any?, into: MutableMap<String, String?>) {
        for (v in result.arr("vulns")) v.str("id")?.let { into[it] = v.str("modified") }
    }

    private fun fetch(ids: List<String>, checkCanceled: () -> Unit, progress: (Int) -> Unit) {
        if (ids.isEmpty()) return
        val pool = Executors.newFixedThreadPool(minOf(PARALLEL, ids.size))
        try {
            val futures: List<Future<Vulnerability>> = ids.map { id -> pool.submit<Vulnerability> { parse(Json.parse(transport.get("$baseUrl/vulns/$id"))) } }
            for ((i, f) in futures.withIndex()) {
                checkCanceled()
                val v = try {
                    f.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
                details[v.id] = v
                progress(i + 1)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        private const val BATCH = 500
        private const val PARALLEL = 8

        /** An OSV vulnerability record. */
        fun parse(json: Any?): Vulnerability {
            val id = json.str("id") ?: throw IllegalArgumentException("Advisory without id")
            val fixed = LinkedHashMap<String, MutableList<String>>()
            for (affected in json.arr("affected")) {
                val pkg = affected.obj("package")
                if (pkg.str("ecosystem") != "Maven") continue
                val name = pkg.str("name") ?: continue
                for (range in affected.arr("ranges")) for (event in range.arr("events")) {
                    event.str("fixed")?.let { fixed.getOrPut(name) { ArrayList() }.add(it) }
                }
            }
            val vector = json.arr("severity").firstOrNull { it.str("type") == "CVSS_V3" }?.str("score")
            val score = vector?.let { Cvss.score(it) }
            val severity = when (json.obj("database_specific").str("severity")?.uppercase()) {
                "CRITICAL" -> VulnSeverity.CRITICAL
                "HIGH" -> VulnSeverity.HIGH
                "MODERATE", "MEDIUM" -> VulnSeverity.MEDIUM
                "LOW" -> VulnSeverity.LOW
                else -> score?.let { Cvss.severity(it) } ?: VulnSeverity.UNKNOWN
            }
            return Vulnerability(
                id = id,
                aliases = json.arr("aliases").filterIsInstance<String>(),
                summary = json.str("summary") ?: json.str("details")?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(200) ?: "",
                severity = severity,
                score = score,
                fixedIn = fixed,
                published = json.str("published"),
                modified = json.str("modified"),
                references = json.arr("references").mapNotNull { it.str("url") },
            )
        }
    }
}

/** CVSS v3.x base scores (https://www.first.org/cvss/v3.1/specification-document). */
object Cvss {
    /** The base score of a vector like `CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H`, or null when it is not a complete v3 vector. */
    fun score(vector: String): Double? {
        if (!vector.startsWith("CVSS:3")) return null
        val m = vector.split('/').drop(1).mapNotNull { part -> part.split(':').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val changed = when (m["S"]) { "U" -> false; "C" -> true; else -> return null }
        val av = mapOf("N" to 0.85, "A" to 0.62, "L" to 0.55, "P" to 0.2)[m["AV"]] ?: return null
        val ac = mapOf("L" to 0.77, "H" to 0.44)[m["AC"]] ?: return null
        val pr = when (m["PR"]) { "N" -> 0.85; "L" -> if (changed) 0.68 else 0.62; "H" -> if (changed) 0.5 else 0.27; else -> return null }
        val ui = mapOf("N" to 0.85, "R" to 0.62)[m["UI"]] ?: return null
        val cia = mapOf("H" to 0.56, "L" to 0.22, "N" to 0.0)
        val c = cia[m["C"]] ?: return null
        val i = cia[m["I"]] ?: return null
        val a = cia[m["A"]] ?: return null
        val iss = 1 - (1 - c) * (1 - i) * (1 - a)
        val impact = if (changed) 7.52 * (iss - 0.029) - 3.25 * (iss - 0.02).pow(15) else 6.42 * iss
        if (impact <= 0) return 0.0
        val exploitability = 8.22 * av * ac * pr * ui
        return roundUp(minOf(if (changed) 1.08 * (impact + exploitability) else impact + exploitability, 10.0))
    }

    fun severity(score: Double): VulnSeverity = when {
        score >= 9.0 -> VulnSeverity.CRITICAL
        score >= 7.0 -> VulnSeverity.HIGH
        score >= 4.0 -> VulnSeverity.MEDIUM
        score > 0.0 -> VulnSeverity.LOW
        else -> VulnSeverity.UNKNOWN
    }

    /** The specification's Roundup: the smallest number with one decimal that is >= [x], robust to float noise. */
    private fun roundUp(x: Double): Double {
        val n = (x * 100_000).roundToLong()
        return if (n % 10_000 == 0L) n / 100_000.0 else (floor(n / 10_000.0) + 1) / 10.0
    }
}

object VulnerabilityScanner {
    /**
     * Every artifact version on a module's resolved classpath (direct and transitive, all scopes), with the usages
     * that bring it in. Workspace modules ([isWorkspace]) and versions that are not concrete are skipped.
     */
    fun targets(libraries: Collection<Library>, isWorkspace: (ArtifactVersion) -> Boolean): Map<ArtifactVersion, List<LibraryUsage>> {
        val out = LinkedHashMap<ArtifactVersion, MutableList<LibraryUsage>>()
        for (lib in libraries) for (u in lib.usages) {
            val v = u.node.version ?: continue
            if (u.node.status != NodeStatus.INCLUDED || "\${" in v || v.any { it in "[](),"}) continue
            val a = ArtifactVersion(lib.groupId, lib.artifactId, v)
            if (isWorkspace(a)) continue
            out.getOrPut(a) { ArrayList() }.add(u)
        }
        return out
    }

    fun scan(
        client: OsvClient,
        targets: Map<ArtifactVersion, List<LibraryUsage>>,
        checkCanceled: () -> Unit = {},
        progress: (done: Int, total: Int, phase: String) -> Unit = { _, _, _ -> },
    ): ScanResult {
        val found = client.query(targets.keys, checkCanceled, progress)
        val vulnerable = found.map { (a, vulns) ->
            VulnerableArtifact(a, vulns.sortedWith(compareBy({ it.severity }, { -(it.score ?: 0.0) }, { it.id })), targets.getValue(a))
        }.sortedWith(compareBy({ it.severity }, { -it.vulnerabilities.size }, { it.artifact.toString() }))
        return ScanResult(targets.size, vulnerable)
    }

    /** Requests that upgrade every vulnerable artifact with a known fix to that version, merged per library. */
    fun fixRequests(artifacts: Collection<VulnerableArtifact>, libraries: Map<String, Library>): List<AlignRequest> =
        artifacts.filter { it.fixVersion != null }.groupBy { it.artifact.ga }.map { (ga, list) ->
            val first = list.first().artifact
            val target = list.mapNotNull { it.fixVersion }.map(::MavenVersion).max().text
            AlignRequest(first.groupId, first.artifactId, target, libraries[ga]?.usages ?: list.flatMap { it.usages })
        }
}
