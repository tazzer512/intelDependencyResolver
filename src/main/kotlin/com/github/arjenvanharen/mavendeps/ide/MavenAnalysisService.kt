package com.github.arjenvanharen.mavendeps.ide

import com.github.arjenvanharen.mavendeps.core.AnalysisEngine
import com.github.arjenvanharen.mavendeps.core.AnalysisResult
import com.github.arjenvanharen.mavendeps.core.CacheStore
import com.github.arjenvanharen.mavendeps.core.RefreshMode
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

fun interface MavenAnalysisListener {
    fun analysisUpdated(result: AnalysisResult)

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(MavenAnalysisListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/**
 * Owns the analysis engine of a project. Refresh requests are merged and debounced, then run one at a time in the
 * background; parsed POMs are persisted to the IDE system directory so a restart only has to stat files.
 */
@Service(Service.Level.PROJECT)
class MavenAnalysisService(private val project: Project, scope: CoroutineScope) {
    private val engine = AnalysisEngine()
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var pendingMode: RefreshMode? = null
    private val pendingPaths = HashSet<Path>()
    private var cacheRestored = false
    private var savedCount = -1

    @Volatile
    var lastResult: AnalysisResult? = null
        private set

    /** Files and their ancestor directories that matter for the analysis; used to filter file events cheaply. */
    @Volatile
    private var watch = Watch(emptySet(), emptySet())

    private class Watch(val files: Set<Path>, val dirs: Set<Path>)

    private val cacheFile: Path
        get() = PathManager.getSystemDir().resolve("maven-dependency-analyzer").resolve("${project.locationHash}.bin")

    init {
        scope.launch {
            for (ignored in trigger) {
                delay(DEBOUNCE_MS)
                runPending()
            }
        }
    }

    fun requestRefresh(mode: RefreshMode, changed: Collection<Path> = emptyList()) {
        synchronized(lock) {
            pendingMode = pendingMode?.let { maxOf(it, mode) } ?: mode
            pendingPaths += changed
        }
        trigger.trySend(Unit)
    }

    /** POMs (known or referenced-but-missing) at or below [path]; empty when the event is irrelevant. */
    fun pomsAffectedBy(path: Path): List<Path> {
        val w = watch
        return when (path) {
            in w.files -> listOf(path)
            in w.dirs -> w.files.filter { it.startsWith(path) }
            else -> emptyList()
        }
    }

    private suspend fun runPending() {
        val (mode, paths) = synchronized(lock) {
            val mode = pendingMode ?: return
            val paths = pendingPaths.toList()
            pendingMode = null
            pendingPaths.clear()
            mode to paths
        }
        try {
            val result = withBackgroundProgress(project, "Analyzing Maven POMs", cancellable = false) {
                val roots = rootPoms()
                withContext(Dispatchers.IO) { refresh(roots, mode, paths) }
            }
            lastResult = result
            val files = engine.knownPaths + engine.missingPaths
            watch = Watch(files, files.flatMapTo(HashSet()) { generateSequence(it.parent) { p -> p.parent }.asIterable() })
            project.messageBus.syncPublisher(MavenAnalysisListener.TOPIC).analysisUpdated(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Maven POM analysis failed", e)
        }
    }

    private fun refresh(roots: List<Path>, mode: RefreshMode, paths: List<Path>): AnalysisResult {
        var effectiveMode = mode
        if (!cacheRestored) {
            cacheRestored = true
            if (mode != RefreshMode.FULL) {
                engine.restore(CacheStore.read(cacheFile))
                effectiveMode = RefreshMode.RESCAN
            }
        }
        val result = engine.refresh(roots, effectiveMode, paths)
        if (result.stats.parsed > 0 || result.stats.total != savedCount) {
            try {
                CacheStore.write(cacheFile, engine.snapshot())
                savedCount = result.stats.total
            } catch (e: Exception) {
                LOG.warn("Could not write Maven analysis cache to $cacheFile", e)
            }
        }
        return result
    }

    private suspend fun rootPoms(): List<Path> {
        val candidates = ArrayList<Path>()
        project.basePath?.let { candidates.add(Paths.get(it)) }
        readAction { ProjectRootManager.getInstance(project).contentRoots }
            .filter { it.isInLocalFileSystem }
            .forEach { candidates.add(it.toNioPath()) }
        return candidates.map { it.resolve("pom.xml").toAbsolutePath().normalize() }.distinct().filter { Files.isRegularFile(it) }
            .ifEmpty { listOfNotNull(project.basePath?.let { Paths.get(it, "pom.xml").toAbsolutePath().normalize() }) }
    }

    companion object {
        private val LOG = logger<MavenAnalysisService>()
        private const val DEBOUNCE_MS = 300L

        fun getInstance(project: Project): MavenAnalysisService = project.service()
    }
}
