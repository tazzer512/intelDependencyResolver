package com.github.arjenvanharen.mavendeps.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Hand-made class files and jars for the code impact tests. */
object CodeImpactFixtures {
    /** A class file whose constant pool names [name], its superclass and [refs] (internal names). */
    fun classFile(name: String, vararg refs: String): ByteArray {
        val names = listOf(name, "java/lang/Object") + refs
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0xCAFEBABE.toInt())
            writeShort(0)
            writeShort(65)
            writeShort(1 + names.size * 2)
            for ((i, n) in names.withIndex()) {
                writeByte(1)
                writeUTF(n)
                writeByte(7)
                writeShort(1 + i * 2)
            }
            // access, this, super, then no interfaces, fields, methods or attributes.
            writeShort(0x21)
            writeShort(2)
            writeShort(4)
            repeat(4) { writeShort(0) }
        }
        return out.toByteArray()
    }

    /**
     * A class file using every constant pool tag: the class [name] references `org/x/Arg` only through a method
     * descriptor and `org.x.Reflected` only through a string constant (as `Class.forName` would).
     */
    fun richClassFile(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0xCAFEBABE.toInt())
            writeShort(0)
            writeShort(65)
            // Entries 1..17; Long and Double take two slots each.
            writeShort(18)
            writeByte(1); writeUTF(name) // 1
            writeByte(7); writeShort(1) // 2
            writeByte(1); writeUTF("(Lorg/x/Arg;)V") // 3
            writeByte(1); writeUTF("org.x.Reflected") // 4
            writeByte(8); writeShort(4) // 5 String
            writeByte(3); writeInt(42) // 6 Integer
            writeByte(4); writeFloat(1f) // 7 Float
            writeByte(5); writeLong(1L) // 8-9 Long
            writeByte(6); writeDouble(1.0) // 10-11 Double
            writeByte(12); writeShort(1); writeShort(3) // 12 NameAndType
            writeByte(10); writeShort(2); writeShort(12) // 13 Methodref
            writeByte(15); writeByte(6); writeShort(13) // 14 MethodHandle
            writeByte(16); writeShort(3) // 15 MethodType
            writeByte(18); writeShort(0); writeShort(12) // 16 InvokeDynamic
            writeByte(20); writeShort(1) // 17 Package
            writeShort(0x21)
            writeShort(2)
            writeShort(0)
            repeat(4) { writeShort(0) }
        }
        return out.toByteArray()
    }

    fun jar(f: Fixture, gav: String, entries: Map<String, ByteArray>, classifier: String? = null) {
        val (g, a, v) = gav.split(':')
        f.artifact(gav)
        val p = f.path(".m2/${g.replace('.', '/')}/$a/$v/$a-$v${classifier?.let { "-$it" } ?: ""}.jar")
        ZipOutputStream(Files.newOutputStream(p)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
