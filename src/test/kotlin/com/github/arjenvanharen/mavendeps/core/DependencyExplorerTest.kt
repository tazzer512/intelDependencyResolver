package com.github.arjenvanharen.mavendeps.core

import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.deps
import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.pom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DependencyExplorerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f by lazy { Fixture(tmp.root.toPath().toAbsolutePath().normalize()) }

    @Test
    fun `an incomplete pom snippet is parsed`() {
        val pasted = """
                <dependencies>
                    <dependency>
                        <groupId>org.openjfx</groupId>
                        <artifactId>javafx-controls</artifactId>
                        <version>${'$'}{javafx.version}</version>
                    </dependency>
        """
        assertEquals(listOf(Coordinates("org.openjfx", "javafx-controls", "\${javafx.version}")), DependencySnippet.parse(pasted))
    }

    @Test
    fun `exclusions and comments are ignored and every dependency is returned`() {
        val pasted = """
            <!-- <dependency><groupId>old</groupId><artifactId>gone</artifactId></dependency> -->
            <dependency>
                <groupId>org.a</groupId>
                <artifactId>a</artifactId>
                <exclusions><exclusion><groupId>x</groupId><artifactId>y</artifactId></exclusion></exclusions>
                <version>1</version>
                <scope>test</scope>
            </dependency>
            <dependency><groupId>org.b</groupId><artifactId>b</artifactId>
        """
        assertEquals(listOf(Coordinates("org.a", "a", "1", "test"), Coordinates("org.b", "b", null)), DependencySnippet.parse(pasted))
        // Bare tags without <dependency>.
        assertEquals(listOf(Coordinates("g", "a", null)), DependencySnippet.parse("<groupId>g</groupId>\n<artifactId>a</artifactId>"))
    }

    @Test
    fun `coordinate notations`() {
        assertEquals(listOf(Coordinates("org.x", "y", "1.0")), DependencySnippet.parse("implementation(\"org.x:y:1.0\")"))
        assertEquals(listOf(Coordinates("org.x", "y", null)), DependencySnippet.parse("org.x:y"))
        assertEquals(listOf(Coordinates("org.x", "y", "2.1", "compile")), DependencySnippet.parse("[INFO] +- org.x:y:jar:2.1:compile"))
        assertEquals(listOf(Coordinates("org.x", "y", "2.1", "test")), DependencySnippet.parse("\\- org.x:y:jar:linux:2.1:test"))
        assertEquals(emptyList<Coordinates>(), DependencySnippet.parse("nothing to see"))
    }

    @Test
    fun `property versions are resolved against the workspace`() {
        f.write("pom.xml", pom("<groupId>g</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>a</module></modules><properties><javafx.version>21</javafx.version></properties>"))
        f.write("a/pom.xml", pom("<parent><groupId>g</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>a</artifactId><properties><javafx.version>22</javafx.version></properties>"))
        val ws = f.engine().first.workspaceSnapshot()
        assertEquals(listOf("21", "22"), DependencySnippet.resolveVersion(ws, "\${javafx.version}"))
        assertEquals(listOf("3"), DependencySnippet.resolveVersion(ws, "3"))
        assertEquals(emptyList<String>(), DependencySnippet.resolveVersion(ws, "\${nope}"))
    }

    @Test
    fun `libraries are indexed over all modules and conflicts are flagged`() {
        val parent = "<parent><groupId>g</groupId><artifactId>root</artifactId><version>1</version></parent>"
        f.write("pom.xml", pom("<groupId>g</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>a</module><module>b</module></modules>"))
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId>${deps("org:lib:1", "org:uses:1")}"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId>${deps("org:uses:1", "g:a:1")}"))
        f.artifact("org:lib:1")
        f.artifact("org:lib:2")
        f.artifact("org:uses:1", deps("org:lib:2"))
        val r = f.resolver()
        val modules = listOf(f.path("pom.xml"), f.path("a/pom.xml"), f.path("b/pom.xml"))
        val seen = ArrayList<Int>()
        val trees = DependencyIndex.resolveAll(r, modules, progress = { done, total, _ -> seen.add(done); assertEquals(3, total) })
        assertEquals(listOf(0, 1, 2), seen)
        val libs = DependencyIndex.libraries(trees).associateBy { it.ga }
        assertEquals(listOf("g:a", "org:lib", "org:uses"), libs.keys.toList())

        val lib = libs.getValue("org:lib")
        // a declares 1 (and omits 2 from uses); b only gets 2 transitively through uses and through module a.
        assertEquals(listOf("1", "2"), lib.versions)
        assertTrue(lib.conflict)
        assertTrue(lib.declared)
        assertEquals(setOf(f.path("a/pom.xml"), f.path("b/pom.xml")), lib.modules)

        val uses = libs.getValue("org:uses")
        assertFalse(uses.conflict)
        assertEquals(f.path("a/pom.xml"), r.workspacePom("g", "a", "1"))
    }

    @Test
    fun `refresh reports progress through both phases`() {
        f.write("pom.xml", pom("<groupId>g</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>a</module><module>b</module></modules>"))
        val parent = "<parent><groupId>g</groupId><artifactId>root</artifactId><version>1</version></parent>"
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId>"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId>"))
        val events = ArrayList<RefreshProgress>()
        AnalysisEngine().refresh(listOf(f.root), RefreshMode.FULL, progress = { events.add(it) })
        val reading = events.filter { it.phase == RefreshProgress.Phase.DISCOVERING }
        assertEquals(listOf(1, 2, 3), reading.map { it.done })
        val last = events.last()
        assertEquals(RefreshProgress(RefreshProgress.Phase.VALIDATING, 3, 3), last)
        assertEquals(1.0, last.fraction, 0.0)
    }
}
