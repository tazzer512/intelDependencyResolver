package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only view of the local Maven repository. Nothing is downloaded: artifacts that were never resolved by a
 * Maven build are reported as missing. Parsed POMs are cached by file stamp.
 */
class LocalRepository(val root: Path) {
    private class Cached(val size: Long, val mtime: Long, val model: PomModel?)

    private val cache = ConcurrentHashMap<Path, Cached>()

    /**
     * The directory of an artifact, or null when the coordinates are not usable as path segments. They come from
     * (possibly untrusted) POMs, so `..`, separators and expressions are rejected and the result must stay under [root].
     */
    private fun artifactDir(groupId: String, artifactId: String): Path? {
        val segments = groupId.split('.') + artifactId
        if (segments.any { !isSafeSegment(it) }) return null
        return segments.fold(root) { dir, s -> dir.resolve(s) }.normalize().takeIf { it.startsWith(root.normalize()) }
    }

    private fun isSafeSegment(s: String) =
        s.isNotEmpty() && s != "." && s != ".." && s.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() } && "\${" !in s

    /** Where the POM of an artifact lives, or null when the coordinates cannot name a file in the repository. */
    fun pomPath(groupId: String, artifactId: String, version: String): Path? {
        if (!isSafeSegment(version)) return null
        return artifactDir(groupId, artifactId)?.resolve(version)?.resolve("$artifactId-$version.pom")
    }

    fun pom(groupId: String, artifactId: String, version: String): PomModel? {
        val file = pomPath(groupId, artifactId, version) ?: return null
        val attrs = try {
            Files.readAttributes(file, BasicFileAttributes::class.java)
        } catch (_: Exception) {
            return null
        }
        if (!attrs.isRegularFile || attrs.size() > PomParser.MAX_BYTES) return null
        val hit = cache[file]
        if (hit != null && hit.size == attrs.size() && hit.mtime == attrs.lastModifiedTime().toMillis()) return hit.model
        val model = try {
            PomParser.parse(Files.readAllBytes(file)).model.takeIf { it.wellFormed }
        } catch (_: Exception) {
            null
        }
        cache[file] = Cached(attrs.size(), attrs.lastModifiedTime().toMillis(), model)
        return model
    }

    /**
     * Versions of an artifact in the repository that have a POM, oldest first. Directories left behind by failed
     * downloads (only `*.lastUpdated` files) are skipped.
     */
    fun versions(groupId: String, artifactId: String): List<String> {
        val dir = artifactDir(groupId, artifactId) ?: return emptyList()
        val names = try {
            Files.list(dir).use { s -> s.filter { Files.isRegularFile(it.resolve("$artifactId-${it.fileName}.pom")) }.map { it.fileName.toString() }.toList() }
        } catch (_: Exception) {
            return emptyList()
        }
        return names.sortedWith(MavenVersion.COMPARATOR)
    }

    /** Local versions newer than [version]; pre-releases only when [version] is one itself. */
    fun newerVersions(groupId: String, artifactId: String, version: String): List<String> {
        val current = MavenVersion(version)
        return versions(groupId, artifactId).filter { v -> MavenVersion(v).let { it > current && (current.isPreRelease || !it.isPreRelease) } }
    }

    companion object {
        /** `-Dmaven.repo.local`, else `<localRepository>` from `~/.m2/settings.xml`, else `~/.m2/repository`. */
        fun default(): LocalRepository {
            System.getProperty("maven.repo.local")?.takeIf { it.isNotBlank() }?.let { return LocalRepository(Paths.get(it)) }
            val m2 = Paths.get(System.getProperty("user.home"), ".m2")
            val fromSettings = try {
                PomParser.parse(Files.readAllBytes(m2.resolve("settings.xml"))).root?.childText("localRepository")
                    ?.replace("\${user.home}", System.getProperty("user.home"))?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
            return LocalRepository(fromSettings?.let { Paths.get(it) } ?: m2.resolve("repository"))
        }
    }
}

/** A dependency after inheritance, interpolation and dependency management, with where it was declared. */
data class EffectiveDependency(
    val groupId: String,
    val artifactId: String,
    val version: String?,
    val type: String,
    val classifier: String?,
    val scope: String,
    val optional: Boolean,
    val exclusions: List<String>,
    /** The workspace POM declaring it, or null for POMs from the repository. */
    val declaredIn: Path?,
    val pos: Pos,
) {
    val ga: String get() = "$groupId:$artifactId"
    val key: String get() = "$groupId:$artifactId:$type" + (classifier?.let { ":$it" } ?: "")
}

data class ManagedDependency(val version: String?, val scope: String?, val exclusions: List<String>, val declaredIn: Path?)

class EffectiveModel(
    val gav: Gav,
    val properties: Map<String, String>,
    /** Keyed by `groupId:artifactId:type[:classifier]`. */
    val managed: Map<String, ManagedDependency>,
    val dependencies: List<EffectiveDependency>,
) {
    /** The first managed entry per `groupId:artifactId`; BOMs like Spring Boot's manage over a thousand artifacts. */
    private val byGa: Map<String, ManagedDependency> by lazy {
        HashMap<String, ManagedDependency>().also { map -> managed.forEach { (key, md) -> map.putIfAbsent(key.split(':').take(2).joinToString(":"), md) } }
    }

    fun managedFor(groupId: String, artifactId: String): ManagedDependency? = byGa["$groupId:$artifactId"]
}

enum class NodeStatus { INCLUDED, OMITTED_DUPLICATE, OMITTED_CONFLICT }

/** One edge in a module's dependency tree. Depth 1 nodes are the module's own (or inherited) dependencies. */
class DependencyNode(
    val dependency: EffectiveDependency,
    val version: String?,
    val scope: String,
    val parent: DependencyNode?,
    /** Exclusions applied to this node's subtree. */
    val exclusions: List<String>,
    /** Version forced by the module's dependency management instead of the declared one. */
    val managedFrom: String?,
) {
    val depth: Int = (parent?.depth ?: 0) + 1
    val children = ArrayList<DependencyNode>()
    var status = NodeStatus.INCLUDED
        internal set

    /** The version that won when this node was omitted for a conflict. */
    var winnerVersion: String? = null
        internal set

    /** False when the artifact's POM is neither in the workspace nor in the local repository. */
    var pomFound = true
        internal set

    val groupId get() = dependency.groupId
    val artifactId get() = dependency.artifactId
    val label: String get() = "$groupId:$artifactId:${version ?: "?"}"

    /** From the module's direct dependency down to this node. */
    val chain: List<DependencyNode> get() = generateSequence(this) { it.parent }.toList().reversed()

    val direct: DependencyNode get() = chain.first()
}

class DependencyTree(val module: Path, val roots: List<DependencyNode>, val missingPoms: Set<String>) {
    fun all(): Sequence<DependencyNode> = sequence {
        val stack = ArrayDeque(roots.reversed())
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            yield(n)
            n.children.reversed().forEach { stack.addLast(it) }
        }
    }
}

/**
 * Builds effective models and dependency trees the way Maven 3 does in its common cases: inheritance, property
 * interpolation, dependency management with BOM imports, scope propagation, optional dependencies, exclusions and
 * nearest-wins conflict resolution. Profiles are activated by `activeByDefault`, `os`, `jdk` and `property`
 * conditions (see [ActivationContext]); version ranges, relocations and file-based activation are not evaluated.
 * Instances cache effective models and are meant to be used for one query over one [Workspace].
 */
class DependencyResolver(val ws: Workspace, private val repo: LocalRepository, private val env: ActivationContext = ActivationContext.current()) {
    private val effectiveCache = HashMap<String, EffectiveModel?>()
    private val workspaceModels = HashMap<Path, EffectiveModel>()
    private val byGav: Map<String, Path> = ws.entries.keys.associateBy { gavKey(it) }

    private fun gavKey(path: Path): String {
        val gav = ws.model(path)!!.effectiveGav
        return "${ws.interpolate(path, gav.groupId)}:${gav.artifactId}:${ws.interpolate(path, gav.version)}"
    }

    private val building = HashSet<Path>()

    /** The effective model of a workspace POM. */
    fun effective(path: Path): EffectiveModel = workspaceModels[path] ?: run {
        // A BOM import cycle inside the workspace: Maven fails the build; here the import is simply ignored.
        if (!building.add(path)) return EffectiveModel(Gav(null, null, null), emptyMap(), emptyMap(), emptyList())
        val chain = ArrayList<Pair<PomModel, Path?>>()
        chain += ws.model(path)!! to path
        ws.ancestry(path).chain.forEach { chain += ws.model(it)!! to it }
        // Continue outside the workspace through the repository, e.g. for spring-boot-starter-parent.
        extendFromRepository(chain)
        build(chain, HashSet()).also {
            workspaceModels[path] = it
            building.remove(path)
        }
    }

    /** The workspace POM with these coordinates, if the artifact is built in the workspace. */
    fun workspacePom(groupId: String, artifactId: String, version: String?): Path? = byGav["$groupId:$artifactId:$version"]

    /** The effective model of any artifact: a workspace module with these coordinates or a repository POM. */
    fun effective(groupId: String, artifactId: String, version: String): EffectiveModel? {
        byGav["$groupId:$artifactId:$version"]?.let { return effective(it) }
        return effectiveFromRepository(groupId, artifactId, version, HashSet())
    }

    private fun effectiveFromRepository(groupId: String, artifactId: String, version: String, importing: MutableSet<String>): EffectiveModel? {
        val key = "$groupId:$artifactId:$version"
        effectiveCache[key]?.let { return it }
        if (key in effectiveCache) return null
        val model = repo.pom(groupId, artifactId, version)
        val result = model?.let {
            val chain = arrayListOf<Pair<PomModel, Path?>>(it to null)
            extendFromRepository(chain)
            build(chain, importing)
        }
        effectiveCache[key] = result
        return result
    }

    private fun extendFromRepository(chain: MutableList<Pair<PomModel, Path?>>) {
        val seen = HashSet<String>()
        while (chain.size < 30) {
            val (last, path) = chain.last()
            if (path != null && ws.resolveParent(path) !is ParentResolution.External) return
            val parent = last.parent ?: return
            val props = chain.reversed().fold(HashMap<String, String>()) { acc, (m, _) -> acc.apply { putAll(m.properties) } }
            val g = parent.gav.groupId?.let { Workspace.substitute(it, props) } ?: return
            val a = parent.gav.artifactId ?: return
            val v = parent.gav.version?.let { Workspace.substitute(it, props) } ?: return
            if (!seen.add("$g:$a:$v")) return
            val model = byGav["$g:$a:$v"]?.let { ws.model(it) } ?: repo.pom(g, a, v) ?: return
            chain += model to byGav["$g:$a:$v"]
        }
    }

    /** [chain] is leaf first. */
    private fun build(chain: List<Pair<PomModel, Path?>>, importing: MutableSet<String>): EffectiveModel {
        val leaf = chain.first().first
        val props = HashMap<String, String>()
        val active = chain.associate { (m, _) -> m to env.activeProfiles(m) }
        for ((m, _) in chain.reversed()) {
            props.putAll(m.properties)
            m.profiles.filter { it.id in active.getValue(m) }.forEach { props.putAll(it.properties) }
        }
        val gav = leaf.effectiveGav
        fun project(key: String, value: String?) {
            if (value != null) {
                props["project.$key"] = value
                props["pom.$key"] = value
            }
        }
        project("groupId", gav.groupId)
        project("artifactId", gav.artifactId)
        project("version", gav.version)
        project("packaging", leaf.effectivePackaging)
        project("parent.groupId", leaf.parent?.gav?.groupId)
        project("parent.artifactId", leaf.parent?.gav?.artifactId)
        project("parent.version", leaf.parent?.gav?.version)
        chain.first().second?.let { props["basedir"] = it.parent.toString(); props["project.basedir"] = it.parent.toString() }
        fun i(value: String?) = value?.let { Workspace.substitute(it, props) }

        val managed = LinkedHashMap<String, ManagedDependency>()
        val imports = ArrayList<Triple<String, String, String>>()
        for ((m, path) in chain) {
            for (d in m.dependencies) {
                if (d.section != DependencySection.DEPENDENCY_MANAGEMENT || (d.profile != null && d.profile !in active.getValue(m))) continue
                if (i(d.scope) == "import" && i(d.type) == "pom") {
                    val g = i(d.groupId)
                    val a = i(d.artifactId)
                    val v = i(d.version)
                    if (g != null && a != null && v != null) imports += Triple(g, a, v)
                    continue
                }
                managed.putIfAbsent(d.managementKey { i(it) }, ManagedDependency(i(d.version), i(d.scope), d.exclusions.map { i(it)!! }, path))
            }
        }
        for ((g, a, v) in imports) {
            if (!importing.add("$g:$a:$v")) continue
            val bom = byGav["$g:$a:$v"]?.let { effective(it) } ?: effectiveFromRepository(g, a, v, importing)
            bom?.managed?.forEach { (k, md) -> managed.putIfAbsent(k, md) }
        }

        val deps = LinkedHashMap<String, EffectiveDependency>()
        for ((m, path) in chain.reversed()) {
            for (d in m.dependencies) {
                if (d.section != DependencySection.DEPENDENCIES || (d.profile != null && d.profile !in active.getValue(m))) continue
                val g = i(d.groupId) ?: continue
                val a = i(d.artifactId) ?: continue
                val key = d.managementKey { i(it) }
                val md = managed[key]
                deps[key] = EffectiveDependency(
                    groupId = g,
                    artifactId = a,
                    version = i(d.version)?.takeIf { it.isNotEmpty() } ?: md?.version,
                    type = i(d.type) ?: "jar",
                    classifier = i(d.classifier)?.takeIf { it.isNotEmpty() },
                    scope = i(d.scope) ?: md?.scope ?: "compile",
                    optional = d.optional,
                    exclusions = (d.exclusions + md?.exclusions.orEmpty()).map { i(it)!! }.distinct(),
                    declaredIn = path,
                    pos = d.pos,
                )
            }
        }
        return EffectiveModel(Gav(i(gav.groupId), gav.artifactId, i(gav.version)), props, managed, deps.values.toList())
    }

    /** The dependency tree of workspace POM [module], resolved breadth-first with nearest-wins mediation. */
    fun tree(module: Path, maxNodes: Int = 50_000): DependencyTree {
        val root = effective(module)
        val missing = LinkedHashSet<String>()
        val winners = HashMap<String, DependencyNode>()
        val roots = root.dependencies.map { DependencyNode(it, it.version, it.scope, null, it.exclusions, null) }
        val queue = ArrayDeque(roots)
        var count = 0
        while (queue.isNotEmpty() && count++ < maxNodes) {
            val node = queue.removeFirst()
            val key = node.dependency.key
            val winner = winners[key]
            if (winner != null) {
                node.status = if (winner.version == node.version) NodeStatus.OMITTED_DUPLICATE else NodeStatus.OMITTED_CONFLICT
                node.winnerVersion = winner.version
                continue
            }
            winners[key] = node
            val version = node.version ?: continue
            val model = effective(node.groupId, node.artifactId, version)
            if (model == null) {
                node.pomFound = false
                missing += node.label
                continue
            }
            for (dep in model.dependencies) {
                if (dep.optional || dep.scope == "test" || dep.scope == "provided" || dep.scope == "system") continue
                if (node.exclusions.any { matches(it, dep) }) continue
                // Cycles (e.g. a classifier artifact sharing its POM with the main artifact) are dropped, as in Maven.
                if (generateSequence(node) { it.parent }.any { it.dependency.key == dep.key }) continue
                val scope = when (node.scope) {
                    "test", "provided" -> node.scope
                    "runtime" -> "runtime"
                    else -> if (dep.scope == "runtime") "runtime" else "compile"
                }
                val md = root.managed[dep.key]
                val managedVersion = md?.version?.takeIf { it != dep.version }
                val child = DependencyNode(
                    dep, managedVersion ?: dep.version, md?.scope?.takeIf { node.scope == "compile" } ?: scope, node,
                    (node.exclusions + dep.exclusions).distinct(), if (managedVersion != null) dep.version else null,
                )
                node.children += child
                queue.addLast(child)
            }
        }
        return DependencyTree(module, roots, missing)
    }

    private fun matches(pattern: String, dep: EffectiveDependency): Boolean {
        val (g, a) = pattern.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "*" } }
        return (g == "*" || g == dep.groupId) && (a == "*" || a == dep.artifactId)
    }
}

/** The environment profiles are activated against; defaults to the running JVM, like a Maven build on this machine. */
data class ActivationContext(val osName: String, val osArch: String, val javaVersion: String, val properties: Map<String, String>) {
    /** Ids of the active profiles of [model]; `activeByDefault` only applies when no other profile is active. */
    fun activeProfiles(model: PomModel): Set<String> {
        val explicit = model.profiles.filter { isActive(it.activation) }.mapTo(LinkedHashSet()) { it.id }
        return explicit.ifEmpty { model.profiles.filter { it.activation.activeByDefault }.mapTo(LinkedHashSet()) { it.id } }
    }

    /** All specified conditions must hold; a profile without conditions is never activated automatically. */
    fun isActive(a: Activation): Boolean {
        val conditions = listOfNotNull(
            a.jdk?.let { jdkMatches(it) },
            a.osFamily?.let { negatable(it) { v -> familyMatches(v) } },
            a.osName?.let { negatable(it) { v -> v.equals(osName, ignoreCase = true) } },
            a.osArch?.let { negatable(it) { v -> v.equals(osArch, ignoreCase = true) } },
            a.propertyName?.let { name ->
                if (name.startsWith("!")) properties[name.drop(1)] == null
                else {
                    val actual = properties[name]
                    when {
                        a.propertyValue == null -> actual != null
                        a.propertyValue.startsWith("!") -> actual != a.propertyValue.drop(1)
                        else -> actual == a.propertyValue
                    }
                }
            },
        )
        return conditions.isNotEmpty() && conditions.all { it }
    }

    private fun negatable(value: String, test: (String) -> Boolean) = if (value.startsWith("!")) !test(value.drop(1)) else test(value)

    private fun familyMatches(family: String): Boolean {
        val name = osName.lowercase()
        val windows = "windows" in name
        val mac = "mac" in name
        return when (family.lowercase()) {
            "windows" -> windows
            "mac" -> mac
            "unix" -> !windows && (!mac || name.endsWith("x") || "os x" in name || "macos" in name || "darwin" in name)
            "dos" -> windows
            else -> name.contains(family.lowercase())
        }
    }

    private fun jdkMatches(spec: String): Boolean {
        if (spec.startsWith("!")) return !jdkMatches(spec.drop(1))
        if (spec.startsWith("[") || spec.startsWith("(")) {
            val v = versionParts(javaVersion)
            return spec.split(Regex("(?<=[\\])]),(?=[\\[(])")).any { range ->
                val lowerInclusive = range.startsWith("[")
                val upperInclusive = range.endsWith("]")
                val (lo, hi) = range.trim('[', '(', ')', ']').split(',', limit = 2).let { b -> b[0].trim() to b.getOrElse(1) { b[0] }.trim() }
                val okLow = lo.isEmpty() || compare(v, versionParts(lo)).let { if (lowerInclusive) it >= 0 else it > 0 }
                val okHigh = hi.isEmpty() || compare(v, versionParts(hi)).let { if (upperInclusive) it <= 0 else it < 0 }
                okLow && okHigh
            }
        }
        return javaVersion.startsWith(spec)
    }

    private fun versionParts(v: String) = v.split('.', '-', '_', '+').mapNotNull { it.toIntOrNull() }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    companion object {
        fun current() = ActivationContext(
            System.getProperty("os.name").lowercase(),
            System.getProperty("os.arch").lowercase(),
            System.getProperty("java.version"),
            System.getProperties().stringPropertyNames().associateWith { System.getProperty(it) },
        )
    }
}
