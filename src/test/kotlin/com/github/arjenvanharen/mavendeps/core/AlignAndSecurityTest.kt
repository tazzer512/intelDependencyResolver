package com.github.arjenvanharen.mavendeps.core

import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.dep
import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.deps
import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.pom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

class AlignAndSecurityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f by lazy { Fixture(tmp.root.toPath().toAbsolutePath().normalize()) }

    private val parent = "<parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent>"

    /**
     * a: org:lib:1 (literal, the newest)
     * b: org:brings-lib:1 → org:lib:0.9 (transitive only)
     * c: org:lib:${lib.version} with lib.version=0.8 defined in c
     */
    private fun workspace() {
        f.write(
            "pom.xml",
            pom(
                "<groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version><packaging>pom</packaging>" +
                    "<modules><module>a</module><module>b</module><module>c</module></modules>",
            ),
        )
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId>${deps("org:lib:1")}"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId>${deps("org:brings-lib:1")}"))
        f.write("c/pom.xml", pom("$parent<artifactId>c</artifactId><properties><lib.version>0.8</lib.version></properties><dependencies>${dep("org:lib:\${lib.version}")}</dependencies>"))
        listOf("1", "0.9", "0.8").forEach { f.artifact("org:lib:$it") }
        f.artifact("org:brings-lib:1", deps("org:lib:0.9"))
    }

    private fun libraries(): Pair<DependencyResolver, Map<String, Library>> {
        val r = f.resolver()
        val trees = DependencyIndex.resolveAll(r, r.ws.entries.keys)
        return r to DependencyIndex.libraries(trees).associateBy { it.ga }
    }

    private fun applyAll(items: List<PlanItem>) = items.filter { it.selected }.forEach { f.apply(it.pom, it.fix) }

    private fun versionsByModule(): Map<String, String?> {
        val (_, libs) = libraries()
        return libs.getValue("org:lib").usages.filter { it.node.status == NodeStatus.INCLUDED }
            .associate { it.module.parent.fileName.toString() to it.node.version }
    }

    @Test
    fun `newest prefers releases and skips ranges`() {
        assertEquals("2.0", VersionAligner.newest(listOf("1.0", "2.0", "2.1-RC1", "[3,4)", "\${x}")))
        assertEquals("2.1-RC1", VersionAligner.newest(listOf("2.0-beta", "2.1-RC1")))
        assertNull(VersionAligner.newest(listOf("\${x}")))
    }

    @Test
    fun `conflicts are resolved to the newest version everywhere`() {
        workspace()
        val (r, libs) = libraries()
        val conflicts = VersionAligner.conflicts(libs.values)
        assertEquals(listOf("org:lib" to "1"), conflicts.map { it.library.ga to it.target })
        assertEquals(setOf("b", "c"), conflicts.single().outdated.keys.map { it.parent.fileName.toString() }.toSet())

        val plan = VersionAligner.plan(r.ws, r, conflicts.map { it.request() })
        assertEquals(
            listOf(PlanItem.Kind.PROPERTY to "c", PlanItem.Kind.MANAGED to "pom.xml"),
            plan.map { it.kind to (f.root.relativize(it.pom).let { p: Path -> if (p.nameCount > 1) p.getName(0).toString() else p.toString() }) },
        )
        applyAll(plan)
        assertEquals(mapOf("a" to "1", "b" to "1", "c" to "1"), versionsByModule())
        assertTrue(f.read("pom.xml").contains("<dependencyManagement>"))
        val (_, after) = libraries()
        assertTrue(VersionAligner.conflicts(after.values).isEmpty())
    }

    @Test
    fun `aligning never lowers a version`() {
        workspace()
        val (r, libs) = libraries()
        // A fix version below what module a already declares: only b and c are raised, and the pin keeps a's 1.
        val plan = VersionAligner.plan(r.ws, r, listOf(AlignRequest("org", "lib", "0.95", libs.getValue("org:lib").usages)))
        assertTrue(plan.none { it.description.contains("from 1 ") })
        applyAll(plan)
        val v = versionsByModule()
        assertEquals("1", v["a"])
        assertTrue(MavenVersion(v.getValue("b")!!) >= MavenVersion("0.95"))
        assertEquals("0.95", v["c"])
    }

    @Test
    fun `managed AddDependency updates an existing entry`() {
        val path = f.write("pom.xml", pom("<groupId>g</groupId><artifactId>a</artifactId><version>1</version><dependencyManagement>${deps("org:x:1")}</dependencyManagement>"))
        f.apply(path, AddDependency("t", null, "org", "x", "2", managed = true))
        f.apply(path, AddDependency("t", null, "org", "y", "3", managed = true))
        val model = PomParser.parse(f.read("pom.xml")).model
        assertEquals(listOf("org:x:2", "org:y:3"), model.dependencies.map { "${it.groupId}:${it.artifactId}:${it.version}" })
        assertTrue(model.dependencies.all { it.section == DependencySection.DEPENDENCY_MANAGEMENT })
    }

    @Test
    fun `json and cvss`() {
        val parsed = Json.parse("""{"a":[1,2.5,"x\"A"],"b":{"c":null,"d":true}}""")
        assertEquals(listOf(1.0, 2.5, "x\"A"), parsed.arr("a"))
        assertEquals(true, parsed.obj("b")!!["d"])
        assertEquals("\"a\\\"b\\n\"", Json.quote("a\"b\n"))
        assertEquals(9.8, Cvss.score("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"))
        assertEquals(6.1, Cvss.score("CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N"))
        assertEquals(10.0, Cvss.score("CVSS:3.0/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H"))
        assertNull(Cvss.score("CVSS:4.0/AV:N"))
    }

    @Test
    fun `scan finds vulnerable direct and transitive artifacts`() {
        workspace()
        val (r, libs) = libraries()
        val requests = ArrayList<String>()
        val transport = object : OsvTransport {
            override fun post(url: String, json: String): String {
                requests += json
                val queries = Json.parse(json).arr("queries")
                val results = queries.map { q ->
                    val name = q.obj("package").str("name")
                    val version = q.str("version")
                    if (name == "org:lib" && version != "1") """{"vulns":[{"id":"GHSA-1","modified":"2026-01-01"}]}""" else "{}"
                }
                return """{"results":[${results.joinToString(",")}]}"""
            }

            override fun get(url: String): String {
                assertTrue(url.endsWith("/vulns/GHSA-1"))
                return """{"id":"GHSA-1","modified":"2026-01-01","aliases":["CVE-2026-0001"],"summary":"Remote code execution",
                    "severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"}],
                    "affected":[{"package":{"ecosystem":"Maven","name":"org:lib"},"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"0.9.1"}]}]}]}"""
            }
        }
        val targets = VulnerabilityScanner.targets(libs.values) { r.workspacePom(it.groupId, it.artifactId, it.version) != null }
        val result = VulnerabilityScanner.scan(OsvClient(transport), targets)
        assertEquals(1, requests.size)
        assertEquals(listOf("org:lib:0.8", "org:lib:0.9"), result.vulnerable.map { it.artifact.toString() }.sorted())
        val transitive = result.vulnerable.single { it.artifact.version == "0.9" }
        assertEquals(false, transitive.direct)
        assertEquals("0.9.1", transitive.fixVersion)
        assertEquals(VulnSeverity.CRITICAL, transitive.severity)
        assertEquals("CVE-2026-0001", transitive.vulnerabilities.single().cve)
        assertEquals(2, result.count(VulnSeverity.CRITICAL))

        // Fixing everything raises both modules to at least the fixed version.
        applyAll(VersionAligner.plan(r.ws, r, VulnerabilityScanner.fixRequests(result.vulnerable, libs)))
        versionsByModule().values.forEach { assertTrue(it, MavenVersion(it!!) >= MavenVersion("0.9.1")) }
    }
}
