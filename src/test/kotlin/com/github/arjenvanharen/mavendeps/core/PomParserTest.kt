package com.github.arjenvanharen.mavendeps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PomParserTest {
    private val pom = """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>g</groupId>
          <artifactId>a</artifactId>
          <version>${'$'}{revision}</version>
          <packaging>pom</packaging>
          <parent><groupId>p</groupId><artifactId>q</artifactId><version>1</version><relativePath/></parent>
          <properties><revision>1.0</revision></properties>
          <modules><module>m1</module></modules>
          <subprojects><subproject>m2</subproject></subprojects>
          <dependencyManagement><dependencies>
            <dependency><groupId>x</groupId><artifactId>y</artifactId><version>2</version><scope>import</scope><type>pom</type></dependency>
          </dependencies></dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>d</groupId><artifactId>e</artifactId><optional>true</optional>
              <exclusions><exclusion><groupId>z</groupId><artifactId>*</artifactId></exclusion></exclusions>
            </dependency>
          </dependencies>
          <build><finalName>${'$'}{name.from.plugin}</finalName></build>
          <profiles><profile><id>extra</id>
            <properties><only.in.profile>x</only.in.profile></properties>
            <modules><module>m3</module></modules>
            <dependencies><dependency><groupId>pd</groupId><artifactId>pa</artifactId><version>${'$'}{pv}</version></dependency></dependencies>
          </profile></profiles>
        </project>
    """.trimIndent()

    @Test
    fun `extracts the model`() {
        val r = PomParser.parse(pom.toByteArray())
        val m = r.model
        assertTrue(m.wellFormed)
        assertEquals(Gav("g", "a", "\${revision}"), m.gav)
        assertEquals("pom", m.effectivePackaging)
        assertEquals(ParentRef(Gav("p", "q", "1"), "", m.parent!!.pos), m.parent)
        assertEquals(listOf("m1" to null, "m2" to null, "m3" to "extra"), m.modules.map { it.name to it.profile })
        assertEquals(mapOf("revision" to "1.0"), m.properties)
        assertEquals(setOf("only.in.profile"), m.profileProperties)
        val (d, bom, profiled) = m.dependencies
        assertEquals(DependencySection.DEPENDENCY_MANAGEMENT, bom.section)
        assertEquals("import", bom.scope)
        assertTrue(d.optional)
        assertEquals(listOf("z:*"), d.exclusions)
        assertEquals("extra", profiled.profile)
        assertEquals(emptyList<Problem>(), r.problems)
    }

    @Test
    fun `property references are critical outside plugin territory`() {
        val refs = PomParser.parse(pom.toByteArray()).model.propertyRefs.associate { it.name to it.critical }
        assertEquals(mapOf("revision" to true, "name.from.plugin" to false, "pv" to true), refs)
    }

    @Test
    fun `positions point at the end of the start tag and the end tag`() {
        val root = PomParser.parse("<project>\n  <a>x</a><b/>\n</project>").root!!
        assertEquals(Pos(2, 6), root.child("a")!!.pos)
        assertEquals(Pos(2, 11), root.child("a")!!.endPos)
        assertEquals(root.child("b")!!.pos, root.child("b")!!.endPos)
    }

    @Test
    fun `text and bytes parse to the same positions`() {
        val text = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<project>\r\n  <name>héllo</name><x/>\r\n</project>"
        fun positions(root: XNode): List<Pos> = listOf(root.pos, root.endPos) + root.children.flatMap { positions(it) }
        assertEquals(positions(PomParser.parse(text.toByteArray()).root!!), positions(PomParser.parse(text).root!!))
    }

    @Test
    fun `syntax errors stop structure validation`() {
        val r = PomParser.parse("<project>\n<a>\n</project>".toByteArray())
        assertFalse(r.model.wellFormed)
        assertEquals(Category.XML_SYNTAX, r.problems.single().category)
        assertEquals(3, r.problems.single().pos.line)
        assertFalse(r.problems.single().message.startsWith("Message:"))
    }

    @Test
    fun `empty file has no root element`() {
        val r = PomParser.parse(ByteArray(0))
        assertEquals(PomModel.EMPTY, r.model)
        assertEquals(Category.XML_SYNTAX, r.problems.single().category)
    }

    @Test
    fun `external entities are not resolved`() {
        val secret = kotlin.io.path.createTempFile().toFile().apply { writeText("TOP-SECRET") }
        val xml = "<?xml version=\"1.0\"?><!DOCTYPE p [<!ENTITY x SYSTEM \"${secret.toURI()}\">]><project><name>&x;</name></project>"
        val r = PomParser.parse(xml.toByteArray())
        assertFalse(r.problems.toString(), r.problems.toString().contains("TOP-SECRET"))
        assertFalse(r.root?.child("name")?.text.orEmpty().contains("TOP-SECRET"))
        secret.delete()
    }

    @Test
    fun `referenced properties`() {
        assertEquals(listOf("a", "b.c"), PomParser.referencedProperties("x\${a}-\${ b.c }\${}"))
        assertEquals(emptyList<String>(), PomParser.referencedProperties("no refs \${unterminated"))
    }
}
