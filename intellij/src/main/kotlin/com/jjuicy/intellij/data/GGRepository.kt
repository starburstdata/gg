package com.jjuicy.intellij.data

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.Topic
import com.jjuicy.intellij.jj.JJException
import com.jjuicy.intellij.jj.JJGraphLayout
import com.jjuicy.intellij.jj.JJRunner
import java.io.File

private val LOG = logger<GGRepository>()

/** Message bus topic — subscribers are notified when the log data may have changed. */
val GG_LOG_CHANGED: Topic<GGRepository.LogListener> =
    Topic.create("GG Log Changed", GGRepository.LogListener::class.java)

// jj log template — pipe-separated fields, one commit per line.
// Fields: changeRef|commitHex|commitShort|descFirstLine|authorName|authorEmail|timestamp|parentCommitHexes(csv)|isWC|isImmutable|hasConflict|bookmarkNames(csv)|remoteBookmarkTokens(csv)
// remoteBookmarkTokens format: "name@remote" (e.g. "main@origin")
// changeRef = "prefix[rest]" from change_id.shortest(12): prefix is the unique shortest prefix,
//   rest is the remainder to reach 12 chars. Brackets delimit the split; safe because change IDs
//   use only a-z. changePrefix+changeRest is used as the jj revision specifier.
// NOTE: descriptions whose first line contains '|' will corrupt parsing (accepted trade-off).
private const val LOG_TEMPLATE = """change_id.shortest(12).prefix() ++ "[" ++ change_id.shortest(12).rest() ++ "]" ++ "|" ++ commit_id.short(64) ++ "|" ++ commit_id.short() ++ "|" ++ description.first_line() ++ "|" ++ author.name() ++ "|" ++ author.email() ++ "|" ++ author.timestamp().format("%Y-%m-%dT%H:%M:%S%:z") ++ "|" ++ parents.map(|p| p.commit_id().short(64)).join(",") ++ "|" ++ if(current_working_copy, "1", "0") ++ "|" ++ if(immutable, "1", "0") ++ "|" ++ if(conflict, "1", "0") ++ "|" ++ bookmarks.map(|b| b.name()).join(",") ++ "|" ++ remote_bookmarks.map(|b| b.name() ++ "@" ++ b.remote()).join(",") ++ "\n""""

/**
 * Project-scoped data façade that speaks directly to the `jj` CLI.
 *
 * Replaces the previous HTTP-to-gg-web approach. All queries and mutations run
 * [JJRunner] as a subprocess; no Rust backend is required.
 *
 * Change detection uses two mechanisms:
 *  1. VFS listener on `.jj/op_heads/` — fires after every jj operation.
 *  2. 5-second polling fallback via `jj op log --limit 1` for changes the VFS missed.
 */
@Service(Service.Level.PROJECT)
class GGRepository(private val project: Project) : Disposable {

    interface LogListener {
        fun onLogChanged()
    }

    private val runner: JJRunner by lazy { JJRunner(project.basePath ?: "") }
    private var pollThread: Thread? = null

    val isReady: Boolean get() = project.basePath != null && runner.findBinary() != null

    // --- Initialisation ---

    /**
     * Start background change-detection. Call once after the UI is shown.
     * Also registers the VFS listener for `.jj/op_heads/`.
     */
    fun startWatching() {
        startOpPolling()
        startVfsWatching()
    }

    private fun startVfsWatching() {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    val jjPath = "${project.basePath}/.jj/"
                    if (events.any { it.path.startsWith(jjPath) }) {
                        fireLogChanged()
                    }
                }
            }
        )
    }

    // every 5s stat .jj/op_heads/ to catch external changes the VFS missed
    private fun startOpPolling() {
        val opHeads = File("${project.basePath}/.jj/op_heads")
        var lastMtime = opHeads.lastModified()

        pollThread?.interrupt()
        pollThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(5_000)
                    val mtime = opHeads.lastModified()
                    if (mtime != lastMtime) {
                        lastMtime = mtime
                        fireLogChanged()
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    LOG.debug("op poll: ${e.message}")
                }
            }
        }, "jj-op-poller")
        pollThread!!.isDaemon = true
        pollThread!!.start()
    }

    private fun fireLogChanged() {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(GG_LOG_CHANGED).onLogChanged()
            }
        }
    }

    // --- Queries ---

    /**
     * Returns workspace configuration. `latest_query` is always `"all()"` since
     * we no longer persist the last revset in the backend.
     */
    fun loadWorkspace(): RepoConfig {
        val rootResult = runner.run("root")
        if (rootResult.exitCode != 0) {
            return RepoConfig.LoadError(
                absolute_path = DisplayPath(project.basePath ?: ""),
                message = rootResult.stderr.trim(),
            )
        }
        val root = rootResult.stdout.trim()
        val remotes = loadRemotes()
        return RepoConfig.Workspace(
            absolute_path = DisplayPath(root),
            git_remotes = remotes,
            query_choices = emptyMap(),
            latest_query = "all()",
            status = RepoStatus("", CommitId("", "", "")),
        )
    }

    /** Runs `jj log` with the given revset and returns a [LogPage] with graph layout. */
    fun loadLog(revset: String): LogPage {
        val output = runner.runOrThrow(
            "log", "--no-graph", "--color=never",
            "-r", revset,
            "-T", LOG_TEMPLATE,
        )
        val headers = output.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { parseLogLine(it) }
        val rows = JJGraphLayout.computeRows(headers)
        return LogPage(rows = rows, has_more = false)
    }

    /**
     * Returns revision details (headers + file-level changes) for a [RevSet].
     * Only singleton sets are supported (from == to); range sets are silently
     * treated as the `from` revision.
     */
    fun loadRevisions(set: RevSet): RevsResult {
        val changeHex = set.from.change.hex
        // fetch full description separately (log template only captures first_line)
        val descOutput = runner.run("log", "-r", changeHex, "--no-graph", "--color=never", "-T", "description")
        val fullDesc = if (descOutput.exitCode == 0) descOutput.stdout else set.from.change.hex

        val headerOutput = runner.run(
            "log", "--no-graph", "--color=never",
            "-r", changeHex,
            "-T", LOG_TEMPLATE,
        )
        val header = headerOutput.stdout.lines()
            .firstOrNull { it.isNotBlank() }
            ?.let { parseLogLine(it) }
            ?: return RevsResult.NotFound(set)

        // patch in the full description
        val fullHeader = header.copy(
            description = MultilineString(fullDesc.trimEnd().lines())
        )

        val changes = loadChanges(changeHex)
        return RevsResult.Detail(
            set = set,
            headers = listOf(fullHeader),
            parents = emptyList(),
            changes = changes,
            conflicts = emptyList(),
        )
    }

    /** Returns before/after content for a file at the given revision. */
    fun loadFileContent(id: RevId, path: TreePath): FileContentResult {
        val changeHex = id.change.hex
        val filePath = path.repo_path

        val afterResult = runner.run("file", "show", "-r", changeHex, filePath)
        val after = if (afterResult.exitCode == 0) afterResult.stdout else null

        val beforeResult = runner.run("file", "show", "-r", "$changeHex-", filePath)
        val before = if (beforeResult.exitCode == 0) beforeResult.stdout else null

        return FileContentResult(before = before, after = after)
    }

    /** Returns a list of git remote names for this repo. */
    fun loadRemotes(): List<String> {
        val result = runner.run("git", "remote", "list")
        if (result.exitCode != 0) return emptyList()
        // Each line is "<name> <url>" — take only the first token (the name).
        return result.stdout.lines()
            .mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { n -> n.isNotEmpty() } }
    }

    // --- Mutations ---
    // Each method maps directly to a jj CLI command; throws JJException on failure.

    fun describeRevision(id: RevId, description: String) {
        runner.runOrThrow("describe", "-r", id.change.hex, "-m", description)
    }

    fun checkoutRevision(id: RevId) {
        runner.runOrThrow("edit", "-r", id.change.hex)
    }

    fun createRevision(parentId: RevId) {
        runner.runOrThrow("new", "-r", parentId.change.hex)
    }

    fun squashIntoParent(fromId: RevId) {
        runner.runOrThrow("squash", "-r", fromId.change.hex)
    }

    fun createBookmark(name: String, revId: RevId) {
        runner.runOrThrow("bookmark", "create", name, "-r", revId.change.hex)
    }

    fun moveBookmark(ref: StoreRef.LocalBookmark, toId: RevId) {
        runner.runOrThrow("bookmark", "set", ref.bookmark_name, "-r", toId.change.hex)
    }

    fun abandonRevisions(id: RevId) {
        runner.runOrThrow("abandon", id.change.hex)
    }

    fun undoOperation() {
        runner.runOrThrow("undo")
    }

    fun gitPush(remote: String, bookmarkName: String? = null) {
        if (bookmarkName != null) {
            runner.runOrThrow("git", "push", "-b", bookmarkName, "--remote", remote)
        } else {
            runner.runOrThrow("git", "push", "--remote", remote)
        }
    }

    fun gitFetch(remote: String) {
        runner.runOrThrow("git", "fetch", "--remote", remote)
    }

    // --- Parsing helpers ---

    private fun parseLogLine(line: String): RevHeader? {
        val parts = line.split("|")
        if (parts.size < 13) return null
        return try {
            val rawChange = parts[0]      // "prefix[rest]" from change_id.shortest(12)
            val bracketIdx = rawChange.indexOf('[')
            val changePrefix: String
            val changeRest: String
            if (bracketIdx >= 0) {
                changePrefix = rawChange.substring(0, bracketIdx)
                changeRest = rawChange.substring(bracketIdx + 1, rawChange.length - 1)
            } else {
                changePrefix = rawChange
                changeRest = ""
            }
            val changeRef = changePrefix + changeRest  // full 12-char form for jj revision specifier
            val commitHex = parts[1]      // commit_id.short(64) — full hex
            val commitShort = parts[2]    // commit_id.short()
            val descFirstLine = parts[3]
            val authorName = parts[4]
            val authorEmail = parts[5]
            val timestamp = parts[6]
            val parentHexes = parts[7].split(",").filter { it.isNotBlank() }
            val isWC = parts[8] == "1"
            val isImmutable = parts[9] == "1"
            val hasConflict = parts[10] == "1"
            val localBookmarkNames = parts[11].split(",").filter { it.isNotBlank() }
            val remoteBookmarkTokens = parts[12].split(",").filter { it.isNotBlank() }

            val localBookmarkNamesSet = localBookmarkNames.toSet()
            val localRefs = localBookmarkNames.map { name ->
                // synced = the remote counterpart is at this same commit
                val isSynced = remoteBookmarkTokens.any { it.startsWith("$name@") }
                StoreRef.LocalBookmark(
                    bookmark_name = name,
                    has_conflict = false,
                    is_synced = isSynced,
                    tracking_remotes = emptyList(),
                    available_remotes = 0,
                    potential_remotes = 0,
                )
            }
            val remoteRefs = remoteBookmarkTokens.mapNotNull { token ->
                // format: "name@remote" (e.g. "main@origin")
                val atIdx = token.lastIndexOf('@')
                if (atIdx > 0) {
                    val name = token.substring(0, atIdx)
                    // hide remote chip when the local bookmark is co-located (synced) — just show green
                    if (name !in localBookmarkNamesSet) StoreRef.RemoteBookmark(
                        bookmark_name = name,
                        remote_name = token.substring(atIdx + 1),
                        has_conflict = false,
                        is_synced = false,
                        is_tracked = true,
                        is_absent = false,
                    ) else null
                } else null
            }

            RevHeader(
                id = RevId(
                    change = ChangeId(hex = changeRef, prefix = changePrefix, rest = changeRest),
                    commit = CommitId(hex = commitHex, prefix = commitShort, rest = commitHex.drop(commitShort.length)),
                ),
                description = MultilineString(listOf(descFirstLine)),
                author = RevAuthor(email = authorEmail, name = authorName, timestamp = timestamp),
                has_conflict = hasConflict,
                is_working_copy = isWC,
                is_immutable = isImmutable,
                refs = localRefs + remoteRefs,
                parent_ids = parentHexes.map { hex ->
                    CommitId(hex = hex, prefix = hex.take(8), rest = hex.drop(8))
                },
            )
        } catch (e: Exception) {
            LOG.warn("Failed to parse log line: $line", e)
            null
        }
    }

    /** Parses `jj diff -r <changeHex> --git` output into a list of [RevChange]s. */
    private fun loadChanges(changeHex: String): List<RevChange> {
        val diffOutput = runner.run("diff", "-r", changeHex, "--git", "--color=never")
        if (diffOutput.exitCode != 0 || diffOutput.stdout.isBlank()) return emptyList()
        return parseGitDiff(diffOutput.stdout)
    }

    private fun parseGitDiff(diff: String): List<RevChange> {
        val changes = mutableListOf<RevChange>()
        var path: String? = null
        var kind = ChangeKind.Modified

        for (line in diff.lines()) {
            when {
                line.startsWith("diff --git ") -> {
                    path?.let { changes.add(makeChange(it, kind)) }
                    // "diff --git a/foo b/foo" → extract path after " b/"
                    val bIdx = line.lastIndexOf(" b/")
                    path = if (bIdx >= 0) line.substring(bIdx + 3) else null
                    kind = ChangeKind.Modified
                }
                line.startsWith("new file mode") -> kind = ChangeKind.Added
                line.startsWith("deleted file mode") -> kind = ChangeKind.Deleted
            }
        }
        path?.let { changes.add(makeChange(it, kind)) }
        return changes
    }

    private fun makeChange(repoPath: String, kind: ChangeKind) = RevChange(
        kind = kind,
        path = TreePath(repo_path = repoPath, relative_path = DisplayPath(repoPath)),
        has_conflict = false,
        hunks = emptyList(),
    )

    // --- Lifecycle ---

    override fun dispose() {
        pollThread?.interrupt()
        pollThread = null
    }

    companion object {
        fun getInstance(project: Project): GGRepository =
            project.getService(GGRepository::class.java)
    }
}
