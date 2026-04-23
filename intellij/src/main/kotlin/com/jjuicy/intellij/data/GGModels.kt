package com.jjuicy.intellij.data

// --- Primitive helpers ---

/** Serializes as a plain string, e.g. "path/to/file" */
data class DisplayPath(val value: String)

/** Serializes as a JSON array [col, row] */
data class LogCoordinates(val col: Int, val row: Int)

// --- IDs ---

data class CommitId(val hex: String, val prefix: String, val rest: String)

data class ChangeId(val hex: String, val prefix: String, val rest: String, val is_divergent: Boolean = false)

data class RevId(val change: ChangeId, val commit: CommitId)

data class RevSet(val from: RevId, val to: RevId)

// --- Revision metadata ---

data class MultilineString(val lines: List<String>) {
    val text: String get() = lines.joinToString("\n")
    val firstLine: String get() = lines.firstOrNull() ?: ""
}

data class RevAuthor(val email: String, val name: String, val timestamp: String)

sealed class StoreRef {
    data class LocalBookmark(
        val bookmark_name: String,
        val has_conflict: Boolean,
        val is_synced: Boolean,
        val tracking_remotes: List<String>,
        val available_remotes: Int,
        val potential_remotes: Int,
    ) : StoreRef()

    data class RemoteBookmark(
        val bookmark_name: String,
        val remote_name: String,
        val has_conflict: Boolean,
        val is_synced: Boolean,
        val is_tracked: Boolean,
        val is_absent: Boolean,
    ) : StoreRef()

    data class Tag(val tag_name: String) : StoreRef()
}

data class RevHeader(
    val id: RevId,
    val description: MultilineString,
    val author: RevAuthor,
    val has_conflict: Boolean,
    val is_working_copy: Boolean,
    val is_immutable: Boolean,
    val refs: List<StoreRef>,
    val parent_ids: List<CommitId>,
    val working_copy_of: String? = null,
)

// --- Workspace / config ---

/** Minimal repo status — used by GGRepository for simple state tracking. */
data class RepoStatus(val operation_description: String, val working_copy: CommitId)

sealed class RepoConfig {
    object Initial : RepoConfig()

    data class Workspace(
        val absolute_path: DisplayPath,
        val git_remotes: List<String>,
        val query_choices: Map<String, String>,
        val latest_query: String,
        val status: RepoStatus,
    ) : RepoConfig()

    data class LoadError(val absolute_path: DisplayPath, val message: String) : RepoConfig()
}

// --- Log graph types ---

sealed class LogLine {
    abstract val source: LogCoordinates
    abstract val target: LogCoordinates
    abstract val indirect: Boolean

    data class FromNode(
        override val source: LogCoordinates,
        override val target: LogCoordinates,
        override val indirect: Boolean,
        val via: Int? = null,
    ) : LogLine()

    data class ToNode(
        override val source: LogCoordinates,
        override val target: LogCoordinates,
        override val indirect: Boolean,
    ) : LogLine()

    data class ToIntersection(
        override val source: LogCoordinates,
        override val target: LogCoordinates,
        override val indirect: Boolean,
    ) : LogLine()

    data class ToMissing(
        override val source: LogCoordinates,
        override val target: LogCoordinates,
        override val indirect: Boolean,
    ) : LogLine()
}

data class LogRow(
    val revision: RevHeader,
    val location: LogCoordinates,
    val padding: Int,
    val lines: List<LogLine>,
    val hidden_forks: List<String>,
)

data class LogPage(val rows: List<LogRow>, val has_more: Boolean)

// --- Diff / change types ---

enum class ChangeKind { None, Added, Deleted, Modified }

data class ChangeRange(val start: Int, val len: Int)

data class ChangeLocation(val from_file: ChangeRange, val to_file: ChangeRange)

data class ChangeHunk(val location: ChangeLocation, val lines: MultilineString)

data class TreePath(val repo_path: String, val relative_path: DisplayPath)

data class RevChange(
    val kind: ChangeKind,
    val path: TreePath,
    val has_conflict: Boolean,
    val hunks: List<ChangeHunk>,
)

data class RevConflict(val path: TreePath, val hunks: List<ChangeHunk>)

sealed class RevsResult {
    data class NotFound(val set: RevSet) : RevsResult()

    data class Detail(
        val set: RevSet,
        val headers: List<RevHeader>,
        val parents: List<RevHeader>,
        val changes: List<RevChange>,
        val conflicts: List<RevConflict>,
    ) : RevsResult()
}

data class FileContentResult(val before: String? = null, val after: String? = null)
