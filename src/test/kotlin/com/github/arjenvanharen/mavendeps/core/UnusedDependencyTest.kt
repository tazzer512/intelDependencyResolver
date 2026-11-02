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

/** Declared dependencies that nothing needs, verified against code, other libraries and runtime registrations. */
class UnusedDependencyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f by lazy { Fixture(tmp.root.toPath().toAbsolutePath().normalize()) }

    private fun cls(name: String, vararg refs: String) = CodeImpactFixtures.classFile(name, *refs)

    private fun jar(gav: String, vararg entries: Pair<String, ByteArray>) = CodeImpactFixtures.jar(f, gav, linkedMapOf(*entries))

    /** org:used:1 (org.used.Used) and org:unused:1 (org.unused.Unused). */
    private fun libraries() {
        jar("org:used:1", "org/used/Used.class" to cls("org/used/Used"))
        jar("org:unused:1", "org/unused/Unused.class" to cls("org/unused/Unused"))
    }

    private fun app(dependencies: String, source: String = "package com.example;\nimport org.used.Used;\nclass App { Used u; }\n") {
        f.write("pom.xml", pom("<groupId>com.example</groupId><artifactId>app</artifactId><version>1.0</version>$dependencies"))
        if (source.isNotEmpty()) f.write("src/main/java/com/example/App.java", source)
    }

    private fun analyze(): UnusedDependencyResult {
        val r = f.resolver()
        return UnusedDependencyAnalyzer(f.repo).analyze(r, DependencyIndex.resolveAll(r, r.ws.entries.keys))
    }

    private fun unused(result: UnusedDependencyResult = analyze()): List<String> =
        result.problems.values.flatten().map { it.message.substringBefore(" is not used") }.sorted()

    private fun usage(result: UnusedDependencyResult, artifactId: String) = result.verdicts.single { it.node.artifactId == artifactId }.usage

    @Test
    fun `a dependency no code references is reported and its fix removes it`() {
        libraries()
        app(deps("org:used:1", "org:unused:1"))
        val result = analyze()
        assertEquals(listOf("org:unused:1"), unused(result))
        assertEquals(Usage.USED, usage(result, "used"))
        assertTrue(result.verdicts.single { it.node.artifactId == "used" }.evidence.single().contains("App.java:2 → org.used.Used"))

        val path = f.path("pom.xml")
        val problem = result.problems.getValue(path).single()
        assertEquals(Severity.WARNING, problem.severity)
        f.apply(path, problem.fixes.single())
        assertFalse(f.read("pom.xml").contains("org.unused") || f.read("pom.xml").contains(">unused<"))
        assertTrue(unused().isEmpty())
    }

    @Test
    fun `qualified names, wildcard imports, configuration files and compiled classes count as use`() {
        libraries()
        app(deps("org:unused:1"), "class App { Object o = new org.unused.Unused(); }")
        assertTrue(unused().isEmpty())

        app(deps("org:unused:1"), "import org.unused.*;\nclass App {}")
        assertTrue(unused().isEmpty())

        app(deps("org:unused:1"), "class App {}")
        f.write("src/main/resources/logback.xml", "<configuration><appender class=\"org.unused.Unused\"/></configuration>")
        assertTrue(unused().isEmpty())

        f.write("src/main/resources/logback.xml", "<configuration/>")
        assertEquals(listOf("org:unused:1"), unused())
        f.write("target/classes/com/example/App.class", "")
        java.nio.file.Files.write(f.path("target/classes/com/example/App.class"), cls("com/example/App", "org/unused/Unused"))
        assertTrue(unused().isEmpty())
    }

    @Test
    fun `classes it brings in transitively count as its own`() {
        jar("org:unused:1", "org/unused/Unused.class" to cls("org/unused/Unused"))
        jar("org:inner:1", "org/inner/Inner.class" to cls("org/inner/Inner"))
        f.artifact("org:unused:1", deps("org:inner:1"))
        app(deps("org:unused:1"), "import org.inner.Inner;\nclass App {}")
        assertTrue(unused().isEmpty())
    }

    @Test
    fun `a dependency another library needs is not reported`() {
        libraries()
        // org.used.Used extends a class of org:unused, which org:used only has as an optional dependency.
        jar("org:used:1", "org/used/Used.class" to cls("org/used/Used", "org/unused/Unused"))
        app(deps("org:used:1", "org:unused:1"))
        val result = analyze()
        assertTrue(unused(result).isEmpty())
        assertEquals(Usage.NEEDED_BY_LIBRARY, usage(result, "unused"))
    }

    @Test
    fun `jars loaded at runtime through services are not reported`() {
        libraries()
        jar(
            "org:unused:1",
            "org/unused/Unused.class" to cls("org/unused/Unused"),
            "META-INF/services/java.sql.Driver" to "org.unused.Unused\n".toByteArray(),
        )
        app(deps("org:used:1", "org:unused:1"))
        val result = analyze()
        assertTrue(unused(result).isEmpty())
        assertEquals(Usage.LOADED_AT_RUNTIME, usage(result, "unused"))
    }

    @Test
    fun `nothing is reported when the check cannot be sure`() {
        libraries()
        // The jar is missing from the repository.
        f.artifact("org:nojar:1")
        app(deps("org:used:1", "org:nojar:1"))
        val result = analyze()
        assertTrue(unused(result).isEmpty())
        assertEquals(Usage.UNKNOWN, usage(result, "nojar"))

        // A module without any code.
        f.path("src").toFile().deleteRecursively()
        app(deps("org:unused:1"), "")
        assertTrue(unused().isEmpty())

        // Runtime scope is loaded by the application, not referenced by it.
        app("<dependencies>${dep("org:used:1")}${dep("org:unused:1", "<scope>runtime</scope>")}</dependencies>")
        assertTrue(unused().isEmpty())
    }

    @Test
    fun `a dependency declared in a parent is reported only when no child uses it`() {
        libraries()
        val parent = "<parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent>"
        f.write(
            "pom.xml",
            pom("<groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version><packaging>pom</packaging>" +
                "<modules><module>a</module><module>b</module></modules>${deps("org:unused:1")}"),
        )
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId>"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId>"))
        f.write("a/src/main/java/A.java", "class A {}")
        f.write("b/src/main/java/B.java", "import org.unused.Unused;\nclass B {}")
        assertTrue(unused().isEmpty())

        f.write("b/src/main/java/B.java", "class B {}")
        val result = analyze()
        assertEquals(listOf("org:unused:1"), unused(result))
        assertEquals(setOf(f.path("pom.xml")), result.problems.keys)
        assertTrue(result.problems.values.single().single().message.contains("modules a, b"))
    }

    @Test
    fun `results of a POM that changed since are dropped`() {
        libraries()
        app(deps("org:used:1", "org:unused:1"))
        val result = analyze()
        val (_, analysis) = f.engine()
        assertEquals(1, result.current(analysis).size)
        app(deps("org:used:1", "org:unused:1") + "<!-- edited -->")
        val (_, after) = f.engine()
        assertTrue(result.current(after).isEmpty())
    }
}
