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
import java.nio.file.Paths

/** The checks and resolution features from docs/IMPROVEMENTS.md (comments carry the item number, e.g. `// I1`). */
class ImprovementsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f by lazy { Fixture(tmp.root.toPath().toAbsolutePath().normalize()) }

    private val parent = "<parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent>"

    private fun rootPom(extra: String = "", modules: String = "<module>a</module>", version: String = "1.0") = pom(
        "<groupId>com.example</groupId><artifactId>root</artifactId><version>$version</version><packaging>pom</packaging><modules>$modules</modules>$extra",
    )

    private fun plugin(a: String, v: String? = null) = "<plugin><artifactId>$a</artifactId>${v?.let { "<version>$it</version>" } ?: ""}</plugin>"

    private fun AnalysisResult.problems(rel: String) = poms.getValue(f.path(rel)).problems

    private fun AnalysisResult.messages(rel: String) = problems(rel).map { it.message }

    /** Applies the first fix of the problem containing [message] and re-analyses incrementally. */
    private fun fix(engine: AnalysisEngine, result: AnalysisResult, rel: String, message: String, input: String? = null): AnalysisResult {
        val owner = f.path(rel)
        val problem = result.problems(rel).first { message in it.message }
        val fix = problem.fixes.first()
        f.apply(owner, fix, input)
        return engine.refresh(listOf(f.root), RefreshMode.INCREMENTAL, FixApplier.files(fix, owner))
    }

    // I1
    @Test
    fun `plugins without a managed version are reported, and the fix sets one`() {
        f.write("pom.xml", rootPom("<build><pluginManagement><plugins>${plugin("maven-jar-plugin", "3.4.2")}</plugins></pluginManagement></build>"))
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId><build><plugins>${plugin("maven-jar-plugin")}${plugin("maven-surefire-plugin")}</plugins></build>"))
        val (engine, result) = f.engine()
        val messages = result.messages("a/pom.xml")
        assertEquals(1, messages.size)
        assertTrue(messages.single(), messages.single().contains("org.apache.maven.plugins:maven-surefire-plugin has no version"))
        val after = fix(engine, result, "a/pom.xml", "maven-surefire-plugin has no version", input = "3.5.2")
        assertEquals(emptyList<String>(), after.messages("a/pom.xml"))
        assertTrue(f.read("a/pom.xml"), f.read("a/pom.xml").contains(Regex("<artifactId>maven-surefire-plugin</artifactId>\\s*<version>3.5.2</version>")))
    }

    // I1
    @Test
    fun `duplicate plugins are reported, and external parents silence the version check`() {
        f.write(
            "pom.xml",
            pom(
                "<parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.3.0</version></parent>" +
                    "<artifactId>app</artifactId><build><plugins>${plugin("maven-compiler-plugin")}${plugin("maven-compiler-plugin")}</plugins></build>",
            ),
        )
        val (engine, result) = f.engine()
        assertEquals(listOf("Plugin org.apache.maven.plugins:maven-compiler-plugin is declared more than once in <plugins>"), result.messages("pom.xml"))
        val after = fix(engine, result, "pom.xml", "declared more than once")
        assertEquals(emptyList<String>(), after.messages("pom.xml"))
        assertEquals(1, Regex("maven-compiler-plugin").findAll(f.read("pom.xml")).count())
    }

    // I2
    @Test
    fun `a dependency on a workspace module with another version is reported and aligned`() {
        f.write("pom.xml", rootPom(modules = "<module>a</module><module>b</module>", version = "2.0"))
        f.write("a/pom.xml", pom("<parent><groupId>com.example</groupId><artifactId>root</artifactId><version>2.0</version></parent><artifactId>a</artifactId>"))
        f.write(
            "b/pom.xml",
            pom("<parent><groupId>com.example</groupId><artifactId>root</artifactId><version>2.0</version></parent><artifactId>b</artifactId>${deps("com.example:a:1.0")}"),
        )
        val (engine, result) = f.engine()
        val problem = result.problems("b/pom.xml").single()
        assertTrue(problem.message, problem.message.contains("com.example:a:1.0 is built in this workspace as version 2.0"))
        val after = fix(engine, result, "b/pom.xml", "is built in this workspace")
        assertEquals(emptyList<String>(), after.messages("b/pom.xml"))
        assertTrue(f.read("b/pom.xml").contains("<version>\${project.version}</version>"))
    }

    // I2: a matching version, or one only known through an unresolved property, is fine.
    @Test
    fun `matching module versions are not reported`() {
        f.write("pom.xml", rootPom(modules = "<module>a</module><module>b</module>"))
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId>"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId><dependencies>${dep("com.example:a:\${project.version}")}${dep("com.example:a:\${unknown}", "<classifier>x</classifier>")}</dependencies>"))
        val (_, result) = f.engine()
        assertTrue(result.messages("b/pom.xml").none { "built in this workspace" in it })
    }

    // I3
    @Test
    fun `a version equal to the managed one is a hint whose fix removes it`() {
        f.write("pom.xml", rootPom("<dependencyManagement>${deps("org.lib:lib:3.1", "org.lib:other:1.0")}</dependencyManagement>"))
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId>${deps("org.lib:lib:3.1", "org.lib:other:2.0")}"))
        val (engine, result) = f.engine()
        val problem = result.problems("a/pom.xml").also { assertEquals(it.map { p -> p.message }.toString(), 1, it.size) }.single()
        assertEquals(Severity.INFO, problem.severity)
        assertEquals("Version 3.1 of org.lib:lib is already managed by com.example:root; <version> can be removed", problem.message)
        val after = fix(engine, result, "a/pom.xml", "already managed")
        assertEquals(emptyList<String>(), after.messages("a/pom.xml"))
        assertTrue(f.read("a/pom.xml").contains("<dependency><groupId>org.lib</groupId><artifactId>lib</artifactId></dependency>"))
    }

    // I4
    @Test
    fun `a release depending on a snapshot is reported`() {
        f.write("pom.xml", pom("<groupId>com.example</groupId><artifactId>app</artifactId><version>1.0</version>${deps("org.lib:lib:2.0-SNAPSHOT", "org.lib:ok:2.0")}"))
        assertEquals(listOf("Release 1.0 depends on snapshot org.lib:lib:2.0-SNAPSHOT; the build is not reproducible"), f.engine().second.messages("pom.xml"))
        f.write("pom.xml", pom("<groupId>com.example</groupId><artifactId>app</artifactId><version>1.1-SNAPSHOT</version>${deps("org.lib:lib:2.0-SNAPSHOT")}"))
        assertEquals(emptyList<String>(), f.engine().second.messages("pom.xml"))
    }

    // I5
    @Test
    fun `version ranges parse and match like Maven`() {
        val r = VersionRange.parse("[1.0,2.0)")!!
        assertTrue(r.contains("1.0") && r.contains("1.9.9") && !r.contains("2.0") && !r.contains("0.9"))
        assertTrue(VersionRange.parse("(,1.0]")!!.contains("0.1"))
        assertFalse(VersionRange.parse("(1.0,)")!!.contains("1.0"))
        assertTrue(VersionRange.parse("[1.2]")!!.contains("1.2.0"))
        assertTrue(VersionRange.parse("[1,2),[3,4)")!!.let { it.contains("3.5") && !it.contains("2.5") })
        assertEquals("1.5", VersionRange.parse("[1,2)")!!.highest(listOf("0.9", "1.0", "1.5", "2.0")))
        for (bad in listOf("1.0", "[1.0", "(1.0)", "[]", "[1,2,3]")) assertNull(bad, VersionRange.parse(bad))
    }

    // I5
    @Test
    fun `a range resolves to the highest local match, and an unmatched range is missing`() {
        f.artifact("org:a:1.0")
        f.artifact("org:a:1.5", deps("org:c:1"))
        f.artifact("org:a:2.0")
        f.artifact("org:c:1")
        f.write("pom.xml", pom("<groupId>com.example</groupId><artifactId>app</artifactId><version>1.0</version>${deps("org:a:[1.0,2.0)", "org:b:[5,6)")}"))
        val tree = f.resolver().tree(f.path("pom.xml"))
        val a = tree.roots[0]
        assertEquals("1.5", a.version)
        assertEquals("[1.0,2.0)", a.requestedRange)
        assertEquals(listOf("org:c:1"), a.children.map { it.label })
        val b = tree.roots[1]
        assertEquals("[5,6)", b.version)
        assertFalse(b.pomFound)
    }

    // I6
    @Test
    fun `relocations are followed before mediation`() {
        f.artifact("mysql:mysql-connector-java:8.0.33", "<distributionManagement><relocation><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId></relocation></distributionManagement>")
        f.artifact("com.mysql:mysql-connector-j:8.0.33", deps("com.google.protobuf:protobuf-java:3.21.9"))
        f.artifact("com.google.protobuf:protobuf-java:3.21.9")
        // A relocation cycle must stop.
        f.artifact("org:x:1", "<distributionManagement><relocation><artifactId>y</artifactId></relocation></distributionManagement>")
        f.artifact("org:y:1", "<distributionManagement><relocation><artifactId>x</artifactId></relocation></distributionManagement>")
        f.write("pom.xml", pom("<groupId>com.example</groupId><artifactId>app</artifactId><version>1.0</version>${deps("mysql:mysql-connector-java:8.0.33", "com.mysql:mysql-connector-j:8.0.33", "org:x:1")}"))
        val tree = f.resolver().tree(f.path("pom.xml"))
        val first = tree.roots[0]
        assertEquals("com.mysql:mysql-connector-j:8.0.33", first.label)
        assertEquals("mysql:mysql-connector-java:8.0.33", first.relocatedFrom)
        assertEquals(listOf("com.google.protobuf:protobuf-java:3.21.9"), first.children.map { it.label })
        assertEquals(NodeStatus.OMITTED_DUPLICATE, tree.roots[1].status)
        assertTrue(tree.roots[2].label, tree.roots[2].label in setOf("org:x:1", "org:y:1"))
    }

    // I7
    @Test
    fun `trees render like mvn dependency tree`() {
        f.artifact("org:a:1", deps("org:c:1.0"))
        f.artifact("org:b:1", "<dependencies>${dep("org:c:2.0")}${dep("org:d:1", "<scope>runtime</scope>")}</dependencies>")
        f.artifact("org:c:1.0")
        f.artifact("org:d:1")
        f.write("pom.xml", pom("<groupId>com.example</groupId><artifactId>app</artifactId><version>1.0</version><dependencies>${dep("org:a:1")}${dep("org:b:1", "<scope>test</scope>")}</dependencies>"))
        val tree = f.resolver().tree(f.path("pom.xml"))
        val gav = Gav("com.example", "app", "1.0")
        assertEquals(
            """
            |com.example:app:jar:1.0
            |+- org:a:jar:1:compile
            ||  \- org:c:jar:1.0:compile
            |\- org:b:jar:1:test
            |   \- org:d:jar:1:test
            |""".trimMargin(),
            DependencyTreeText.render(gav, "jar", tree),
        )
        val verbose = DependencyTreeText.render(gav, "jar", tree, verbose = true)
        assertTrue(verbose, verbose.contains("   +- (org:c:jar:2.0:test - omitted for conflict with 1.0)"))
    }

    @Test
    fun `plugins, relocation and version positions survive the cache`() {
        val text = pom(
            "<groupId>g</groupId><artifactId>a</artifactId><version>1</version>${deps("org:x:1")}" +
                "<build><plugins>${plugin("p", "1")}</plugins><pluginManagement><plugins><plugin><groupId>org.x</groupId><artifactId>q</artifactId></plugin></plugins></pluginManagement></build>" +
                "<distributionManagement><relocation><groupId>h</groupId></relocation></distributionManagement>",
        )
        val model = PomParser.parse(text).model
        assertEquals(listOf("org.apache.maven.plugins:p:1:false", "org.x:q:null:true"), model.plugins.map { "${it.effectiveGroupId}:${it.artifactId}:${it.version}:${it.managed}" })
        assertEquals(Gav("h", null, null), model.relocation)
        assertTrue(model.dependencies.single().versionPos != Pos.NONE)
        val entry = PomEntry(Paths.get("/w/pom.xml"), 1, 2, "h", model, emptyList())
        val file = f.path("cache.bin")
        CacheStore.write(file, listOf(entry))
        assertEquals(entry, CacheStore.read(file).single())
    }
}
