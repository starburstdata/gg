use std::{
    borrow::Borrow,
    collections::HashSet,
    io::Write,
    iter::{Peekable, Skip},
    mem,
    ops::Range,
};

use anyhow::{Result, anyhow};

use futures_util::{StreamExt, try_join};
use gix::bstr::ByteVec;
use itertools::Itertools;
use jj_cli::diff_util::LineDiffOptions;
use jj_lib::{
    backend::CommitId,
    conflict_labels::ConflictLabels,
    conflicts::{
        self, ConflictMarkerStyle, ConflictMaterializeOptions, MaterializedFileValue,
        MaterializedTreeValue,
    },
    diff::{
        CompareBytesExactly, CompareBytesIgnoreAllWhitespace, CompareBytesIgnoreWhitespaceAmount,
        ContentDiff, DiffHunk, DiffHunkKind, find_line_ranges,
    },
    diff_presentation::LineCompareMode,
    files::{self, FileMergeHunkLevel, MergeResult},
    graph::{GraphEdge, GraphEdgeType, TopoGroupedGraphIterator},
    matchers::EverythingMatcher,
    merge::{Diff, SameChange},
    merged_tree::{TreeDiffEntry, TreeDiffStream},
    ref_name::{RefNameBuf, RemoteNameBuf, RemoteRefSymbol},
    repo::Repo,
    repo_path::RepoPath,
    revset::{Revset, RevsetEvaluationError},
    rewrite,
    tree_merge::MergeOptions,
};

use crate::messages::{
    ChangeHunk, ChangeLocation, ChangeRange, MultilineString, RevSet, queries::*,
};
#[cfg(test)]
use crate::messages::{RevHeader, RevId};

use super::{WorkspaceSession, git_util::get_git_remote_names};

/// A column in the active stem array. Each column is either empty or holds a
/// stem targeting some commit; the stem "closes" when we process its target,
/// at which point we place the commit's node at that column.
///
/// The layout algorithm is a port of sapling-renderdag's `Renderer`. Stems
/// carry the source position (where they were created) so when they're
/// terminated we can emit a single LogLine spanning from child to parent.
#[derive(Clone)]
struct Stem {
    source: LogCoordinates,
    target: CommitId,
    indirect: bool,
    known_immutable: bool,
    /// True once this stem has been swapped into its current slot by a
    /// rescue sweep. The rescue emits a FromNode that already ends at the
    /// rescuing commit's row+1 coordinates, so if the stem's target turns
    /// out to be the very next commit we'd otherwise overlay a second
    /// ToNode on the same 1-row segment. See step 2 for the skip.
    rescued: bool,
}

/// state used for init or restart of a query
pub struct QueryState {
    /// max number of rows per page
    page_size: usize,
    /// number of rows already yielded
    next_row: usize,
    /// ongoing vertical stems keyed by column; None indicates an empty slot
    stems: Vec<Option<Stem>>,
}

impl QueryState {
    pub fn new(page_size: usize) -> QueryState {
        QueryState {
            page_size,
            next_row: 0,
            stems: Vec::new(),
        }
    }
}

/// live instance of a query
pub struct QuerySession<'q, 'w: 'q> {
    pub ws: &'q WorkspaceSession<'w>,
    pub state: QueryState,
    #[allow(clippy::type_complexity)]
    iter: Peekable<
        Skip<
            TopoGroupedGraphIterator<
                CommitId,
                CommitId,
                Box<
                    dyn Iterator<
                            Item = Result<
                                (CommitId, Vec<GraphEdge<CommitId>>),
                                RevsetEvaluationError,
                            >,
                        > + 'q,
                >,
                for<'a> fn(&'a CommitId) -> &'a CommitId,
            >,
        >,
    >,
    #[allow(clippy::type_complexity)]
    is_immutable: Box<dyn Fn(&CommitId) -> Result<bool, RevsetEvaluationError> + 'q>,
    hidden_forks: std::collections::HashMap<CommitId, Vec<String>>,
}

impl<'q, 'w> QuerySession<'q, 'w> {
    pub fn new(
        ws: &'q WorkspaceSession<'w>,
        revset: &'q dyn Revset,
        state: QueryState,
        hidden_forks: std::collections::HashMap<CommitId, Vec<String>>,
    ) -> QuerySession<'q, 'w> {
        let as_id: for<'a> fn(&'a CommitId) -> &'a CommitId = commit_id_identity;
        let iter = TopoGroupedGraphIterator::new(revset.iter_graph(), as_id)
            .skip(state.next_row)
            .peekable();

        let immutable_revset = ws.evaluate_immutable().unwrap();
        let is_immutable = immutable_revset.containing_fn();

        QuerySession {
            ws,
            iter,
            state,
            is_immutable,
            hidden_forks,
        }
    }

    pub fn get_page(&mut self) -> Result<LogPage> {
        let mut rows: Vec<LogRow> = Vec::with_capacity(self.state.page_size);
        let mut row = self.state.next_row;
        let max = row + self.state.page_size;

        let root_id = self.ws.repo().store().root_commit_id().clone();

        while let Some(Ok((commit_id, commit_edges))) = self.iter.next() {
            let mut lines: Vec<LogLine> = Vec::new();

            // 1. find a column for this commit: prefer an existing stem
            //    targeting it, then the leftmost empty slot, finally a new
            //    column on the right.
            let node_col = find_column(&self.state.stems, &commit_id);

            // 2. terminate any stem that was targeting this commit. Emit a
            //    ToNode line spanning from the stem's source to our position —
            //    the original, unbroken edge. If the stem was rescued in the
            //    immediately preceding row, its rescue FromNode already ends
            //    exactly at our coordinates; skipping the ToNode avoids
            //    overlaying two lines on the same 1-row segment. Rescues more
            //    than one row back still need the ToNode to cover the vertical
            //    run between the rescue endpoint (row+1) and our row.
            let mut stem_known_immutable = false;
            if let Some(slot) = self.state.stems.get_mut(node_col).and_then(|s| s.take()) {
                stem_known_immutable = slot.known_immutable;
                let rescue_already_covers =
                    slot.rescued && slot.source.0 == node_col && slot.source.1 + 1 == row;
                if !rescue_already_covers {
                    lines.push(LogLine::ToNode {
                        indirect: slot.indirect,
                        source: slot.source,
                        target: LogCoordinates(node_col, row),
                    });
                }
            } else if node_col >= self.state.stems.len() {
                self.state.stems.resize_with(node_col + 1, || None);
            }

            let known_immutable = if stem_known_immutable {
                Some(true)
            } else {
                Some((self.is_immutable)(&commit_id)?)
            };
            let header = self
                .ws
                .format_header(&self.ws.get_commit(&commit_id)?, known_immutable)?;

            // 3. assign each parent edge to a column.
            //    - Existing stem for this target → emit ToIntersection (merge).
            //    - Otherwise create a new stem (prefer the commit's own column
            //      if empty, else the leftmost gap, else push to the right).
            //    Track the only-parent case so step 4 can rescue it.
            let mut parent_count = 0usize;
            let mut single_parent_merge: Option<(usize, usize)> = None;

            for edge in commit_edges.iter() {
                if edge.edge_type == GraphEdgeType::Missing && edge.target == root_id {
                    continue;
                }
                parent_count += 1;
                let indirect = edge.edge_type != GraphEdgeType::Direct;

                if let Some(slot) = self
                    .state
                    .stems
                    .iter()
                    .position(|s| s.as_ref().map(|st| &st.target) == Some(&edge.target))
                {
                    let line_idx = lines.len();
                    lines.push(LogLine::ToIntersection {
                        indirect,
                        source: LogCoordinates(node_col, row),
                        target: LogCoordinates(slot, row + 1),
                    });
                    if parent_count == 1 {
                        single_parent_merge = Some((slot, line_idx));
                    } else {
                        single_parent_merge = None;
                    }
                    continue;
                }

                // no existing stem — allocate one.
                let col = if matches!(self.state.stems.get(node_col), Some(None)) {
                    node_col
                } else if let Some(i) = self.state.stems.iter().position(|s| s.is_none()) {
                    i
                } else {
                    self.state.stems.push(None);
                    self.state.stems.len() - 1
                };
                self.state.stems[col] = Some(Stem {
                    source: LogCoordinates(node_col, row),
                    target: edge.target.clone(),
                    indirect,
                    known_immutable: header.is_immutable,
                    rescued: false,
                });
                single_parent_merge = None;
            }

            // 4. column rescue (sapling-style): single-parent commits whose
            //    parent ended up as an existing stem to the right get their
            //    stem swapped back to the commit's column. The ToIntersection
            //    we just emitted would land in an empty column after the swap,
            //    so replace it with a FromNode that sweeps the old stem's
            //    source into the commit's column — but aiming one row PAST
            //    the commit so the curve ends below its circle. That way it
            //    merges into the stem's continuing column rather than
            //    appearing to terminate at the commit itself (which would
            //    read as a spurious parent-child edge to the rescuing
            //    sibling). Fire whenever the stem was created in any prior
            //    row so the rescue can cascade one lane at a time across
            //    consecutive sibling merges — matching jj's layout where a
            //    common-parent stem migrates leftward through each sibling
            //    instead of jumping across several lanes in one step.
            if parent_count == 1
                && let Some((slot, line_idx)) = single_parent_merge
                && slot > node_col
                && let Some(stem_ref) = self.state.stems[slot].as_ref()
                && stem_ref.source.1 < row
            {
                let mut stem = self.state.stems[slot].take().expect("rescued stem");

                // Redirect the current row's ToIntersection (emitted just
                // above) onto the stem's new column so the rescuing commit
                // keeps its own parent-child arc, pointing at the stem's
                // continued run below its circle.
                if let LogLine::ToIntersection { target, .. } = &mut lines[line_idx] {
                    target.0 = node_col;
                }

                // Emit the compaction sweep as a separate FromNode. The
                // logical source stays at the allocator's position (where
                // the stem was born, often kylr's row 0 col 0) so the line
                // visually grows out of the allocator's circle. The via
                // column is the stem's current slot — the renderer bends
                // right out of the allocator, runs the long vertical at
                // `via` (an empty lane), then bends left to reach the
                // rescuing commit. Running the vertical at the allocator's
                // column instead would slice through every circle on the
                // trunk; skipping the allocator's position would leave the
                // top of the line floating in empty space.
                lines.push(LogLine::FromNode {
                    indirect: stem.indirect,
                    source: stem.source,
                    target: LogCoordinates(node_col, row + 1),
                    via: Some(slot),
                });
                stem.source = LogCoordinates(node_col, row);
                stem.rescued = true;
                self.state.stems[node_col] = Some(stem);

                // earlier siblings that merged into this stem emitted
                // ToIntersection arcs targeting (slot, sibling_row + 1).
                // Leave those arcs alone: the rescue FromNode's source-
                // column vertical runs from stem.source.1 down to the
                // rescue row's boundary, so each sibling's endpoint at
                // (slot, sibling_row + 1) lies on that vertical and joins
                // the rescued stem cleanly. Redirecting them to node_col
                // would run them across the rescuing commit's own circle.
            }

            // 5. trim trailing empty stems and handle any "missing" edges that
            //    need a terminator in the next row (Sapling marks these with ~).
            while matches!(self.state.stems.last(), Some(None)) {
                self.state.stems.pop();
            }

            let location = LogCoordinates(node_col, row);
            let padding = self.state.stems.len().saturating_sub(node_col + 1);

            let hidden_forks = self
                .hidden_forks
                .get(&commit_id)
                .cloned()
                .unwrap_or_default();

            rows.push(LogRow {
                revision: header,
                location,
                padding,
                lines,
                hidden_forks,
            });
            row += 1;

            // missing-parent terminators: any new stem targeting a commit that
            // isn't actually in the revset (jj tags it Missing non-root) gets
            // a ~ marker drawn at the next row via ToMissing.
            let mut next_missing: Option<(usize, bool)> = None;
            for edge in commit_edges.iter() {
                if edge.edge_type == GraphEdgeType::Missing
                    && edge.target != root_id
                    && let Some(slot) = self
                        .state
                        .stems
                        .iter()
                        .position(|s| s.as_ref().map(|st| &st.target) == Some(&edge.target))
                {
                    let indirect = self.state.stems[slot].as_ref().is_none_or(|s| s.indirect);
                    next_missing = Some((slot, indirect));
                    break;
                }
            }
            if let Some((slot, indirect)) = next_missing {
                rows.last_mut().unwrap().lines.push(LogLine::ToMissing {
                    indirect,
                    source: LogCoordinates(node_col, row - 1),
                    target: LogCoordinates(slot, row),
                });
                self.state.stems[slot] = None;
                row += 1;
            }

            if row == max {
                break;
            }
        }

        self.state.next_row = row;
        Ok(LogPage {
            rows,
            has_more: self.iter.peek().is_some(),
        })
    }
}

fn find_column(stems: &[Option<Stem>], commit_id: &CommitId) -> usize {
    if let Some(i) = stems
        .iter()
        .position(|s| s.as_ref().map(|st| &st.target) == Some(commit_id))
    {
        return i;
    }
    if let Some(i) = stems.iter().position(|s| s.is_none()) {
        return i;
    }
    stems.len()
}

#[cfg(test)]
pub fn query_log(ws: &WorkspaceSession, revset_str: &str, max_results: usize) -> Result<LogPage> {
    let state = QueryState::new(max_results);
    let revset = ws.evaluate_revset_str(revset_str)?;
    let hidden_forks = ws.compute_hidden_forks(revset_str)?;
    let mut session = QuerySession::new(ws, &*revset, state, hidden_forks);
    session.get_page()
}

#[cfg(test)]
pub fn query_revision(ws: &WorkspaceSession<'_>, id: &RevId) -> Result<Option<RevHeader>> {
    ws.resolve_optional_id(id)?
        .map(|c| ws.format_header(&c, None))
        .transpose()
}

/// Read display details for a revset (limited to sequences). Returns headers in topological order (children first).
pub async fn query_revisions(ws: &WorkspaceSession<'_>, set: RevSet) -> Result<RevsResult> {
    // resolve singleton or arbitrary revset
    let commits = if set.from.change.hex == set.to.change.hex {
        match ws.resolve_optional_id(&set.from)? {
            Some(commit) => vec![commit],
            None => return Ok(RevsResult::NotFound { set }),
        }
    } else {
        match ws.resolve_optional_set(&set)? {
            Some(commits) => commits,
            None => return Ok(RevsResult::NotFound { set }),
        }
    };

    // trees before and after the revset
    let oldest_commit = commits.last().ok_or(anyhow!("slice is_empty()"))?;
    let oldest_parents = oldest_commit.parents().await?;
    let parent_tree = rewrite::merge_commit_trees(ws.repo(), &oldest_parents).await?;

    let newest_commit = commits.first().ok_or(anyhow!("slice is_empty()"))?;
    let final_tree = newest_commit.tree();

    // compute combined changes: diff from parents to final
    let mut changes = Vec::new();
    let tree_diff = parent_tree.diff_stream(&final_tree, &EverythingMatcher);
    let conflict_labels = Diff::new(parent_tree.labels(), final_tree.labels());
    format_tree_changes(ws, &mut changes, tree_diff, conflict_labels).await?;

    // find inherited conflicts: files conflicted in final_tree but unchanged relative to parent.
    // conflicted changes with empty hunks (tree value differs but materialized content is identical)
    // are also handled here, since they have nothing useful to display as a diff.
    changes.retain(|c| !c.has_conflict || !c.hunks.is_empty());
    let changed_paths: HashSet<&str> = changes.iter().map(|c| c.path.repo_path.as_str()).collect();
    let mut conflicts = Vec::new();
    for (path, entry) in final_tree.entries() {
        if let Ok(entry) = entry
            && !entry.is_resolved()
        {
            let formatted_path = ws.format_path(path.clone())?;
            if changed_paths.contains(formatted_path.repo_path.as_str()) {
                continue;
            }

            match conflicts::materialize_tree_value(
                ws.repo().store(),
                &path,
                entry,
                final_tree.labels(),
            )
            .await?
            {
                MaterializedTreeValue::FileConflict(file) => {
                    let merge_result = files::merge_hunks(
                        &file.contents,
                        &MergeOptions {
                            hunk_level: FileMergeHunkLevel::Line,
                            same_change: SameChange::Accept,
                        },
                    );
                    let hunks = format_conflict_hunks(merge_result, &file.labels, 3)?;
                    if !hunks.is_empty() {
                        conflicts.push(RevConflict {
                            path: formatted_path,
                            hunks,
                        });
                    }
                }
                _ => {
                    log::warn!("nonresolved tree entry did not materialise as conflict");
                }
            }
        }
    }

    // details for each revision in the set
    let mut headers = Vec::new();
    let mut known_immutable: Option<bool> = None;
    for commit in &commits {
        // optimization: once we find an immutable revision, its ancestors must be immutable too
        let header = ws.format_header(commit, known_immutable)?;
        if known_immutable.is_none() && header.is_immutable {
            known_immutable = Some(true);
        }
        headers.push(header);
    }

    // optimization: if anything was immutable, the oldest revision's parents must also be immutable
    let parents = oldest_commit
        .parents()
        .await?
        .iter()
        .map(|p| ws.format_header(p, known_immutable))
        .collect::<Result<Vec<_>, _>>()?;

    Ok(RevsResult::Detail {
        set,
        headers,
        parents,
        changes,
        conflicts,
    })
}

pub fn query_remotes(
    ws: &WorkspaceSession,
    tracking_bookmark: Option<String>,
) -> Result<Vec<String>> {
    let git_repo = match ws.git_repo() {
        Some(git_repo) => git_repo,
        None => return Err(anyhow!("No git backend")),
    };

    let all_remotes = get_git_remote_names(&git_repo);

    let matching_remotes = match tracking_bookmark {
        Some(bookmark_name) => all_remotes
            .into_iter()
            .filter(|remote_name| {
                let remote_name_ref = RemoteNameBuf::from(remote_name);
                let bookmark_name_ref = RefNameBuf::from(bookmark_name.clone());
                let remote_ref_symbol = RemoteRefSymbol {
                    name: &bookmark_name_ref,
                    remote: &remote_name_ref,
                };
                let remote_ref = ws.view().get_remote_bookmark(remote_ref_symbol);
                !remote_ref.is_absent() && remote_ref.is_tracked()
            })
            .collect(),
        None => all_remotes,
    };

    Ok(matching_remotes)
}

async fn format_tree_changes(
    ws: &WorkspaceSession<'_>,
    changes: &mut Vec<RevChange>,
    mut tree_diff: TreeDiffStream<'_>,
    conflict_labels: Diff<&ConflictLabels>,
) -> Result<()> {
    let store = ws.repo().store();

    while let Some(TreeDiffEntry { path, values }) = tree_diff.next().await {
        let diff = values?;
        let before = &diff.before;
        let after = &diff.after;

        let kind = if before.is_present() && after.is_present() {
            ChangeKind::Modified
        } else if before.is_absent() {
            ChangeKind::Added
        } else {
            ChangeKind::Deleted
        };

        let has_conflict = !after.is_resolved();

        let hunks = if has_conflict {
            let after_value = conflicts::materialize_tree_value(
                store,
                &path,
                after.clone(),
                conflict_labels.after,
            )
            .await?;
            match after_value {
                MaterializedTreeValue::FileConflict(file) => {
                    let merge_result = files::merge_hunks(
                        &file.contents,
                        &MergeOptions {
                            hunk_level: FileMergeHunkLevel::Line,
                            same_change: SameChange::Accept,
                        },
                    );
                    format_conflict_hunks(merge_result, &file.labels, 3)?
                }
                other => {
                    let before_value = conflicts::materialize_tree_value(
                        store,
                        &path,
                        before.clone(),
                        conflict_labels.before,
                    )
                    .await?;
                    get_value_hunks(3, &path, before_value, other).await?
                }
            }
        } else {
            let before_future = conflicts::materialize_tree_value(
                store,
                &path,
                before.clone(),
                conflict_labels.before,
            );
            let after_future = conflicts::materialize_tree_value(
                store,
                &path,
                after.clone(),
                conflict_labels.after,
            );
            let (before_value, after_value) = try_join!(before_future, after_future)?;
            get_value_hunks(3, &path, before_value, after_value).await?
        };

        changes.push(RevChange {
            path: ws.format_path(path)?,
            kind,
            has_conflict,
            hunks,
        });
    }
    Ok(())
}

async fn get_value_hunks(
    num_context_lines: usize,
    path: &RepoPath,
    left_value: MaterializedTreeValue,
    right_value: MaterializedTreeValue,
) -> Result<Vec<ChangeHunk>> {
    if left_value.is_absent() {
        let right_part = get_value_contents(path, right_value).await?;
        get_unified_hunks(num_context_lines, &[], &right_part)
    } else if right_value.is_present() {
        let left_part = get_value_contents(path, left_value).await?;
        let right_part = get_value_contents(path, right_value).await?;
        get_unified_hunks(num_context_lines, &left_part, &right_part)
    } else {
        let left_part = get_value_contents(path, left_value).await?;
        get_unified_hunks(num_context_lines, &left_part, &[])
    }
}

async fn get_value_contents(path: &RepoPath, value: MaterializedTreeValue) -> Result<Vec<u8>> {
    use tokio::io::AsyncReadExt;

    match value {
        MaterializedTreeValue::Absent => Err(anyhow!(
            "Absent path {path:?} in diff should have been handled by caller"
        )),
        MaterializedTreeValue::File(MaterializedFileValue { mut reader, .. }) => {
            let mut contents = vec![];
            reader.read_to_end(&mut contents).await?;

            let start = &contents[..8000.min(contents.len())]; // same heuristic git uses
            let is_binary = start.contains(&b'\0');
            if is_binary {
                contents.clear();
                contents.push_str("(binary)");
            }
            Ok(contents)
        }
        MaterializedTreeValue::Symlink { target, .. } => Ok(target.into_bytes()),
        MaterializedTreeValue::GitSubmodule(_) => Ok("(submodule)".to_owned().into_bytes()),
        MaterializedTreeValue::FileConflict(file) => {
            let mut hunk_content = vec![];
            conflicts::materialize_merge_result(
                &file.contents,
                &file.labels,
                &mut hunk_content,
                &ConflictMaterializeOptions {
                    marker_style: ConflictMarkerStyle::Git,
                    marker_len: None,
                    merge: MergeOptions {
                        hunk_level: FileMergeHunkLevel::Line,
                        same_change: SameChange::Accept,
                    },
                },
            )?;
            Ok(hunk_content)
        }
        MaterializedTreeValue::OtherConflict { id, labels } => {
            Ok(id.describe(&labels).into_bytes())
        }
        MaterializedTreeValue::Tree(_) => Err(anyhow!("Unexpected tree in diff at path {path:?}")),
        MaterializedTreeValue::AccessDenied(error) => Err(anyhow!(error)),
    }
}

/// render a conflict as a sequence of diff hunks: base content as deletions,
/// sides as additions, resolved content as context. resolved regions are
/// trimmed to `num_context_lines` around each conflict; if too much resolved
/// content sits between two conflicts, the hunk is split so each carries its
/// own `@@ -a,b +c,d @@` header.
fn format_conflict_hunks(
    merge_result: MergeResult,
    labels: &ConflictLabels,
    num_context_lines: usize,
) -> Result<Vec<ChangeHunk>> {
    let hunks = match merge_result {
        MergeResult::Resolved(content) => {
            let mut lines = Vec::new();
            for line in String::from_utf8_lossy(&content).lines() {
                lines.push(format!(" {line}\n"));
            }
            let len = lines.len();
            if len == 0 {
                return Ok(Vec::new());
            }
            return Ok(vec![ChangeHunk {
                location: ChangeLocation {
                    from_file: ChangeRange { start: 1, len },
                    to_file: ChangeRange { start: 1, len },
                },
                lines: MultilineString { lines },
            }]);
        }
        MergeResult::Conflict(hunks) => hunks,
    };

    let num_conflicts = hunks.iter().filter(|h| !h.is_resolved()).count();
    let mut out: Vec<ChangeHunk> = Vec::new();
    let mut pending_resolved: Vec<String> = Vec::new();
    let mut from_cursor: usize = 0;
    let mut to_cursor: usize = 0;
    let mut current: Option<PendingHunk> = None;
    let mut conflict_idx = 0;

    for hunk in &hunks {
        if let Some(content) = hunk.resolve_trivial(SameChange::Accept) {
            for line in String::from_utf8_lossy(content).lines() {
                pending_resolved.push(format!(" {line}\n"));
            }
            continue;
        }

        flush_before_conflict(
            &mut out,
            &mut current,
            &mut pending_resolved,
            &mut from_cursor,
            &mut to_cursor,
            num_context_lines,
        );
        let cur = current.as_mut().expect("hunk must exist after flush");
        conflict_idx += 1;

        // when one side is empty (edit-vs-delete), show a diff between
        // base and the non-empty side instead of the raw merge output
        let adds: Vec<_> = hunk.adds().collect();
        let removes: Vec<_> = hunk.removes().collect();
        if removes.len() == 1
            && adds.len() == 2
            && let Some(empty_idx) = adds.iter().position(|s| s.is_empty())
        {
            let other_idx = 1 - empty_idx;
            let deleted_label = labels.get_add(empty_idx).map_or_else(
                || format!("side {}", empty_idx + 1),
                |l| format!("side {} ({l})", empty_idx + 1),
            );
            let kept_label = labels.get_add(other_idx).map_or_else(
                || format!("side {}", other_idx + 1),
                |l| format!("side {} ({l})", other_idx + 1),
            );
            cur.lines.push(format!(
                " <<<<<<< conflict {conflict_idx} of {num_conflicts} \
                 — deleted by {deleted_label}\n"
            ));
            let base_content = removes[0];
            let side_content = adds[other_idx];
            let diff_hunks =
                get_unified_hunks(3, base_content.as_ref(), side_content.as_ref())?;
            if diff_hunks.is_empty() {
                // base and kept side are identical
                cur.lines.push(format!(" +++++++ {kept_label} (unchanged)\n"));
                for line in String::from_utf8_lossy(side_content).lines() {
                    cur.lines.push(format!(" {line}\n"));
                    cur.from_len += 1;
                    cur.to_len += 1;
                    from_cursor += 1;
                    to_cursor += 1;
                }
            } else {
                cur.lines.push(format!(" +++++++ {kept_label}\n"));
                for diff_hunk in &diff_hunks {
                    for line in &diff_hunk.lines.lines {
                        cur.lines.push(line.clone());
                        if line.starts_with('-') {
                            cur.from_len += 1;
                            from_cursor += 1;
                        } else if line.starts_with('+') {
                            cur.to_len += 1;
                            to_cursor += 1;
                        } else {
                            cur.from_len += 1;
                            cur.to_len += 1;
                            from_cursor += 1;
                            to_cursor += 1;
                        }
                    }
                }
            }
            cur.lines.push(format!(
                " >>>>>>> conflict {conflict_idx} of {num_conflicts}\n"
            ));
            continue;
        }

        cur.lines.push(format!(
            " <<<<<<< conflict {conflict_idx} of {num_conflicts}\n"
        ));
        for base in hunk.removes() {
            for line in String::from_utf8_lossy(base).lines() {
                cur.lines.push(format!("-{line}\n"));
                cur.from_len += 1;
                from_cursor += 1;
            }
        }
        for (i, side) in hunk.adds().enumerate() {
            let label = labels.get_add(i).map_or_else(
                || format!(" +++++++ side {}\n", i + 1),
                |l| format!(" +++++++ side {} ({l})\n", i + 1),
            );
            cur.lines.push(label);
            for line in String::from_utf8_lossy(side).lines() {
                cur.lines.push(format!("+{line}\n"));
                cur.to_len += 1;
                to_cursor += 1;
            }
        }
        cur.lines.push(format!(
            " >>>>>>> conflict {conflict_idx} of {num_conflicts}\n"
        ));
    }

    // trailing resolved context: keep at most `num_context_lines`, discard the rest
    if let Some(ref mut cur) = current {
        let keep = num_context_lines.min(pending_resolved.len());
        for line in pending_resolved.drain(..keep) {
            cur.lines.push(line);
            cur.from_len += 1;
            cur.to_len += 1;
        }
    }
    if let Some(cur) = current {
        out.push(cur.into_change_hunk());
    }

    Ok(out)
}

struct PendingHunk {
    from_start: usize, // 0-based absolute position at hunk start
    to_start: usize,
    lines: Vec<String>,
    from_len: usize,
    to_len: usize,
}

impl PendingHunk {
    fn new(from_start: usize, to_start: usize) -> Self {
        Self {
            from_start,
            to_start,
            lines: Vec::new(),
            from_len: 0,
            to_len: 0,
        }
    }

    fn into_change_hunk(self) -> ChangeHunk {
        ChangeHunk {
            location: ChangeLocation {
                from_file: ChangeRange {
                    start: self.from_start + 1,
                    len: self.from_len,
                },
                to_file: ChangeRange {
                    start: self.to_start + 1,
                    len: self.to_len,
                },
            },
            lines: MultilineString { lines: self.lines },
        }
    }
}

/// before emitting a conflict, flush buffered resolved-trivial lines.
/// keeps up to `num_context_lines` adjacent to each conflict; if the gap
/// between two conflicts exceeds `2 * num_context_lines`, splits the current
/// hunk and starts a new one so each has its own header.
fn flush_before_conflict(
    out: &mut Vec<ChangeHunk>,
    current: &mut Option<PendingHunk>,
    pending: &mut Vec<String>,
    from_cursor: &mut usize,
    to_cursor: &mut usize,
    num_context_lines: usize,
) {
    let n = pending.len();

    if let Some(cur) = current {
        if n <= 2 * num_context_lines {
            for line in pending.drain(..) {
                cur.lines.push(line);
                cur.from_len += 1;
                cur.to_len += 1;
                *from_cursor += 1;
                *to_cursor += 1;
            }
            return;
        }

        for line in pending.drain(..num_context_lines) {
            cur.lines.push(line);
            cur.from_len += 1;
            cur.to_len += 1;
            *from_cursor += 1;
            *to_cursor += 1;
        }
        let skip = n - 2 * num_context_lines;
        pending.drain(..skip);
        *from_cursor += skip;
        *to_cursor += skip;
        out.push(current.take().unwrap().into_change_hunk());
    } else if n > num_context_lines {
        let skip = n - num_context_lines;
        pending.drain(..skip);
        *from_cursor += skip;
        *to_cursor += skip;
    }

    let mut new_hunk = PendingHunk::new(*from_cursor, *to_cursor);
    for line in pending.drain(..) {
        new_hunk.lines.push(line);
        new_hunk.from_len += 1;
        new_hunk.to_len += 1;
        *from_cursor += 1;
        *to_cursor += 1;
    }
    *current = Some(new_hunk);
}

fn get_unified_hunks(
    num_context_lines: usize,
    left_content: &[u8],
    right_content: &[u8],
) -> Result<Vec<ChangeHunk>> {
    let mut hunks = Vec::new();

    for hunk in unified_diff_hunks(
        left_content,
        right_content,
        &UnifiedDiffOptions {
            context: num_context_lines,
            line_diff: LineDiffOptions {
                compare_mode: LineCompareMode::Exact,
            },
        },
    ) {
        let location = ChangeLocation {
            from_file: ChangeRange {
                start: hunk.left_line_range.start,
                len: hunk.left_line_range.len(),
            },
            to_file: ChangeRange {
                start: hunk.right_line_range.start,
                len: hunk.right_line_range.len(),
            },
        };

        let mut lines = Vec::new();
        for (line_type, tokens) in hunk.lines {
            let mut formatter: Vec<u8> = vec![];
            match line_type {
                DiffLineType::Context => {
                    write!(formatter, " ")?;
                }
                DiffLineType::Removed => {
                    write!(formatter, "-")?;
                }
                DiffLineType::Added => {
                    write!(formatter, "+")?;
                }
            }

            for (token_type, content) in tokens {
                match token_type {
                    DiffTokenType::Matching => formatter.write_all(content)?,
                    DiffTokenType::Different => formatter.write_all(content)?, // XXX mark this for GUI display
                }
            }

            lines.push(String::from_utf8_lossy(&formatter).into_owned());
        }

        hunks.push(ChangeHunk {
            location,
            lines: MultilineString { lines },
        });
    }

    Ok(hunks)
}

/**************************/
/* from jj_cli::diff_util */
/**************************/

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UnifiedDiffOptions {
    /// Number of context lines to show.
    pub context: usize,
    /// How lines are tokenized and compared.
    pub line_diff: LineDiffOptions,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum DiffLineType {
    Context,
    Removed,
    Added,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum DiffTokenType {
    Matching,
    Different,
}

type DiffTokenVec<'content> = Vec<(DiffTokenType, &'content [u8])>;

struct UnifiedDiffHunk<'content> {
    left_line_range: Range<usize>,
    right_line_range: Range<usize>,
    lines: Vec<(DiffLineType, DiffTokenVec<'content>)>,
}

impl<'content> UnifiedDiffHunk<'content> {
    fn extend_context_lines(&mut self, lines: impl IntoIterator<Item = &'content [u8]>) {
        let old_len = self.lines.len();
        self.lines.extend(lines.into_iter().map(|line| {
            let tokens = vec![(DiffTokenType::Matching, line)];
            (DiffLineType::Context, tokens)
        }));
        self.left_line_range.end += self.lines.len() - old_len;
        self.right_line_range.end += self.lines.len() - old_len;
    }

    fn extend_removed_lines(&mut self, lines: impl IntoIterator<Item = DiffTokenVec<'content>>) {
        let old_len = self.lines.len();
        self.lines
            .extend(lines.into_iter().map(|line| (DiffLineType::Removed, line)));
        self.left_line_range.end += self.lines.len() - old_len;
    }

    fn extend_added_lines(&mut self, lines: impl IntoIterator<Item = DiffTokenVec<'content>>) {
        let old_len = self.lines.len();
        self.lines
            .extend(lines.into_iter().map(|line| (DiffLineType::Added, line)));
        self.right_line_range.end += self.lines.len() - old_len;
    }
}

fn unified_diff_hunks<'content>(
    left_content: &'content [u8],
    right_content: &'content [u8],
    options: &UnifiedDiffOptions,
) -> Vec<UnifiedDiffHunk<'content>> {
    let mut hunks = vec![];
    let mut current_hunk = UnifiedDiffHunk {
        left_line_range: 1..1,
        right_line_range: 1..1,
        lines: vec![],
    };
    let diff = diff_by_line([left_content, right_content], &options.line_diff);
    let mut diff_hunks = diff.hunks().peekable();
    while let Some(hunk) = diff_hunks.next() {
        match hunk.kind {
            DiffHunkKind::Matching => {
                // Just use the right (i.e. new) content. We could count the
                // number of skipped lines separately, but the number of the
                // context lines should match the displayed content.
                let [_, right] = hunk.contents[..].try_into().unwrap();
                let mut lines = right.split_inclusive(|b| *b == b'\n').fuse();
                if !current_hunk.lines.is_empty() {
                    // The previous hunk line should be either removed/added.
                    current_hunk.extend_context_lines(lines.by_ref().take(options.context));
                }
                let before_lines = if diff_hunks.peek().is_some() {
                    lines.by_ref().rev().take(options.context).collect()
                } else {
                    vec![] // No more hunks
                };
                let num_skip_lines = lines.count();
                if num_skip_lines > 0 {
                    let left_start = current_hunk.left_line_range.end + num_skip_lines;
                    let right_start = current_hunk.right_line_range.end + num_skip_lines;
                    if !current_hunk.lines.is_empty() {
                        hunks.push(current_hunk);
                    }
                    current_hunk = UnifiedDiffHunk {
                        left_line_range: left_start..left_start,
                        right_line_range: right_start..right_start,
                        lines: vec![],
                    };
                }
                // The next hunk should be of DiffHunk::Different type if any.
                current_hunk.extend_context_lines(before_lines.into_iter().rev());
            }
            DiffHunkKind::Different => {
                let (left_lines, right_lines) =
                    unzip_diff_hunks_to_lines(ContentDiff::by_word(hunk.contents).hunks());
                current_hunk.extend_removed_lines(left_lines);
                current_hunk.extend_added_lines(right_lines);
            }
        }
    }
    if !current_hunk.lines.is_empty() {
        hunks.push(current_hunk);
    }
    hunks
}

/// Splits `(left, right)` hunk pairs into `(left_lines, right_lines)`.
#[allow(dead_code)]
fn unzip_diff_hunks_to_lines<'content, I>(
    diff_hunks: I,
) -> (Vec<DiffTokenVec<'content>>, Vec<DiffTokenVec<'content>>)
where
    I: IntoIterator,
    I::Item: Borrow<DiffHunk<'content>>,
{
    let mut left_lines: Vec<DiffTokenVec<'content>> = vec![];
    let mut right_lines: Vec<DiffTokenVec<'content>> = vec![];
    let mut left_tokens: DiffTokenVec<'content> = vec![];
    let mut right_tokens: DiffTokenVec<'content> = vec![];

    for hunk in diff_hunks {
        let hunk = hunk.borrow();
        match hunk.kind {
            DiffHunkKind::Matching => {
                // TODO: add support for unmatched contexts
                debug_assert!(hunk.contents.iter().all_equal());
                for token in hunk.contents[0].split_inclusive(|b| *b == b'\n') {
                    left_tokens.push((DiffTokenType::Matching, token));
                    right_tokens.push((DiffTokenType::Matching, token));
                    if token.ends_with(b"\n") {
                        left_lines.push(mem::take(&mut left_tokens));
                        right_lines.push(mem::take(&mut right_tokens));
                    }
                }
            }
            DiffHunkKind::Different => {
                let [left, right] = hunk.contents[..]
                    .try_into()
                    .expect("hunk should have exactly two inputs");
                for token in left.split_inclusive(|b| *b == b'\n') {
                    left_tokens.push((DiffTokenType::Different, token));
                    if token.ends_with(b"\n") {
                        left_lines.push(mem::take(&mut left_tokens));
                    }
                }
                for token in right.split_inclusive(|b| *b == b'\n') {
                    right_tokens.push((DiffTokenType::Different, token));
                    if token.ends_with(b"\n") {
                        right_lines.push(mem::take(&mut right_tokens));
                    }
                }
            }
        }
    }

    if !left_tokens.is_empty() {
        left_lines.push(left_tokens);
    }
    if !right_tokens.is_empty() {
        right_lines.push(right_tokens);
    }
    (left_lines, right_lines)
}

fn diff_by_line<'input, T: AsRef<[u8]> + ?Sized + 'input>(
    inputs: impl IntoIterator<Item = &'input T>,
    options: &LineDiffOptions,
) -> jj_lib::diff::ContentDiff<'input> {
    // TODO: If we add --ignore-blank-lines, its tokenizer will have to attach
    // blank lines to the preceding range. Maybe it can also be implemented as a
    // post-process (similar to refine_changed_regions()) that expands unchanged
    // regions across blank lines.
    use jj_lib::diff::ContentDiff;
    match options.compare_mode {
        LineCompareMode::Exact => {
            ContentDiff::for_tokenizer(inputs, find_line_ranges, CompareBytesExactly)
        }
        LineCompareMode::IgnoreAllSpace => {
            ContentDiff::for_tokenizer(inputs, find_line_ranges, CompareBytesIgnoreAllWhitespace)
        }
        LineCompareMode::IgnoreSpaceChange => {
            ContentDiff::for_tokenizer(inputs, find_line_ranges, CompareBytesIgnoreWhitespaceAmount)
        }
    }
}

fn commit_id_identity(commit_id: &CommitId) -> &CommitId {
    commit_id
}
