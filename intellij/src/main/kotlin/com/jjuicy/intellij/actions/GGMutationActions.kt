package com.jjuicy.intellij.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.jjuicy.intellij.data.GGRepository
import com.jjuicy.intellij.data.GG_LOG_CHANGED
import com.jjuicy.intellij.jj.JJException

private val LOG = logger<GGUndoAction>()

/**
 * Shared helper to run a mutation on a background thread, show any error dialog,
 * and broadcast a log refresh on success.
 */
internal fun performMutation(
    project: Project,
    taskTitle: String,
    block: () -> Unit,
) {
    object : Task.Backgroundable(project, taskTitle) {
        override fun run(indicator: ProgressIndicator) {
            try {
                block()
                ApplicationManager.getApplication().invokeLater {
                    project.messageBus.syncPublisher(GG_LOG_CHANGED).onLogChanged()
                }
            } catch (e: JJException) {
                LOG.warn("$taskTitle failed: ${e.message}")
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, e.message ?: "Unknown error", "jjuicy")
                }
            } catch (e: Exception) {
                LOG.warn("$taskTitle failed", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, e.message ?: "Unknown error", "jjuicy")
                }
            }
        }
    }.queue()
}

// ---------------------------------------------------------------------------
// Undo
// ---------------------------------------------------------------------------

class GGUndoAction : AnAction("Undo Operation", "Undo the last jj operation", AllIcons.Actions.Rollback), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { GGRepository.getInstance(it).isReady } == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        performMutation(project, "jj undo") {
            GGRepository.getInstance(project).undoOperation()
        }
    }
}

// ---------------------------------------------------------------------------
// Push
// ---------------------------------------------------------------------------

class GGPushAction : AnAction("Push", "Push bookmarks to remote", AllIcons.Vcs.Push), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { GGRepository.getInstance(it).isReady } == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val remotes = GGRepository.getInstance(project).loadRemotes()
                val remote = chooseRemote(project, remotes, "Push") ?: return@executeOnPooledThread
                performMutation(project, "jj git push") {
                    GGRepository.getInstance(project).gitPush(remote)
                }
            } catch (e: Exception) {
                LOG.warn("Push failed", e)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Fetch
// ---------------------------------------------------------------------------

class GGFetchAction : AnAction("Fetch", "Fetch from remote", AllIcons.Vcs.Fetch), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { GGRepository.getInstance(it).isReady } == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val remotes = GGRepository.getInstance(project).loadRemotes()
                val remote = chooseRemote(project, remotes, "Fetch") ?: return@executeOnPooledThread
                performMutation(project, "jj git fetch") {
                    GGRepository.getInstance(project).gitFetch(remote)
                }
            } catch (e: Exception) {
                LOG.warn("Fetch failed", e)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/** Shows a remote selection dialog; returns null if user cancelled. */
internal fun chooseRemote(project: Project, remotes: List<String>, action: String): String? {
    if (remotes.isEmpty()) {
        var name: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            name = Messages.showInputDialog(project, "Remote name:", action, null)
        }
        return name
    }
    if (remotes.size == 1) return remotes[0]
    var chosen: String? = null
    ApplicationManager.getApplication().invokeAndWait {
        chosen = Messages.showEditableChooseDialog(
            "Select remote:", action, null, remotes.toTypedArray(), remotes[0], null
        )
    }
    return chosen
}
