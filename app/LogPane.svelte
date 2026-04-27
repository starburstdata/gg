<script lang="ts">
    import type { LogPage } from "./messages/LogPage.js";
    import type { LogRow } from "./messages/LogRow.js";
    import type { RevHeader } from "./messages/RevHeader";
    import type { RevSet } from "./messages/RevSet";
    import { getInput, query, trigger } from "./ipc.js";
    import { sameChange } from "./ids.js";
    import { ignoreToggled, repoStatusEvent, revisionSelectEvent } from "./stores.js";
    import RevisionMutator from "./mutators/RevisionMutator.js";
    import Pane from "./shell/Pane.svelte";
    import RevisionObject from "./objects/RevisionObject.svelte";
    import ActionWidget from "./controls/ActionWidget.svelte";
    import Icon from "./controls/Icon.svelte";
    import SelectWidget from "./controls/SelectWidget.svelte";
    import ListWidget, { type List, type Selection } from "./controls/ListWidget.svelte";
    import { type EnhancedRow, default as GraphLog, type EnhancedLine } from "./GraphLog.svelte";

    export let query_choices: Record<string, string>;
    export let latest_query: string;

    function toTitleCase(kebab: string): string {
        return kebab
            .split("-")
            .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
            .join(" ");
    }

    $: presets = (() => {
        let result: { label: string; value: string; separator?: boolean }[] = [];

        // revsets.log first
        if (query_choices["default"] !== undefined) {
            result.push({ label: "Default", value: query_choices["default"] });
        }

        // followed by [gg.presets]
        let others = Object.entries(query_choices)
            .filter(([key]) => key !== "default")
            .map(([key, value]) => ({ label: toTitleCase(key), value }))
            .sort((a, b) => a.label.localeCompare(b.label));

        if (others.length > 0) {
            result.push({ label: "", value: "", separator: true });
            result.push(...others);
        }

        return result;
    })();

    let choices: ReturnType<typeof getChoices>;
    let entered_query = latest_query;
    let graphRows: EnhancedRow[] | undefined;

    let selectionAnchorIdx: number | undefined; // selection model is topologically ordered, selection view requires an anchor point

    let logHeight = 0;
    let logWidth = 0;
    let logScrollTop = 0;

    /**
     * Helper to set selection with proper topological ordering.
     * In the graph, higher indices are older (ancestors), so from should have the higher index.
     * @param anchorIdx - The anchor point (first clicked). Pass undefined to keep existing anchor.
     * @param extendIdx - The extension point (shift-clicked or arrow-extended to).
     */
    function setSelection(anchorIdx: number | undefined, extendIdx: number) {
        if (!graphRows) return;

        if (anchorIdx !== undefined) {
            selectionAnchorIdx = anchorIdx;
        }

        const effectiveAnchor = selectionAnchorIdx ?? extendIdx;
        const fromIdx = Math.max(effectiveAnchor, extendIdx);
        const toIdx = Math.min(effectiveAnchor, extendIdx);

        $revisionSelectEvent = {
            from: graphRows[fromIdx].revision.id,
            to: graphRows[toIdx].revision.id,
        };
    }

    // all these calculations are not efficient. probably doesn't matter
    let list: List = {
        getSize() {
            return graphRows?.length ?? 0;
        },
        getSelection(): Selection {
            if (!graphRows || selectionAnchorIdx === undefined || !$revisionSelectEvent) {
                return { from: -1, to: -1 };
            }

            // translate from toplogical from::to to listwidget's anchor::extension
            const revSetFromIdx = rowIdxByHex.get($revisionSelectEvent!.from.commit.hex) ?? -1;
            const revSetToIdx = rowIdxByHex.get($revisionSelectEvent!.to.commit.hex) ?? -1;

            const extensionIdx = revSetFromIdx === selectionAnchorIdx ? revSetToIdx : revSetFromIdx;

            return { from: selectionAnchorIdx, to: extensionIdx };
        },
        selectRow(row: number) {
            setSelection(row, row);
        },
        extendSelection(row: number) {
            if (!graphRows || selectionAnchorIdx === undefined) return;

            const limitIdx = findLinearLimit(selectionAnchorIdx, row);
            if (limitIdx === row) {
                setSelection(undefined, row); // Keep anchor, extend to new row
            }
        },
        editRow(row: number) {
            if (row != -1) {
                new RevisionMutator([graphRows![row].revision], $ignoreToggled).onEdit();
            }
        },
    };

    $: choices = getChoices(entered_query, presets);

    // dedupe repoStatusEvent — it fires multiple times during the startup handshake
    // with identical content. first change drives the initial fast-path load; later
    // genuine changes drive a full reload so we can relocate the previous selection.
    let lastStatusKey: string | null = null;
    $: if ($repoStatusEvent) {
        let key = JSON.stringify($repoStatusEvent);
        if (key !== lastStatusKey) {
            let isFirst = lastStatusKey === null;
            lastStatusKey = key;
            loadLog(isFirst);
        }
    }

    // index rows by commit hex so selection and per-row lookups stay O(1) as the graph grows
    $: rowIdxByHex = (() => {
        let map = new Map<string, number>();
        if (graphRows) {
            for (let i = 0; i < graphRows.length; i++) {
                map.set(graphRows[i].revision.id.commit.hex, i);
            }
        }
        return map;
    })();

    $: selectionRange = (() => {
        if (!$revisionSelectEvent || !graphRows) return null;
        let a = rowIdxByHex.get($revisionSelectEvent.from.commit.hex);
        let b = rowIdxByHex.get($revisionSelectEvent.to.commit.hex);
        if (a === undefined || b === undefined) return null;
        return { min: Math.min(a, b), max: Math.max(a, b) };
    })();

    function isInSelectedRange(row: EnhancedRow, range: { min: number; max: number } | null): boolean {
        if (!range) return false;
        let idx = rowIdxByHex.get(row.revision.id.commit.hex);
        return idx !== undefined && idx >= range.min && idx <= range.max;
    }

    /**
     * Check if childRow's revision is a direct (non-elided) parent of parentRow's revision.
     * In the graph, lower indices are children (newer), higher indices are parents (older).
     */
    function isDirectParent(childRow: EnhancedRow, parentRow: EnhancedRow): boolean {
        const childCommitHex = childRow.revision.id.commit.hex;
        const parentCommitHex = parentRow.revision.id.commit.hex;

        const isParent = childRow.revision.parent_ids.some((p) => p.hex === parentCommitHex);
        if (!isParent) {
            return false;
        }

        // find a connecting line
        for (const line of childRow.passingLines) {
            if (line.child.id.commit.hex === childCommitHex && line.parent.id.commit.hex === parentCommitHex) {
                return !line.indirect && line.type !== "ToMissing";
            }
        }

        // elided sequences not supported for now - this is possible, but perhaps not useful
        return false;
    }

    /**
     * Find the farthest index from anchorIdx toward targetIdx that maintains linearity.
     */
    function findLinearLimit(anchorIdx: number, targetIdx: number): number {
        if (!graphRows) return anchorIdx;

        const direction = targetIdx > anchorIdx ? 1 : -1;
        let lastValidIdx = anchorIdx;

        for (let i = anchorIdx + direction; direction > 0 ? i <= targetIdx : i >= targetIdx; i += direction) {
            const checkStart = direction > 0 ? lastValidIdx : i;
            const checkEnd = direction > 0 ? i : lastValidIdx;
            if (isDirectParent(graphRows[checkStart], graphRows[checkEnd])) {
                lastValidIdx = i;
            } else {
                break;
            }
        }

        return lastValidIdx;
    }

    function handleClick(header: RevHeader) {
        if (!graphRows) return;

        const clickedIdx = rowIdxByHex.get(header.id.commit.hex) ?? -1;
        if (clickedIdx !== -1) {
            setSelection(clickedIdx, clickedIdx);
        }
    }

    function handleShiftClick(header: RevHeader) {
        if (!graphRows || selectionAnchorIdx === undefined) {
            handleClick(header); // initial selection
            return;
        }

        const clickedIdx = rowIdxByHex.get(header.id.commit.hex) ?? -1;
        if (clickedIdx === -1) {
            handleClick(header); // invalid selection
            return;
        }

        const limitIdx = findLinearLimit(selectionAnchorIdx, clickedIdx);
        setSelection(undefined, limitIdx); // keep anchor, extend to limit
    }

    function getChoices(query: string, presetList: typeof presets) {
        for (let choice of presetList) {
            if (query == choice.value) {
                return presetList;
            }
        }

        return [{ label: "Custom", value: query }, ...presetList];
    }

    $: isCustom = !presets.some((p) => !p.separator && p.value === entered_query);

    $: isDeletable =
        !isCustom && presets.some((p) => !p.separator && p.value === entered_query && p.label !== "Default");

    function toKebabCase(text: string): string {
        return text
            .trim()
            .toLowerCase()
            .replace(/\s+/g, "-")
            .replace(/[^a-z0-9-]/g, "");
    }

    async function handleSavePreset() {
        let response = await getInput("Save Revset", "code:" + entered_query, [
            { label: "Preset Name", choices: [] },
            { label: "Save globally", choices: ["false", "true"] },
        ]);
        if (!response) return;

        let name = toKebabCase(response["Preset Name"]);
        if (!name || name === "default") return;

        let scope = response["Save globally"] === "true" ? "user" : "repo";
        query_choices[name] = entered_query;
        query_choices = query_choices;
        trigger("write_config_entry", { scope, key: ["gg", "presets", name], value: entered_query });
    }

    function handleDeletePreset() {
        let keyToDelete = Object.entries(query_choices).find(
            ([key, value]) => key !== "default" && value === entered_query,
        )?.[0];
        if (!keyToDelete) return;

        delete query_choices[keyToDelete];
        query_choices = query_choices;
        entered_query = query_choices["default"] ?? "";
        // attempt deletion from both scopes; one will be a no-op
        trigger("delete_config_entry", { scope: "repo", key: ["gg", "presets", keyToDelete] });
        trigger("delete_config_entry", { scope: "user", key: ["gg", "presets", keyToDelete] });
        reloadLog();
    }

    // epoch-based cancellation: a new loadLog bumps the epoch, discarding any
    // in-flight page from a previous call. prevents startup races where repeated
    // repoStatusEvents would concurrently mutate graphRows and the shared
    // graph-building state (passNextRow, lineKey) below.
    let loadEpoch = 0;

    async function loadLog(selectFirst: boolean) {
        const epoch = ++loadEpoch;
        // passNextRow can carry stale lines from a cancelled load. lineKey must stay
        // monotonic so reload produces fresh keys — otherwise Svelte's keyed {#each}
        // reuses GraphLine components, whose path is computed in a non-reactive let
        // block and won't update to the new line's geometry.
        passNextRow = [];
        locationIdxMap.clear();
        let page = await query<LogPage>(
            "query_log",
            {
                revset: entered_query == "" ? "all()" : entered_query,
            },
            () => (graphRows = undefined),
        );
        if (epoch !== loadEpoch) return;

        if (page.type == "data") {
            graphRows = [];
            graphRows = addPageToGraph(graphRows, page.value.rows);
            let hasMorePages = page.value.has_more;

            // fast initial paint: select row 0 before loading the rest so the user sees
            // the first page immediately. subsequent pages arrive asynchronously.
            if (selectFirst && page.value.rows.length > 0) {
                setSelection(0, 0);
            }

            // always drain remaining pages: a FromNode line can span page boundaries, and
            // its passingLines on earlier rows are only populated when the target row (on a
            // later page) is processed. stopping at page 1 leaves those lines unrendered.
            while (hasMorePages) {
                let next_page = await query<LogPage>("query_log_next_page", null);
                if (epoch !== loadEpoch) return;
                if (next_page.type == "data") {
                    graphRows = addPageToGraph(graphRows, next_page.value.rows);
                    hasMorePages = next_page.value.has_more;
                } else {
                    hasMorePages = false;
                    break;
                }
            }

            if (!selectFirst) {
                syncSelectionWithGraph();
            }
        }
    }

    // policy: reselect by commit id if the original revisions are still around, update by change id if they aren't
    function syncSelectionWithGraph() {
        const selection = $revisionSelectEvent;
        if (!selection || !graphRows || graphRows.length === 0) {
            return;
        }

        let fromIdx = rowIdxByHex.get(selection.from.commit.hex) ?? -1;
        let toIdx = rowIdxByHex.get(selection.to.commit.hex) ?? -1;

        if (fromIdx === -1) {
            fromIdx = graphRows.findIndex((r) => sameChange(r.revision.id.change, selection.from.change));
        }
        if (toIdx === -1) {
            toIdx = graphRows.findIndex((r) => sameChange(r.revision.id.change, selection.to.change));
        }

        // reposition anchor, update ids if changed
        if (fromIdx !== -1 && toIdx !== -1) {
            selectionAnchorIdx = toIdx;

            const newFrom = graphRows[fromIdx].revision.id;
            const newTo = graphRows[toIdx].revision.id;
            if (newFrom.commit.hex !== selection.from.commit.hex || newTo.commit.hex !== selection.to.commit.hex) {
                $revisionSelectEvent = { from: newFrom, to: newTo };
            }
        } else {
            // selection no longer valid (e.g., revision was abandoned), select first row
            setSelection(0, 0);
        }
    }

    function reloadLog() {
        loadLog(false);
    }

    // augment rows with all lines that pass through them
    let lineKey = 0;
    let passNextRow: EnhancedLine[] = [];
    // map from backend location[1] (which skips slots for missing-parent terminators)
    // to graph array index — avoids an O(N) findIndex per line as the log grows.
    let locationIdxMap = new Map<number, number>();

    function addPageToGraph(graph: EnhancedRow[], page: LogRow[]): EnhancedRow[] {
        for (let row of page) {
            let enhancedRow = row as EnhancedRow;
            for (let passingRow of passNextRow) {
                passingRow.parent = row.revision;
            }
            enhancedRow.passingLines = passNextRow;
            passNextRow = [];

            let rowIdx = graph.length;
            graph.push(enhancedRow);
            locationIdxMap.set(enhancedRow.location[1], rowIdx);

            for (let line of enhancedRow.lines) {
                let enhancedLine = line as EnhancedLine;
                enhancedLine.key = lineKey++;

                if (line.type == "ToIntersection" || line.type == "ToMissing") {
                    // ToIntersection lines begin at their owning row, so they run from this row to the next one that we read (which may not be on the same page)
                    enhancedLine.child = row.revision;
                    enhancedRow.passingLines.push(enhancedLine);
                    passNextRow.push(enhancedLine);
                } else {
                    // other lines end at their owning row, so we need to add them to all previous rows and then this one.
                    enhancedLine.parent = row.revision;
                    let sourceIdx = locationIdxMap.get(line.source[1]);
                    if (sourceIdx === undefined) continue; // defensive: source row unknown
                    enhancedLine.child = graph[sourceIdx].revision;
                    for (let i = sourceIdx; i < rowIdx && graph[i].location[1] < line.target[1]; i++) {
                        graph[i].passingLines.push(enhancedLine);
                    }
                    enhancedRow.passingLines.push(enhancedLine);
                }
            }
        }

        return graph;
    }
</script>

<Pane>
    <div slot="header" class="log-selector">
        <div class="title">Revset</div>
        <div class="selector-row">
            <SelectWidget options={choices} bind:value={entered_query} on:change={reloadLog}>
                <svelte:fragment let:option>{option.label}</svelte:fragment>
            </SelectWidget>
            {#if isCustom}
                <ActionWidget secondary tip="Save revset" onClick={handleSavePreset}>
                    <Icon name="save" /> Save
                </ActionWidget>
            {:else if isDeletable}
                <ActionWidget secondary tip="Delete revset" onClick={handleDeletePreset}>
                    <Icon name="trash-2" state="remove" /> <span class="delete">Delete</span>
                </ActionWidget>
            {/if}
        </div>
        <input type="text" bind:value={entered_query} on:change={reloadLog} />
    </div>

    <ListWidget
        slot="body"
        type="Revision"
        descendant={$revisionSelectEvent?.to.commit.prefix}
        {list}
        bind:clientHeight={logHeight}
        bind:clientWidth={logWidth}
        bind:scrollTop={logScrollTop}>
        {#if graphRows}
            <GraphLog
                containerHeight={logHeight}
                containerWidth={logWidth}
                scrollTop={logScrollTop}
                rows={graphRows}
                let:row>
                {#if row}
                    <div class="log-entry">
                        <RevisionObject
                            header={row.revision}
                            hiddenForks={row.hidden_forks}
                            selected={isInSelectedRange(row, selectionRange)}
                            onClick={handleClick}
                            onShiftClick={handleShiftClick} />
                    </div>
                {/if}
            </GraphLog>
        {:else}
            <div>Loading changes...</div>
        {/if}
    </ListWidget>
</Pane>

<style>
    .log-selector {
        display: flex;
        flex-direction: column;
        gap: 4px;
    }

    .selector-row {
        display: flex;
        gap: 4px;

        & :global(.wrapper) {
            flex: 1;
        }
    }

    .delete {
        color: var(--gg-colors-error);
    }

     /* override some ListWidget styles */
    .title {
        font-family: var(--gg-text-familyUi);
        font-size: 0.8em;
        font-weight: 600;
        text-transform: uppercase;
        color: var(--gg-colors-foregroundSubtle);
        padding: 2px 3px;
    }

    input {
        font-family: var(--gg-text-familyCode);
        font-size: 14px;
        width: 100%;
        box-sizing: border-box;
        margin: 3px 0;
    }

    .log-entry {
        padding-left: 24px;
    }
</style>
