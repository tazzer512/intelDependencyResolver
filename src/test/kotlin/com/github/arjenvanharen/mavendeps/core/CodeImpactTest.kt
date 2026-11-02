package com.github.arjenvanharen.mavendeps.core

import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.deps
import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.pom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CodeImpactTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f by lazy { Fixture(tmp.root.toPath().toAbsolutePath().normalize()) }

    private fun classFile(name: String, vararg refs: String) = CodeImpactFixtures.classFile(name, *refs)

    private fun jar(gav: String, entries: Map<String, ByteArray>) = CodeImpactFixtures.jar(f, gav, entries)

    private fun libJar(services: String? = null) = jar(
        "org:lib:1",
        linkedMapOf(
            "org/lib/Vulnerable.class" to classFile("org/lib/Vulnerable"),
            "org/lib/Vulnerable\$Inner.class" to classFile("org/lib/Vulnerable\$Inner", "org/lib/Vulnerable"),
            "org/lib/UsesVulnerable.class" to classFile("org/lib/UsesVulnerable", "org/lib/Vulnerable"),
            "org/lib/Safe.class" to classFile("org/lib/Safe"),
        ) + (services?.let { mapOf("META-INF/services/$it" to "org.lib.Vulnerable\n".toByteArray()) } ?: emptyMap()),
    )

    private fun workspace(source: String, otherRefs: List<String> = emptyList(), otherJar: Boolean = true) {
        f.write("pom.xml", pom("<groupId>com.example</groupId><artifactId>app</artifactId><version>1.0</version>${deps("org:lib:1", "org:other:1")}"))
        f.write("src/main/java/com/example/App.java", source)
        if (otherJar) jar("org:other:1", mapOf("org/other/Other.class" to classFile("org/other/Other", *otherRefs.toTypedArray()))) else f.artifact("org:other:1")
    }

    private fun vuln(details: String) = Vulnerability("GHSA-1", emptyList(), "A vulnerability", VulnSeverity.HIGH, null, emptyMap(), null, null, emptyList(), details)

    private fun impact(details: String = "Unsafe deserialization in `Vulnerable.read()`."): CodeImpact {
        val r = f.resolver()
        val libs = DependencyIndex.libraries(DependencyIndex.resolveAll(r, r.ws.entries.keys))
        val targets = VulnerabilityScanner.targets(libs) { false }
        val a = targets.keys.single { it.artifactId == "lib" }
        val v = vuln(details)
        val result = CodeImpactAnalyzer(f.repo).analyze(listOf(VulnerableArtifact(a, listOf(v), targets.getValue(a))), libs, { g, ar, ver -> r.workspacePom(g, ar, ver) })
        return result.getValue(a to v.id)
    }

    @Test
    fun `code that uses only safe classes is not affected`() {
        libJar()
        workspace("package com.example;\nimport org.lib.Safe;\nclass App { Safe s; }\n")
        val i = impact()
        assertEquals(Impact.NOT_AFFECTED, i.impact)
        assertEquals(listOf("org.lib.Vulnerable"), i.namedClasses)
        // Vulnerable and UsesVulnerable (which calls it).
        assertEquals(2, i.surfaceSize)
    }

    @Test
    fun `code that uses a class leading to the vulnerable class is affected`() {
        libJar()
        workspace("package com.example;\n\nimport org.lib.UsesVulnerable;\nclass App {}\n")
        val i = impact()
        assertEquals(Impact.AFFECTED, i.impact)
        assertEquals(listOf("${f.root.fileName}/src/main/java/com/example/App.java:3 → org.lib.UsesVulnerable"), i.evidence)
    }

    @Test
    fun `fully qualified names and wildcard imports count`() {
        libJar()
        workspace("class App { Object o = new org.lib.Vulnerable(); }")
        assertEquals(Impact.AFFECTED, impact().impact)
        workspace("import org.lib.*;\nclass App {}")
        assertTrue(impact().evidence.single().endsWith("App.java:1 → org.lib.*"))
    }

    @Test
    fun `another library that references the vulnerable class makes it affected`() {
        libJar()
        workspace("class App {}", otherRefs = listOf("org/lib/Vulnerable\$Inner"))
        assertEquals(listOf("org:other:1 → org.lib.Vulnerable"), impact().evidence)
    }

    @Test
    fun `an advisory that names no class covers the whole artifact`() {
        libJar()
        workspace("import org.lib.Safe;\nclass App {}")
        val i = impact("Something is wrong in this library.")
        assertEquals(Impact.AFFECTED, i.impact)
        assertEquals(emptyList<String>(), i.namedClasses)
    }

    @Test
    fun `missing jars give an unknown impact rather than a guess`() {
        libJar()
        workspace("class App {}", otherJar = false)
        val i = impact()
        assertEquals(Impact.UNKNOWN, i.impact)
        assertEquals(listOf("The jar of org:other:1 is not in the local repository"), i.reasons)
    }

    // B5 (2026-11-02 audit)
    @Test
    fun `unreadable jars are reported as such`() {
        libJar()
        workspace("class App {}")
        Files.writeString(f.path(".m2/org/other/1/other-1.jar"), "not a zip")
        assertEquals(listOf("The jar of org:other:1 is not a readable jar"), impact().reasons)
        Files.writeString(f.path(".m2/org/lib/1/lib-1.jar"), "not a zip")
        assertEquals(listOf("The jar of org:lib:1 is not a readable jar"), impact().reasons)
    }

    // S4 (2026-11-02 audit)
    @Test
    fun `source links are not followed out of the project`() {
        libJar()
        workspace("class App {}")
        val outside = tmp.newFolder("outside").toPath().resolve("Evil.java")
        Files.writeString(outside, "import org.lib.Vulnerable;")
        Files.createSymbolicLink(f.path("src/main/java/com/example/Evil.java"), outside)
        Files.createSymbolicLink(f.path("src/main/java/com/example/linked"), outside.parent)
        assertEquals(Impact.NOT_AFFECTED, impact().impact)
    }

    @Test
    fun `compiled classes, configuration files and Kotlin imports count`() {
        libJar()
        workspace("class App {}")
        Files.createDirectories(f.path("target/classes/com/example"))
        Files.write(f.path("target/classes/com/example/App.class"), classFile("com/example/App", "org/lib/UsesVulnerable"))
        assertEquals(listOf("${f.root.fileName}/target/classes/com/example/App.class → org.lib.UsesVulnerable"), impact().evidence)
        Files.delete(f.path("target/classes/com/example/App.class"))
        f.write("src/main/resources/beans.xml", "<bean class=\"org.lib.Vulnerable\"/>")
        assertEquals(listOf("${f.root.fileName}/src/main/resources/beans.xml:1 → org.lib.Vulnerable"), impact().evidence)
        Files.delete(f.path("src/main/resources/beans.xml"))
        // A Kotlin top-level function import may use anything in its package.
        f.write("src/main/kotlin/App.kt", "import org.lib.vulnerableHelper\nfun main() {}")
        assertEquals(listOf("${f.root.fileName}/src/main/kotlin/App.kt:1 → org.lib.*"), impact().evidence)
    }

    @Test
    fun `test-jar dependencies are read from the tests classifier`() {
        f.write("pom.xml", pom("<groupId>com.example</groupId><artifactId>app</artifactId><version>1.0</version>" +
            "<dependencies>${Fixture.dep("org:lib:1", "<type>test-jar</type>")}</dependencies>"))
        f.write("src/test/java/T.java", "import org.lib.Vulnerable;")
        CodeImpactFixtures.jar(f, "org:lib:1", mapOf("org/lib/Vulnerable.class" to classFile("org/lib/Vulnerable")), classifier = "tests")
        assertEquals(Impact.AFFECTED, impact().impact)
    }

    @Test
    fun `providers of JDK services are loaded without references`() {
        libJar(services = "java.sql.Driver")
        workspace("class App {}")
        assertEquals(Impact.UNKNOWN, impact().impact)
    }

    @Test
    fun `providers of library services are reached through the interface`() {
        libJar(services = "org.other.Spi")
        workspace("class App {}", otherRefs = listOf("org/other/Spi"))
        assertEquals(listOf("org:other:1 → org.other.Spi"), impact().evidence)
    }

    @Test
    fun `fix report lists only advisories that impact the code`() {
        libJar()
        workspace("package com.example;\n\nimport org.lib.UsesVulnerable;\nclass App {}\n")
        val r = f.resolver()
        val libs = DependencyIndex.libraries(DependencyIndex.resolveAll(r, r.ws.entries.keys))
        val targets = VulnerabilityScanner.targets(libs) { false }
        val a = targets.keys.single { it.artifactId == "lib" }
        val hit = Vulnerability("GHSA-1", listOf("CVE-2026-1"), "RCE in Vulnerable", VulnSeverity.CRITICAL, 9.8, mapOf("org:lib" to listOf("1.2")), null, null, emptyList(), "`Vulnerable` runs code")
        val miss = Vulnerability("GHSA-2", listOf("CVE-2026-2"), "DoS in Safe", VulnSeverity.LOW, null, emptyMap(), null, null, emptyList(), "`Safe` loops")
        val artifacts = listOf(VulnerableArtifact(a, listOf(hit, miss), targets.getValue(a)))
        val impacts = CodeImpactAnalyzer(f.repo).analyze(artifacts, libs, { g, ar, ver -> r.workspacePom(g, ar, ver) })
        val report = FixReport.render(artifacts, impacts, { f.root.relativize(it).toString() }, { "app" })
        assertTrue(report, "## 1. org:lib:1 → upgrade to 1.2" in report)
        assertTrue(report, "- CVE-2026-1 / GHSA-1 (Critical 9.8, fixed in 1.2): RCE in Vulnerable" in report)
        assertTrue(report, "- Comes in: declared in pom.xml:" in report)
        assertTrue(report, "App.java:3 → org.lib.UsesVulnerable" in report)
        assertTrue(report, "CVE-2026-2" !in report.substringBefore("Left out"))
        assertTrue(report, "Left out (do not impact the code): org:lib:1 CVE-2026-2" in report)
        // Without the impact check every advisory is listed.
        assertTrue("DoS in Safe" in FixReport.render(artifacts, null, { it.toString() }, { "app" }))
    }

    @Test
    fun `advisory text names classes`() {
        val classes = setOf("org.x.StringSubstitutor", "org.x.Lookup", "org.x.Text", "org.y.Text")
        fun named(text: String) = CodeImpactAnalyzer.namedClasses(vuln(text), classes)
        assertEquals(setOf("org.x.StringSubstitutor"), named("StringSubstitutor interpolation can run code"))
        assertEquals(setOf("org.x.Lookup"), named("Calling `Lookup` is unsafe; a Lookup or Text is fine"))
        assertEquals(setOf("org.x.Lookup"), named("org.x.Lookup.lookup() runs scripts"))
        assertEquals(setOf("org.x.Text", "org.y.Text"), named("Text#format(String) leaks"))
        assertEquals(emptySet<String>(), named("Remote code execution in GitHub"))
    }

    @Test
    fun `class files are read defensively`() {
        assertEquals(setOf("a.B", "java.lang.Object", "c.D"), ClassFiles.references(classFile("a/B", "c/D\$E")))
        // Descriptors and class names in string constants (reflection) count; every constant pool tag is skipped correctly.
        assertEquals(setOf("a.B", "org.x.Arg", "org.x.Reflected"), ClassFiles.references(CodeImpactFixtures.richClassFile("a/B")))
        assertNull(ClassFiles.references(CodeImpactFixtures.richClassFile("a/B").also { it[10] = 99 }))
        assertNull(ClassFiles.references(byteArrayOf(1, 2, 3)))
        assertNull(ClassFiles.references(classFile("a/B").copyOf(12)))
        assertNull(f.repo.artifactPath("org", "lib", "..", null))
        assertNull(f.repo.artifactPath("org", "lib", "1", "../x"))
        assertEquals(f.path(".m2/org/lib/1/lib-1-tests.jar"), f.repo.artifactPath("org", "lib", "1", "tests"))
    }
}
