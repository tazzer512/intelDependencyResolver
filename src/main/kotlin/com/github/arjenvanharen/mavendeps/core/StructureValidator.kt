package com.github.arjenvanharen.mavendeps.core

/** Checks that only need a single file: element names, required coordinates, dependency declarations. */
object StructureValidator {
    private val PROJECT_CHILDREN = setOf(
        "modelVersion", "parent", "groupId", "artifactId", "version", "packaging", "name", "description", "url",
        "inceptionYear", "organization", "licenses", "developers", "contributors", "mailingLists", "prerequisites",
        "modules", "subprojects", "scm", "issueManagement", "ciManagement", "distributionManagement", "properties",
        "dependencyManagement", "dependencies", "repositories", "pluginRepositories", "build", "reporting",
        "profiles", "reports",
    )
    private val PARENT_CHILDREN = setOf("groupId", "artifactId", "version", "relativePath")
    private val DEPENDENCY_CHILDREN = setOf(
        "groupId", "artifactId", "version", "type", "classifier", "scope", "systemPath", "exclusions", "optional",
    )
    private val EXCLUSION_CHILDREN = setOf("groupId", "artifactId")
    private val MODEL_VERSIONS = setOf("4.0.0", "4.1.0")
    private val SCOPES = setOf("compile", "provided", "runtime", "test", "system", "import")
    private val ID_PATTERN = Regex("[A-Za-z0-9_\\-.]+")

    fun validate(root: XNode, model: PomModel): List<Problem> {
        val out = ArrayList<Problem>()
        fun error(msg: String, node: XNode, vararg fixes: QuickFix) = out.add(Problem(Severity.ERROR, Category.STRUCTURE, msg, node.pos, fixes.toList()))
        fun set(node: XNode, child: String, value: String?, title: String = if (value == null) "Set <$child>…" else "Set <$child> to '$value'") =
            SetChild(title, ref(node), child, value)

        if (root.name != "project") {
            error("Root element must be <project>, found <${root.name}>", root)
            return out
        }

        checkChildren(root, PROJECT_CHILDREN, "project", out)

        when (val mv = model.modelVersion) {
            null -> error("'modelVersion' is missing", root, set(root, "modelVersion", "4.0.0"))
            !in MODEL_VERSIONS -> error("'modelVersion' must be one of $MODEL_VERSIONS but is '$mv'", root.child("modelVersion")!!, set(root, "modelVersion", "4.0.0"))
        }
        val maven4 = model.modelVersion == "4.1.0"

        if (model.gav.artifactId.isNullOrEmpty()) error("'artifactId' is missing", root, set(root, "artifactId", null))
        if (model.effectiveGav.groupId.isNullOrEmpty()) error("'groupId' is missing and not inherited from a parent", root, set(root, "groupId", null))
        if (model.effectiveGav.version.isNullOrEmpty() && !maven4) {
            error("'version' is missing and not inherited from a parent", root, set(root, "version", null))
        }
        checkId("groupId", model.gav.groupId, root.child("groupId"), out)
        checkId("artifactId", model.gav.artifactId, root.child("artifactId"), out)

        root.child("parent")?.let { parent ->
            checkChildren(parent, PARENT_CHILDREN, "parent", out)
            if (parent.childText("groupId").isNullOrEmpty()) error("'parent.groupId' is missing", parent, set(parent, "groupId", null))
            if (parent.childText("artifactId").isNullOrEmpty()) error("'parent.artifactId' is missing", parent, set(parent, "artifactId", null))
            if (parent.childText("version").isNullOrEmpty() && !maven4) error("'parent.version' is missing", parent, set(parent, "version", null))
            if (model.gav.groupId != null && model.gav.groupId == parent.childText("groupId") &&
                model.gav.artifactId != null && model.gav.artifactId == parent.childText("artifactId")
            ) {
                error("Project uses its own coordinates as parent", parent)
            }
        }

        if (model.modules.any { it.profile == null } && model.effectivePackaging != "pom") {
            error(
                "Aggregator projects require 'pom' as packaging but it is '${model.effectivePackaging}'", root.child("packaging") ?: root,
                set(root, "packaging", "pom"),
            )
        }
        model.modules.filter { it.name.isEmpty() }.forEach {
            out += Problem(Severity.ERROR, Category.STRUCTURE, "Empty module entry", it.pos, listOf(RemoveElement("Remove empty module entry", ElementRef(null, null, it.pos))))
        }

        for (owner in listOf(root) + (root.child("profiles")?.children("profile") ?: emptyList())) {
            owner.children("dependencies").forEach { validateDependencies(it, DependencySection.DEPENDENCIES, out) }
            owner.children("dependencyManagement").forEach { dm ->
                dm.children("dependencies").forEach { validateDependencies(it, DependencySection.DEPENDENCY_MANAGEMENT, out) }
            }
        }
        return out
    }

    private fun validateDependencies(container: XNode, section: DependencySection, out: MutableList<Problem>) {
        val prefix = if (section == DependencySection.DEPENDENCIES) "dependencies.dependency" else "dependencyManagement.dependencies.dependency"
        val seen = HashMap<String, XNode>()
        for (dep in container.children) {
            if (dep.name != "dependency") {
                out += Problem(Severity.ERROR, Category.STRUCTURE, "Unrecognised tag <${dep.name}> in <dependencies>", dep.pos, listOf(removeFix(dep)))
                continue
            }
            checkChildren(dep, DEPENDENCY_CHILDREN, "dependency", out)
            dep.child("exclusions")?.children("exclusion")?.forEach { checkChildren(it, EXCLUSION_CHILDREN, "exclusion", out) }

            val g = dep.childText("groupId")
            val a = dep.childText("artifactId")
            val v = dep.childText("version")
            val type = dep.childText("type") ?: "jar"
            val scope = dep.childText("scope")
            val label = "${g ?: "?"}:${a ?: "?"}"
            fun error(msg: String, vararg fixes: QuickFix) = out.add(Problem(Severity.ERROR, Category.STRUCTURE, msg, dep.pos, fixes.toList()))
            fun warn(msg: String, vararg fixes: QuickFix) = out.add(Problem(Severity.WARNING, Category.STRUCTURE, msg, dep.pos, fixes.toList()))
            fun ask(child: String) = SetChild("Set <$child>…", ref(dep), child, null)

            if (g.isNullOrEmpty()) error("'$prefix.groupId' is missing for $label", ask("groupId"))
            if (a.isNullOrEmpty()) error("'$prefix.artifactId' is missing for $label", ask("artifactId"))
            if (section == DependencySection.DEPENDENCY_MANAGEMENT && v.isNullOrEmpty()) error("'$prefix.version' is missing for $label", ask("version"))
            if (v == "RELEASE" || v == "LATEST") {
                warn("Version '$v' for $label is deprecated and makes builds non-reproducible", SetChild("Pin version…", ref(dep), "version", null))
            }

            val scopeNode = dep.child("scope")
            if (scope != null && scopeNode != null && !scope.contains("\${")) {
                when {
                    scope !in SCOPES -> warn(
                        "'$prefix.scope' for $label must be one of $SCOPES but is '$scope'",
                        RemoveElement("Remove <scope> (defaults to compile)", ref(scopeNode)),
                    )
                    scope == "import" && section == DependencySection.DEPENDENCIES ->
                        warn("Scope 'import' for $label is only meaningful in <dependencyManagement>", RemoveElement("Remove <scope>", ref(scopeNode)))
                    scope == "import" && type != "pom" ->
                        error("Import-scoped $label must have type 'pom' but is '$type'", SetChild("Set <type> to 'pom'", ref(dep), "type", "pom"))
                }
            }
            if (scope == "system" && dep.childText("systemPath").isNullOrEmpty()) error("'$prefix.systemPath' is missing for system-scoped $label", ask("systemPath"))
            val systemPath = dep.child("systemPath")
            if (scope != "system" && systemPath != null) {
                warn("'$prefix.systemPath' is only allowed for system scope ($label)", RemoveElement("Remove <systemPath>", ref(systemPath)))
            }

            if (g != null && a != null) {
                val key = "$g:$a:$type" + (dep.childText("classifier")?.let { ":$it" } ?: "")
                if (seen.put(key, dep) != null) {
                    warn(
                        "'$prefix.(groupId:artifactId:type:classifier)' must be unique: $key is declared more than once",
                        RemoveElement("Remove duplicate declaration", ref(dep)),
                    )
                }
            }
        }
    }

    private fun checkChildren(node: XNode, allowed: Set<String>, parentName: String, out: MutableList<Problem>) {
        val seen = HashSet<String>()
        for (child in node.children) {
            if (child.name !in allowed) {
                out += Problem(Severity.ERROR, Category.STRUCTURE, "Unrecognised tag <${child.name}> in <$parentName>", child.pos, listOf(removeFix(child)))
            } else if (!seen.add(child.name)) {
                out += Problem(Severity.ERROR, Category.STRUCTURE, "Duplicated tag <${child.name}> in <$parentName>", child.pos, listOf(removeFix(child)))
            }
        }
    }

    private fun ref(node: XNode) = ElementRef(null, node.name, if (node.parent == null) Pos.NONE else node.pos)

    private fun removeFix(node: XNode) = RemoveElement("Remove <${node.name}>", ref(node))

    private fun checkId(field: String, value: String?, node: XNode?, out: MutableList<Problem>) {
        if (node == null || value.isNullOrEmpty() || value.contains("\${")) return
        if (!ID_PATTERN.matches(value)) {
            out += Problem(Severity.ERROR, Category.STRUCTURE, "'$field' '$value' contains invalid characters (allowed: A-Z a-z 0-9 _ - .)", node.pos)
        }
    }
}
