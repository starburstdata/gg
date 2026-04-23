package com.jjuicy.intellij.jj

import com.intellij.openapi.diagnostic.logger
import com.jjuicy.intellij.GGSettings
import java.io.File

private val LOG = logger<JJRunner>()

data class JJResult(val stdout: String, val stderr: String, val exitCode: Int)

class JJException(message: String) : Exception(message)

/**
 * Locates the jj binary and runs commands against a workspace.
 *
 * Binary discovery: check GGSettings.jjPath, then search PATH.
 * No bundled binary — jj must be installed by the user.
 */
class JJRunner(val workspacePath: String) {

    fun findBinary(): String? {
        val configured = GGSettings.instance.jjPath
        if (configured.isNotBlank()) {
            val f = File(configured)
            if (f.isFile && f.canExecute()) return configured
        }
        return findOnPath("jj")
    }

    private fun findOnPath(name: String): String? {
        val pathEnv = System.getenv("PATH") ?: return null
        val sep = System.getProperty("path.separator", ":")
        val isWindows = System.getProperty("os.name", "").lowercase().contains("windows")
        for (dir in pathEnv.split(sep)) {
            val candidate = File(dir, name)
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
            if (isWindows) {
                val win = File(dir, "$name.exe")
                if (win.isFile && win.canExecute()) return win.absolutePath
            }
        }
        return null
    }

    /** Runs jj with the given args. Never throws — returns exit code in the result. */
    fun run(vararg args: String): JJResult {
        val binary = findBinary() ?: return JJResult("", "jj not found. Install jj or set the path in Settings → Tools → jjuicy.", 1)
        val cmd = listOf(binary) + args.toList()
        LOG.debug("jj: ${cmd.joinToString(" ")}")
        return try {
            val process = ProcessBuilder(cmd)
                .directory(File(workspacePath))
                .start()
            // read stdout and stderr concurrently to avoid blocking
            val stdoutFuture = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val stderrFuture = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
            val exitCode = process.waitFor()
            JJResult(stdoutFuture, stderrFuture, exitCode)
        } catch (e: Exception) {
            JJResult("", e.message ?: "failed to run jj", 1)
        }
    }

    /** Runs jj; throws [JJException] if the exit code is non-zero. Returns stdout. */
    fun runOrThrow(vararg args: String): String {
        val result = run(*args)
        if (result.exitCode != 0) {
            throw JJException(result.stderr.trim().ifBlank { "jj exited with code ${result.exitCode}" })
        }
        return result.stdout
    }
}
