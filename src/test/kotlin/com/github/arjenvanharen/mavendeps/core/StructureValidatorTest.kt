package com.github.arjenvanharen.mavendeps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructureValidatorTest {
    private fun problems(body: String, modelVersion: String? = "4.0.0"): List<Problem> {
        val mv = modelVersion?.let { "<modelVersion>$it</modelVersion>" } ?: ""
        return PomParser.parse("<project>$mv$body</project>".toByteArray()).problems
    }

    private val gav = "<groupId>g</groupId><artifactId>a</artifactId><version>1</version>"

    /** Asserts exactly one problem containing [expected] and returns it. */
    private fun single(expected: String, body: String, modelVersion: String? = "4.0.0"): Problem {
        val p = problems(body, modelVersion)
        assertTrue("expected only '$expected' in ${p.map { it.message }}", p.size == 1 && p[0].message.contains(expected))
        return p[0]
    }

    private fun fixTitles(p: Problem) = p.fixes.map { it.title }

    private fun dep(inner: String) = "<dependencies><dependency>$inner</dependency></dependencies>"

    @Test
    fun `valid pom has no problems`() = assertEquals(emptyList<Problem>(), problems(gav))

    @Test
    fun `root must be project`() {
        assertEquals(listOf("Root element must be <project>, found <foo>"), PomParser.parse("<foo/>".toByteArray()).problems.map { it.message })
    }

    @Test
    fun `model version`() {
        assertEquals(listOf("Set <modelVersion> to '4.0.0'"), fixTitles(single("'modelVersion' is missing", gav, null)))
        assertEquals(listOf("Set <modelVersion> to '4.0.0'"), fixTitles(single("'modelVersion' must be one of", gav, "3.0")))
        assertEquals(emptyList<Problem>(), problems(gav, "4.1.0"))
    }

    @Test
    fun `required coordinates`() {
        assertEquals(listOf("Set <artifactId>…"), fixTitles(single("'artifactId' is missing", "<groupId>g</groupId><version>1</version>")))
        single("'groupId' is missing and not inherited", "<artifactId>a</artifactId><version>1</version>")
        single("'version' is missing and not inherited", "<groupId>g</groupId><artifactId>a</artifactId>")
        // Maven 4 infers the version.
        assertEquals(emptyList<Problem>(), problems("<groupId>g</groupId><artifactId>a</artifactId>", "4.1.0"))
        // Inherited from the parent declaration.
        assertEquals(emptyList<Problem>(), problems("<parent><groupId>p</groupId><artifactId>q</artifactId><version>1</version></parent><artifactId>a</artifactId>"))
    }

    @Test
    fun `invalid id characters`() = single("'artifactId' 'a b' contains invalid characters", "<groupId>g</groupId><artifactId>a b</artifactId><version>1</version>").let { }

    @Test
    fun `unknown and duplicated tags`() {
        assertEquals(listOf("Remove <bogus>"), fixTitles(single("Unrecognised tag <bogus> in <project>", "$gav<bogus/>")))
        assertEquals(listOf("Remove <name>"), fixTitles(single("Duplicated tag <name> in <project>", "$gav<name>x</name><name>y</name>")))
        single("Unrecognised tag <foo> in <parent>", "<parent><groupId>p</groupId><artifactId>q</artifactId><version>1</version><foo/></parent><artifactId>a</artifactId>")
        single("Unrecognised tag <foo> in <dependency>", gav + dep("<groupId>d</groupId><artifactId>e</artifactId><version>1</version><foo/>"))
        single("Unrecognised tag <foo> in <exclusion>", gav + dep("<groupId>d</groupId><artifactId>e</artifactId><version>1</version><exclusions><exclusion><foo/></exclusion></exclusions>"))
        single("Unrecognised tag <dep> in <dependencies>", "$gav<dependencies><dep/></dependencies>")
    }

    @Test
    fun `parent checks`() {
        single("'parent.version' is missing", "<parent><groupId>p</groupId><artifactId>q</artifactId></parent><artifactId>a</artifactId><version>1</version>")
        single("Project uses its own coordinates as parent", "<parent><groupId>g</groupId><artifactId>a</artifactId><version>0</version></parent>$gav")
    }

    @Test
    fun `aggregator needs pom packaging`() {
        val p = single("Aggregator projects require 'pom' as packaging but it is 'jar'", "$gav<modules><module>m</module></modules>")
        assertEquals(listOf("Set <packaging> to 'pom'"), fixTitles(p))
        // Modules only in a profile do not make the project an aggregator.
        assertEquals(emptyList<Problem>(), problems("$gav<profiles><profile><id>x</id><modules><module>m</module></modules></profile></profiles>"))
        single("Empty module entry", "$gav<packaging>pom</packaging><modules><module> </module></modules>")
    }

    @Test
    fun `dependency checks`() {
        single("'dependencies.dependency.groupId' is missing", gav + dep("<artifactId>e</artifactId><version>1</version>"))
        single("'dependencyManagement.dependencies.dependency.version' is missing", "$gav<dependencyManagement>${dep("<groupId>d</groupId><artifactId>e</artifactId>")}</dependencyManagement>")
        assertEquals(listOf("Pin version…"), fixTitles(single("Version 'LATEST' for d:e is deprecated", gav + dep("<groupId>d</groupId><artifactId>e</artifactId><version>LATEST</version>"))))
        assertEquals(
            listOf("Remove <scope> (defaults to compile)"),
            fixTitles(single("must be one of", gav + dep("<groupId>d</groupId><artifactId>e</artifactId><version>1</version><scope>wrong</scope>"))),
        )
        single("Scope 'import' for d:e is only meaningful", gav + dep("<groupId>d</groupId><artifactId>e</artifactId><version>1</version><scope>import</scope><type>pom</type>"))
        assertEquals(
            listOf("Set <type> to 'pom'"),
            fixTitles(single("Import-scoped d:e must have type 'pom'", "$gav<dependencyManagement>${dep("<groupId>d</groupId><artifactId>e</artifactId><version>1</version><scope>import</scope>")}</dependencyManagement>")),
        )
        single("systemPath' is missing for system-scoped", gav + dep("<groupId>d</groupId><artifactId>e</artifactId><version>1</version><scope>system</scope>"))
        assertEquals(
            listOf("Remove <systemPath>"),
            fixTitles(single("only allowed for system scope", gav + dep("<groupId>d</groupId><artifactId>e</artifactId><version>1</version><systemPath>/x</systemPath>"))),
        )
        val dup = "<dependency><groupId>d</groupId><artifactId>e</artifactId><version>1</version></dependency>"
        assertEquals(listOf("Remove duplicate declaration"), fixTitles(single("d:e:jar is declared more than once", "$gav<dependencies>$dup$dup</dependencies>")))
        // Same artifact with another classifier is fine.
        assertEquals(emptyList<Problem>(), problems("$gav<dependencies>$dup${dup.replace("</version>", "</version><classifier>tests</classifier>")}</dependencies>"))
    }
}
