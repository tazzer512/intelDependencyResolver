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

class DependencyQueriesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f by lazy { Fixture(tmp.root.toPath().toAbsolutePath().normalize()) }

    private val parent = "<parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent>"

    /**
     * root: manages org:managed:1, defines guava.version=30
     * a: org:lib:1 (literal), com.google:guava:${guava.version}, org:managed (managed)
     * b: org:brings-lib:1 → org:lib:0.9 → nothing; org:other:1 → org:brings-lib:1 (duplicate)
     */
    private fun workspace() {
        f.write(
            "pom.xml",
            pom(
                "<groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version><packaging>pom</packaging>" +
                    "<modules><module>a</module><module>b</module></modules><properties><guava.version>30</guava.version></properties>" +
                    "<dependencyManagement>${deps("org:managed:1")}</dependencyManagement>",
            ),
        )
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId><dependencies>${dep("org:lib:1")}${dep("com.google:guava:\${guava.version}")}${dep("org:managed")}</dependencies>"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId>${deps("org:brings-lib:1", "org:other:1")}"))
        f.artifact("org:lib:1")
        f.artifact("org:lib:0.9")
        f.artifact("org:brings-lib:1", deps("org:lib:0.9"))
        f.artifact("org:other:1", deps("org:brings-lib:1"))
    }

    @Test
    fun `query matching`() {
        val q = DependencyQuery("org.*", "lib", "1.*")
        assertTrue(q.matches("org.x", "lib"))
        assertFalse(q.matches("com.x", "lib"))
        assertTrue(q.matchesVersion("1.2"))
        assertFalse(q.matchesVersion("2.0"))
        assertTrue(DependencyQuery("", "*").matches("any", "thing"))
        assertTrue(DependencyQuery("a.b", "c").matches("a.b", "c"))
        assertFalse(DependencyQuery("a.b", "c").matches("axb", "c"))
    }

    @Test
    fun `search distinguishes declared and transitive occurrences`() {
        workspace()
        val r = f.resolver()
        val result = DependencySearch.search(r.ws, r, DependencyQuery("org", "lib"))
        assertEquals(listOf(f.path("a/pom.xml") to "1"), result.declared.map { it.pom to it.resolvedVersion })
        fun describe(hits: List<TransitiveHit>) = hits.map { h -> h.node.chain.joinToString(" → ") { it.label } + " " + h.node.status }
        // lib is reached once; the second path to it is cut off at the duplicate brings-lib.
        assertEquals(listOf("org:brings-lib:1 → org:lib:0.9 INCLUDED"), describe(result.transitive))
        assertEquals(
            listOf("org:other:1 → org:brings-lib:1 OMITTED_DUPLICATE"),
            describe(DependencySearch.search(r.ws, r, DependencyQuery("org", "brings-lib")).transitive),
        )
        assertTrue(result.transitive.all { it.module == f.path("b/pom.xml") })
        assertEquals(emptySet<String>(), result.missingPoms - setOf("org:managed:1", "com.google:guava:30"))

        val versioned = DependencySearch.search(r.ws, r, DependencyQuery("org", "lib", "0.9"))
        assertEquals(0, versioned.declared.size)
        assertEquals(1, versioned.transitive.size)
    }

    @Test
    fun `declared versions explain where they come from`() {
        workspace()
        val r = f.resolver()
        val hits = DependencySearch.declarations(r.ws, r, DependencyQuery("*", "*")).associate { "${it.pom.parent.fileName}/${it.artifactId}" to (it.resolvedVersion to it.versionSource) }
        assertEquals("1" to "literal", hits["a/lib"])
        assertEquals("30" to "\${guava.version}", hits["a/guava"])
        assertEquals("1" to "managed", hits["a/managed"])
    }

    @Test
    fun `version update changes literals and properties and excludes other transitive versions`() {
        workspace()
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId>${deps("org:brings-lib:1", "org:other:1", "com.google:guava:30")}"))
        f.artifact("com.google:guava:30")
        val r = f.resolver()

        val lib = VersionUpdatePlanner.plan(r.ws, r, "org", "lib", null, "2")
        assertEquals(
            listOf(
                PlanItem.Kind.DECLARATION to "a",
                PlanItem.Kind.EXCLUSION to "b",
                PlanItem.Kind.DIRECT_DEPENDENCY to "b",
            ),
            lib.map { it.kind to it.pom.parent.fileName.toString() },
        )
        assertFalse(lib.last().selected)
        assertTrue(lib[1].description, lib[1].description.startsWith("Exclude org:lib:0.9 brought in by org:brings-lib:1 via org:brings-lib:1 → org:lib:0.9"))

        lib.filter { it.selected }.forEach { f.apply(it.pom, it.fix) }
        assertTrue(f.read("a/pom.xml").contains("<artifactId>lib</artifactId><version>2</version>"))
        assertTrue(f.read("b/pom.xml"), f.read("b/pom.xml").contains("<artifactId>brings-lib</artifactId><version>1</version>\n    <exclusions>\n        <exclusion>"))
        // After the update nothing is left to do.
        val again = f.resolver()
        assertEquals(emptyList<PlanItem>(), VersionUpdatePlanner.plan(again.ws, again, "org", "lib", null, "2").filter { it.selected })

        val guava = VersionUpdatePlanner.plan(again.ws, again, "com.google", "guava", "30", "33")
        assertEquals(listOf(PlanItem.Kind.PROPERTY to "pom.xml", PlanItem.Kind.DECLARATION to "pom.xml"), guava.map { it.kind to it.pom.fileName.toString() })
        assertEquals(f.path("pom.xml"), guava[0].pom)
        assertEquals(f.path("b/pom.xml"), guava[1].pom)
        guava.forEach { f.apply(it.pom, it.fix) }
        assertTrue(f.read("pom.xml").contains("<guava.version>33</guava.version>"))
    }

    @Test
    fun `managed versions are updated in dependencyManagement and need no exclusions`() {
        workspace()
        f.artifact("org:uses-managed:1", deps("org:managed:0.5"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId>${deps("org:uses-managed:1")}"))
        val r = f.resolver()
        val plan = VersionUpdatePlanner.plan(r.ws, r, "org", "managed", "1", "2")
        assertEquals(listOf(PlanItem.Kind.DECLARATION), plan.map { it.kind })
        assertTrue(plan.single().description.contains("<dependencyManagement>"))
    }

    @Test
    fun `only the requested current version is updated`() {
        workspace()
        val r = f.resolver()
        assertEquals(emptyList<PlanItem>(), VersionUpdatePlanner.plan(r.ws, r, "org", "lib", "7", "8").filter { it.kind == PlanItem.Kind.DECLARATION })
    }
}
