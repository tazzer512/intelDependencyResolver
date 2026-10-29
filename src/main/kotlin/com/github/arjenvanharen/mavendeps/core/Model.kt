package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Path

/** 1-based line/column inside a POM file. [NONE] when unknown. */
data class Pos(val line: Int, val column: Int) {
    companion object {
        val NONE = Pos(0, 0)
    }
}

enum class Severity { ERROR, WARNING, INFO }

enum class Category(val displayName: String) {
    XML_SYNTAX("XML syntax"),
    STRUCTURE("POM structure"),
    MULTI_MODULE("Multi-module"),
    PROPERTIES("Properties"),
}

data class Problem(val severity: Severity, val category: Category, val message: String, val pos: Pos)

data class Gav(val groupId: String?, val artifactId: String?, val version: String?) {
    val ga: String get() = "${groupId ?: "?"}:${artifactId ?: "?"}"

    override fun toString() = "${groupId ?: "?"}:${artifactId ?: "?"}:${version ?: "?"}"
}

/**
 * The `<parent>` element. [relativePath] is null when absent (Maven default `../pom.xml`)
 * and empty for `<relativePath/>`, which disables the local lookup.
 */
data class ParentRef(val gav: Gav, val relativePath: String?, val pos: Pos)

/** A `<module>` (or Maven 4 `<subproject>`) entry; [profile] is set when declared inside a profile. */
data class ModuleRef(val name: String, val pos: Pos, val profile: String?)

enum class DependencySection { DEPENDENCIES, DEPENDENCY_MANAGEMENT }

data class Dependency(
    val groupId: String?,
    val artifactId: String?,
    val version: String?,
    val type: String?,
    val classifier: String?,
    val scope: String?,
    val systemPath: String?,
    val section: DependencySection,
    val profile: String?,
    val pos: Pos,
) {
    /** Maven's management key: `groupId:artifactId:type[:classifier]`. */
    fun managementKey(resolve: (String?) -> String? = { it }): String {
        val base = "${resolve(groupId)}:${resolve(artifactId)}:${resolve(type) ?: "jar"}"
        val c = resolve(classifier)
        return if (c.isNullOrEmpty()) base else "$base:$c"
    }

    val display: String get() = "${groupId ?: "?"}:${artifactId ?: "?"}${version?.let { ":$it" } ?: ""}"
}

/** A `${name}` reference. [critical] references affect the project model (coordinates, dependencies, ...). */
data class PropertyRef(val name: String, val pos: Pos, val critical: Boolean)

data class PomModel(
    val wellFormed: Boolean,
    val modelVersion: String?,
    /** Coordinates as declared in the file, without inheritance. */
    val gav: Gav,
    /** Where the project's own coordinates are declared (artifactId, else the root element). */
    val gavPos: Pos,
    val packaging: String?,
    val parent: ParentRef?,
    val modules: List<ModuleRef>,
    val properties: Map<String, String>,
    val profileProperties: Set<String>,
    val dependencies: List<Dependency>,
    val propertyRefs: List<PropertyRef>,
) {
    /** Coordinates with groupId/version inherited from the parent declaration when missing. */
    val effectiveGav: Gav
        get() = Gav(gav.groupId ?: parent?.gav?.groupId, gav.artifactId, gav.version ?: parent?.gav?.version)

    val effectivePackaging: String get() = packaging ?: "jar"

    companion object {
        val EMPTY = PomModel(false, null, Gav(null, null, null), Pos.NONE, null, null, emptyList(), emptyMap(), emptySet(), emptyList(), emptyList())
    }
}

/** One parsed POM file plus the stamp used to decide whether it must be parsed again. */
data class PomEntry(
    val path: Path,
    val size: Long,
    val lastModified: Long,
    val hash: String,
    val model: PomModel,
    /** Problems that only depend on the file itself (syntax, structure). */
    val localProblems: List<Problem>,
)
