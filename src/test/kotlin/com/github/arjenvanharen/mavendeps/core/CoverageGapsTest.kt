package com.github.arjenvanharen.mavendeps.core

import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.dep
import com.github.arjenvanharen.mavendeps.core.Fixture.Companion.pom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Paths

/** Behaviour that the feature tests did not reach: serialization, JSON, OSV records, profiles and workspace BOMs. */
class CoverageGapsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f by lazy { Fixture(tmp.root.toPath().toAbsolutePath().normalize()) }

    @Test
    fun `the disk cache keeps every kind of fix, profile and dependency detail`() {
        val pos = Pos(3, 7)
        val ref = ElementRef(Paths.get("/w/a/pom.xml"), "dependency", pos)
        val fixes = listOf(
            RemoveElement("remove", ElementRef(null, null, Pos.NONE)),
            SetChild("set", ref, "version", null),
            DefineProperty("define", Paths.get("/w/pom.xml"), "x.version", "1"),
            DefineProperty("define here", null, "y", null),
            AddExclusion("exclude", ref, "g", "a"),
            AddDependency("add", null, "g", "a", "1", managed = true),
            CompositeFix("both", listOf(SetChild("s", ref, "scope", "test"), AddDependency("d", Paths.get("/w/pom.xml"), "g", "b", "2"))),
        )
        val model = PomModel(
            wellFormed = true, modelVersion = "4.0.0", gav = Gav("g", "a", "1"), gavPos = pos, packaging = "pom",
            parent = ParentRef(Gav("g", "p", "1"), "", pos),
            modules = listOf(ModuleRef("m", pos, null), ModuleRef("n", pos, "ci")),
            properties = linkedMapOf("a" to "1", "b" to ""), profileProperties = setOf("c"),
            dependencies = listOf(
                Dependency("g", "d", null, "test-jar", "tests", "test", null, DependencySection.DEPENDENCIES, "ci", pos, optional = true, exclusions = listOf("*:*")),
                Dependency("g", "bom", "1", "pom", null, "import", "/x", DependencySection.DEPENDENCY_MANAGEMENT, null, pos),
            ),
            propertyRefs = listOf(PropertyRef("a", pos, true), PropertyRef("z", pos, false)),
            profiles = listOf(Profile("ci", Activation(true, "[17,)", "unix", "linux", "amd64", "!skip", "x"), mapOf("c" to "3"))),
        )
        val entry = PomEntry(Paths.get("/w/pom.xml"), 10, 20, "hash", model, fixes.map { Problem(Severity.WARNING, Category.SUGGESTIONS, "m", pos, listOf(it)) })
        val file = f.path("cache.bin")
        CacheStore.write(file, listOf(entry, entry.copy(path = Paths.get("/w/b/pom.xml"), model = PomModel.EMPTY, localProblems = emptyList())))
        val read = CacheStore.read(file)
        assertEquals(2, read.size)
        assertEquals(entry, read[0])
        assertEquals(PomModel.EMPTY, read[1].model)
    }

    @Test
    fun `json reads escapes and rejects malformed input`() {
        assertEquals("a\n\t\r\b\u000C/é\"", Json.parse("\"a\\n\\t\\r\\b\\f\\/\\u00e9\\\"\""))
        assertEquals(listOf(-1.5e3, true, false, null, emptyMap<String, Any?>(), emptyList<Any?>()), Json.parse(" [ -1.5e3 , true, false, null, {}, [] ] "))
        assertEquals("\"\\u0001\"", Json.quote("\u0001"))
        for (bad in listOf("", "[1,]", "{\"a\" 1}", "{\"a\":1,}", "{1:2}", "[1 2]", "\"abc", "\"\\u12\"", "\"\\", "tru", "-x", "{} x", "[".repeat(500) + "]".repeat(500))) {
            try {
                Json.parse(bad)
                fail("accepted: ${bad.take(20)}")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `osv records map severities, fixes and summaries`() {
        fun parse(json: String) = OsvClient.parse(Json.parse(json))
        val maven = """"affected":[{"package":{"ecosystem":"Maven","name":"g:a"},"ranges":[{"events":[{"introduced":"0"},{"fixed":"1.2"}]}],
            "ecosystem_specific":{"affected_functions":["org.x.Bad.run"]}},{"package":{"ecosystem":"npm","name":"a"},"ranges":[{"events":[{"fixed":"9"}]}]}]"""
        val v = parse("""{"id":"GHSA-x","details":"\n\nFirst line of details\nmore","database_specific":{"severity":"MODERATE"},$maven}""")
        assertEquals("First line of details", v.summary)
        assertEquals(VulnSeverity.MEDIUM, v.severity)
        assertEquals(mapOf("g:a" to listOf("1.2")), v.fixedIn)
        assertEquals(listOf("org.x.Bad.run"), v.symbols)
        assertNull(v.cve)
        assertEquals(VulnSeverity.LOW, parse("""{"id":"x","database_specific":{"severity":"LOW"}}""").severity)
        assertEquals(VulnSeverity.HIGH, parse("""{"id":"x","database_specific":{"severity":"high"}}""").severity)
        assertEquals(VulnSeverity.CRITICAL, parse("""{"id":"x","database_specific":{"severity":"CRITICAL"}}""").severity)
        // Without a database severity the CVSS v3 score decides; without either it is unknown.
        assertEquals(VulnSeverity.MEDIUM, parse("""{"id":"x","severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:H/PR:L/UI:R/S:U/C:L/I:L/A:L"}]}""").severity)
        assertEquals(VulnSeverity.UNKNOWN, parse("""{"id":"x","severity":[{"type":"CVSS_V4","score":"CVSS:4.0/AV:N"}]}""").severity)
        assertEquals(VulnSeverity.LOW, Cvss.severity(0.1))
        assertEquals(VulnSeverity.UNKNOWN, Cvss.severity(0.0))
        assertEquals(0.0, Cvss.score("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N"))
        assertNull(Cvss.score("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:X/C:H/I:H/A:H"))
        try {
            parse("{}")
            fail("an advisory without id was accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    // S5
    @Test
    fun `osv ids must look like advisory ids and paging is bounded`() {
        val gets = ArrayList<String>()
        var posts = 0
        val transport = object : OsvTransport {
            override fun post(url: String, json: String): String {
                posts++
                // A server that never stops paging, and ids that would change the request path.
                return if (url.endsWith("/querybatch")) """{"results":[{"vulns":[{"id":"GHSA-ok","modified":"2026-01-01"},{"id":"../../admin"},{"id":"a b"}],"next_page_token":"t"}]}"""
                else """{"vulns":[],"next_page_token":"t"}"""
            }

            override fun get(url: String): String {
                gets += url
                return """{"id":"GHSA-ok","modified":"2026-01-01","summary":"s"}"""
            }
        }
        val found = OsvClient(transport, "https://osv.test/v1").query(listOf(ArtifactVersion("g", "a", "1")))
        assertEquals(listOf("https://osv.test/v1/vulns/GHSA-ok"), gets)
        assertEquals(listOf("GHSA-ok"), found.values.single().map { it.id })
        assertTrue(posts in 2..102)
        // Advisory details are cached across scans while their modified stamp is unchanged.
        OsvClient(transport, "https://osv.test/v1").also { c -> c.query(listOf(ArtifactVersion("g", "a", "1"))); c.query(listOf(ArtifactVersion("g", "a", "1"))) }
        assertEquals(2, gets.size)
    }

    @Test
    fun `profiles activate by os family, name, arch and jdk ranges`() {
        fun ctx(os: String) = ActivationContext(os, "amd64", "21.0.2", mapOf("env" to "ci"))
        fun active(c: ActivationContext, a: Activation) = c.isActive(a)
        assertTrue(active(ctx("mac os x"), Activation(osFamily = "mac")))
        assertTrue(active(ctx("mac os x"), Activation(osFamily = "unix")))
        assertTrue(active(ctx("linux"), Activation(osFamily = "unix")))
        assertTrue(active(ctx("windows 11"), Activation(osFamily = "dos")))
        assertTrue(active(ctx("windows 11"), Activation(osFamily = "!unix")))
        assertTrue(active(ctx("sunos"), Activation(osFamily = "sun")))
        assertTrue(active(ctx("linux"), Activation(osName = "LINUX", osArch = "amd64")))
        assertTrue(active(ctx("linux"), Activation(jdk = "[17,)")))
        assertTrue(active(ctx("linux"), Activation(jdk = "(,11],[21,22)")))
        assertTrue(!active(ctx("linux"), Activation(jdk = "(21.0.2,)")))
        assertTrue(active(ctx("linux"), Activation(jdk = "!1.8")))
        assertTrue(active(ctx("linux"), Activation(propertyName = "env", propertyValue = "!local")))
        assertTrue(active(ctx("linux"), Activation(propertyName = "!release")))
        assertTrue(!active(ctx("linux"), Activation()))
        // activeByDefault only applies when nothing else is active.
        val model = PomModel.EMPTY.copy(profiles = listOf(Profile("d", Activation(activeByDefault = true), emptyMap()), Profile("e", Activation(propertyName = "env"), emptyMap())))
        assertEquals(setOf("e"), ctx("linux").activeProfiles(model))
    }

    @Test
    fun `a BOM built in the workspace manages the versions of the modules that import it`() {
        f.write("pom.xml", pom("<groupId>g</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>bom</module><module>app</module></modules>"))
        f.write("bom/pom.xml", pom("<groupId>g</groupId><artifactId>bom</artifactId><version>\${v}</version><packaging>pom</packaging><properties><v>1</v></properties>" +
            "<dependencyManagement><dependencies>${dep("org:lib:2")}</dependencies></dependencyManagement>"))
        val app = f.write("app/pom.xml", pom("<groupId>g</groupId><artifactId>app</artifactId><version>1</version>" +
            "<dependencyManagement><dependencies>${dep("g:bom:1", "<type>pom</type><scope>import</scope>")}</dependencies></dependencyManagement>" +
            "<dependencies>${dep("org:lib")}${dep("org:other")}</dependencies>"))
        val messages = f.engine().second.poms.getValue(app).problems.map { it.message }
        assertTrue(messages.none { "org:lib" in it })
        assertTrue(messages.any { "org:other:jar" in it && "not managed" in it })
    }

    @Test
    fun `a parent cycle is reported`() {
        val a = f.write("a/pom.xml", pom("<parent><groupId>g</groupId><artifactId>b</artifactId><version>1</version><relativePath>../b/pom.xml</relativePath></parent><artifactId>a</artifactId><packaging>pom</packaging>"))
        f.write("b/pom.xml", pom("<parent><groupId>g</groupId><artifactId>a</artifactId><version>1</version><relativePath>../a/pom.xml</relativePath></parent><artifactId>b</artifactId><packaging>pom</packaging>"))
        val result = AnalysisEngine().refresh(listOf(a), RefreshMode.FULL)
        assertTrue(result.poms.getValue(a).problems.any { "contains a cycle" in it.message })
    }

    @Test
    fun `a child that belongs first is inserted before the existing children`() {
        val text = pom("<groupId>g</groupId><artifactId>a</artifactId><version>1</version>").replace("    <modelVersion>4.0.0</modelVersion>\n", "")
        val path = f.write("pom.xml", text)
        f.apply(path, SetChild("set", ElementRef(null, null, Pos.NONE), "modelVersion", "4.0.0"))
        assertTrue(f.read("pom.xml").contains("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n    <modelVersion>4.0.0</modelVersion>\n    <groupId>g</groupId>"))
    }
}
