package com.github.arjenvanharen.mavendeps.core

import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.dep
import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.pom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Analyse → apply the offered fix → re-analyse incrementally: the problem must be gone. */
class QuickFixFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f by lazy { Fixture(tmp.root.toPath().toAbsolutePath().normalize()) }

    private val parent = "<parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent>"

    private fun rootPom(extra: String = "", modules: String = "<module>a</module>") = pom(
        """
        <groupId>com.example</groupId>
        <artifactId>root</artifactId>
        <version>1.0</version>
        <packaging>pom</packaging>
        <modules>$modules</modules>
        $extra
        """,
    )

    /** Applies the fix titled [title] of the problem containing [message] in [rel] and returns the fresh result. */
    private fun fix(engine: AnalysisEngine, result: AnalysisResult, rel: String, message: String, title: String, input: String? = null): AnalysisResult {
        val owner = f.path(rel)
        val problem = result.poms.getValue(owner).problems.firstOrNull { it.message.contains(message) }
            ?: throw AssertionError("no '$message' in ${result.poms.getValue(owner).problems.map { it.message }}")
        val fix = problem.fixes.firstOrNull { it.title.contains(title) } ?: throw AssertionError("no fix '$title' in ${problem.fixes.map { it.title }}")
        val touched = FixApplier.files(fix, owner)
        f.apply(owner, fix, input)
        return engine.refresh(listOf(f.root), RefreshMode.INCREMENTAL, touched)
    }

    private fun AnalysisResult.messages(rel: String) = poms.getValue(f.path(rel)).problems.map { it.message }

    @Test
    fun `remove missing module`() {
        f.write("pom.xml", rootPom(modules = "<module>a</module><module>gone</module>"))
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId>"))
        val (engine, result) = f.engine()
        val after = fix(engine, result, "pom.xml", "Module 'gone' does not exist", "Remove module 'gone'")
        assertEquals(emptyList<String>(), after.messages("pom.xml"))
        assertTrue(f.read("pom.xml").contains("<modules><module>a</module></modules>"))
    }

    @Test
    fun `add a version to an unmanaged dependency`() {
        f.write("pom.xml", rootPom())
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId><dependencies>${dep("org.lib:lib")}</dependencies>"))
        val (engine, result) = f.engine()
        val after = fix(engine, result, "a/pom.xml", "is missing and not managed", "Add version", input = "3.1")
        assertEquals(emptyList<String>(), after.messages("a/pom.xml"))
        assertTrue(f.read("a/pom.xml").contains("<version>3.1</version>"))
    }

    @Test
    fun `define an undefined property in this pom or in the root pom`() {
        f.write("pom.xml", rootPom())
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId><name>\${app.title}</name><description>\${other}</description>"))
        val (engine, result) = f.engine()
        val titles = result.poms.getValue(f.path("a/pom.xml")).problems.first().fixes.map { it.title }
        assertEquals(listOf("Define property 'app.title' in this POM…", "Define property 'app.title' in root POM com.example:root…"), titles)

        var after = fix(engine, result, "a/pom.xml", "app.title", "in this POM", input = "My App")
        assertTrue(f.read("a/pom.xml").contains("<app.title>My App</app.title>"))
        after = fix(engine, after, "a/pom.xml", "other", "in root POM", input = "x")
        assertTrue(f.read("pom.xml").contains("<properties>\n        <other>x</other>\n    </properties>"))
        assertEquals(emptyList<String>(), after.messages("a/pom.xml"))
    }

    @Test
    fun `relativePath fixes`() {
        f.write("pom.xml", rootPom(modules = "<module>parent</module><module>a</module>"))
        f.write("parent/pom.xml", pom("<groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version><packaging>pom</packaging>"))
        // a's default relativePath (../pom.xml) points at root, but it declares 'parent' as its parent.
        f.write("a/pom.xml", pom(parent.replace(">root<", ">parent<") + "<artifactId>a</artifactId>"))
        val (engine, result) = f.engine()
        val problem = result.poms.getValue(f.path("a/pom.xml")).problems.single()
        assertEquals(listOf("Point relativePath at ../parent/pom.xml", "Use an empty <relativePath/>"), problem.fixes.map { it.title })
        val after = fix(engine, result, "a/pom.xml", "relativePath points at", "Point relativePath")
        assertEquals(emptyList<String>(), after.messages("a/pom.xml"))
        assertTrue(f.read("a/pom.xml").contains("<relativePath>../parent/pom.xml</relativePath></parent>"))
    }

    @Test
    fun `parent version mismatch offers the local version`() {
        f.write("pom.xml", rootPom())
        f.write("a/pom.xml", pom(parent.replace("1.0", "0.9") + "<artifactId>a</artifactId>"))
        val (engine, result) = f.engine()
        val after = fix(engine, result, "a/pom.xml", "does not match version 1.0", "Change parent version to '1.0'")
        assertEquals(emptyList<String>(), after.messages("a/pom.xml"))
    }

    @Test
    fun `parent packaging is fixed in the parent pom`() {
        f.write("pom.xml", rootPom().replace("<packaging>pom</packaging>", ""))
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId>"))
        val (engine, result) = f.engine()
        val after = fix(engine, result, "a/pom.xml", "Invalid packaging for parent POM", "Set packaging of the parent POM")
        assertEquals(emptyList<String>(), after.messages("a/pom.xml"))
        assertTrue(f.read("pom.xml").contains("<version>1.0</version>\n    <packaging>pom</packaging>"))
    }

    @Test
    fun `structure fix survives the disk cache`() {
        f.write("pom.xml", rootPom(extra = "<bogus/>"))
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId>"))
        val (first, _) = f.engine()
        val cache = f.path("cache.bin")
        CacheStore.write(cache, first.snapshot())
        val engine = AnalysisEngine()
        engine.restore(CacheStore.read(cache))
        val result = engine.refresh(listOf(f.root), RefreshMode.RESCAN)
        assertEquals(0, result.stats.parsed)
        val after = fix(engine, result, "pom.xml", "Unrecognised tag <bogus>", "Remove <bogus>")
        assertEquals(emptyList<String>(), after.messages("pom.xml"))
    }

    @Test
    fun `repeated versions are extracted into a property in the common parent`() {
        f.write("pom.xml", rootPom(modules = "<module>a</module><module>b</module>"))
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId><dependencies>${dep("com.fasterxml.jackson.core:jackson-core:2.17.0")}${dep("org.x:solo:1")}</dependencies>"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId><dependencies>${dep("com.fasterxml.jackson.core:jackson-databind:2.17.0")}</dependencies>"))
        val (engine, result) = f.engine()
        val messages = result.messages("a/pom.xml")
        assertEquals(
            listOf("Version 2.17.0 of com.fasterxml.jackson.core is repeated in 2 declarations; extract property \${jackson.version} into com.example:root"),
            messages,
        )
        assertEquals(Severity.INFO, result.poms.getValue(f.path("a/pom.xml")).problems.single().severity)
        val after = fix(engine, result, "a/pom.xml", "is repeated", "Extract \${jackson.version} for 2 declarations")
        assertTrue(f.read("pom.xml").contains("<jackson.version>2.17.0</jackson.version>"))
        assertTrue(f.read("a/pom.xml").contains("<version>\${jackson.version}</version>"))
        assertTrue(f.read("b/pom.xml").contains("<version>\${jackson.version}</version>"))
        assertTrue(after.poms.values.all { it.problems.isEmpty() })
    }

    @Test
    fun `an existing property with the same value is reused`() {
        f.write("pom.xml", rootPom(modules = "<module>a</module><module>b</module>", extra = "<properties><lib.version>2.0</lib.version></properties>"))
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId><dependencies>${dep("org.lib:lib:2.0")}</dependencies>"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId><dependencies>${dep("org.lib:lib:2.0")}</dependencies>"))
        val (engine, result) = f.engine()
        assertTrue(result.messages("b/pom.xml").single().endsWith("reuse existing property \${lib.version}"))
        fix(engine, result, "b/pom.xml", "is repeated", "Use \${lib.version}")
        assertEquals(1, Regex("lib.version>").findAll(f.read("pom.xml")).count() / 2)
        assertTrue(f.read("a/pom.xml").contains("<version>\${lib.version}</version>"))
    }

    @Test
    fun `versions in unrelated hierarchies or used once are not suggested`() {
        f.write("pom.xml", rootPom(modules = "<module>a</module><module>b</module>"))
        f.write("a/pom.xml", pom("<groupId>x</groupId><artifactId>a</artifactId><version>1</version><dependencies>${dep("org.lib:lib:2.0")}</dependencies>"))
        f.write("b/pom.xml", pom("<groupId>x</groupId><artifactId>b</artifactId><version>1</version><dependencies>${dep("org.lib:lib:2.0")}${dep("org.lib:other:3.0")}</dependencies>"))
        val (_, result) = f.engine()
        assertTrue(result.poms.values.flatMap { it.problems }.none { it.category == Category.SUGGESTIONS })
    }
}
