package com.jjuicy.intellij

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val LOG = logger<GGProcessManager>()

/**
 * Manages the lifecycle of the `gg web` (or `jjuicy web`) child process for a project.
 *
 * Created per-project. Spawns the backend when [start] is called, parses the
 * readiness URL from stderr, and kills the process on [dispose].
 */
@Service(Service.Level.PROJECT)
class GGProcessManager(private val project: Project) : Disposable {

    private val processRef = AtomicReference<Process?>(null)
    private val readyFuture = CompletableFuture<String>()

    // listeners notified when the process starts or crashes
    private val listeners = mutableListOf<ProcessListener>()

    interface ProcessListener {
        fun onReady(url: String)
        fun onCrashed()
    }

    fun addListener(listener: ProcessListener) {
        listeners.add(listener)
        // if already ready, notify immediately
        if (readyFuture.isDone && !readyFuture.isCompletedExceptionally) {
            listener.onReady(readyFuture.get())
        }
    }

    fun removeListener(listener: ProcessListener) {
        listeners.remove(listener)
    }

    /**
     * Returns true if the backend is ready to accept connections.
     */
    val isReady: Boolean get() = readyFuture.isDone && !readyFuture.isCompletedExceptionally

    /**
     * The URL the backend is listening on, or null if not yet ready.
     */
    val serverUrl: String? get() = if (isReady) readyFuture.get() else null

    /**
     * Finds the gg binary: user-configured path, then bundled binary, then PATH.
     */
    private fun findBinary(): String? {
        val settings = GGSettings.instance
        if (settings.binaryPath.isNotBlank()) {
            val f = java.io.File(settings.binaryPath)
            if (f.isFile && f.canExecute()) return settings.binaryPath
        }
        extractBundledBinary()?.let { return it }
        for (name in listOf("jjuicy", "gg")) {
            val found = findOnPath(name)
            if (found != null) return found
        }
        return null
    }

    /**
     * Extracts the bundled gg binary from JAR resources to a versioned cache
     * directory (~/.jjuicy/bin/<version>/gg) and marks it executable.
     *
     * JARs don't preserve Unix execute permissions, so extraction is required
     * on every platform. The version subdirectory means plugin upgrades
     * automatically pick up the new binary.
     */
    private fun extractBundledBinary(): String? {
        val binaryName = if (System.getProperty("os.name", "").lowercase().contains("windows")) "gg.exe" else "gg"
        val resource = "/binaries/$binaryName"
        val stream = GGProcessManager::class.java.getResourceAsStream(resource) ?: return null

        val version = try {
            com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                com.intellij.openapi.extensions.PluginId.getId("com.jjuicy")
            )?.version ?: "bundled"
        } catch (_: Exception) { "bundled" }

        val destDir = java.io.File(System.getProperty("user.home"), ".jjuicy/bin/$version")
        destDir.mkdirs()
        val dest = java.io.File(destDir, binaryName)

        if (!dest.exists()) {
            stream.use { it.copyTo(dest.outputStream()) }
            dest.setExecutable(true, false)
            LOG.info("Extracted bundled gg binary to ${dest.absolutePath}")
        }

        return if (dest.canExecute()) dest.absolutePath else null
    }

    private fun findOnPath(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        val sep = System.getProperty("path.separator", ":")
        for (dir in path.split(sep)) {
            val candidate = java.io.File(dir, name)
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
        }
        // on Windows, also check .exe
        if (System.getProperty("os.name", "").lowercase().contains("windows")) {
            for (dir in path.split(sep)) {
                val candidate = java.io.File(dir, "$name.exe")
                if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
            }
        }
        return null
    }

    /**
     * Spawns `gg web <workspace> --port 0 --no-launch --foreground` and waits
     * for the readiness signal on stderr.
     *
     * Idempotent: safe to call multiple times; only starts once per service lifecycle.
     */
    fun start() {
        if (processRef.get() != null) return

        val binary = findBinary()
        if (binary == null) {
            readyFuture.completeExceptionally(
                IllegalStateException("jjuicy/gg binary not found. Set the path in Settings → Tools → jjuicy.")
            )
            showBinaryNotFoundNotification()
            return
        }

        val workspacePath = project.basePath ?: run {
            LOG.warn("Project has no base path, cannot start gg web")
            return
        }

        val cmd = listOf(binary, "web", workspacePath, "--port", "0", "--no-launch", "--foreground")
        LOG.info("Starting gg web: ${cmd.joinToString(" ")}")

        val process = try {
            ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            LOG.error("Failed to start gg web", e)
            readyFuture.completeExceptionally(e)
            return
        }
        processRef.set(process)

        // read stderr for the readiness signal on a background thread
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(process.errorStream))
                val urlPattern = Regex("""http://127\.0\.0\.1:\d+""")
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    LOG.debug("gg web stderr: $line")
                    val match = urlPattern.find(line!!)
                    if (match != null && !readyFuture.isDone) {
                        val url = match.value
                        LOG.info("gg web ready at $url")
                        readyFuture.complete(url)
                        listeners.forEach { it.onReady(url) }
                    }
                }
            } catch (e: Exception) {
                if (!readyFuture.isDone) {
                    readyFuture.completeExceptionally(e)
                }
            }
        }, "gg-web-stderr-reader").also { it.isDaemon = true }.start()

        // also drain stdout to avoid blocking
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    LOG.debug("gg web stdout: $line")
                }
            } catch (_: Exception) {}
        }, "gg-web-stdout-reader").also { it.isDaemon = true }.start()

        // monitor for unexpected exit
        Thread({
            try {
                val exitCode = process.waitFor()
                LOG.warn("gg web exited with code $exitCode")
                if (!readyFuture.isDone) {
                    readyFuture.completeExceptionally(
                        RuntimeException("gg web exited with code $exitCode before becoming ready")
                    )
                } else {
                    // process died after we were ready — notify listeners
                    listeners.forEach { it.onCrashed() }
                    showCrashNotification(exitCode)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "gg-web-monitor").also { it.isDaemon = true }.start()
    }

    /**
     * Restarts the backend process. Kills any existing process first.
     */
    fun restart() {
        val old = processRef.getAndSet(null)
        if (old != null && old.isAlive) {
            old.destroy()
            old.waitFor(5, TimeUnit.SECONDS)
            if (old.isAlive) old.destroyForcibly()
        }
        start()
    }

    override fun dispose() {
        val process = processRef.getAndSet(null) ?: return
        LOG.info("Stopping gg web process")
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            LOG.warn("gg web did not stop gracefully, force-killing")
            process.destroyForcibly()
        }
    }

    private fun showBinaryNotFoundNotification() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("jjuicy")
            .createNotification(
                "jjuicy",
                "Binary not found. Please install jjuicy/gg or set the path in Settings → Tools → jjuicy.",
                NotificationType.WARNING
            )
            .addAction(object : AnAction("Open Settings") {
                override fun actionPerformed(e: AnActionEvent) {
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, GGSettingsConfigurable::class.java)
                }
            })
            .notify(project)
    }

    private fun showCrashNotification(exitCode: Int) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("jjuicy")
            .createNotification(
                "jjuicy",
                "The backend process exited unexpectedly (code $exitCode).",
                NotificationType.ERROR
            )
            .addAction(object : AnAction("Restart") {
                override fun actionPerformed(e: AnActionEvent) {
                    restart()
                }
            })
            .notify(project)
    }

    companion object {
        fun getInstance(project: Project): GGProcessManager =
            project.getService(GGProcessManager::class.java)
    }
}
