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
import java.nio.file.Files
import java.nio.file.attribute.FileTime

/** Regressions for the findings of the 2026-11-02 audit (see docs/AUDIT.md). */
class AuditFixesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f by lazy { Fixture(tmp.root.toPath().toAbsolutePath().normalize()) }

    private val parent = "<parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent>"

    // S1
    @Test(timeout = 10_000)
    fun `many references to one large property stay bounded`() {
        val props = mapOf("big" to "x".repeat(60_000))
        val value = "\${big}".repeat(5_000)
        val out = Workspace.substitute(value, props)
        assertTrue(out.length <= 64 * 1024)
        assertTrue(out.startsWith("xxxx"))
        // Unchanged behaviour for ordinary values: undefined and nested references are kept.
        assertEquals("1.0-\${missing}-\${a\${b}}", Workspace.substitute("\${v}-\${missing}-\${a\${b}}", mapOf("v" to "1.0", "b" to "x")))
        assertEquals("3", Workspace.substitute("\${a}", mapOf("a" to "\${b}", "b" to "3")))
    }

    // S2
    @Test(timeout = 30_000)
    fun `a module folder linking back to its parent is followed once`() {
        f.write("pom.xml", pom("<groupId>g</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>loop</module></modules>"))
        Files.createSymbolicLink(f.path("loop"), f.root)
        val result = f.engine().second
        assertEquals(1, result.poms.size)
    }

    // S2
    @Test
    fun `the number of POMs is bounded`() {
        val modules = (1..5).joinToString("") { "<module>m$it</module>" }
        f.write("pom.xml", pom("<groupId>g</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules>$modules</modules>"))
        (1..5).forEach { f.write("m$it/pom.xml", pom("<groupId>g</groupId><artifactId>m$it</artifactId><version>1</version>")) }
        val result = AnalysisEngine(maxPoms = 3).refresh(listOf(f.root), RefreshMode.FULL)
        assertEquals(3, result.poms.size)
        assertTrue(result.poms.getValue(f.path("pom.xml")).problems.any { "More than 3 POMs" in it.message })
    }

    // S3
    @Test(timeout = 60_000)
    fun `deep chains of BOM imports do not overflow the stack`() {
        val depth = 3_000
        fun bom(i: Int) = "<dependencyManagement><dependencies>" +
            dep("org:bom$i:1", "<type>pom</type><scope>import</scope>") + dep("org:lib$i:1") +
            "</dependencies></dependencyManagement><packaging>pom</packaging>"
        for (i in 0 until depth) f.artifact("org:bom$i:1", bom(i + 1))
        f.write("pom.xml", pom("<groupId>g</groupId><artifactId>app</artifactId><version>1</version>" +
            "<dependencyManagement><dependencies>${dep("org:bom0:1", "<type>pom</type><scope>import</scope>")}</dependencies></dependencyManagement>"))
        // bom<i> manages lib<i+1> and imports bom<i+1>.
        val managed = f.resolver().effective(f.path("pom.xml")).managed.keys
        assertTrue("org:lib1:jar" in managed)
        assertTrue("org:lib${DependencyResolver.MAX_IMPORT_DEPTH}:jar" in managed)
        // Imports nested deeper than the limit are ignored rather than recursed into.
        assertFalse("org:lib$depth:jar" in managed)
    }

    // B1
    @Test
    fun `an analysis is current only for the file it read`() {
        val path = f.write("pom.xml", pom("<groupId>g</groupId><artifactId>a</artifactId><version>1</version>"))
        val result = f.engine().second
        assertTrue(result.isCurrent(path))
        Files.writeString(path, Files.readString(path).replace("<version>1</version>", "<version>2</version>"))
        Files.setLastModifiedTime(path, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 5_000))
        assertFalse(result.isCurrent(path))
        assertFalse(result.isCurrent(f.path("other/pom.xml")))
    }

    // B3
    @Test
    fun `a project with an inherited groupId can still be its own parent`() {
        val path = f.write("pom.xml", pom("<parent><groupId>g</groupId><artifactId>a</artifactId><version>1</version></parent><artifactId>a</artifactId>"))
        assertTrue(f.engine().second.poms.getValue(path).problems.any { it.message == "Project uses its own coordinates as parent" })
    }

    // B4
    @Test
    fun `no version property is suggested under a name that cannot be an element`() {
        f.write("pom.xml", pom("<groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version><packaging>pom</packaging><modules><module>a</module><module>b</module></modules>"))
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId>${deps("org.x:3d:1.0")}"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId>${deps("org.x:3d:1.0")}"))
        val result = f.engine().second
        assertTrue(result.poms.values.flatMap { it.problems }.none { it.category == Category.SUGGESTIONS })
        // A valid name is still suggested.
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId>${deps("org.x:lib:1.0")}"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId>${deps("org.x:lib:1.0")}"))
        assertTrue(f.engine().second.poms.values.flatMap { it.problems }.any { it.message.contains("lib.version") })
    }

    // P1
    @Test
    fun `a library module used by many modules is scanned once per check`() {
        val modules = (1..4).joinToString("") { "<module>m$it</module>" }
        f.write("pom.xml", pom("<groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version><packaging>pom</packaging><modules><module>lib</module>$modules</modules>"))
        f.write("lib/pom.xml", pom("$parent<artifactId>lib</artifactId>${deps("org:vuln:1")}"))
        f.write("lib/src/main/java/L.java", "import org.vuln.Bad;\nclass L {}")
        (1..4).forEach { f.write("m$it/pom.xml", pom("$parent<artifactId>m$it</artifactId>${deps("com.example:lib:1.0")}")) }
        CodeImpactFixtures.jar(f, "org:vuln:1", mapOf("org/vuln/Bad.class" to CodeImpactFixtures.classFile("org/vuln/Bad")))
        val r = f.resolver()
        val libs = DependencyIndex.libraries(DependencyIndex.resolveAll(r, r.ws.entries.keys))
        val targets = VulnerabilityScanner.targets(libs) { r.workspacePom(it.groupId, it.artifactId, it.version) != null }
        val a = targets.keys.single()
        val v = Vulnerability("GHSA-1", emptyList(), "", VulnSeverity.HIGH, null, emptyMap(), null, null, emptyList(), "`Bad` is bad")
        var scans = 0
        val index = object : ClassIndex() {
            override fun sources(moduleDir: java.nio.file.Path): Code = super.sources(moduleDir).also { scans++ }
        }
        val impact = CodeImpactAnalyzer(f.repo, index).analyze(listOf(VulnerableArtifact(a, listOf(v), targets.getValue(a))), libs, { g, ar, ver -> r.workspacePom(g, ar, ver) })
        assertEquals(Impact.AFFECTED, impact.values.single().impact)
        // lib and m1..m4 once each, although lib is on the classpath of all four.
        assertEquals(5, scans)
    }
}
