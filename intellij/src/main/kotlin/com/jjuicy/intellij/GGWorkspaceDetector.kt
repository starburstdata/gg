package com.jjuicy.intellij

import com.intellij.openapi.project.Project
import java.io.File

/**
 * Detects whether a project contains a Jujutsu (.jj/) workspace.
 *
 * Walks from the project base path upward, checking each directory for .jj/.
 * This mirrors jj-lib's workspace discovery logic.
 */
object GGWorkspaceDetector {

    fun hasJjWorkspace(project: Project): Boolean {
        val basePath = project.basePath ?: return false
        return findJjRoot(File(basePath)) != null
    }

    fun findJjRoot(startDir: File): File? {
        var dir: File? = startDir
        while (dir != null) {
            if (File(dir, ".jj").isDirectory) return dir
            dir = dir.parentFile
        }
        return null
    }
}
