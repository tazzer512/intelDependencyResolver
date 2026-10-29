package com.github.arjenvanharen.mavendeps.ide

import com.github.arjenvanharen.mavendeps.core.AnalysisEngine
import com.github.arjenvanharen.mavendeps.core.AnalysisResult
import com.github.arjenvanharen.mavendeps.core.CacheStore
import com.github.arjenvanharen.mavendeps.core.DependencyResolver
import com.github.arjenvanharen.mavendeps.core.LocalRepository
import com.github.arjenvanharen.mavendeps.core.RefreshMode
import com.github.arjenvanharen.mavendeps.core.RefreshProgress
import com.github.arjenvanharen.mavendeps.core.VersionHint
import com.github.arjenvanharen.mavendeps.core.VersionHints
import com.github.arjenvanharen.mavendeps.core.Workspace
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

/** Progress of a running background job; [fraction] is null while it cannot be estimated. */
data class ProgressState(val fraction: Double?, val text: String)

/** Progress of the POM analysis, for inline progress bars. A null state means the analysis finished. */
fun interface MavenAnalysisProgressListener {
    fun progress(state: ProgressState?)

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(MavenAnalysisProgressListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** Handed to background queries: invoke it to check for cancellation, [report] to show progress. */
class QueryProgress internal constructor(
    private val check: () -> Unit,
    private val reporter: RawProgressReporter,
    private val listener: ((ProgressState?) -> Unit)?,
) : () -> Unit {
    private var lastPublished = 0L

    override fun invoke() = check()

    fun report(fraction: Double?, text: String) {
        reporter.fraction(fraction)
        reporter.text(text)
        val now = System.nanoTime()
        if (listener != null && now - lastPublished > 50_000_000) {
            lastPublished = now
            ApplicationManager.getApplication().invokeLater { listener(ProgressState(fraction, text)) }
        }
    }
}

/**
 * Owns the analysis engine of a project. Refresh requests are merged and debounced, then run one at a time in the
 * background; parsed POMs are persisted to the IDE system directory so a restart only has to stat files.
 */
@Service(Service.Level.PROJECT)
class MavenAnalysisService(private val project: Project, private val scope: CoroutineScope) {
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

    /** The local Maven repository used for transitive dependencies. */
    val repository: LocalRepository by lazy { LocalRepository.default() }

    /** A resolver over the last analysed workspace, for one query. */
    fun resolver(): DependencyResolver = DependencyResolver(engine.workspaceSnapshot(), repository)

    /** The last analysed workspace. */
    fun workspace(): Workspace = engine.workspaceSnapshot()

    /** Version hints of the last analysis, computed per POM on first use; one resolver shares its cache for all of them. */
    private class Insights(val result: AnalysisResult, val resolver: DependencyResolver) {
        val hints = HashMap<Path, List<VersionHint>>()
    }

    @Volatile
    private var insights: Insights? = null

    /** The version hints for a workspace POM, or null when it was not analysed. Reads `~/.m2`; call off the EDT. */
    fun versionHints(path: Path): List<VersionHint>? {
        val result = lastResult ?: return null
        if (result.poms[path]?.model?.wellFormed != true) return null
        val current = insights?.takeIf { it.result === result } ?: Insights(result, resolver()).also { insights = it }
        return synchronized(current) {
            current.hints.getOrPut(path) {
                try {
                    VersionHints.forPom(current.resolver.ws, current.resolver, repository, path)
                } catch (e: Exception) {
                    LOG.warn("Could not compute version hints for $path", e)
                    emptyList()
                }
            }
        }
    }

    /**
     * Runs [compute] in the background with a cancellable progress indicator and hands the result to [onDone] on
     * the EDT. [compute] receives a [QueryProgress] that throws when invoked after the user cancelled. [onProgress]
     * receives its reports on the EDT, and null when the query ends in any way.
     */
    fun <T> runQuery(
        title: String,
        compute: (progress: QueryProgress) -> T,
        onProgress: ((ProgressState?) -> Unit)? = null,
        onFailure: (Exception) -> Unit = {},
        onDone: (T) -> Unit,
    ) {
        scope.launch {
            onProgress?.let { listener -> ApplicationManager.getApplication().invokeLater { listener(ProgressState(null, title)) } }
            try {
                val result = withBackgroundProgress(project, title, cancellable = true) {
                    withContext(Dispatchers.Default) {
                        val context = coroutineContext
                        reportRawProgress { reporter -> compute(QueryProgress({ context.ensureActive() }, reporter, onProgress)) }
                    }
                }
                withContext(Dispatchers.EDT) { onDone(result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("$title failed", e)
                withContext(Dispatchers.EDT) { onFailure(e) }
            } finally {
                onProgress?.let { listener -> ApplicationManager.getApplication().invokeLater { listener(null) } }
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
            val publisher = project.messageBus.syncPublisher(MavenAnalysisProgressListener.TOPIC)
            val result = try {
                withBackgroundProgress(project, "Analyzing Maven POMs", cancellable = false) {
                    val roots = rootPoms()
                    withContext(Dispatchers.IO) {
                        reportRawProgress { reporter -> refresh(roots, mode, paths, ProgressForwarder(reporter, publisher)) }
                    }
                }
            } finally {
                publisher.progress(null)
            }
            lastResult = result
            val files = engine.knownPaths + engine.missingPaths
            watch = Watch(files, files.flatMapTo(HashSet()) { generateSequence(it.parent) { p -> p.parent }.asIterable() })
            project.messageBus.syncPublisher(MavenAnalysisListener.TOPIC).analysisUpdated(result)
            // Open POM editors show problems and version hints from the analysis.
            ApplicationManager.getApplication().invokeLater({ DaemonCodeAnalyzer.getInstance(project).restart("Maven analysis updated") }, project.disposed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Maven POM analysis failed", e)
        }
    }

    /** Passes engine progress to the IDE progress indicator and, throttled and never going backwards, to the inline bars. */
    private class ProgressForwarder(
        private val reporter: RawProgressReporter,
        private val publisher: MavenAnalysisProgressListener,
    ) : (RefreshProgress) -> Unit {
        private var fraction = 0.0
        private var lastPublished = 0L

        override fun invoke(p: RefreshProgress) {
            fraction = maxOf(fraction, p.fraction)
            val text = "${p.phase.displayName}: ${p.done} of ${p.total}"
            reporter.fraction(fraction)
            reporter.text(text)
            val now = System.nanoTime()
            if (now - lastPublished > 50_000_000) {
                lastPublished = now
                publisher.progress(ProgressState(fraction, text))
            }
        }
    }

    private fun refresh(roots: List<Path>, mode: RefreshMode, paths: List<Path>, progress: (RefreshProgress) -> Unit): AnalysisResult {
        var effectiveMode = mode
        if (!cacheRestored) {
            cacheRestored = true
            if (mode != RefreshMode.FULL) {
                engine.restore(CacheStore.read(cacheFile))
                effectiveMode = RefreshMode.RESCAN
            }
        }
        val result = engine.refresh(roots, effectiveMode, paths, progress)
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
