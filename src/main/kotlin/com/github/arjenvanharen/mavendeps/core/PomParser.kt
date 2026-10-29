package com.github.arjenvanharen.mavendeps.core

import java.io.ByteArrayInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/** Minimal element tree with source positions; enough for POM extraction and validation. */
class XNode(val name: String, val pos: Pos, val parent: XNode?) {
    val children = ArrayList<XNode>()
    val attributes = LinkedHashMap<String, String>()
    internal val textBuilder = StringBuilder()

    val text: String get() = textBuilder.toString().trim()

    fun child(name: String): XNode? = children.firstOrNull { it.name == name }

    fun children(name: String): List<XNode> = children.filter { it.name == name }

    fun childText(name: String): String? = child(name)?.text

    fun ancestors(): Sequence<XNode> = generateSequence(parent) { it.parent }
}

class ParseResult(val root: XNode?, val model: PomModel, val problems: List<Problem>)

object PomParser {
    private val factory: XMLInputFactory = XMLInputFactory.newDefaultFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_COALESCING, true)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
    }

    fun parse(bytes: ByteArray): ParseResult {
        val problems = ArrayList<Problem>()
        var root: XNode? = null
        var wellFormed = true
        try {
            val reader = synchronized(factory) { factory.createXMLStreamReader(ByteArrayInputStream(bytes)) }
            try {
                var current: XNode? = null
                while (reader.hasNext()) {
                    when (reader.next()) {
                        XMLStreamConstants.START_ELEMENT -> {
                            val node = XNode(reader.localName, Pos(reader.location.lineNumber, reader.location.columnNumber), current)
                            for (i in 0 until reader.attributeCount) {
                                node.attributes[reader.getAttributeLocalName(i)] = reader.getAttributeValue(i)
                            }
                            if (current == null) root = node else current.children.add(node)
                            current = node
                        }
                        XMLStreamConstants.END_ELEMENT -> current = current?.parent
                        XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                            current?.textBuilder?.append(reader.text)
                    }
                }
            } finally {
                reader.close()
            }
        } catch (e: XMLStreamException) {
            wellFormed = false
            val loc = e.location
            problems += Problem(
                Severity.ERROR, Category.XML_SYNTAX,
                cleanMessage(e.message),
                if (loc != null) Pos(loc.lineNumber, loc.columnNumber) else Pos.NONE,
            )
        }
        if (root == null) {
            if (problems.isEmpty()) problems += Problem(Severity.ERROR, Category.XML_SYNTAX, "File contains no root element", Pos.NONE)
            return ParseResult(null, PomModel.EMPTY, problems)
        }
        val model = extract(root, wellFormed)
        // Structural checks on a truncated tree would only produce noise; report the syntax error alone.
        if (wellFormed) problems += StructureValidator.validate(root, model)
        return ParseResult(root, model, problems)
    }

    private fun cleanMessage(message: String?): String {
        if (message == null) return "Malformed XML"
        val idx = message.indexOf("Message:")
        return (if (idx >= 0) message.substring(idx + "Message:".length) else message).trim()
    }

    private fun extract(root: XNode, wellFormed: Boolean): PomModel {
        val modules = ArrayList<ModuleRef>()
        val dependencies = ArrayList<Dependency>()
        val profileProperties = LinkedHashSet<String>()

        fun collect(owner: XNode, profile: String?) {
            for (container in owner.children("modules")) container.children("module").forEach { modules += ModuleRef(it.text, it.pos, profile) }
            for (container in owner.children("subprojects")) container.children("subproject").forEach { modules += ModuleRef(it.text, it.pos, profile) }
            owner.children("dependencies").forEach { deps ->
                deps.children("dependency").forEach { dependencies += dependency(it, DependencySection.DEPENDENCIES, profile) }
            }
            owner.children("dependencyManagement").forEach { dm ->
                dm.children("dependencies").forEach { deps ->
                    deps.children("dependency").forEach { dependencies += dependency(it, DependencySection.DEPENDENCY_MANAGEMENT, profile) }
                }
            }
        }

        collect(root, null)
        root.child("profiles")?.children("profile")?.forEach { profile ->
            val id = profile.childText("id") ?: "<no id>"
            collect(profile, id)
            profile.child("properties")?.children?.forEach { profileProperties += it.name }
        }

        val properties = LinkedHashMap<String, String>()
        root.child("properties")?.children?.forEach { properties[it.name] = it.text }

        val parent = root.child("parent")?.let {
            ParentRef(Gav(it.childText("groupId"), it.childText("artifactId"), it.childText("version")), it.child("relativePath")?.text, it.pos)
        }

        return PomModel(
            wellFormed = wellFormed,
            modelVersion = root.childText("modelVersion"),
            gav = Gav(root.childText("groupId"), root.childText("artifactId"), root.childText("version")),
            gavPos = (root.child("artifactId") ?: root).pos,
            packaging = root.childText("packaging"),
            parent = parent,
            modules = modules,
            properties = properties,
            profileProperties = profileProperties,
            dependencies = dependencies,
            propertyRefs = collectPropertyRefs(root),
        )
    }

    private fun dependency(node: XNode, section: DependencySection, profile: String?) = Dependency(
        groupId = node.childText("groupId"),
        artifactId = node.childText("artifactId"),
        version = node.childText("version"),
        type = node.childText("type"),
        classifier = node.childText("classifier"),
        scope = node.childText("scope"),
        systemPath = node.childText("systemPath"),
        section = section,
        profile = profile,
        pos = node.pos,
    )

    /** Elements whose contents are interpreted by plugins rather than by the model builder. */
    private val NON_CRITICAL_SCOPES = setOf("build", "reporting", "configuration", "scm", "distributionManagement", "site", "url")

    private fun collectPropertyRefs(root: XNode): List<PropertyRef> {
        val refs = ArrayList<PropertyRef>()
        fun visit(node: XNode, critical: Boolean) {
            val nowCritical = critical && node.name !in NON_CRITICAL_SCOPES
            for (name in referencedProperties(node.text)) refs += PropertyRef(name, node.pos, nowCritical)
            for (value in node.attributes.values) for (name in referencedProperties(value)) refs += PropertyRef(name, node.pos, false)
            node.children.forEach { visit(it, nowCritical) }
        }
        visit(root, true)
        return refs
    }

    /** Names referenced as `${name}` in [text]; nested expressions are skipped. */
    fun referencedProperties(text: String): List<String> {
        if (!text.contains("\${")) return emptyList()
        val result = ArrayList<String>()
        var i = text.indexOf("\${")
        while (i >= 0) {
            val end = text.indexOf('}', i + 2)
            if (end < 0) break
            val name = text.substring(i + 2, end).trim()
            if (name.isNotEmpty() && !name.contains("\${")) result += name
            i = text.indexOf("\${", end + 1)
        }
        return result
    }
}
