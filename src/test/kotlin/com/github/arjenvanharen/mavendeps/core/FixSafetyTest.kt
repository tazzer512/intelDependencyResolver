package com.github.arjenvanharen.mavendeps.core

import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.dep
import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.deps
import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.pom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Fixes that stick (classifier pins, verified upgrade targets) and the check for new problems before a fix is applied. */
class FixSafetyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f by lazy { Fixture(tmp.root.toPath().toAbsolutePath().normalize()) }

    private fun libraries(): Map<String, Library> {
        val r = f.resolver()
        return DependencyIndex.libraries(DependencyIndex.resolveAll(r, r.ws.entries.keys)).associateBy { it.ga }
    }

    private fun applyAll(items: List<PlanItem>) = items.filter { it.selected }.forEach { f.apply(it.pom, it.fix) }

    @Test
    fun `a transitive artifact with a classifier is pinned with its classifier, and the fix sticks`() {
        // The netty case: io.netty:netty-transport-native-epoll:4.1.94.Final:linux-x86_64, only transitive.
        f.write("pom.xml", pom("<groupId>com.example</groupId><artifactId>app</artifactId><version>1.0</version>${deps("org:brings:1")}"))
        f.artifact("org:brings:1", "<dependencies>${dep("io.netty:epoll:4.1.94.Final", "<classifier>linux-x86_64</classifier>")}</dependencies>")
        listOf("4.1.94.Final", "4.1.100.Final").forEach { f.artifact("io.netty:epoll:$it") }

        fun resolved() = libraries().getValue("io.netty:epoll").usages.filter { it.node.status == NodeStatus.INCLUDED }.map { it.node.version }
        assertEquals(listOf("4.1.94.Final"), resolved())

        val r = f.resolver()
        val request = AlignRequest("io.netty", "epoll", "4.1.100.Final", libraries().getValue("io.netty:epoll").usages)
        val plan = VersionAligner.plan(r.ws, r, listOf(request))
        assertTrue(plan.single().description.contains("(linux-x86_64)"))
        applyAll(plan)
        assertTrue(f.read("pom.xml").contains("<classifier>linux-x86_64</classifier>"))
        assertEquals(listOf("4.1.100.Final"), resolved())

        // Planning again finds nothing left to do (it used to plan the same unmatched pin forever).
        val r2 = f.resolver()
        assertTrue(VersionAligner.plan(r2.ws, r2, listOf(request.copy(usages = libraries().getValue("io.netty:epoll").usages))).isEmpty())
    }

    @Test
    fun `a managed entry with another classifier is not taken for the pin`() {
        val path = f.write(
            "pom.xml",
            pom("<groupId>g</groupId><artifactId>a</artifactId><version>1</version><dependencyManagement>${deps("org:x:1")}</dependencyManagement>"),
        )
        f.apply(path, AddDependency("t", null, "org", "x", "2", managed = true, classifier = "linux"))
        val managed = PomParser.parse(f.read("pom.xml")).model.dependencies.map { "${it.artifactId}:${it.version}:${it.classifier}" }
        assertEquals(listOf("x:1:null", "x:2:linux"), managed)
        f.apply(path, AddDependency("t", null, "org", "x", "3", managed = true, classifier = "linux"))
        assertEquals(listOf("x:1:null", "x:3:linux"), PomParser.parse(f.read("pom.xml")).model.dependencies.map { "${it.artifactId}:${it.version}:${it.classifier}" })
    }

    private fun advisory(id: String, events: String) = """{"id":"$id","modified":"2026-01-01","summary":"s",
        "affected":[{"package":{"ecosystem":"Maven","name":"org:lib"},"ranges":[{"type":"ECOSYSTEM","events":[$events]}]}]}"""

    /** osv.dev with advisories [records] (id to JSON) and their affected versions by [affects]. */
    private fun client(records: Map<String, String>, affects: (String) -> List<String>) = OsvClient(object : OsvTransport {
        override fun post(url: String, json: String): String {
            val results = Json.parse(json).arr("queries").map { q ->
                """{"vulns":[${affects(q.str("version")!!).joinToString(",") { """{"id":"$it","modified":"2026-01-01"}""" }}]}"""
            }
            return """{"results":[${results.joinToString(",")}]}"""
        }

        override fun get(url: String): String = records.getValue(url.substringAfterLast('/'))
    })

    @Test
    fun `an upgrade target with advisories of its own is raised until it has none`() {
        val records = mapOf(
            "GHSA-1" to advisory("GHSA-1", """{"introduced":"0"},{"fixed":"1.1.Final"}"""),
            "GHSA-2" to advisory("GHSA-2", """{"introduced":"1.1.Final"},{"fixed":"1.2.Final"}"""),
        )
        val c = client(records) { v ->
            when (MavenVersion(v)) {
                MavenVersion("1.0.Final") -> listOf("GHSA-1")
                MavenVersion("1.1.Final") -> listOf("GHSA-2")
                else -> emptyList()
            }
        }
        val vulns = c.query(listOf(ArtifactVersion("org", "lib", "1.0.Final"))).values.single()
        val a = VulnerableArtifact(ArtifactVersion("org", "lib", "1.0.Final"), vulns, emptyList())
        assertEquals("1.1.Final", a.fixVersion)
        val verified = VulnerabilityScanner.verifyUpgrades(c, listOf(a)).single()
        assertEquals("1.2.Final", verified.fixVersion)
        assertEquals("1.1.Final", verified.advisoryFixVersion)
        assertFalse(verified.fixStillVulnerable)

        // A target whose own advisory has no fix stays the best known upgrade, flagged.
        val stuck = client(records + ("GHSA-3" to advisory("GHSA-3", """{"introduced":"1.1.Final"}"""))) { v ->
            if (MavenVersion(v) == MavenVersion("1.0.Final")) listOf("GHSA-1") else listOf("GHSA-3")
        }
        val flagged = VulnerabilityScanner.verifyUpgrades(stuck, listOf(a)).single()
        assertEquals("1.1.Final", flagged.fixVersion)
        assertTrue(flagged.fixStillVulnerable)
    }

    private fun workspace() {
        val parent = "<parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent>"
        f.write(
            "pom.xml",
            pom("<groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version><packaging>pom</packaging>" +
                "<properties><lib.version>1</lib.version></properties><modules><module>a</module><module>b</module></modules>"),
        )
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId><dependencies>${dep("org:lib:\${lib.version}")}</dependencies>"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId>${deps("org:lib:1")}"))
        listOf("1", "2").forEach { f.artifact("org:lib:$it") }
    }

    private fun check(path: String, fix: QuickFix): FixCheck {
        val p = f.path(path)
        val edits = FixApplier.edits(fix.anchored(p), p) { java.nio.file.Files.readString(it) }
        val texts = edits.mapValues { (file, list) -> FixApplier.applyTo(java.nio.file.Files.readString(file), list) }
        return FixVerifier.check(f.engine().first.workspaceSnapshot(), texts, f.repo)
    }

    private fun dependencyIn(path: String): ElementRef =
        ElementRef(null, "dependency", PomParser.parse(f.read(path)).model.dependencies.first().pos)

    @Test
    fun `a change that adds a problem or a version conflict is caught before it is applied`() {
        workspace()
        // A version that uses a property nobody defines.
        val broken = check("a/pom.xml", SetChild("Set version", dependencyIn("a/pom.xml"), "version", "\${missing.version}"))
        assertFalse(broken.clean)
        assertTrue(broken.newProblems.toString(), broken.newProblems.any { (p, problem) -> p == f.path("a/pom.xml") && "missing.version" in problem.message })

        // Upgrading only b: org:lib resolves to 1 in a and 2 in b.
        val conflict = check("b/pom.xml", SetChild("Set version", dependencyIn("b/pom.xml"), "version", "2"))
        assertEquals(listOf("org:lib: 1 and 2"), conflict.newConflicts)
        assertTrue(conflict.newProblems.isEmpty())

        // Upgrading both through the property and b's literal keeps them aligned.
        val aligned = check("b/pom.xml", CompositeFix("Both", listOf(
            SetChild("Set version", dependencyIn("b/pom.xml"), "version", "2"),
            DefineProperty("Set", f.path("pom.xml"), "lib.version", "2"),
        )))
        assertTrue(aligned.clean)
    }

    @Test
    fun `positions that move with an edit do not count as new problems`() {
        workspace()
        // A duplicate declaration in b: the same warning before and after an edit that moves its lines.
        f.write("b/pom.xml", pom("<parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent>" +
            "<artifactId>b</artifactId>${deps("org:lib:1", "org:lib:1")}"))
        assertTrue(FixVerifier.problems(f.engine().first.workspaceSnapshot()).getValue(f.path("b/pom.xml")).isNotEmpty())
        val moved = check("b/pom.xml", SetChild("Scope", dependencyIn("b/pom.xml"), "scope", "compile"))
        assertTrue(moved.newProblems.toString(), moved.clean)
        assertEquals(0, moved.resolvedProblems)
    }
}
