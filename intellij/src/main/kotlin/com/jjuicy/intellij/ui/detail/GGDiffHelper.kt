package com.jjuicy.intellij.ui.detail

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jjuicy.intellij.data.GGRepository
import com.jjuicy.intellij.data.RevId
import com.jjuicy.intellij.data.TreePath

private val LOG = logger<GGDiffHelper>()

/**
 * Opens the native IntelliJ diff viewer for a changed file.
 *
 * Extracted from GGBrowserPanel.openDiff() — the same logic but taking typed
 * arguments rather than raw JSON.
 */
object GGDiffHelper {

    private var activeDiffFile: VirtualFile? = null

    fun openDiff(project: Project, revId: RevId, path: TreePath) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = GGRepository.getInstance(project).loadFileContent(revId, path)
                val fileName = path.relative_path.value

                ApplicationManager.getApplication().invokeLater {
                    val factory = DiffContentFactory.getInstance()
                    val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)

                    val beforeContent = factory.create(project, result.before ?: "", fileType)
                    val afterContent = factory.create(project, result.after ?: "", fileType)

                    val request = SimpleDiffRequest(
                        fileName,
                        beforeContent,
                        afterContent,
                        "Before",
                        "After",
                    )

                    val editorManager = FileEditorManager.getInstance(project)
                    activeDiffFile?.let { editorManager.closeFile(it) }
                    val openBefore = editorManager.openFiles.toSet()
                    DiffManager.getInstance().showDiff(project, request)
                    activeDiffFile = (editorManager.openFiles.toSet() - openBefore).firstOrNull()
                }
            } catch (e: Exception) {
                LOG.warn("Failed to open IntelliJ diff view", e)
            }
        }
    }
}
