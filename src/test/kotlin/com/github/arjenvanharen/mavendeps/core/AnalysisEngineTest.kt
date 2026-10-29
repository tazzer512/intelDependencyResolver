package com.github.arjenvanharen.mavendeps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class AnalysisEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root: Path get() = tmp.root.toPath().toAbsolutePath().normalize()

    private fun write(rel: String, content: String): Path {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, content.trimIndent())
        return p
    }

    private fun pom(body: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
        $body
        </project>
    """.lines().joinToString("\n") { it.trim() }.trim()

    private fun AnalysisResult.problemsOf(rel: String) = poms.getValue(root.resolve(rel)).problems
    private fun AnalysisResult.messages(rel: String) = problemsOf(rel).map { it.message }

    private fun multiModule() {
        write(
            "pom.xml",
            pom(
                """
                <groupId>com.example</groupId>
                <artifactId>root</artifactId>
                <version>1.0</version>
                <packaging>pom</packaging>
                <modules><module>a</module><module>b</module></modules>
                <properties><lib.version>2.0</lib.version></properties>
                <dependencyManagement><dependencies>
                  <dependency><groupId>org.lib</groupId><artifactId>lib</artifactId><version>${'$'}{lib.version}</version></dependency>
                </dependencies></dependencyManagement>
                """,
            ),
        )
        write(
            "a/pom.xml",
            pom(
                """
                <parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent>
                <artifactId>a</artifactId>
                <dependencies>
                  <dependency><groupId>org.lib</groupId><artifactId>lib</artifactId></dependency>
                </dependencies>
                """,
            ),
        )
        write(
            "b/pom.xml",
            pom(
                """
                <parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent>
                <artifactId>b</artifactId>
                <name>${'$'}{lib.version}</name>
                """,
            ),
        )
    }

    @Test
    fun `clean multi-module project has no problems`() {
        multiModule()
        val result = AnalysisEngine().refresh(listOf(root), RefreshMode.RESCAN)
        assertEquals(3, result.stats.total)
        assertEquals(listOf(root.resolve("pom.xml")), result.roots)
        assertEquals(emptyList<Problem>(), result.poms.values.flatMap { it.problems })
    }

    @Test
    fun `reports missing modules, unmanaged versions and undefined properties`() {
        multiModule()
        write("pom.xml", Files.readString(root.resolve("pom.xml")).replace("<module>b</module>", "<module>b</module><module>c</module>"))
        write(
            "b/pom.xml",
            pom(
                """
                <parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent>
                <artifactId>b</artifactId>
                <dependencies><dependency><groupId>org.other</groupId><artifactId>x</artifactId></dependency></dependencies>
                <name>${'$'}{nope}</name>
                """,
            ),
        )
        val result = AnalysisEngine().refresh(listOf(root), RefreshMode.RESCAN)
        assertTrue(result.messages("pom.xml").single().startsWith("Module 'c' does not exist"))
        val b = result.messages("b/pom.xml")
        assertTrue(b.toString(), b.any { it.contains("org.other:x:jar") && it.contains("version") })
        assertTrue(b.toString(), b.any { it.contains("\${nope}") })
    }

    @Test
    fun `syntax error is reported with position and suppresses structure noise`() {
        write("pom.xml", pom("<groupId>g</groupId>\n<artifactId>a</artifactId>\n<version>1</version>\n<name>broken</nme>"))
        val problems = AnalysisEngine().refresh(listOf(root), RefreshMode.RESCAN).problemsOf("pom.xml")
        assertEquals(1, problems.size)
        assertEquals(Category.XML_SYNTAX, problems[0].category)
        assertEquals(7, problems[0].pos.line)
    }

    @Test
    fun `structure validation`() {
        write(
            "pom.xml",
            pom(
                """
                <groupId>g</groupId>
                <version>1</version>
                <modules><module>x</module></modules>
                <bogus/>
                <dependencies>
                  <dependency><groupId>d</groupId><artifactId>e</artifactId><version>1</version></dependency>
                  <dependency><groupId>d</groupId><artifactId>e</artifactId><version>2</version><scope>wrong</scope></dependency>
                </dependencies>
                """,
            ),
        )
        val messages = AnalysisEngine().refresh(listOf(root), RefreshMode.RESCAN).messages("pom.xml")
        listOf("'artifactId' is missing", "Unrecognised tag <bogus>", "require 'pom' as packaging", "must be unique", "scope", "Module 'x' does not exist")
            .forEach { expected -> assertTrue("$expected not in $messages", messages.any { it.contains(expected) }) }
    }

    @Test
    fun `external parent suppresses inherited checks`() {
        write(
            "pom.xml",
            pom(
                """
                <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.5.0</version><relativePath/></parent>
                <artifactId>app</artifactId>
                <dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter</artifactId></dependency></dependencies>
                <properties><x>${'$'}{java.version}${'$'}{spring.version}</x></properties>
                """,
            ),
        )
        assertEquals(emptyList<Problem>(), AnalysisEngine().refresh(listOf(root), RefreshMode.RESCAN).problemsOf("pom.xml"))
    }

    @Test
    fun `relativePath pointing at the wrong pom is a warning`() {
        multiModule()
        write("a/pom.xml", Files.readString(root.resolve("a/pom.xml")).replace("<artifactId>root</artifactId>", "<artifactId>other</artifactId>"))
        val messages = AnalysisEngine().refresh(listOf(root), RefreshMode.RESCAN).messages("a/pom.xml")
        assertTrue(messages.toString(), messages.any { it.startsWith("relativePath points at com.example:root:1.0") })
    }

    @Test
    fun `parent may use properties defined by its children`() {
        multiModule()
        write("pom.xml", Files.readString(root.resolve("pom.xml")).replace("</dependencyManagement>", "</dependencyManagement><build><finalName>${'$'}{app.name}</finalName></build>"))
        val a = write("a/pom.xml", Files.readString(root.resolve("a/pom.xml")).replace("<artifactId>a</artifactId>", "<artifactId>a</artifactId><properties><app.name>A</app.name></properties>"))
        val engine = AnalysisEngine()
        assertEquals(emptyList<String>(), engine.refresh(listOf(root), RefreshMode.RESCAN).messages("pom.xml"))

        // Dropping the property from the child must re-validate the (unchanged) parent.
        write("a/pom.xml", Files.readString(a).replace("<properties><app.name>A</app.name></properties>", ""))
        val result = engine.refresh(listOf(root), RefreshMode.INCREMENTAL, listOf(a))
        assertTrue(result.messages("pom.xml").toString(), result.messages("pom.xml").any { it.contains("\${app.name}") })
    }

    @Test
    fun `duplicate coordinates across modules`() {
        multiModule()
        write("b/pom.xml", Files.readString(root.resolve("b/pom.xml")).replace("<artifactId>b</artifactId>", "<artifactId>a</artifactId>"))
        val result = AnalysisEngine().refresh(listOf(root), RefreshMode.RESCAN)
        assertTrue(result.messages("a/pom.xml").any { it.contains("com.example:a:1.0 are also used by") })
        assertTrue(result.messages("b/pom.xml").any { it.contains("com.example:a:1.0 are also used by") })
    }

    @Test
    fun `incremental refresh parses only changed files and revalidates dependents`() {
        multiModule()
        val engine = AnalysisEngine()
        engine.refresh(listOf(root), RefreshMode.RESCAN)

        val b = write("b/pom.xml", Files.readString(root.resolve("b/pom.xml")).replace("<name>", "<description>x</description><name>"))
        var result = engine.refresh(listOf(root), RefreshMode.INCREMENTAL, listOf(b))
        assertEquals(1, result.stats.parsed)
        assertEquals(2, result.stats.reused)
        assertEquals(2, result.stats.revalidated) // b and its parent, not sibling a

        // Removing a managed version in the root must surface in module a, which itself did not change.
        val rootPom = write("pom.xml", Files.readString(root.resolve("pom.xml")).replace("<artifactId>lib</artifactId>", "<artifactId>lib2</artifactId>"))
        result = engine.refresh(listOf(root), RefreshMode.INCREMENTAL, listOf(rootPom))
        assertEquals(1, result.stats.parsed)
        assertEquals(3, result.stats.revalidated)
        assertTrue(result.messages("a/pom.xml").any { it.contains("org.lib:lib:jar") })

        // Unchanged incremental refresh does no I/O-driven work.
        result = engine.refresh(listOf(root), RefreshMode.INCREMENTAL)
        assertEquals(0, result.stats.parsed)
        assertEquals(0, result.stats.revalidated)
    }

    @Test
    fun `new module file is picked up once referenced`() {
        multiModule()
        val engine = AnalysisEngine()
        val rootPom = write("pom.xml", Files.readString(root.resolve("pom.xml")).replace("<module>b</module>", "<module>b</module><module>c</module>"))
        engine.refresh(listOf(root), RefreshMode.RESCAN)
        assertTrue(root.resolve("c/pom.xml") in engine.missingPaths)

        val c = write("c/pom.xml", pom("<parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent><artifactId>c</artifactId>"))
        val result = engine.refresh(listOf(root), RefreshMode.INCREMENTAL, listOf(c))
        assertEquals(4, result.stats.total)
        assertEquals(emptyList<String>(), result.messages("pom.xml"))
        assertTrue(rootPom in engine.knownPaths)
    }

    @Test
    fun `disk cache round trip avoids re-parsing`() {
        multiModule()
        val first = AnalysisEngine()
        val firstResult = first.refresh(listOf(root), RefreshMode.RESCAN)
        val cacheFile = root.resolve("cache/analysis.bin")
        CacheStore.write(cacheFile, first.snapshot())

        val restored = CacheStore.read(cacheFile)
        assertEquals(first.snapshot().toList(), restored)

        val second = AnalysisEngine()
        second.restore(restored)
        val result = second.refresh(listOf(root), RefreshMode.RESCAN)
        assertEquals(0, result.stats.parsed)
        assertEquals(3, result.stats.reused)
        assertEquals(firstResult.poms.mapValues { it.value.problems }, result.poms.mapValues { it.value.problems })

        val full = second.refresh(listOf(root), RefreshMode.FULL)
        assertEquals(3, full.stats.parsed)
    }

    @Test
    fun `corrupt cache is ignored`() {
        val f = write("cache.bin", "garbage")
        assertEquals(emptyList<PomEntry>(), CacheStore.read(f))
    }
}
