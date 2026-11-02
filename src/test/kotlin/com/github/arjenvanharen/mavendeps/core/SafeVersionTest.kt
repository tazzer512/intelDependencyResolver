package com.github.arjenvanharen.mavendeps.core

import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.dep
import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.deps
import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.pom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Falling back to an older version without known advisories when no newer version fixes them. */
class SafeVersionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f by lazy { Fixture(tmp.root.toPath().toAbsolutePath().normalize()) }

    private fun advisory(id: String, affected: String) = """{"id":"$id","modified":"2026-01-01","summary":"s",
        "affected":[{"package":{"ecosystem":"Maven","name":"org:lib"},$affected}]}"""

    /** osv.dev: [vulnerable] maps a version of org:lib to its advisory ids; [details] are the advisory records. */
    private class FakeOsv(val vulnerable: (String) -> List<String>, val details: Map<String, String>) : OsvTransport {
        val queried = ArrayList<String>()

        override fun post(url: String, json: String): String {
            val results = Json.parse(json).arr("queries").map { q ->
                val version = q.str("version")!!
                queried += version
                val ids = if (q.obj("package").str("name") == "org:lib") vulnerable(version) else emptyList()
                """{"vulns":[${ids.joinToString(",") { """{"id":"$it","modified":"2026-01-01"}""" }}]}"""
            }
            return """{"results":[${results.joinToString(",")}]}"""
        }

        override fun get(url: String): String = details.getValue(url.substringAfterLast('/'))
    }

    @Test
    fun `advisories know which versions they affect`() {
        val v = OsvClient.parse(
            Json.parse(
                advisory(
                    "GHSA-1",
                    """"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"1.2"},{"fixed":"1.4"},{"introduced":"2.0"},{"last_affected":"2.1"}]},
                       {"type":"GIT","events":[{"introduced":"abc123"}]}],
                       "versions":["0.5"]""",
                ),
            ),
        )
        val affected = listOf("0.5", "1.0", "1.2", "1.3.9", "1.4", "1.9", "2.0", "2.1", "2.1.1").filter { v.affects("org:lib", it) }
        assertEquals(listOf("0.5", "1.2", "1.3.9", "2.0", "2.1"), affected)
        assertEquals(listOf("1.4"), v.fixedIn["org:lib"])
        assertFalse(v.affects("org:other", "1.2"))

        val all = OsvClient.parse(Json.parse(advisory("GHSA-2", """"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"}]}]""")))
        assertTrue(all.affects("org:lib", "0.0.1"))
    }

    @Test
    fun `maven-metadata versions are parsed and unsafe coordinates are never requested`() {
        val xml = """<metadata><groupId>org</groupId><versioning><latest>2.0</latest><release>2.0</release>
            <versions><version>1.0</version><version> 1.1 </version><version>2.0</version></versions></versioning></metadata>"""
        assertEquals(listOf("1.0", "1.1", "2.0"), MavenCentral.parse(xml))
        assertEquals(emptyList<String>(), MavenCentral.parse("<html>not found</html>"))

        val urls = ArrayList<String>()
        val transport = object : OsvTransport {
            override fun post(url: String, json: String) = error("no posts")
            override fun get(url: String): String {
                urls += url
                return xml
            }
        }
        val central = MavenCentral(transport, "https://repo.test/m2")
        assertEquals(listOf("1.0", "1.1", "2.0"), central.versions("org.example", "lib"))
        assertEquals(listOf("https://repo.test/m2/org/example/lib/maven-metadata.xml"), urls)
        assertEquals(emptyList<String>(), central.versions("org/../..", "lib"))
        assertEquals(emptyList<String>(), central.versions("org", "lib?x=1"))
        assertEquals(1, urls.size)

        val failing = object : OsvTransport {
            override fun post(url: String, json: String) = error("no posts")
            override fun get(url: String): String = throw java.io.IOException("404")
        }
        assertEquals(emptyList<String>(), MavenCentral(failing).versions("org", "lib"))
    }

    @Test
    fun `finder picks the newest older release that osv dev reports nothing for`() {
        // 2.0 is vulnerable without a fix (introduced in 1.2); 1.1 has another advisory; 1.0 is clean.
        val main = advisory("GHSA-1", """"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"1.2"}]}]""")
        val osv = FakeOsv({ v -> if (v == "1.1") listOf("GHSA-OLD") else if (MavenVersion(v) >= MavenVersion("1.2")) listOf("GHSA-1") else emptyList() }, mapOf("GHSA-1" to main))
        val client = OsvClient(osv)
        val vulns = client.query(listOf(ArtifactVersion("org", "lib", "2.0"))).values.single()
        val artifact = VulnerableArtifact(ArtifactVersion("org", "lib", "2.0"), vulns, emptyList())
        assertNull(artifact.fixVersion)
        assertEquals(1, artifact.unfixed)

        osv.queried.clear()
        val finder = SafeVersionFinder(client) { _, _ -> listOf("1.0", "1.1", "1.2", "1.5", "1.9-RC1", "2.0", "3.0", "[1,2)") }
        assertEquals("1.0", finder.find(artifact))
        // Versions the advisory itself marks as affected, pre-releases and newer versions are never queried.
        assertEquals(listOf("1.1", "1.0"), osv.queried)

        assertNull(SafeVersionFinder(client) { _, _ -> listOf("1.1", "1.5") }.find(artifact))
        assertNull(SafeVersionFinder(client) { _, _ -> emptyList() }.find(artifact))
    }

    @Test
    fun `a missing osv result is not taken as safe`() {
        val transport = object : OsvTransport {
            override fun post(url: String, json: String) = """{"results":[]}"""
            override fun get(url: String) = error("no details")
        }
        val ids = OsvClient(transport).advisoryIds(listOf(ArtifactVersion("org", "lib", "1.0")))
        assertTrue(ids.values.single().isNotEmpty())
    }

    @Test
    fun `scan falls back to a safe older version and the fix downgrades only vulnerable versions`() {
        // a declares org:lib:1, b gets 0.9 transitively, c declares 0.8 through a property. 0.9 and up are vulnerable, unfixed.
        val parent = "<parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent>"
        f.write(
            "pom.xml",
            pom("<groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version><packaging>pom</packaging>" +
                "<modules><module>a</module><module>b</module><module>c</module></modules>"),
        )
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId>${deps("org:lib:1")}"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId>${deps("org:brings-lib:1")}"))
        f.write("c/pom.xml", pom("$parent<artifactId>c</artifactId><properties><lib.version>0.8</lib.version></properties><dependencies>${dep("org:lib:\${lib.version}")}</dependencies>"))
        listOf("1", "0.9", "0.8", "0.7").forEach { f.artifact("org:lib:$it") }
        f.artifact("org:brings-lib:1", deps("org:lib:0.9"))

        val main = advisory("GHSA-1", """"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0.9"}]}]""")
        val client = OsvClient(FakeOsv({ v -> if (MavenVersion(v) >= MavenVersion("0.9")) listOf("GHSA-1") else emptyList() }, mapOf("GHSA-1" to main)))
        val r = f.resolver()
        val libs = DependencyIndex.libraries(DependencyIndex.resolveAll(r, r.ws.entries.keys)).associateBy { it.ga }
        val targets = VulnerabilityScanner.targets(libs.values) { r.workspacePom(it.groupId, it.artifactId, it.version) != null }
        val result = VulnerabilityScanner.scan(client, targets, finder = SafeVersionFinder(client) { g, a -> f.repo.versions(g, a) })

        assertEquals(listOf("0.9", "1"), result.vulnerable.map { it.artifact.version }.sorted())
        assertTrue(result.vulnerable.all { it.fixVersion == null && it.saferOlderVersion == "0.8" && it.target == "0.8" })
        assertTrue(FixReport.render(result.vulnerable, null, { it.toString() }, { it.toString() }).contains("downgrade to 0.8"))

        val request = VulnerabilityScanner.fixRequests(result.vulnerable, libs).single()
        assertEquals("0.8", request.target)
        assertEquals(setOf("1", "0.9"), request.replace)
        assertTrue(request.downgrade)

        val plan = VersionAligner.plan(r.ws, r, listOf(request))
        // c already uses the safe version: its property is left alone.
        assertTrue(plan.none { it.kind == PlanItem.Kind.PROPERTY })
        plan.filter { it.selected }.forEach { f.apply(it.pom, it.fix) }
        val r2 = f.resolver()
        val after = DependencyIndex.libraries(DependencyIndex.resolveAll(r2, r2.ws.entries.keys)).single { it.ga == "org:lib" }
        assertEquals(
            mapOf("a" to "0.8", "b" to "0.8", "c" to "0.8"),
            after.usages.filter { it.node.status == NodeStatus.INCLUDED }.associate { it.module.parent.fileName.toString() to it.node.version },
        )
    }

    @Test
    fun `an upgrade that fixes something wins over a downgrade`() {
        val fixed = OsvClient.parse(Json.parse(advisory("GHSA-1", """"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"2.1"}]}]""")))
        val unfixed = OsvClient.parse(Json.parse(advisory("GHSA-2", """"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"1.5"}]}]""")))
        val a = VulnerableArtifact(ArtifactVersion("org", "lib", "2.0"), listOf(fixed, unfixed), emptyList(), saferOlderVersion = "1.0")
        val request = VulnerabilityScanner.fixRequests(listOf(a), emptyMap()).single()
        assertEquals("2.1", request.target)
        assertNull(request.replace)
        assertFalse(request.downgrade)
    }
}
