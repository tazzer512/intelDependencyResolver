package com.github.arjenvanharen.mavendeps.core

import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.pom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/** Crafted input (from an untrusted project or repository) must not escape, exhaust or corrupt anything. */
class HardeningTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f by lazy { Fixture(tmp.root.toPath().toAbsolutePath().normalize()) }

    @Test
    fun `repository coordinates cannot leave the repository`() {
        f.artifact("org.ok:lib:1.0")
        f.write("outside/x/1/x-1.pom", pom("<groupId>evil</groupId><artifactId>x</artifactId><version>1</version>"))
        val repo = f.repo
        assertNotNull(repo.pom("org.ok", "lib", "1.0"))
        assertEquals(listOf("1.0"), repo.versions("org.ok", "lib"))
        // ".." as groupId used to become "//", i.e. the file system root.
        assertNull(repo.pomPath("..", "x", "1"))
        assertNull(repo.pomPath("org", "..", "1"))
        assertNull(repo.pomPath("org", "a", ".."))
        assertNull(repo.pomPath("org", "a\\b", "1"))
        assertNull(repo.pom("..", "outside", "1"))
        assertTrue(repo.versions("..", "outside").isEmpty())
    }

    @Test
    fun `deeply nested XML is a syntax error, not a stack overflow`() {
        val depth = 20_000
        val text = "<project>" + "<a>".repeat(depth) + "</a>".repeat(depth) + "</project>"
        val result = PomParser.parse(text)
        assertFalse(result.model.wellFormed)
        assertTrue(result.problems.single().message, Regex("nested|depth").containsMatchIn(result.problems.single().message))
    }

    @Test
    fun `oversized POMs are skipped`() {
        val path = f.write("pom.xml", pom("<groupId>g</groupId><artifactId>a</artifactId><version>1</version>"))
        Files.write(path, ByteArray(PomParser.MAX_BYTES + 1) { ' '.code.toByte() }, java.nio.file.StandardOpenOption.APPEND)
        val problems = f.engine().second.poms.getValue(path).problems
        assertTrue(problems.single().message.contains("too large"))
    }

    @Test
    fun `corrupt cache with huge counts reads as empty`() {
        val file = f.path("cache.bin")
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(file))).use {
            it.writeInt(0x4D444143)
            it.writeInt(3)
            it.writeInt(Int.MAX_VALUE)
        }
        assertTrue(CacheStore.read(file).isEmpty())
    }

    @Test
    fun `invalid property names get no fix and cannot be written`() {
        val path = f.write("pom.xml", pom("<groupId>g</groupId><artifactId>a</artifactId><version>\${a b}</version>"))
        val problem = f.engine().second.poms.getValue(path).problems.single { it.category == Category.PROPERTIES }
        assertTrue(problem.fixes.isEmpty())
        try {
            f.apply(path, DefineProperty("Define", null, "x><y", null), "1")
            throw AssertionError("expected a FixException")
        } catch (_: FixException) {
        }
        assertTrue(PomParser.isElementName("xmlunit.version"))
    }

    @Test
    fun `self-amplifying properties stay bounded`() {
        val props = (0 until 40).associate { "p$it" to "\${p${it + 1}}\${p${it + 1}}" } + ("p40" to "x".repeat(100))
        assertTrue(Workspace.substitute("\${p0}", props).length < 1_000_000)
    }
}
