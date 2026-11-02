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
    /** The full advisory text (markdown). */
    val details: String = "",
    /** Strings from the advisory's ecosystem- or database-specific data, which may name vulnerable classes or functions. */
    val symbols: List<String> = emptyList(),
    /** Affected version ranges per `groupId:artifactId`: each range is its OSV events, `introduced`/`fixed`/`last_affected` to a version. */
    val ranges: Map<String, List<List<Pair<String, String>>>> = emptyMap(),
    /** Versions the advisory explicitly lists as affected, per `groupId:artifactId`. */
    val affectedVersions: Map<String, Set<String>> = emptyMap(),
) {
    val cve: String? get() = (listOf(id) + aliases).firstOrNull { it.startsWith("CVE-") }
    val url: String get() = "https://osv.dev/vulnerability/$id"

    /** The lowest version newer than [version] that fixes this advisory for [ga], or null when none is known. */
    fun fixFor(ga: String, version: String): String? {
        val current = MavenVersion(version)
        return fixedIn[ga].orEmpty().map(::MavenVersion).filter { it > current }.minOrNull()?.text
    }

    /**
     * Whether this advisory affects [version] of [ga] according to its own data (listed versions, then OSV range
     * events in version order). This only knows this advisory: a version can still have other advisories.
     */
    fun affects(ga: String, version: String): Boolean {
        if (version in affectedVersions[ga].orEmpty()) return true
        val v = MavenVersion(version)
        return ranges[ga].orEmpty().any { events ->
            var affected = false
            for ((type, at) in events.sortedWith(compareBy { if (it.second == "0") MIN else MavenVersion(it.second) })) {
                when (type) {
                    "introduced" -> if (at == "0" || v >= MavenVersion(at)) affected = true
                    "fixed" -> if (v >= MavenVersion(at)) affected = false
                    "last_affected" -> if (v > MavenVersion(at)) affected = false
                }
            }
            affected
        }
    }

    private companion object {
        val MIN = MavenVersion("0")
    }
}

data class ArtifactVersion(val groupId: String, val artifactId: String, val version: String) {
    val ga: String get() = "$groupId:$artifactId"
    override fun toString() = "$groupId:$artifactId:$version"
}

/** A resolved artifact version with known vulnerabilities, and where the modules get it from. */
class VulnerableArtifact(
    val artifact: ArtifactVersion,
    val vulnerabilities: List<Vulnerability>,
    val usages: List<LibraryUsage>,
    /**
     * When no newer version fixes every advisory: the newest older version that osv.dev knows no advisories for
     * (see [SafeVersionFinder]), or null when none was found or the search did not run.
     */
    val saferOlderVersion: String? = null,
    /**
     * The upgrade after checking it at osv.dev: when the advisories' own fix version has advisories of its own, the
     * version that also fixes those (see [VulnerabilityScanner.scan]). Null: not checked.
     */
    private val verifiedFix: String? = null,
    /** The upgrade still has known advisories (none of its fixes is known, or the check gave up). */
    val fixStillVulnerable: Boolean = false,
) {
    val severity: VulnSeverity = vulnerabilities.minOfOrNull { it.severity } ?: VulnSeverity.UNKNOWN

    /** The advisories' own fix: the lowest version that fixes every advisory with a known fix; null when none has one. */
    val advisoryFixVersion: String? = vulnerabilities.mapNotNull { it.fixFor(artifact.ga, artifact.version) }.map(::MavenVersion).maxOrNull()?.text

    /** The version to upgrade to: [advisoryFixVersion], raised until it has no known advisories itself when that was checked. */
    val fixVersion: String? = verifiedFix ?: advisoryFixVersion

    /** Advisories without a known fixed version newer than the current one. */
    val unfixed: Int = vulnerabilities.count { it.fixFor(artifact.ga, artifact.version) == null }

    /** The version a fix moves to: the upgrade when one is known, otherwise the safe older version. */
    val target: String? get() = fixVersion ?: saferOlderVersion

    fun withSaferOlderVersion(version: String?) = VulnerableArtifact(artifact, vulnerabilities, usages, version, verifiedFix, fixStillVulnerable)

    fun withVerifiedFix(version: String, stillVulnerable: Boolean) = VulnerableArtifact(artifact, vulnerabilities, usages, saferOlderVersion, version, stillVulnerable)

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
                // Rare: more advisories than fit one page. Bounded, in case the server keeps returning a token.
                var token = r.str("next_page_token")
                var pages = 0
                while (token != null && pages++ < MAX_PAGES) {
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

    /**
     * Only the ids of the advisories affecting each artifact (one batch request per [BATCH] artifacts, no advisory
     * details); an artifact without advisories maps to an empty set.
     */
    fun advisoryIds(artifacts: Collection<ArtifactVersion>, checkCanceled: () -> Unit = {}): Map<ArtifactVersion, Set<String>> {
        val out = LinkedHashMap<ArtifactVersion, Set<String>>()
        for (chunk in artifacts.toList().chunked(BATCH)) {
            checkCanceled()
            val body = "{\"queries\":[" + chunk.joinToString(",") { query(it, null) } + "]}"
            val results = Json.parse(transport.post("$baseUrl/querybatch", body)).arr("results")
            for ((i, a) in chunk.withIndex()) {
                // A missing result is not proof of safety: treat it as affected.
                val r = results.getOrNull(i)
                val ids = LinkedHashMap<String, String?>()
                collect(r, ids)
                out[a] = if (r == null || r.str("next_page_token") != null) ids.keys + UNKNOWN else ids.keys
            }
        }
        return out
    }

    private fun query(a: ArtifactVersion, pageToken: String?) =
        "{\"package\":{\"ecosystem\":\"Maven\",\"name\":${Json.quote(a.ga)}},\"version\":${Json.quote(a.version)}" +
            (pageToken?.let { ",\"page_token\":${Json.quote(it)}" } ?: "") + "}"

    private fun collect(result: Any?, into: MutableMap<String, String?>) {
        // Ids become part of a URL path: only accept what advisory ids look like (GHSA-…, CVE-…, PYSEC-…).
        for (v in result.arr("vulns")) v.str("id")?.takeIf { ID.matches(it) }?.let { into[it] = v.str("modified") }
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
        private const val MAX_PAGES = 100
        /** Stands for advisories that were not listed (missing result, more pages): never "no advisories". */
        private const val UNKNOWN = "?"
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

        /** An OSV vulnerability record. */
        fun parse(json: Any?): Vulnerability {
            val id = json.str("id") ?: throw IllegalArgumentException("Advisory without id")
            val fixed = LinkedHashMap<String, MutableList<String>>()
            val symbols = ArrayList<String>()
            val ranges = LinkedHashMap<String, MutableList<List<Pair<String, String>>>>()
            val versions = LinkedHashMap<String, MutableSet<String>>()
            for (affected in json.arr("affected")) {
                val pkg = affected.obj("package")
                if (pkg.str("ecosystem") != "Maven") continue
                val name = pkg.str("name") ?: continue
                strings(affected.obj("ecosystem_specific"), symbols)
                strings(affected.obj("database_specific"), symbols)
                for (range in affected.arr("ranges")) {
                    val events = ArrayList<Pair<String, String>>()
                    for (event in range.arr("events")) {
                        event.str("fixed")?.let { fixed.getOrPut(name) { ArrayList() }.add(it) }
                        for (type in EVENTS) event.str(type)?.let { events += type to it }
                    }
                    // GIT ranges use commit hashes, not versions.
                    if (range.str("type") != "GIT" && events.isNotEmpty()) ranges.getOrPut(name) { ArrayList() }.add(events)
                }
                affected.arr("versions").filterIsInstance<String>().takeIf { it.isNotEmpty() }?.let { listed ->
                    versions.getOrPut(name) { LinkedHashSet() }.addAll(listed.take(MAX_LISTED))
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
                details = json.str("details")?.take(MAX_DETAILS) ?: "",
                symbols = symbols.distinct().take(MAX_SYMBOLS),
                ranges = ranges,
                affectedVersions = versions,
            )
        }

        private const val MAX_DETAILS = 64 * 1024
        private const val MAX_SYMBOLS = 500
        private const val MAX_LISTED = 5_000
        private val EVENTS = listOf("introduced", "fixed", "last_affected")

        /** Every string value in [json], depth-first. */
        private fun strings(json: Any?, into: MutableList<String>, depth: Int = 0) {
            if (depth > 8 || into.size >= MAX_SYMBOLS) return
            when (json) {
                is String -> if (json.length <= 300) into += json
                is Map<*, *> -> json.values.forEach { strings(it, into, depth + 1) }
                is List<*> -> json.forEach { strings(it, into, depth + 1) }
            }
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

    /**
     * Scans [targets]. With a [finder], artifacts that no newer version fully fixes also get the newest older version
     * without known advisories ([VulnerableArtifact.saferOlderVersion]).
     */
    fun scan(
        client: OsvClient,
        targets: Map<ArtifactVersion, List<LibraryUsage>>,
        checkCanceled: () -> Unit = {},
        finder: SafeVersionFinder? = null,
        progress: (done: Int, total: Int, phase: String) -> Unit = { _, _, _ -> },
    ): ScanResult {
        val found = client.query(targets.keys, checkCanceled, progress)
        val unfixed = found.count { (a, vulns) -> VulnerableArtifact(a, vulns, emptyList()).unfixed > 0 }
        var searched = 0
        val vulnerable = found.map { (a, vulns) ->
            val artifact = VulnerableArtifact(a, vulns.sortedWith(compareBy({ it.severity }, { -(it.score ?: 0.0) }, { it.id })), targets.getValue(a))
            if (finder == null || artifact.unfixed == 0) artifact
            else {
                progress(searched++, unfixed, "Looking for older versions without known vulnerabilities")
                artifact.withSaferOlderVersion(finder.find(artifact, checkCanceled))
            }
        }.let { verifyUpgrades(client, it, checkCanceled) }
            .sortedWith(compareBy({ it.severity }, { -it.vulnerabilities.size }, { it.artifact.toString() }))
        return ScanResult(targets.size, vulnerable)
    }

    private const val MAX_UPGRADE_ROUNDS = 5

    /**
     * An advisory's fix version can be affected by later advisories: upgrading to it would make the scan report the
     * library again. Each upgrade target is checked at osv.dev and raised to the fix of its own advisories until it
     * has none (at most [MAX_UPGRADE_ROUNDS] rounds, one batch query each).
     */
    fun verifyUpgrades(client: OsvClient, artifacts: List<VulnerableArtifact>, checkCanceled: () -> Unit = {}): List<VulnerableArtifact> {
        val result = HashMap<VulnerableArtifact, Pair<String, Boolean>>()
        var pending: Map<VulnerableArtifact, String> = artifacts.mapNotNull { a -> a.advisoryFixVersion?.let { a to it } }.toMap()
        repeat(MAX_UPGRADE_ROUNDS) {
            if (pending.isEmpty()) return@repeat
            checkCanceled()
            val found = client.query(pending.map { (a, v) -> ArtifactVersion(a.artifact.groupId, a.artifact.artifactId, v) }.distinct(), checkCanceled)
            val next = HashMap<VulnerableArtifact, String>()
            for ((a, target) in pending) {
                val vulns = found[ArtifactVersion(a.artifact.groupId, a.artifact.artifactId, target)].orEmpty()
                val higher = vulns.mapNotNull { it.fixFor(a.artifact.ga, target) }.map(::MavenVersion).maxOrNull()?.text
                when {
                    vulns.isEmpty() -> result[a] = target to false
                    // No newer version fixes the target's advisories: the best known upgrade, flagged.
                    higher == null -> result[a] = target to true
                    else -> next[a] = higher
                }
            }
            pending = next
        }
        pending.forEach { (a, target) -> result[a] = target to true }
        return artifacts.map { a -> result[a]?.let { (v, still) -> a.withVerifiedFix(v, still) } ?: a }
    }

    /**
     * Requests that upgrade every vulnerable artifact with a known fix to that version, merged per library. A library
     * without any upgrade that fixes something falls back to its safe older version: a downgrade request that only
     * changes the vulnerable versions.
     */
    fun fixRequests(artifacts: Collection<VulnerableArtifact>, libraries: Map<String, Library>): List<AlignRequest> =
        artifacts.filter { it.target != null }.groupBy { it.artifact.ga }.map { (ga, list) ->
            val first = list.first().artifact
            val usages = libraries[ga]?.usages ?: list.flatMap { it.usages }
            val upgrades = list.mapNotNull { it.fixVersion }
            if (upgrades.isNotEmpty()) AlignRequest(first.groupId, first.artifactId, upgrades.map(::MavenVersion).max().text, usages)
            else {
                // Every candidate was checked to have no advisories; the newest one changes the least.
                val target = list.mapNotNull { it.saferOlderVersion }.map(::MavenVersion).max().text
                AlignRequest(first.groupId, first.artifactId, target, usages, replace = list.mapTo(LinkedHashSet()) { it.artifact.version })
            }
        }
}

/** Versions of an artifact that Maven Central publishes, from its `maven-metadata.xml`. Only `groupId:artifactId` is sent. */
class MavenCentral(private val transport: OsvTransport, private val baseUrl: String = "https://repo1.maven.org/maven2") {
    /** Published versions in the repository's order; empty when the coordinates are unusable or the request fails. */
    fun versions(groupId: String, artifactId: String): List<String> {
        val segments = groupId.split('.') + artifactId
        if (segments.any { !LocalRepository.isSafeSegment(it) || !SEGMENT.matches(it) }) return emptyList()
        val xml = try {
            transport.get("$baseUrl/${segments.joinToString("/")}/maven-metadata.xml")
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            return emptyList()
        }
        return parse(xml)
    }

    companion object {
        private val SEGMENT = Regex("[A-Za-z0-9._-]{1,200}")
        private val VERSION = Regex("<version>\\s*([^<\\s]{1,100})\\s*</version>")
        private const val MAX_VERSIONS = 5_000

        /** The `<versions>` of a `maven-metadata.xml`. */
        fun parse(xml: String): List<String> {
            val block = xml.substringAfter("<versions>", "").substringBefore("</versions>")
            return VERSION.findAll(block).map { it.groupValues[1] }.filter { v -> v.none { it.isISOControl() } }.distinct().take(MAX_VERSIONS).toList()
        }
    }
}

/**
 * Finds a version to fall back to when no newer version fixes every advisory of an artifact: the newest older
 * version (a release, unless the current version is a pre-release) that osv.dev reports no advisories for.
 * Candidates come from [versions] (Maven Central and `~/.m2`); those that the artifact's own advisories already mark
 * as affected are skipped before asking osv.dev, which checks the rest against *all* advisories.
 */
class SafeVersionFinder(private val client: OsvClient, private val versions: (groupId: String, artifactId: String) -> Collection<String>) {
    fun find(a: VulnerableArtifact, checkCanceled: () -> Unit = {}): String? {
        val ga = a.artifact.ga
        val current = MavenVersion(a.artifact.version)
        val candidates = versions(a.artifact.groupId, a.artifact.artifactId).asSequence()
            .filter { v -> v.isNotBlank() && v.none { it in "[](),$" } }
            .distinct()
            .map(::MavenVersion)
            .filter { it < current && (current.isPreRelease || !it.isPreRelease) }
            .sortedDescending()
            .map { it.text }
            .filter { v -> a.vulnerabilities.none { it.affects(ga, v) } }
            .take(MAX_CANDIDATES)
            .toList()
        for (chunk in candidates.chunked(CHUNK)) {
            checkCanceled()
            val ids = client.advisoryIds(chunk.map { ArtifactVersion(a.artifact.groupId, a.artifact.artifactId, it) }, checkCanceled)
            chunk.firstOrNull { v -> ids[ArtifactVersion(a.artifact.groupId, a.artifact.artifactId, v)]?.isEmpty() == true }?.let { return it }
        }
        return null
    }

    companion object {
        const val MAX_CANDIDATES = 200
        private const val CHUNK = 25
    }
}
