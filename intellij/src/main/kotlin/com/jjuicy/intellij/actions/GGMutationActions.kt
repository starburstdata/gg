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
import com.jjuicy.intellij.data.*

private val LOG = logger<GGUndoAction>()

/**
 * Shared helper to run a mutation on a background thread, handle InputRequired
 * dialogs, and broadcast a refresh on success.
 */
private fun performMutation(
    project: Project,
    taskTitle: String,
    command: String,
    options: MutationOptions = MutationOptions(),
    buildMutation: () -> Any,
) {
    object : Task.Backgroundable(project, taskTitle) {
        override fun run(indicator: ProgressIndicator) {
            try {
                val repo = GGRepository.getInstance(project)
                var mutation = buildMutation()
                var result = repo.mutate(command, mutation, options)

                // Handle credential / multi-step input (e.g. git push with username/password).
                // We collect the field values and let the caller inject them — but since the
                // mutation type is opaque here, we just retry once and show an error if it
                // returns InputRequired again (most users will have SSH/credential manager).
                if (result is MutationResult.InputRequired) {
                    val req = result.request
                    val responses = mutableMapOf<String, String>()
                    ApplicationManager.getApplication().invokeAndWait {
                        for (field in req.fields) {
                            val choices = field.choices
                            val value = if (choices.isEmpty()) {
                                Messages.showInputDialog(project, field.label, req.title, null)
                            } else {
                                Messages.showEditableChooseDialog(
                                    field.label, req.title, null, choices.toTypedArray(), choices[0], null
                                )
                            } ?: return@invokeAndWait
                            responses[field.label] = value
                        }
                    }
                    if (responses.size < req.fields.size) return // user cancelled
                    // The retry with input is only meaningful if the mutation carries an
                    // InputResponse field (e.g. GitPush.input). Since buildMutation() returns
                    // a fresh value we can't inject into it here — surface a helpful message.
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(
                            project,
                            "Credential input is not yet supported in the native plugin.\n" +
                            "Please configure SSH keys or a credential manager for unattended auth.",
                            "jjuicy — Input Required"
                        )
                    }
                    return
                }

                when (result) {
                    is MutationResult.PreconditionError ->
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, result.message, "jjuicy")
                        }
                    is MutationResult.InternalError ->
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, result.message.text, "jjuicy — Internal Error")
                        }
                    else -> {
                        ApplicationManager.getApplication().invokeLater {
                            project.messageBus.syncPublisher(GG_LOG_CHANGED).onLogChanged()
                        }
                    }
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
        performMutation(project, "jj undo", "undo_operation") { UndoOperation() }
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
                val remote = if (remotes.isEmpty()) {
                    ApplicationManager.getApplication().invokeAndWait {}
                    Messages.showInputDialog(project, "Remote name:", "Push", null) ?: return@executeOnPooledThread
                } else if (remotes.size == 1) {
                    remotes[0]
                } else {
                    var chosen: String? = null
                    ApplicationManager.getApplication().invokeAndWait {
                        chosen = Messages.showEditableChooseDialog(
                            "Select remote to push to:", "Push",
                            null, remotes.toTypedArray(), remotes[0], null
                        )
                    }
                    chosen ?: return@executeOnPooledThread
                }

                performMutation(project, "jj git push", "git_push") {
                    GitPush(refspec = GitRefspec.AllBookmarks(remote_name = remote))
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
                val remote = if (remotes.size == 1) remotes[0] else {
                    var chosen: String? = null
                    ApplicationManager.getApplication().invokeAndWait {
                        chosen = Messages.showEditableChooseDialog(
                            "Select remote to fetch from:", "Fetch",
                            null, remotes.toTypedArray(), remotes.firstOrNull() ?: "origin", null
                        )
                    }
                    chosen ?: return@executeOnPooledThread
                }

                performMutation(project, "jj git fetch", "git_fetch") {
                    GitFetch(refspec = GitRefspec.AllBookmarks(remote_name = remote))
                }
            } catch (e: Exception) {
                LOG.warn("Fetch failed", e)
            }
        }
    }
}
