package com.github.arjenvanharen.mavendeps.core

import java.nio.file.Files
import java.nio.file.Path

/** Builds POM trees (and a fake local repository) inside a temporary directory. */
class Fixture(val root: Path) {
    val repo: LocalRepository get() = LocalRepository(root.resolve(".m2"))

    fun write(rel: String, content: String): Path {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, content)
        return p
    }

    fun read(rel: String): String = Files.readString(root.resolve(rel))

    fun path(rel: String): Path = root.resolve(rel)

    /** Writes `<groupId>/<artifactId>/<version>/<artifactId>-<version>.pom` into the fake repository. */
    fun artifact(gav: String, body: String = "") {
        val (g, a, v) = gav.split(':')
        write(".m2/${g.replace('.', '/')}/$a/$v/$a-$v.pom", pom("<groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version>$body"))
    }

    fun engine(): Pair<AnalysisEngine, AnalysisResult> {
        val engine = AnalysisEngine()
        return engine to engine.refresh(listOf(root), RefreshMode.RESCAN)
    }

    fun resolver(): DependencyResolver = DependencyResolver(engine().first.workspaceSnapshot(), repo)

    /** Applies [fix] (reported on [owner]) to the files on disk. */
    fun apply(owner: Path, fix: QuickFix, input: String? = null) {
        FixApplier.edits(fix, owner, input) { Files.readString(it) }.forEach { (path, edits) ->
            Files.writeString(path, FixApplier.apply(Files.readString(path), edits))
        }
    }

    companion object {
        fun pom(body: String) = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<project xmlns="http://maven.apache.org/POM/4.0.0">
            |    <modelVersion>4.0.0</modelVersion>
            |${body.trimIndent().lines().joinToString("\n") { if (it.isBlank()) "" else "    $it" }}
            |</project>
            |""".trimMargin()

        fun dep(gav: String, extra: String = ""): String {
            val parts = gav.split(':')
            val version = parts.getOrNull(2)?.let { "<version>$it</version>" } ?: ""
            return "<dependency><groupId>${parts[0]}</groupId><artifactId>${parts[1]}</artifactId>$version$extra</dependency>"
        }

        fun deps(vararg gavs: String) = "<dependencies>${gavs.joinToString("") { dep(it) }}</dependencies>"
    }
}
