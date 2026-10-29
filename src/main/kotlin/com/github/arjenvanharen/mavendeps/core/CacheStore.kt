package com.github.arjenvanharen.mavendeps.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Binary on-disk cache of parsed POMs. Any read failure or format change yields an empty cache, which simply
 * means everything is parsed again. Bump [VERSION] whenever [PomEntry] or its parts change shape.
 */
object CacheStore {
    private const val MAGIC = 0x4D444143 // "MDAC"
    private const val VERSION = 3
    private const val MAX_ITEMS = 1_000_000

    fun write(file: Path, entries: Collection<PomEntry>) {
        Files.createDirectories(file.parent)
        val tmp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(Files.newOutputStream(tmp)))).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(entries.size)
                entries.forEach { out.writeEntry(it) }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun read(file: Path): List<PomEntry> {
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            DataInputStream(BufferedInputStream(GZIPInputStream(Files.newInputStream(file)))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return emptyList()
                List(bounded(input.readInt(), MAX_ITEMS)) { input.readEntry() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun DataOutputStream.writeEntry(e: PomEntry) {
        writeStr(e.path.toString())
        writeLong(e.size)
        writeLong(e.lastModified)
        writeStr(e.hash)
        writeModel(e.model)
        writeList(e.localProblems) { writeProblem(it) }
    }

    private fun DataInputStream.readEntry() = PomEntry(
        path = Paths.get(readStr()),
        size = readLong(),
        lastModified = readLong(),
        hash = readStr(),
        model = readModel(),
        localProblems = readList { readProblem() },
    )

    private fun DataOutputStream.writeModel(m: PomModel) {
        writeBoolean(m.wellFormed)
        writeNullable(m.modelVersion)
        writeGav(m.gav)
        writePos(m.gavPos)
        writeNullable(m.packaging)
        writeBoolean(m.parent != null)
        m.parent?.let {
            writeGav(it.gav)
            writeNullable(it.relativePath)
            writePos(it.pos)
        }
        writeList(m.modules) {
            writeStr(it.name)
            writePos(it.pos)
            writeNullable(it.profile)
        }
        writeList(m.properties.entries.toList()) {
            writeStr(it.key)
            writeStr(it.value)
        }
        writeList(m.profileProperties.toList()) { writeStr(it) }
        writeList(m.dependencies) {
            writeNullable(it.groupId)
            writeNullable(it.artifactId)
            writeNullable(it.version)
            writeNullable(it.type)
            writeNullable(it.classifier)
            writeNullable(it.scope)
            writeNullable(it.systemPath)
            writeByte(it.section.ordinal)
            writeNullable(it.profile)
            writePos(it.pos)
            writeBoolean(it.optional)
            writeList(it.exclusions) { e -> writeStr(e) }
        }
        writeList(m.propertyRefs) {
            writeStr(it.name)
            writePos(it.pos)
            writeBoolean(it.critical)
        }
        writeList(m.profiles) { p ->
            writeStr(p.id)
            val a = p.activation
            writeBoolean(a.activeByDefault)
            listOf(a.jdk, a.osFamily, a.osName, a.osArch, a.propertyName, a.propertyValue).forEach { writeNullable(it) }
            writeList(p.properties.entries.toList()) {
                writeStr(it.key)
                writeStr(it.value)
            }
        }
    }

    private fun DataInputStream.readModel(): PomModel {
        val wellFormed = readBoolean()
        val modelVersion = readNullable()
        val gav = readGav()
        val gavPos = readPos()
        val packaging = readNullable()
        val parent = if (readBoolean()) ParentRef(readGav(), readNullable(), readPos()) else null
        val modules = readList { ModuleRef(readStr(), readPos(), readNullable()) }
        val properties = readList { readStr() to readStr() }.toMap(LinkedHashMap())
        val profileProperties = readList { readStr() }.toSet()
        val dependencies = readList {
            Dependency(
                groupId = readNullable(),
                artifactId = readNullable(),
                version = readNullable(),
                type = readNullable(),
                classifier = readNullable(),
                scope = readNullable(),
                systemPath = readNullable(),
                section = DependencySection.entries[readByte().toInt()],
                profile = readNullable(),
                pos = readPos(),
                optional = readBoolean(),
                exclusions = readList { readStr() },
            )
        }
        val refs = readList { PropertyRef(readStr(), readPos(), readBoolean()) }
        val profiles = readList {
            Profile(
                readStr(),
                Activation(readBoolean(), readNullable(), readNullable(), readNullable(), readNullable(), readNullable(), readNullable()),
                readList { readStr() to readStr() }.toMap(LinkedHashMap()),
            )
        }
        return PomModel(wellFormed, modelVersion, gav, gavPos, packaging, parent, modules, properties, profileProperties, dependencies, refs, profiles)
    }

    private fun DataOutputStream.writeProblem(p: Problem) {
        writeByte(p.severity.ordinal)
        writeByte(p.category.ordinal)
        writeStr(p.message)
        writePos(p.pos)
        writeList(p.fixes) { writeFix(it) }
    }

    private fun DataInputStream.readProblem() =
        Problem(Severity.entries[readByte().toInt()], Category.entries[readByte().toInt()], readStr(), readPos(), readList { readFix() })

    private fun DataOutputStream.writeFix(f: QuickFix) {
        writeStr(f.title)
        when (f) {
            is RemoveElement -> {
                writeByte(0)
                writeRef(f.target)
            }
            is SetChild -> {
                writeByte(1)
                writeRef(f.target)
                writeStr(f.child)
                writeNullable(f.value)
            }
            is DefineProperty -> {
                writeByte(2)
                writeNullable(f.pom?.toString())
                writeStr(f.name)
                writeNullable(f.value)
            }
            is AddExclusion -> {
                writeByte(3)
                writeRef(f.target)
                writeStr(f.groupId)
                writeStr(f.artifactId)
            }
            is AddDependency -> {
                writeByte(4)
                writeNullable(f.pom?.toString())
                writeStr(f.groupId)
                writeStr(f.artifactId)
                writeStr(f.version)
            }
            is CompositeFix -> {
                writeByte(5)
                writeList(f.parts) { writeFix(it) }
            }
        }
    }

    private fun DataInputStream.readFix(): QuickFix {
        val title = readStr()
        return when (val kind = readByte().toInt()) {
            0 -> RemoveElement(title, readRef())
            1 -> SetChild(title, readRef(), readStr(), readNullable())
            2 -> DefineProperty(title, readNullable()?.let { Paths.get(it) }, readStr(), readNullable())
            3 -> AddExclusion(title, readRef(), readStr(), readStr())
            4 -> AddDependency(title, readNullable()?.let { Paths.get(it) }, readStr(), readStr(), readStr())
            5 -> CompositeFix(title, readList { readFix() })
            else -> throw java.io.IOException("Unknown fix kind $kind")
        }
    }

    private fun DataOutputStream.writeRef(r: ElementRef) {
        writeNullable(r.path?.toString())
        writeNullable(r.name)
        writePos(r.pos)
    }

    private fun DataInputStream.readRef() = ElementRef(readNullable()?.let { Paths.get(it) }, readNullable(), readPos())

    private fun DataOutputStream.writeGav(g: Gav) {
        writeNullable(g.groupId)
        writeNullable(g.artifactId)
        writeNullable(g.version)
    }

    private fun DataInputStream.readGav() = Gav(readNullable(), readNullable(), readNullable())

    private fun DataOutputStream.writePos(p: Pos) {
        writeInt(p.line)
        writeInt(p.column)
    }

    private fun DataInputStream.readPos() = Pos(readInt(), readInt())

    private fun DataOutputStream.writeStr(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readStr(): String = String(readNBytes(bounded(readInt(), PomParser.MAX_BYTES)), Charsets.UTF_8)

    /** Sizes come from a file that may be corrupt; an absurd one must fail the read, not allocate gigabytes. */
    private fun bounded(n: Int, max: Int): Int = if (n in 0..max) n else throw java.io.IOException("Corrupt cache: size $n")

    private fun DataOutputStream.writeNullable(s: String?) {
        writeBoolean(s != null)
        if (s != null) writeStr(s)
    }

    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readStr() else null

    private fun <T> DataOutputStream.writeList(items: List<T>, writeItem: DataOutputStream.(T) -> Unit) {
        writeInt(items.size)
        items.forEach { writeItem(it) }
    }

    private fun <T> DataInputStream.readList(readItem: DataInputStream.() -> T): List<T> = List(bounded(readInt(), MAX_ITEMS)) { readItem() }
}
