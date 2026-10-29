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

class DependencyResolutionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f by lazy { Fixture(tmp.root.toPath().toAbsolutePath().normalize()) }

    private fun app(body: String) = f.write("pom.xml", pom("<groupId>com.example</groupId><artifactId>app</artifactId><version>1.0</version>$body"))

    private fun tree() = f.resolver().tree(f.path("pom.xml"))

    private fun DependencyTree.lines() = all().map { n ->
        "  ".repeat(n.depth - 1) + n.label + " " + n.scope + when (n.status) {
            NodeStatus.INCLUDED -> if (n.pomFound) "" else " (missing)"
            NodeStatus.OMITTED_DUPLICATE -> " (duplicate)"
            NodeStatus.OMITTED_CONFLICT -> " (conflict with ${n.winnerVersion})"
        }
    }.toList()

    @Test
    fun `nearest wins and the first declaration wins at equal depth`() {
        f.artifact("org:a:1", deps("org:c:1.0"))
        f.artifact("org:b:1", deps("org:c:2.0", "org:d:1"))
        f.artifact("org:c:1.0")
        f.artifact("org:c:2.0")
        f.artifact("org:d:1", deps("org:c:3.0"))
        app(deps("org:a:1", "org:b:1"))
        assertEquals(
            listOf(
                "org:a:1 compile",
                "  org:c:1.0 compile",
                "org:b:1 compile",
                "  org:c:2.0 compile (conflict with 1.0)",
                "  org:d:1 compile",
                "    org:c:3.0 compile (conflict with 1.0)",
            ),
            tree().lines(),
        )
    }

    @Test
    fun `scopes, optional dependencies and exclusions`() {
        f.artifact(
            "org:a:1",
            "<dependencies>${dep("org:t:1", "<scope>test</scope>")}${dep("org:p:1", "<scope>provided</scope>")}" +
                "${dep("org:o:1", "<optional>true</optional>")}${dep("org:r:1", "<scope>runtime</scope>")}${dep("org:x:1")}</dependencies>",
        )
        f.artifact("org:r:1")
        f.artifact("org:x:1", deps("org:y:1"))
        f.artifact("org:y:1")
        app("<dependencies>${dep("org:a:1", "<scope>test</scope>")}${dep("org:x:1", "<exclusions><exclusion><groupId>org</groupId><artifactId>*</artifactId></exclusion></exclusions>")}</dependencies>")
        assertEquals(
            listOf("org:a:1 test", "  org:r:1 test", "  org:x:1 test (duplicate)", "org:x:1 compile"),
            tree().lines(),
        )
    }

    @Test
    fun `runtime scope propagates`() {
        f.artifact("org:a:1", "<dependencies>${dep("org:b:1", "<scope>runtime</scope>")}</dependencies>")
        f.artifact("org:b:1", deps("org:c:1"))
        f.artifact("org:c:1")
        app(deps("org:a:1"))
        assertEquals(listOf("org:a:1 compile", "  org:b:1 runtime", "    org:c:1 runtime"), tree().lines())
    }

    @Test
    fun `dependency management of the module forces transitive versions`() {
        f.artifact("org:a:1", deps("org:c:1.0"))
        f.artifact("org:c:9.0")
        app("<dependencyManagement>${deps("org:c:9.0")}</dependencyManagement>${deps("org:a:1")}")
        val c = tree().all().single { it.artifactId == "c" }
        assertEquals("9.0", c.version)
        assertEquals("1.0", c.managedFrom)
    }

    @Test
    fun `parents, properties and imported BOMs from the repository`() {
        f.artifact("org:bom:1", "<packaging>pom</packaging><dependencyManagement>${deps("org:lib:4.2")}</dependencyManagement>")
        f.artifact(
            "org:parent:1",
            "<packaging>pom</packaging><properties><dep.version>7</dep.version></properties>" +
                "<dependencyManagement><dependencies>${dep("org:bom:1", "<type>pom</type><scope>import</scope>")}</dependencies></dependencyManagement>",
        )
        f.artifact("org:lib:4.2", deps("org:inner:\${dep.version}"))
        f.write(".m2/org/lib/4.2/lib-4.2.pom", pom("<parent><groupId>org</groupId><artifactId>parent</artifactId><version>1</version></parent><artifactId>lib</artifactId><version>4.2</version>${deps("org:inner:\${dep.version}")}"))
        f.artifact("org:inner:7")
        f.write(
            "pom.xml",
            pom("<parent><groupId>org</groupId><artifactId>parent</artifactId><version>1</version><relativePath/></parent><artifactId>app</artifactId>${deps("org:lib")}"),
        )
        assertEquals(listOf("org:lib:4.2 compile", "  org:inner:7 compile"), tree().lines())
        val effective = f.resolver().effective(f.path("pom.xml"))
        assertEquals("7", effective.properties["dep.version"])
        assertEquals("4.2", effective.managedFor("org", "lib")!!.version)
    }

    @Test
    fun `workspace modules are resolved from the workspace and inherited dependencies keep their source`() {
        f.write(
            "pom.xml",
            pom("<groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version><packaging>pom</packaging><modules><module>a</module><module>b</module></modules>${deps("org:common:1")}"),
        )
        val parent = "<parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent>"
        f.write("a/pom.xml", pom("$parent<artifactId>a</artifactId>${deps("com.example:b:1.0")}"))
        f.write("b/pom.xml", pom("$parent<artifactId>b</artifactId>${deps("org:only-b:1")}"))
        val tree = f.resolver().tree(f.path("a/pom.xml"))
        assertEquals(listOf("org:common:1 compile (missing)", "com.example:b:1.0 compile", "  org:common:1 compile (duplicate)", "  org:only-b:1 compile (missing)"), tree.lines())
        assertEquals(f.path("pom.xml"), tree.roots.first().dependency.declaredIn)
        assertEquals(setOf("org:common:1", "org:only-b:1"), tree.missingPoms)
    }

    @Test
    fun `profiles activated by os supply properties and dependencies`() {
        // Like org.openjfx:javafx: the platform classifier comes from an OS-activated profile in the parent.
        f.artifact(
            "org:fx-parent:1",
            "<packaging>pom</packaging><profiles>" +
                "<profile><id>linux</id><activation><os><name>linux</name></os></activation><properties><platform>linux</platform></properties></profile>" +
                "<profile><id>win</id><activation><os><family>windows</family></os></activation><properties><platform>win</platform></properties></profile>" +
                "<profile><id>default</id><activation><activeByDefault>true</activeByDefault></activation>" +
                "<dependencies>${dep("org:only-by-default:1")}</dependencies></profile>" +
                "</profiles>",
        )
        f.write(
            ".m2/org/fx/1/fx-1.pom",
            pom("<parent><groupId>org</groupId><artifactId>fx-parent</artifactId><version>1</version></parent><artifactId>fx</artifactId>" +
                "<dependencies>${dep("org:fx:1", "<classifier>\${platform}</classifier>")}</dependencies>"),
        )
        app(deps("org:fx:1"))
        fun resolve(os: String) = DependencyResolver(f.engine().first.workspaceSnapshot(), f.repo, ActivationContext(os, "amd64", "25", emptyMap()))
            .tree(f.path("pom.xml")).all().map { it.label + (it.dependency.classifier?.let { c -> ":$c" } ?: "") }.toList()
        assertEquals(listOf("org:fx:1", "org:fx:1:linux"), resolve("linux"))
        assertEquals(listOf("org:fx:1", "org:fx:1:win"), resolve("windows 11"))
        // No OS profile matches, so the activeByDefault profile applies and the classifier stays unresolved.
        assertEquals(listOf("org:fx:1", "org:only-by-default:1", "org:fx:1:\${platform}", "org:only-by-default:1"), resolve("plan9"))
    }

    @Test
    fun `activation conditions`() {
        val env = ActivationContext("linux", "amd64", "25.0.5", mapOf("ci" to "true"))
        assertTrue(env.isActive(Activation(jdk = "25")))
        assertFalse(env.isActive(Activation(jdk = "!25")))
        assertTrue(env.isActive(Activation(jdk = "[21,)")))
        assertFalse(env.isActive(Activation(jdk = "[1.8,21)")))
        assertTrue(env.isActive(Activation(osFamily = "unix", osArch = "amd64")))
        assertFalse(env.isActive(Activation(osFamily = "unix", osArch = "aarch64")))
        assertTrue(env.isActive(Activation(osFamily = "!windows")))
        assertTrue(env.isActive(Activation(propertyName = "ci")))
        assertTrue(env.isActive(Activation(propertyName = "ci", propertyValue = "true")))
        assertFalse(env.isActive(Activation(propertyName = "!ci")))
        assertTrue(env.isActive(Activation(propertyName = "!release")))
        assertFalse(env.isActive(Activation()))
        assertFalse(env.isActive(Activation(activeByDefault = true)))
    }

    @Test
    fun `local repository location and caching`() {
        f.artifact("org:a:1")
        val repo = f.repo
        assertEquals(f.path(".m2/org/a/1/a-1.pom"), repo.pomPath("org", "a", "1"))
        assertTrue(repo.pom("org", "a", "1")!!.wellFormed)
        assertNull(repo.pom("org", "a", "2"))
        assertNull(repo.pom("org", "a", "\${v}"))
        assertFalse(LocalRepository.default().root.toString().isEmpty())
    }
}
