package com.github.arjenvanharen.mavendeps.core

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

enum class Impact { AFFECTED, NOT_AFFECTED, UNKNOWN }

/** Whether the project's code can reach the code an advisory is about. */
class CodeImpact(
    val impact: Impact,
    /**
     * The classes of the vulnerable artifact the advisory names. Empty when it names none, so every class of the
     * artifact counted as vulnerable.
     */
    val namedClasses: List<String>,
    /** How many classes of the artifact lead to the vulnerable code (the named classes and the classes that use them). */
    val surfaceSize: Int,
    /** Where the vulnerable classes are referenced, e.g. `app/src/main/java/a/B.java:12 → org.x.Y`. */
    val evidence: List<String>,
    /** Why the impact could not be determined; empty unless [impact] is [Impact.UNKNOWN]. */
    val reasons: List<String>,
)

/**
 * Class-level reachability of vulnerable code, without running or downloading anything.
 *
 * For each advisory, the vulnerable classes are the classes of the artifact's jar that the advisory names (in its
 * text or its ecosystem-specific data), plus every class of the jar that uses them, directly or indirectly. When the
 * advisory names no class of the jar, every class of the jar counts. A module is affected when its own code, the code
 * of workspace modules it depends on, or any other library on its resolved classpath references one of those
 * classes. Code includes sources, compiled classes in `target/`, and class names in resources (Spring, logging and
 * `META-INF/services` configuration). The result is [Impact.NOT_AFFECTED] only when everything could be read.
 *
 * The check is conservative but static: classes loaded purely by reflection from computed names are not seen.
 */
class CodeImpactAnalyzer(private val repo: LocalRepository, private val index: ClassIndex = ClassIndex()) {
    /** A module's classpath entry: a jar from the repository or a workspace module. */
    private class Entry(val label: String, val jar: Path?, val moduleDir: Path?)

    private class Surface(val named: List<String>, val classes: Set<String>, val packages: Set<String>, val reasons: List<String>)

    /**
     * The impact of every advisory of every artifact, keyed by artifact and advisory id.
     * [libraries] are all libraries of all modules (from [DependencyIndex.libraries]); [workspaceModule] gives the
     * POM of a workspace module.
     */
    fun analyze(
        artifacts: Collection<VulnerableArtifact>,
        libraries: Collection<Library>,
        workspaceModule: (groupId: String, artifactId: String, version: String) -> Path?,
        checkCanceled: () -> Unit = {},
        progress: (done: Int, total: Int, phase: String) -> Unit = { _, _, _ -> },
    ): Map<Pair<ArtifactVersion, String>, CodeImpact> {
        // 1. The vulnerable classes per advisory, from each artifact's own jar.
        val jars = HashMap<ArtifactVersion, Path?>()
        val surfaces = HashMap<Pair<ArtifactVersion, String>, Surface>()
        for ((i, a) in artifacts.withIndex()) {
            checkCanceled()
            progress(i, artifacts.size, "Reading vulnerable libraries")
            val node = a.usages.first().node.dependency
            val jar = jarOf(a.artifact.groupId, a.artifact.artifactId, a.artifact.version, node.type, node.classifier)
            jars[a.artifact] = jar
            val graph = jar?.takeIf { it.isRegularFile() }?.let { index.graph(it) }
            for (v in a.vulnerabilities) {
                surfaces[a.artifact to v.id] = when {
                    jar == null || !jar.isRegularFile() -> Surface(emptyList(), emptySet(), emptySet(), listOf("The jar of ${a.artifact} is not in the local repository"))
                    graph == null -> Surface(emptyList(), emptySet(), emptySet(), listOf("The jar of ${a.artifact} is not a readable jar"))
                    !graph.complete || graph.classes.isEmpty() -> Surface(emptyList(), emptySet(), emptySet(), listOf("The jar of ${a.artifact} could not be read completely"))
                    else -> surface(v, graph)
                }
            }
        }
        val wanted = HashSet<String>()
        surfaces.values.forEach { wanted.addAll(it.classes) }
        val wantedPackages = HashSet<String>()
        surfaces.values.forEach { wantedPackages.addAll(it.packages) }

        // 2. Per module: who references the vulnerable classes.
        val modules = artifacts.flatMapTo(LinkedHashSet()) { it.modules }
        val classpaths = classpaths(libraries, modules, workspaceModule)
        val hits = HashMap<Path, ModuleRefs>()
        val run = Run(wanted, wantedPackages)
        for ((i, module) in modules.withIndex()) {
            checkCanceled()
            progress(i, modules.size, "Checking module code")
            hits[module] = moduleRefs(module, classpaths[module].orEmpty(), run, checkCanceled)
        }

        // 3. Combine.
        val out = LinkedHashMap<Pair<ArtifactVersion, String>, CodeImpact>()
        for (a in artifacts) for (v in a.vulnerabilities) {
            val s = surfaces.getValue(a.artifact to v.id)
            val evidence = ArrayList<String>()
            val reasons = ArrayList(s.reasons)
            if (s.classes.isNotEmpty()) {
                val ownJar = jars[a.artifact]
                for (module in a.modules) {
                    val refs = hits[module] ?: continue
                    for (name in s.classes) refs.classes[name]?.firstOrNull { it.jar != ownJar }?.let { evidence += "${it.where} → $name" }
                    for (p in s.packages) refs.packages[p]?.let { evidence += "$it → $p.*" }
                    reasons += refs.reasons
                }
            }
            val impact = when {
                evidence.isNotEmpty() -> Impact.AFFECTED
                reasons.isNotEmpty() -> Impact.UNKNOWN
                else -> Impact.NOT_AFFECTED
            }
            out[a.artifact to v.id] = CodeImpact(
                impact,
                s.named,
                s.classes.size,
                evidence.distinct().sorted().take(MAX_EVIDENCE),
                if (impact == Impact.UNKNOWN) reasons.distinct().take(MAX_EVIDENCE) else emptyList(),
            )
        }
        return out
    }

    private fun jarOf(groupId: String, artifactId: String, version: String, type: String, classifier: String?): Path? = when (type) {
        "pom" -> null
        "test-jar" -> repo.artifactPath(groupId, artifactId, version, classifier ?: "tests")
        else -> repo.artifactPath(groupId, artifactId, version, classifier)
    }

    private fun classpaths(libraries: Collection<Library>, modules: Set<Path>, workspaceModule: (String, String, String) -> Path?): Map<Path, List<Entry>> {
        val out = HashMap<Path, LinkedHashMap<String, Entry>>()
        for (lib in libraries) for (u in lib.usages) {
            if (u.module !in modules || u.node.status != NodeStatus.INCLUDED) continue
            val d = u.node.dependency
            val version = u.node.version ?: continue
            if (d.type == "pom") continue
            val key = "${lib.ga}:$version:${d.type}:${d.classifier}"
            val ws = workspaceModule(lib.groupId, lib.artifactId, version)
            out.getOrPut(u.module) { LinkedHashMap() }.getOrPut(key) {
                if (ws != null) Entry(ws.parent.name, null, ws.parent) else Entry("${lib.ga}:$version", jarOf(lib.groupId, lib.artifactId, version, d.type, d.classifier), null)
            }
        }
        return out.mapValues { it.value.values.toList() }
    }

    private class Ref(val where: String, val jar: Path?)

    private class ModuleRefs(val classes: Map<String, List<Ref>>, val packages: Map<String, String>, val reasons: List<String>)

    /** The code of one module directory, reduced to the vulnerable names it references. */
    private class CodeHits(val classes: List<Pair<String, String>>, val packages: List<Pair<String, String>>, val complete: Boolean)

    /** A jar reduced to the vulnerable names it references; null [references] when it is missing or unreadable. */
    private class JarHits(val references: List<String>?, val complete: Boolean, val missing: Boolean = false)

    /**
     * State of one [analyze] call. A workspace library module or a jar is on the classpath of many modules: each is
     * read and intersected with the vulnerable names once, not once per module.
     */
    private inner class Run(val wanted: Set<String>, val wantedPackages: Set<String>) {
        private val code = HashMap<Path, CodeHits>()
        private val jars = HashMap<Path, JarHits>()

        fun code(dir: Path): CodeHits = code.getOrPut(dir) {
            val src = index.sources(dir)
            val classes = src.classes.filter { it.key in wanted }.map { it.key to it.value } +
                index.compiled(dir).classes.filter { it.key in wanted }.map { it.key to it.value }
            CodeHits(classes, src.packages.filter { it.key in wantedPackages }.map { it.key to it.value }, src.complete)
        }

        fun jar(jar: Path): JarHits = jars.getOrPut(jar) {
            if (!jar.isRegularFile()) return@getOrPut JarHits(null, false, missing = true)
            val summary = index.summary(jar) ?: return@getOrPut JarHits(null, false)
            JarHits(summary.references.filter { it in wanted }, summary.complete)
        }
    }

    private fun moduleRefs(module: Path, classpath: List<Entry>, run: Run, checkCanceled: () -> Unit): ModuleRefs {
        val classes = HashMap<String, MutableList<Ref>>()
        val packages = HashMap<String, String>()
        val reasons = ArrayList<String>()
        fun add(name: String, ref: Ref) {
            val list = classes.getOrPut(name) { ArrayList(2) }
            // Two distinct jars are enough to find one that is not the vulnerable jar itself.
            if (list.size < 2 && list.none { it.jar == ref.jar }) list += ref
        }

        fun code(dir: Path) {
            val hits = run.code(dir)
            for ((name, where) in hits.classes) add(name, Ref(where, null))
            for ((p, where) in hits.packages) packages.putIfAbsent(p, where)
            if (!hits.complete) reasons += "Not every source file of ${dir.name} could be read"
        }
        code(module.parent)
        for (e in classpath) {
            checkCanceled()
            if (e.moduleDir != null) {
                code(e.moduleDir)
                continue
            }
            val jar = e.jar
            val hits = jar?.let { run.jar(it) }
            if (hits?.references == null) {
                reasons += if (hits == null || hits.missing) "The jar of ${e.label} is not in the local repository" else "The jar of ${e.label} is not a readable jar"
                continue
            }
            if (!hits.complete) reasons += "The jar of ${e.label} could not be read completely"
            for (name in hits.references) add(name, Ref(e.label, jar))
        }
        return ModuleRefs(classes, packages, reasons)
    }

    /** The classes of the jar that lead to the code [v] is about. */
    private fun surface(v: Vulnerability, graph: ClassIndex.Graph): Surface {
        val named = namedClasses(v, graph.classes.keys)
        if (named.isEmpty()) {
            val all = graph.classes.keys
            return Surface(emptyList(), all + externalServices(graph, all), packagesOf(all), emptyList())
        }
        // Everything in the jar that uses a named class, directly or indirectly; a service interface reaches its providers.
        val users = HashMap<String, MutableList<String>>()
        for ((c, refs) in graph.classes) for (r in refs) if (r != c && r in graph.classes) users.getOrPut(r) { ArrayList() }.add(c)
        for ((iface, impls) in graph.services) if (iface in graph.classes) for (impl in impls) users.getOrPut(impl) { ArrayList() }.add(iface)
        val seen = LinkedHashSet(named)
        val queue = ArrayDeque(named)
        while (queue.isNotEmpty()) for (u in users[queue.removeFirst()].orEmpty()) if (seen.add(u)) queue.addLast(u)
        val reasons = ArrayList<String>()
        for ((iface, impls) in graph.services) {
            if (iface in graph.classes || impls.none { it in seen }) continue
            if (JDK_PREFIXES.any { iface.startsWith(it) }) reasons += "A vulnerable class is a provider of $iface, which the JDK can load without any reference in code"
        }
        return Surface(named.sorted(), seen + externalServices(graph, seen), packagesOf(seen), reasons)
    }

    private fun packagesOf(classes: Collection<String>): Set<String> = classes.mapNotNullTo(HashSet()) { c -> c.substringBeforeLast('.', "").ifEmpty { null } }

    /** Service interfaces outside the jar with a provider in [classes]: referencing the interface can load the provider. */
    private fun externalServices(graph: ClassIndex.Graph, classes: Set<String>): Set<String> =
        graph.services.filter { (iface, impls) -> iface !in graph.classes && impls.any { it in classes } }.keys

    companion object {
        private const val MAX_EVIDENCE = 50
        private val JDK_PREFIXES = listOf("java.", "jdk.", "sun.", "javax.xml.", "javax.script.", "javax.annotation.processing.", "javax.tools.")
        private val FQN = Regex("""(?<![\w.$])(?:[a-z_][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_]*""")
        private val CAMEL = Regex("""\b[A-Z][A-Za-z0-9_]*[a-z][A-Za-z0-9_]*\b""")
        private val CODE = Regex("""`([A-Za-z_][\w.$#:]*)(?:\([^`]*\))?`|\b([A-Z][A-Za-z0-9_]*)(?:\.|#|::)[a-z]\w*\(""")

        /**
         * The classes of the jar ([classes], dotted top-level names) that an advisory names: fully qualified names,
         * CamelCase names with at least two capitals, and names written as code (`` `Foo` ``, `Foo.bar(`, `Foo#bar(`).
         */
        fun namedClasses(v: Vulnerability, classes: Set<String>): Set<String> {
            val bySimple = classes.groupBy { it.substringAfterLast('.') }
            val out = LinkedHashSet<String>()
            val text = (v.symbols + v.summary + v.details).joinToString("\n")
            for (m in FQN.findAll(text)) if (m.value in classes) out += m.value
            for (m in CAMEL.findAll(text)) if (m.value.count { it.isUpperCase() } >= 2) bySimple[m.value]?.let(out::addAll)
            for (m in CODE.findAll(text)) {
                val token = m.groupValues[1].ifEmpty { m.groupValues[2] }
                val head = token.split('#', ':', '$', '(').first()
                if (head in classes) out += head
                // `Foo.bar` or `a.b.Foo.bar`: the last capitalized segment is the class.
                val simple = head.split('.').lastOrNull { it.firstOrNull()?.isUpperCase() == true } ?: continue
                val fqn = head.substringBefore(".$simple", "").let { if (it.isEmpty()) null else "$it.$simple" }
                if (fqn != null && fqn in classes) out += fqn else bySimple[simple]?.let(out::addAll)
            }
            return out
        }
    }
}

/**
 * Class references read from jars, class directories and source trees, with a bounded cache of jar summaries keyed
 * by file stamp. Input may be crafted: sizes, entry counts and nesting are bounded and malformed classes are skipped.
 */
open class ClassIndex {
    /** Classes defined by a jar and the classes its code (and its configuration resources) references. */
    class Summary(val defined: Set<String>, val references: Set<String>, val complete: Boolean)

    /** Per class of a jar, the classes it references; providers per service interface from `META-INF/services`. */
    class Graph(val classes: Map<String, Set<String>>, val services: Map<String, List<String>>, val complete: Boolean)

    /** References found in a module's sources or compiled classes, with the first place each was seen. */
    class Code(val classes: Map<String, String>, val packages: Map<String, String>, val complete: Boolean)

    private class Stamped<T>(val size: Long, val mtime: Long, val value: T)

    private val summaries = ConcurrentHashMap<Path, Stamped<Summary>>()

    fun summary(jar: Path): Summary? {
        val attrs = attributes(jar) ?: return null
        summaries[jar]?.takeIf { it.size == attrs.size() && it.mtime == attrs.lastModifiedTime().toMillis() }?.let { return it.value }
        // Only the union is kept: per-class references are needed for the vulnerable jars alone (see [graph]).
        val refs = HashSet<String>()
        val defined = HashSet<String>()
        val r = read(jar) { owner, classRefs -> defined += owner; refs.addAll(classRefs) } ?: return null
        // Configuration that names classes to load: service providers, Spring factories and auto-configuration imports.
        r.services.forEach { (iface, impls) -> refs += iface; refs.addAll(impls) }
        val s = Summary(defined, refs, r.complete)
        if (summaries.size >= MAX_CACHED) summaries.clear()
        summaries[jar] = Stamped(attrs.size(), attrs.lastModifiedTime().toMillis(), s)
        return s
    }

    /** Reads every class of [jar] with its references; null when it is not a readable zip. */
    fun graph(jar: Path): Graph? {
        val classes = HashMap<String, MutableSet<String>>()
        val r = read(jar) { owner, refs -> classes.getOrPut(owner) { HashSet() }.addAll(refs) } ?: return null
        return Graph(classes, r.services, r.complete)
    }

    private class Read(val services: Map<String, List<String>>, val complete: Boolean)

    /** Hands every class of [jar] (top-level name, references) to [onClass]; null when it is not a readable zip. */
    private fun read(jar: Path, onClass: (String, Set<String>) -> Unit): Read? {
        val services = HashMap<String, MutableList<String>>()
        var complete = true
        var total = 0L
        try {
            ZipFile(jar.toFile()).use { zip ->
                var count = 0
                for (e in zip.entries()) {
                    if (++count > MAX_ENTRIES) {
                        complete = false
                        break
                    }
                    if (e.isDirectory) continue
                    val name = e.name
                    val isClass = name.endsWith(".class")
                    val isConfig = !isClass && (name.startsWith("META-INF/services/") || name == "META-INF/spring.factories" ||
                        (name.startsWith("META-INF/spring/") && name.endsWith(".imports")))
                    if (!isClass && !isConfig) continue
                    if (e.size > MAX_CLASS_BYTES || total > MAX_JAR_BYTES) {
                        complete = false
                        continue
                    }
                    val bytes = zip.getInputStream(e).use { it.readNBytes(MAX_CLASS_BYTES + 1) }
                    total += bytes.size
                    if (bytes.size > MAX_CLASS_BYTES) {
                        complete = false
                        continue
                    }
                    if (isConfig) {
                        val text = String(bytes, StandardCharsets.UTF_8)
                        val key = if (name.startsWith("META-INF/services/")) name.removePrefix("META-INF/services/").trim() else name
                        services.getOrPut(key) { ArrayList() }.addAll(classNamesIn(text))
                        continue
                    }
                    val path = name.removePrefix("META-INF/versions/").let { if (it != name) it.substringAfter('/') else it }
                    if (path.endsWith("module-info.class") || path.endsWith("package-info.class")) continue
                    val owner = topLevel(path.removeSuffix(".class"))
                    val refs = ClassFiles.references(bytes)
                    if (refs == null) {
                        complete = false
                        continue
                    }
                    onClass(owner, refs)
                }
            }
        } catch (_: Exception) {
            return null
        }
        return Read(services, complete)
    }

    /** Class and package references in the source tree (`src/`) of the module in [moduleDir]. */
    open fun sources(moduleDir: Path): Code {
        val classes = HashMap<String, String>()
        val packages = HashMap<String, String>()
        var complete = true
        val src = moduleDir.resolve("src")
        if (!src.isDirectory(LinkOption.NOFOLLOW_LINKS)) return Code(classes, packages, true)
        try {
            Files.walk(src, MAX_DEPTH).use { stream ->
                var count = 0
                for (file in stream) {
                    // Links are not followed: a crafted project could point them at files outside it.
                    if (!file.isRegularFile(LinkOption.NOFOLLOW_LINKS)) continue
                    val ext = file.extension
                    val code = ext in CODE_EXTENSIONS
                    if (!code && ext !in TEXT_EXTENSIONS && file.parent?.name != "services") continue
                    if (++count > MAX_FILES || Files.size(file) > MAX_SOURCE_BYTES) {
                        complete = false
                        continue
                    }
                    val text = try {
                        String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                    } catch (_: Exception) {
                        complete = false
                        continue
                    }
                    val rel = moduleDir.fileName.toString() + "/" + moduleDir.relativize(file).joinToString("/")
                    val lines = lazy { text.indices.filter { text[it] == '\n' } }
                    fun put(map: HashMap<String, String>, key: String, offset: Int) {
                        if (key in map) return
                        val line = lines.value.binarySearch(offset).let { if (it < 0) -it - 1 else it } + 1
                        map[key] = "$rel:$line"
                    }
                    if (code) {
                        for (m in IMPORT.findAll(text)) {
                            val segments = m.groupValues[1].split('.')
                            val cls = segments.indexOfFirst { it.firstOrNull()?.isUpperCase() == true }
                            when {
                                cls > 0 -> put(classes, segments.take(cls + 1).joinToString("."), m.range.first)
                                m.groupValues[2].isNotEmpty() -> put(packages, m.groupValues[1], m.range.first)
                                // A Kotlin top-level function: anything in its package may be used.
                                segments.size > 1 -> put(packages, segments.dropLast(1).joinToString("."), m.range.first)
                            }
                        }
                    }
                    for (m in QUALIFIED.findAll(text)) put(classes, m.value, m.range.first)
                }
            }
        } catch (_: Exception) {
            complete = false
        }
        return Code(classes, packages, complete)
    }

    /** Class references in the module's compiled classes (`target/classes`, `target/test-classes`), when they exist. */
    fun compiled(moduleDir: Path): Code {
        val classes = HashMap<String, String>()
        var complete = true
        for (dir in listOf("target/classes", "target/test-classes").map { moduleDir.resolve(it) }.filter { it.isDirectory(LinkOption.NOFOLLOW_LINKS) }) {
            try {
                Files.walk(dir, MAX_DEPTH).use { stream ->
                    var count = 0
                    for (file in stream) {
                        if (!file.isRegularFile(LinkOption.NOFOLLOW_LINKS) || file.extension != "class") continue
                        if (++count > MAX_FILES || Files.size(file) > MAX_CLASS_BYTES) {
                            complete = false
                            continue
                        }
                        val where = moduleDir.fileName.toString() + "/" + moduleDir.relativize(file).joinToString("/")
                        ClassFiles.references(Files.readAllBytes(file))?.forEach { classes.putIfAbsent(it, where) }
                    }
                }
            } catch (_: Exception) {
                complete = false
            }
        }
        return Code(classes, emptyMap(), complete)
    }

    private fun attributes(p: Path): BasicFileAttributes? = try {
        Files.readAttributes(p, BasicFileAttributes::class.java).takeIf { it.isRegularFile }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val MAX_CACHED = 5_000
        private const val MAX_ENTRIES = 200_000
        private const val MAX_CLASS_BYTES = 8 * 1024 * 1024
        private const val MAX_JAR_BYTES = 1L shl 30
        private const val MAX_FILES = 200_000
        private const val MAX_SOURCE_BYTES = 4L * 1024 * 1024
        private const val MAX_DEPTH = 64
        private val CODE_EXTENSIONS = setOf("java", "kt", "kts", "groovy", "scala")
        private val TEXT_EXTENSIONS = setOf("xml", "properties", "yml", "yaml", "json", "conf", "factories", "imports", "xsd", "vm", "ftl")
        private val IMPORT = Regex("""(?m)^[ \t]*import\s+(?:static\s+)?([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)(\.\*)?""")
        private val QUALIFIED = Regex("""(?<![\w.$])(?:[a-z_][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_]*""")

        /** Fully qualified class names in configuration text, as top-level names. */
        fun classNamesIn(text: String): List<String> = QUALIFIED.findAll(text).map { it.value }.toList()

        /** `a/b/C$D` or `a.b.C$D` → `a.b.C`. */
        fun topLevel(name: String): String = name.replace('/', '.').substringBefore('$')
    }
}

/** Minimal class file reader: the classes a class references through its constant pool. */
object ClassFiles {
    private val INTERNAL = Regex("""[A-Za-z_$][\w$]*(?:/[A-Za-z_$][\w$]*)+""")
    private val DESCRIPTOR = Regex("""L([A-Za-z_$][\w$]*(?:/[A-Za-z_$][\w$]*)+)[;<]""")
    private val DOTTED = Regex("""(?:[a-z_][a-z0-9_]*\.)+[A-Z][\w$]*""")

    /**
     * Top-level class names (dotted) referenced by a class file: class constants, types in descriptors and
     * signatures, and string constants that are class names (reflection). Null when it is not a valid class file.
     */
    fun references(bytes: ByteArray): Set<String>? = try {
        val b = ByteBuffer.wrap(bytes)
        if (b.getInt() != -0x35014542) null else {
            b.getShort()
            b.getShort()
            val count = b.getShort().toInt() and 0xFFFF
            val out = HashSet<String>()
            var i = 1
            while (i < count) {
                when (b.get().toInt()) {
                    1 -> {
                        val len = b.getShort().toInt() and 0xFFFF
                        val s = String(bytes, b.position(), len, StandardCharsets.UTF_8)
                        b.position(b.position() + len)
                        when {
                            INTERNAL.matches(s) -> out += ClassIndex.topLevel(s)
                            DOTTED.matches(s) -> out += ClassIndex.topLevel(s)
                            else -> DESCRIPTOR.findAll(s).forEach { out += ClassIndex.topLevel(it.groupValues[1]) }
                        }
                    }
                    3, 4, 9, 10, 11, 12, 17, 18 -> b.position(b.position() + 4)
                    5, 6 -> {
                        b.position(b.position() + 8)
                        i++
                    }
                    7, 8, 16, 19, 20 -> b.position(b.position() + 2)
                    15 -> b.position(b.position() + 3)
                    else -> return null
                }
                i++
            }
            out
        }
    } catch (_: Exception) {
        null
    }
}
