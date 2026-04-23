package com.jjuicy.intellij.data

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import kotlinx.serialization.Serializable

private val LOG = logger<GGRepository>()

/** Message bus topic — subscribers are notified when the log data may have changed. */
val GG_LOG_CHANGED: Topic<GGRepository.LogListener> =
    Topic.create("GG Log Changed", GGRepository.LogListener::class.java)

// Typed request wrappers matching the backend's query handlers

@Serializable private data class QueryLogReq(val revset: String)
@Serializable private data class QueryRevisionsReq(val set: RevSet)
@Serializable private data class QueryFileContentReq(val id: RevId, val path: TreePath)
@Serializable private data class QueryWorkspaceReq(val path: String? = null)
@Serializable private data class QueryRemotesReq(val tracking_bookmark: String? = null)

/**
 * Project-scoped data façade.
 *
 * Wraps [GGHttpClient] and exposes typed query/mutate helpers. Publishes
 * [GG_LOG_CHANGED] when the backend signals a state change via SSE.
 */
@Service(Service.Level.PROJECT)
class GGRepository(private val project: Project) : Disposable {

    interface LogListener {
        fun onLogChanged()
    }

    @PublishedApi internal var client: GGHttpClient? = null
    private var sseThread: Thread? = null

    /** Called by GGMainPanel once GGProcessManager reports the backend URL. */
    fun initialize(baseUrl: String) {
        client = GGHttpClient(baseUrl)
        startSse()
    }

    val isReady: Boolean get() = client != null

    // --- Queries ---

    fun loadWorkspace(): RepoConfig {
        return client!!.query<QueryWorkspaceReq, RepoConfig>("query_workspace", QueryWorkspaceReq())
    }

    fun loadLog(revset: String): LogPage {
        return client!!.query<QueryLogReq, LogPage>("query_log", QueryLogReq(revset))
    }

    fun loadNextPage(): LogPage {
        return client!!.query<QueryWorkspaceReq, LogPage>("query_log_next_page", QueryWorkspaceReq())
    }

    fun loadRevisions(set: RevSet): RevsResult {
        return client!!.query<QueryRevisionsReq, RevsResult>("query_revisions", QueryRevisionsReq(set))
    }

    fun loadFileContent(id: RevId, path: TreePath): FileContentResult {
        return client!!.query<QueryFileContentReq, FileContentResult>(
            "query_file_content",
            QueryFileContentReq(id, path)
        )
    }

    fun loadRemotes(trackingBookmark: String? = null): List<String> {
        return client!!.query<QueryRemotesReq, List<String>>(
            "query_remotes",
            QueryRemotesReq(trackingBookmark)
        )
    }

    // --- Mutations ---

    inline fun <reified T> mutate(
        command: String,
        mutation: T,
        options: MutationOptions = MutationOptions(),
    ): MutationResult {
        return client!!.mutate(command, mutation, options)
    }

    // --- SSE ---

    private fun startSse() {
        sseThread?.interrupt()
        sseThread = client!!.streamEvents { _ ->
            // any event from the backend = state may have changed → fire a refresh
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    project.messageBus.syncPublisher(GG_LOG_CHANGED).onLogChanged()
                }
            }
        }
    }

    override fun dispose() {
        sseThread?.interrupt()
        sseThread = null
        client = null
    }

    companion object {
        fun getInstance(project: Project): GGRepository =
            project.getService(GGRepository::class.java)
    }
}
