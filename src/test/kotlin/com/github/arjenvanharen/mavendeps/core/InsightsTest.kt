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
import java.nio.file.Files

class InsightsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f by lazy { Fixture(tmp.root.toPath().toAbsolutePath().normalize()) }

    @Test
    fun `maven version ordering`() {
        val ordered = listOf("1.0-alpha-1", "1.0-beta2", "1.0-M1", "1.0-rc1", "1.0-SNAPSHOT", "1.0", "1.0-sp1", "1.0-xyz", "1.0.1", "1.2", "1.10", "2.0.0-RC1", "2.0.0")
        assertEquals(ordered, ordered.shuffled(java.util.Random(7)).sortedWith(MavenVersion.COMPARATOR))
        assertEquals(MavenVersion("1.0"), MavenVersion("1.0.0"))
        assertEquals(MavenVersion("1.0"), MavenVersion("1-final"))
        assertEquals(MavenVersion("1.0").hashCode(), MavenVersion("1.0.0").hashCode())
        assertTrue(MavenVersion("1.0-1") > MavenVersion("1.0"))
        assertTrue(MavenVersion("2.0.0-RC1").isPreRelease)
        assertTrue(MavenVersion("1.0-SNAPSHOT").isPreRelease)
        assertFalse(MavenVersion("1.0.Final").isPreRelease)
    }

    @Test
    fun `local versions and newer versions`() {
        listOf("1.9", "1.10", "2.0-RC1", "1.2").forEach { f.artifact("org.x:lib:$it") }
        Files.createDirectories(f.path(".m2/org/x/lib/3.0")) // failed download: no POM
        val repo = f.repo
        assertEquals(listOf("1.2", "1.9", "1.10", "2.0-RC1"), repo.versions("org.x", "lib"))
        assertEquals(listOf("1.9", "1.10"), repo.newerVersions("org.x", "lib", "1.2"))
        assertEquals(listOf("2.0-RC1"), repo.newerVersions("org.x", "lib", "2.0-beta1"))
        assertEquals(emptyList<String>(), repo.versions("org.x", "nope"))
    }

    @Test
    fun `layered layout puts dependencies below their users and survives cycles`() {
        val layers = LayeredLayout.layers(listOf("app", "core", "util", "api"), listOf("app" to "core", "core" to "util", "app" to "api", "api" to "util"))
        assertEquals(listOf("util"), layers[0])
        assertEquals(setOf("core", "api"), layers[1].toSet())
        assertEquals(listOf("app"), layers[2])

        val cyclic = LayeredLayout.layers(listOf("a", "b", "c"), listOf("a" to "b", "b" to "c", "c" to "a"))
        assertEquals(setOf("a", "b", "c"), cyclic.flatten().toSet())
        assertEquals(3, cyclic.flatten().size)
    }

    @Test
    fun `module graph has dependency, parent, aggregation and import edges`() {
        val parent = "<parent><groupId>g</groupId><artifactId>root</artifactId><version>1</version></parent>"
        f.write("pom.xml", pom("<groupId>g</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>bom</module><module>a</module><module>b</module></modules>"))
        f.write("bom/pom.xml", pom("$parent<artifactId>bom</artifactId><packaging>pom</packaging>"))
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId><dependencyManagement><dependencies>${dep("g:bom:1", "<type>pom</type><scope>import</scope>")}</dependencies></dependencyManagement>"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId>${deps("g:a:\${project.version}")}"))
        val r = f.resolver()
        val graph = ModuleGraph.build(r.ws, r)
        fun edges(kind: EdgeKind) = graph.edges.filter { it.kind == kind }.map { f.root.relativize(it.from).toString() to f.root.relativize(it.to).toString() }.toSet()
        assertEquals(setOf("b/pom.xml" to "a/pom.xml", "a/pom.xml" to "bom/pom.xml"), edges(EdgeKind.DEPENDENCY))
        assertEquals("import", graph.outgoing(f.path("a/pom.xml"), setOf(EdgeKind.DEPENDENCY)).single().scope)
        assertEquals(setOf("bom/pom.xml", "a/pom.xml", "b/pom.xml").map { it to "pom.xml" }.toSet(), edges(EdgeKind.PARENT))
        assertEquals(setOf("bom/pom.xml", "a/pom.xml", "b/pom.xml").map { "pom.xml" to it }.toSet(), edges(EdgeKind.MODULE))
        assertEquals(4, graph.nodes.size)
    }

    @Test
    fun `version hints show managed and property versions and newer local versions`() {
        f.write(
            "pom.xml",
            pom(
                "<groupId>g</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>a</module></modules>" +
                    "<properties><lib.version>1.2</lib.version></properties>" +
                    "<dependencyManagement><dependencies>${dep("org.x:managed:3.0")}</dependencies></dependencyManagement>",
            ),
        )
        f.write("a/pom.xml", pom("<parent><groupId>g</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>a</artifactId>${deps("org.x:lib:\${lib.version}", "org.x:managed", "org.x:plain:1")}"))
        listOf("1.2", "1.5").forEach { f.artifact("org.x:lib:$it") }
        f.artifact("org.x:managed:3.0")
        val r = f.resolver()
        val hints = VersionHints.forPom(r.ws, r, f.repo, f.path("a/pom.xml")).associateBy { it.artifactId }
        assertEquals("= 1.2  ↑ 1.5 in local repo", hints.getValue("lib").label(r.ws))
        assertEquals("3.0 · managed by root", hints.getValue("managed").label(r.ws))
        assertEquals(f.path("pom.xml"), hints.getValue("managed").managedBy)
        assertNull(hints.getValue("plain").label(r.ws))
        assertEquals(3, hints.size)
    }

    @Test
    fun `hint anchor follows the version or artifactId`() {
        val text = "<dependency><groupId>g</groupId><artifactId>a</artifactId><version>1</version></dependency><dependency><groupId>g</groupId><artifactId>b</artifactId></dependency>"
        val first = "<dependency>".length
        assertEquals(text.indexOf("</version>") + "</version>".length, VersionHints.anchor(text, first))
        val second = text.indexOf("<dependency>", 1) + "<dependency>".length
        assertEquals(text.lastIndexOf("</artifactId>") + "</artifactId>".length, VersionHints.anchor(text, second))
    }
}
