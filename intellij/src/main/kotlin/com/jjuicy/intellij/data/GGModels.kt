package com.jjuicy.intellij.data

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

// --- Primitive helpers ---

/** Serializes as a plain JSON string, e.g. "path/to/file" */
@Serializable(with = DisplayPathSerializer::class)
data class DisplayPath(val value: String)

object DisplayPathSerializer : KSerializer<DisplayPath> {
    override val descriptor = String.serializer().descriptor
    override fun serialize(encoder: Encoder, value: DisplayPath) = encoder.encodeString(value.value)
    override fun deserialize(decoder: Decoder) = DisplayPath(decoder.decodeString())
}

/** Serializes as a JSON array [col, row] */
@Serializable(with = LogCoordinatesSerializer::class)
data class LogCoordinates(val col: Int, val row: Int)

object LogCoordinatesSerializer : KSerializer<LogCoordinates> {
    private val delegate = ListSerializer(Int.serializer())
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: LogCoordinates) =
        delegate.serialize(encoder, listOf(value.col, value.row))
    override fun deserialize(decoder: Decoder): LogCoordinates {
        val list = delegate.deserialize(decoder)
        return LogCoordinates(list[0], list[1])
    }
}

// --- IDs ---
// These structs have `"type": "CommitId"` / `"type": "ChangeId"` in JSON.
// @EncodeDefault ensures the field is always emitted when serializing requests to the backend.

@Serializable
data class CommitId(
    val hex: String,
    val prefix: String,
    val rest: String,
    @EncodeDefault val type: String = "CommitId",
)

@Serializable
data class ChangeId(
    val hex: String,
    val prefix: String,
    val rest: String,
    val offset: Int? = null,
    val is_divergent: Boolean = false,
    @EncodeDefault val type: String = "ChangeId",
)

@Serializable
data class RevId(val change: ChangeId, val commit: CommitId)

@Serializable
data class RevSet(val from: RevId, val to: RevId)

// --- Revision metadata ---

@Serializable
data class MultilineString(val lines: List<String>) {
    val text: String get() = lines.joinToString("\n")
    val firstLine: String get() = lines.firstOrNull() ?: ""
}

@Serializable
data class RevAuthor(val email: String, val name: String, val timestamp: String)

@Serializable
@JsonClassDiscriminator("type")
sealed class StoreRef {
    @Serializable @SerialName("LocalBookmark")
    data class LocalBookmark(
        val bookmark_name: String,
        val has_conflict: Boolean,
        val is_synced: Boolean,
        val tracking_remotes: List<String>,
        val available_remotes: Int,
        val potential_remotes: Int,
    ) : StoreRef()

    @Serializable @SerialName("RemoteBookmark")
    data class RemoteBookmark(
        val bookmark_name: String,
        val remote_name: String,
        val has_conflict: Boolean,
        val is_synced: Boolean,
        val is_tracked: Boolean,
        val is_absent: Boolean,
    ) : StoreRef()

    @Serializable @SerialName("Tag")
    data class Tag(val tag_name: String) : StoreRef()
}

@Serializable
data class RevHeader(
    val id: RevId,
    val description: MultilineString,
    val author: RevAuthor,
    val has_conflict: Boolean,
    val is_working_copy: Boolean,
    val working_copy_of: String? = null,
    val is_immutable: Boolean,
    val refs: List<StoreRef>,
    val parent_ids: List<CommitId>,
)

// --- Workspace / config ---

@Serializable
data class RepoStatus(val operation_description: String, val working_copy: CommitId)

@Serializable
@JsonClassDiscriminator("type")
sealed class RepoConfig {
    @Serializable @SerialName("Initial") object Initial : RepoConfig()

    @Serializable @SerialName("Workspace")
    data class Workspace(
        val absolute_path: DisplayPath,
        val git_remotes: List<String>,
        val query_choices: Map<String, String>,
        val latest_query: String,
        val status: RepoStatus,
        val theme_override: String? = null,
        val mark_unpushed_bookmarks: Boolean = false,
        val track_recent_workspaces: Boolean = false,
        val ignore_immutable: Boolean = false,
        val has_external_diff_tool: Boolean = false,
        val has_external_merge_tool: Boolean = false,
    ) : RepoConfig()

    @Serializable @SerialName("TimeoutError") object TimeoutError : RepoConfig()

    @Serializable @SerialName("LoadError")
    data class LoadError(val absolute_path: DisplayPath, val message: String) : RepoConfig()

    @Serializable @SerialName("WorkerError")
    data class WorkerError(val message: String) : RepoConfig()
}

// --- Log graph types ---

@Serializable
@JsonClassDiscriminator("type")
sealed class LogLine {
    abstract val source: LogCoordinates
    abstract val target: LogCoordinates
    abstract val indirect: Boolean

    @Serializable @SerialName("FromNode")
    data class FromNode(
        override val source: LogCoordinates,
        override val target: LogCoordinates,
        override val indirect: Boolean,
        val via: Int? = null,
    ) : LogLine()

    @Serializable @SerialName("ToNode")
    data class ToNode(
        override val source: LogCoordinates,
        override val target: LogCoordinates,
        override val indirect: Boolean,
    ) : LogLine()

    @Serializable @SerialName("ToIntersection")
    data class ToIntersection(
        override val source: LogCoordinates,
        override val target: LogCoordinates,
        override val indirect: Boolean,
    ) : LogLine()

    @Serializable @SerialName("ToMissing")
    data class ToMissing(
        override val source: LogCoordinates,
        override val target: LogCoordinates,
        override val indirect: Boolean,
    ) : LogLine()
}

@Serializable
data class LogRow(
    val revision: RevHeader,
    val location: LogCoordinates,
    val padding: Int,
    val lines: List<LogLine>,
    val hidden_forks: List<String>,
)

@Serializable
data class LogPage(val rows: List<LogRow>, val has_more: Boolean)

// --- Diff / change types ---

@Serializable
enum class ChangeKind { None, Added, Deleted, Modified }

@Serializable
data class ChangeRange(val start: Int, val len: Int)

@Serializable
data class ChangeLocation(val from_file: ChangeRange, val to_file: ChangeRange)

@Serializable
data class ChangeHunk(val location: ChangeLocation, val lines: MultilineString)

@Serializable
data class TreePath(val repo_path: String, val relative_path: DisplayPath)

@Serializable
data class RevChange(
    val kind: ChangeKind,
    val path: TreePath,
    val has_conflict: Boolean,
    val hunks: List<ChangeHunk>,
)

@Serializable
data class RevConflict(val path: TreePath, val hunks: List<ChangeHunk>)

@Serializable
@JsonClassDiscriminator("type")
sealed class RevsResult {
    @Serializable @SerialName("NotFound")
    data class NotFound(val set: RevSet) : RevsResult()

    @Serializable @SerialName("Detail")
    data class Detail(
        val set: RevSet,
        val headers: List<RevHeader>,
        val parents: List<RevHeader>,
        val changes: List<RevChange>,
        val conflicts: List<RevConflict>,
    ) : RevsResult()
}

@Serializable
data class FileContentResult(val before: String? = null, val after: String? = null)

// --- Input / mutation result ---

@Serializable
data class InputField(val label: String, val choices: List<String>)

@Serializable
data class InputRequest(val title: String, val detail: String, val fields: List<InputField>)

@Serializable
data class InputResponse(val fields: Map<String, String>)

@Serializable
@JsonClassDiscriminator("type")
sealed class MutationResult {
    @Serializable @SerialName("Unchanged") object Unchanged : MutationResult()

    @Serializable @SerialName("Updated")
    data class Updated(val new_status: RepoStatus, val new_selection: RevHeader? = null) : MutationResult()

    @Serializable @SerialName("Reconfigured")
    data class Reconfigured(val new_config: RepoConfig) : MutationResult()

    @Serializable @SerialName("InputRequired")
    data class InputRequired(val request: InputRequest) : MutationResult()

    @Serializable @SerialName("PreconditionError")
    data class PreconditionError(val message: String) : MutationResult()

    @Serializable @SerialName("InternalError")
    data class InternalError(val message: MultilineString) : MutationResult()
}

// --- Mutation request bodies ---

@Serializable
data class MutationOptions(val ignore_immutable: Boolean = false)

@Serializable
data class CheckoutRevision(val id: RevId)

@Serializable
data class CreateRevision(val set: RevSet)

@Serializable
data class DescribeRevision(val id: RevId, val new_description: String, val reset_author: Boolean = false)

@Serializable
data class AbandonRevisions(val set: RevSet)

@Serializable
data class DuplicateRevisions(val set: RevSet)

@Serializable
data class MoveRevisions(val set: RevSet, val parent_ids: List<RevId>)

@Serializable
data class BackoutRevisions(val set: RevSet)

@Serializable
class UndoOperation

@Serializable
@JsonClassDiscriminator("type")
sealed class GitRefspec {
    @Serializable @SerialName("AllBookmarks")
    data class AllBookmarks(val remote_name: String) : GitRefspec()

    @Serializable @SerialName("AllRemotes")
    data class AllRemotes(val bookmark_ref: StoreRef) : GitRefspec()

    @Serializable @SerialName("RemoteBookmark")
    data class RemoteBookmark(val remote_name: String, val bookmark_ref: StoreRef) : GitRefspec()
}

@Serializable
data class GitPush(val refspec: GitRefspec, val input: InputResponse? = null)

@Serializable
data class GitFetch(val refspec: GitRefspec, val input: InputResponse? = null)

@Serializable
data class CreateRef(val id: RevId, val ref_name: String)

@Serializable
data class DeleteRef(val ref_name: String)

@Serializable
data class MoveRef(val ref_name: String, val to_id: RevId)

@Serializable
data class RenameBookmark(val ref_name: String, val new_name: String)
