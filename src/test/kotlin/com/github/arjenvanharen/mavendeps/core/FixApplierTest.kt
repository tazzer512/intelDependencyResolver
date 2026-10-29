package com.github.arjenvanharen.mavendeps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

class FixApplierTest {
    private val owner: Path = Paths.get("/ws/pom.xml").toAbsolutePath()

    private fun apply(text: String, fix: QuickFix, input: String? = null): String =
        FixApplier.apply(text, FixApplier.edits(fix, owner, input) { text }[owner].orEmpty())

    private fun node(text: String, vararg names: String): XNode {
        var n = PomParser.parse(text).root!!
        for (name in names) n = n.child(name)!!
        return n
    }

    private fun ref(text: String, vararg names: String) = node(text, *names).let { ElementRef(null, it.name, it.pos) }

    private val base = """
        <project>
            <modelVersion>4.0.0</modelVersion>
            <groupId>g</groupId>
            <artifactId>a</artifactId>
            <version>1</version>
            <bogus>x</bogus>
            <dependencies>
                <dependency>
                    <groupId>d</groupId>
                    <artifactId>e</artifactId>
                    <scope>wrong</scope>
                </dependency>
            </dependencies>
        </project>
    """.trimIndent()

    @Test
    fun `remove element takes its whole line`() {
        val result = apply(base, RemoveElement("rm", ref(base, "bogus")))
        assertEquals(base.replace("    <bogus>x</bogus>\n", ""), result)
    }

    @Test
    fun `remove element sharing a line only removes the element`() {
        val text = "<project><a/><b>1</b></project>"
        assertEquals("<project><a/></project>", apply(text, RemoveElement("rm", ref(text, "b"))))
    }

    @Test
    fun `set child replaces existing text`() {
        val result = apply(base, SetChild("v", ElementRef(null, "project", Pos.NONE), "version", "2"))
        assertEquals(base.replace("<version>1</version>", "<version>2</version>"), result)
    }

    @Test
    fun `set child inserts at canonical position with document indentation`() {
        val result = apply(base, SetChild("v", ref(base, "dependencies", "dependency"), "version", "1.0"))
        assertTrue(result, result.contains("<artifactId>e</artifactId>\n            <version>1.0</version>\n            <scope>wrong</scope>"))
        val packaged = apply(base, SetChild("p", ElementRef(null, "project", Pos.NONE), "packaging", "pom"))
        assertTrue(packaged, packaged.contains("<version>1</version>\n    <packaging>pom</packaging>\n    <bogus>"))
    }

    @Test
    fun `set child to empty value writes an empty element`() {
        val text = "<project>\n  <parent>\n    <groupId>g</groupId>\n    <relativePath>../x</relativePath>\n  </parent>\n</project>"
        assertEquals(text.replace("<relativePath>../x</relativePath>", "<relativePath/>"), apply(text, SetChild("r", ref(text, "parent"), "relativePath", "")))
    }

    @Test
    fun `value from user input is escaped`() {
        val result = apply(base, SetChild("v", ref(base, "dependencies", "dependency"), "version", null), input = "1<2")
        assertTrue(result, result.contains("<version>1&lt;2</version>"))
    }

    @Test(expected = FixException::class)
    fun `missing input is rejected`() {
        apply(base, SetChild("v", ref(base, "dependencies", "dependency"), "version", null))
    }

    @Test
    fun `define property creates properties section at its canonical position`() {
        val result = apply(base, CompositeFix("p", listOf(DefineProperty("a", null, "x.version", "1"), DefineProperty("b", null, "y", ""))))
        assertTrue(
            result,
            result.contains("<version>1</version>\n    <properties>\n        <x.version>1</x.version>\n        <y></y>\n    </properties>\n    <bogus>"),
        )
        assertTrue(PomParser.parse(result).model.wellFormed)
    }

    @Test
    fun `define property appends to or overwrites existing properties`() {
        val text = "<project>\n    <properties>\n        <a>1</a>\n    </properties>\n</project>"
        assertEquals(
            "<project>\n    <properties>\n        <a>2</a>\n        <b>3</b>\n    </properties>\n</project>",
            apply(text, CompositeFix("p", listOf(DefineProperty("a", null, "a", "2"), DefineProperty("b", null, "b", "3")))),
        )
    }

    @Test
    fun `exclusions are created once and not duplicated`() {
        val dep = ref(base, "dependencies", "dependency")
        val once = apply(base, CompositeFix("x", listOf(AddExclusion("1", dep, "x", "y"), AddExclusion("2", dep, "p", "q"))))
        assertTrue(
            once,
            once.contains(
                "<scope>wrong</scope>\n            <exclusions>\n                <exclusion>\n                    <groupId>x</groupId>\n" +
                    "                    <artifactId>y</artifactId>\n                </exclusion>\n                <exclusion>",
            ),
        )
        val twice = apply(once, AddExclusion("1", ref(once, "dependencies", "dependency"), "x", "y"))
        assertEquals(once, twice)
    }

    @Test
    fun `add dependency appends to dependencies`() {
        val result = apply(base, AddDependency("d", null, "n", "m", "3"))
        assertTrue(result, result.contains("</dependency>\n        <dependency>\n            <groupId>n</groupId>\n            <artifactId>m</artifactId>\n            <version>3</version>\n        </dependency>\n    </dependencies>"))
    }

    @Test
    fun `self-closing container is expanded`() {
        val text = "<project>\n    <properties/>\n</project>"
        assertEquals("<project>\n    <properties>\n        <a>1</a>\n    </properties>\n</project>", apply(text, DefineProperty("p", null, "a", "1")))
    }

    @Test
    fun `crlf line endings are preserved`() {
        val text = base.replace("\n", "\r\n")
        val result = apply(text, CompositeFix("x", listOf(RemoveElement("rm", ref(text, "bogus")), DefineProperty("p", null, "a", "1"))))
        assertEquals(base.replace("    <bogus>x</bogus>\n", "").let { apply(it, DefineProperty("p", null, "a", "1")) }.replace("\n", "\r\n"), result)
    }

    @Test(expected = FixException::class)
    fun `stale position is detected`() {
        apply(base, RemoveElement("rm", ElementRef(null, "bogus", Pos(99, 1))))
    }

    @Test(expected = FixException::class)
    fun `malformed document is rejected`() {
        apply("<project><a></project>", DefineProperty("p", null, "a", "1"))
    }

    @Test
    fun `anchored fills in the owner and files lists every target`() {
        val other = Paths.get("/ws/b/pom.xml").toAbsolutePath()
        val fix = CompositeFix("c", listOf(DefineProperty("p", null, "a", "1"), SetChild("s", ElementRef(other, "project", Pos.NONE), "packaging", "pom")))
        assertEquals(setOf(owner, other), FixApplier.files(fix, owner))
        val anchored = fix.anchored(owner) as CompositeFix
        assertEquals(owner, (anchored.parts[0] as DefineProperty).pom)
        assertEquals("Value for property 'a'", DefineProperty("p", null, "a", null).prompt)
    }
}
