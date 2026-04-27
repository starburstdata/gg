<script lang="ts">
    import type { RevsResult } from "./messages/RevsResult";
    import { ignoreToggled, changeSelectEvent, dragOverWidget } from "./stores";
    import ChangeObject from "./objects/ChangeObject.svelte";
    import HunkObject from "./objects/HunkObject.svelte";
    import RevisionObject from "./objects/RevisionObject.svelte";
    import RevisionMutator from "./mutators/RevisionMutator";
    import ActionWidget from "./controls/ActionWidget.svelte";
    import Icon from "./controls/Icon.svelte";
    import IdSpan from "./controls/IdSpan.svelte";
    import Pane from "./shell/Pane.svelte";
    import Zone from "./objects/Zone.svelte";
    import { onEvent } from "./ipc";
    import AuthorSpan from "./controls/AuthorSpan.svelte";

    import SetSpan from "./controls/SetSpan.svelte";
    import type { RevChange } from "./messages/RevChange";
    import TimestampSpan from "./controls/TimestampSpan.svelte";
    import TimestampRangeSpan from "./controls/TimestampRangeSpan.svelte";

    export let revs: Extract<RevsResult, { type: "Detail" }>;

    let expandedFiles = new Set<string>();

    let changeIds = new Map<string, string>();
    $: {
        changeIds.clear();
        for (let change of syntheticChanges) {
            changeIds.set(`Change-${change.path.repo_path}`, change.path.repo_path);
        }
    }

    function onChangesClick(event: MouseEvent) {
        // walk up from the click target to find a ChangeObject button by its id
        let el = event.target as HTMLElement | null;
        while (el && el !== event.currentTarget) {
            let path = el.id ? changeIds.get(el.id) : undefined;
            if (path) {
                if (expandedFiles.has(path)) {
                    expandedFiles.delete(path);
                } else {
                    expandedFiles.add(path);
                }
                expandedFiles = expandedFiles;
                return;
            }
            el = el.parentElement;
        }
    }


    // headers are in descendant-first order
    $: singleton = revs.set.from.commit.hex == revs.set.to.commit.hex;
    $: newest = revs.headers[0];
    $: oldest = revs.headers[revs.headers.length - 1];
    $: newestImmutable = newest.is_immutable && !$ignoreToggled;
    $: oldestImmutable = oldest.is_immutable && !$ignoreToggled;

    $: mutator = new RevisionMutator(revs.headers, $ignoreToggled);

    // debounce for change detection
    let lastSelectionKey = `${revs.set.from.commit.hex}::${revs.set.to.commit.hex}`;
    $: selectionKey = `${revs.set.from.commit.hex}::${revs.set.to.commit.hex}`;

    // editable description for single-revision mode
    let originalDescription = revs.headers[revs.headers.length - 1].description.lines.join("\n");
    $: editableDescription = revs.headers[revs.headers.length - 1].description.lines.join("\n");
    $: {
        if (selectionKey !== lastSelectionKey) {
            lastSelectionKey = selectionKey;
            originalDescription = editableDescription;
        }
    }
    $: descriptionChanged = originalDescription !== editableDescription;
    let resetAuthor = false;
    function updateDescription() {
        mutator.onDescribe(editableDescription, resetAuthor);
    }

    // grouped authors for range mode
    $: firstTimestamp = new Date(
        Math.min(...revs.headers.map((h) => new Date(h.author.timestamp).getTime())),
    ).toISOString();
    $: lastTimestamp = new Date(
        Math.max(...revs.headers.map((h) => new Date(h.author.timestamp).getTime())),
    ).toISOString();
    $: authors = [...new Map(revs.headers.map((h) => [h.author.email, h.author])).values()];

    let syntheticChanges = revs.changes
        .concat(
            revs.conflicts.map((conflict) => ({
                kind: "None",
                path: conflict.path,
                has_conflict: true,
                hunks: conflict.hunks,
            })),
        )
        .sort((a, b) => a.path.relative_path.localeCompare(b.path.relative_path));

    let unset = true;
    let selectedChange = $changeSelectEvent;
    for (let change of syntheticChanges) {
        if (selectedChange?.path?.repo_path === change.path.repo_path) {
            unset = false;
        }
    }
    if (unset) {
        changeSelectEvent.set(syntheticChanges[0]);
    }

    onEvent<string>("gg://menu/revision", (event) => mutator.handle(event));

    function lineColour(line: string): string | null {
        if (line.startsWith("+")) {
            return "add";
        } else if (line.startsWith("-")) {
            return "remove";
        } else {
            return null;
        }
    }

    interface DiffSegment {
        conflict: boolean;
        lines: string[];
    }

    function segmentHunk(hunkLines: string[]): DiffSegment[] {
        let segments: DiffSegment[] = [];
        let current: DiffSegment = { conflict: false, lines: [] };

        for (let line of hunkLines) {
            if (line.startsWith(" <<<<<<< ")) {
                if (current.lines.length > 0) segments.push(current);
                current = { conflict: true, lines: [line] };
            } else if (line.startsWith(" >>>>>>> ")) {
                current.lines.push(line);
                segments.push(current);
                current = { conflict: false, lines: [] };
            } else {
                current.lines.push(line);
            }
        }
        if (current.lines.length > 0) segments.push(current);
        return segments;
    }

    function isConflictMarker(line: string): boolean {
        return (
            line.startsWith(" <<<<<<< ") ||
            line.startsWith(" >>>>>>> ") ||
            line.startsWith(" +++++++ ")
        );
    }

    let descriptionHeight = 150;
    let bodyEl: HTMLDivElement;
    let commandsEl: HTMLDivElement;
    let separatorEl: HTMLDivElement;

    function onSeparatorPointerDown(event: PointerEvent) {
        if (event.button !== 0) return;
        event.preventDefault();
        separatorEl.setPointerCapture(event.pointerId);
    }

    function onSeparatorPointerMove(event: PointerEvent) {
        if (!separatorEl.hasPointerCapture(event.pointerId)) return;
        let rect = bodyEl.getBoundingClientRect();
        let targetY = event.clientY - rect.top + bodyEl.scrollTop;
        let commandsHeight = commandsEl?.offsetHeight ?? 0;
        descriptionHeight = Math.max(60, targetY - commandsHeight);
    }

    function onSeparatorPointerUp(event: PointerEvent) {
        separatorEl.releasePointerCapture(event.pointerId);
    }
</script>

<Pane>
    <div slot="header" class="metadata">
        {#if singleton}
            <span class="meta-item">
                <span class="meta-label">Change</span> <IdSpan selectable id={newest.id.change} />
            </span>
            <span class="meta-sep">&middot;</span>
            <span class="meta-item">
                <span class="meta-label">Commit</span> <IdSpan selectable id={newest.id.commit} />
            </span>
            <span class="meta-sep">&middot;</span>
            <span class="meta-item meta-inline">
                <AuthorSpan author={newest.author} />
            </span>
            <span class="meta-sep">&middot;</span>
            <span class="meta-item"><TimestampSpan timestamp={newest.author.timestamp} /></span>
            {#if newest.is_working_copy}
                <span class="meta-sep">&middot;</span>
                <span class="meta-item meta-flag">Working copy</span>
            {/if}
            {#if newest.is_immutable}
                <span class="meta-sep">&middot;</span>
                <span class="meta-item meta-flag">Immutable</span>
            {/if}
        {:else}
            <span class="meta-item">
                <SetSpan selectable set={revs.set} /> &middot; {revs.headers.length} revisions
            </span>
            <span class="meta-sep">&middot;</span>
            <span class="meta-item">
                {#each authors as author, ix}
                    <!-- prettier-ignore -->
                    <AuthorSpan {author} />{#if ix < authors.length - 1},&nbsp;{/if}
                {/each}
            </span>
            <span class="meta-sep">&middot;</span>
            <span class="meta-item"><TimestampRangeSpan from={firstTimestamp} to={lastTimestamp} /></span>
        {/if}
    </div>
    <div slot="body" class="body" bind:this={bodyEl}>
        {#if !singleton}
            <!-- prettier-ignore -->
            <div class="description-list" style="height: {descriptionHeight}px">{#each revs.headers as header, i}{#if i > 0}<hr class="description-divider" />{/if}<div class="description-row">{header.description.lines.join("\n")}</div>{/each}</div>
        {:else}
            <textarea
                class="description"
                spellcheck="false"
                disabled={newestImmutable}
                bind:value={editableDescription}
                style="height: {descriptionHeight}px"
                on:dragenter={dragOverWidget}
                on:dragover={dragOverWidget}
                on:keydown={(ev) => {
                    if (descriptionChanged && ev.key === "Enter" && (ev.metaKey || ev.ctrlKey)) {
                        updateDescription();
                    }
                }}></textarea>
        {/if}

        <div class="describe-commands" bind:this={commandsEl}>
            {#if singleton}
                <label class="reset-author-label">
                    <input type="checkbox" bind:checked={resetAuthor} disabled={newestImmutable} />
                    Reset author
                </label>
                <ActionWidget
                    tip="set commit message"
                    onClick={() => mutator.onDescribe(editableDescription, resetAuthor)}
                    disabled={newestImmutable || !descriptionChanged}>
                    Describe
                </ActionWidget>
            {/if}
        </div>

        <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
        <div class="description-separator"
             bind:this={separatorEl}
             role="separator"
             aria-orientation="horizontal"
             on:pointerdown={onSeparatorPointerDown}
             on:pointermove={onSeparatorPointerMove}
             on:pointerup={onSeparatorPointerUp}>
            <div class="hit-area"></div>
        </div>

        {#if revs.parents.length > 0}
            <Zone operand={{ type: "Merge", header: oldest }} let:target>
                <div class="parents" class:target>
                    {#each revs.parents as parent}
                        <div class="parent">
                            <span>Parent:</span>
                            <RevisionObject header={parent} child={oldest} selected={false} noBookmarks />
                        </div>
                    {/each}
                </div>
            </Zone>
        {/if}

        {#if syntheticChanges.length > 0}
            <div class="changes-header">
                <span>Changes ({syntheticChanges.length})</span>
            </div>

            <!-- svelte-ignore a11y-click-events-have-key-events a11y-no-static-element-interactions -->
            <div class="changes" on:click={onChangesClick}>
                {#each syntheticChanges as change}
                    <ChangeObject
                        {change}
                        headers={revs.headers}
                        selected={$changeSelectEvent?.path?.repo_path === change.path.repo_path} />
                    {#if expandedFiles.has(change.path.repo_path)}
                        <div class="change">
                            {#each change.hunks as hunk}
                                <div class="hunk">
                                    <HunkObject
                                        header={!change.has_conflict && singleton ? newest : null}
                                        path={change.path}
                                        {hunk} />
                                </div>
                                <pre class="diff">{#each segmentHunk(hunk.lines.lines) as segment}{#if segment.conflict}<span class="conflict-region">{#each segment.lines as line}{#if isConflictMarker(line)}<span class="conflict-marker">{line}</span>{:else}<span class={lineColour(line)}
                                            >{line}</span
                                        >{/if}{/each}</span>{:else}{#each segment.lines as line}<span class={lineColour(line)}
                                            >{line}</span
                                        >{/each}{/if}{/each}</pre>
                            {/each}
                        </div>
                    {/if}
                {/each}
            </div>
        {:else}
            <div class="changes-header">
                <span>Changes: <span class="no-changes">(empty)</span></span>
            </div>
        {/if}
    </div>
</Pane>

<style>
    .body {
        height: 100%;
        overflow-x: hidden;
        overflow-y: auto;
        pointer-events: auto;
        scrollbar-color: var(--ctp-text) var(--ctp-crust);
        display: flex;
        flex-direction: column;
        margin: 0 -6px -3px -6px;
        padding: 0 6px 3px 6px;
        gap: 0;
    }

    .metadata {
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        gap: 0 6px;
        font-size: 13px;
        font-family: var(--gg-text-familyUi);
        line-height: 1.8;
        background: var(--gg-colors-background);
        margin: 0;
        padding: 3px;
    }

    .meta-item {
        pointer-events: auto;
        user-select: text;
        white-space: nowrap;
    }

    .meta-inline {
        display: inline-flex;
        align-items: center;
        gap: 4px;
    }

    .meta-label {
        color: var(--gg-colors-foregroundMuted);
    }

    .meta-sep {
        color: var(--gg-colors-outlineStrong);
    }

    .meta-flag {
        color: var(--gg-colors-foregroundMuted);
        font-style: italic;
    }

    .body::-webkit-scrollbar {
        width: 6px;
    }

    .body::-webkit-scrollbar-thumb {
        background-color: var(--ctp-text);
        border-radius: 6px;
    }

    .body::-webkit-scrollbar-track {
        background-color: var(--ctp-crust);
    }

    .description {
        resize: none;
        min-height: 60px;
        flex-shrink: 0;
        overflow: auto;
        font-size: var(--gg-text-sizeMd);
    }

    .description-separator {
        height: 3px;
        background: var(--gg-colors-surfaceAlt);
        cursor: row-resize;
        position: relative;
        pointer-events: auto;
        flex-shrink: 0;
    }

    .description-separator:hover,
    .description-separator:active {
        background: var(--gg-colors-surfaceStrong);
    }

    .description-separator .hit-area {
        position: absolute;
        inset: -3px 0;
        z-index: 1;
        cursor: row-resize;
        pointer-events: auto;
    }

    .body:has(.description-separator:active) {
        cursor: row-resize;
        user-select: none;
    }

    .description-list {
        min-height: 60px;
        flex-shrink: 0;
        overflow: auto;
        pointer-events: auto;

        border: 1px solid transparent;
        border-radius: 4px;
        padding: 4px;

        white-space: pre-wrap;
        user-select: text;
        font-size: var(--gg-text-sizeMd);

        color: var(--gg-colors-foregroundMuted);
    }

    .description-row {
        white-space: pre-wrap;
    }

    .description-divider {
        border: none;
        border-top: 1px dashed var(--gg-colors-outline);
        margin: 4px 1px;
    }

    .describe-commands {
        display: flex;
        align-items: center;
        justify-content: end;
        gap: 6px;
        padding: 4px 0;
        flex-shrink: 0;
    }

    .reset-author-label {
        display: flex;
        align-items: center;
        gap: 4px;
        font-family: var(--gg-text-familyUi);
        font-size: 13px;
        color: var(--gg-colors-foregroundMuted);
        cursor: pointer;
        user-select: none;
    }

    .reset-author-label input[type="checkbox"]:disabled {
        cursor: default;
    }

    .reset-author-label:has(input:disabled) {
        cursor: default;
        opacity: 0.5;
    }

    .parents {
        border-top: 1px solid var(--gg-colors-outline);
        padding: 0 3px;
        font-size: 0.9em;
    }

    .parent {
        display: grid;
        grid-template-columns: 63px 1fr;
        align-items: baseline;
        gap: 6px;
    }

    .changes-header {
        border-top: 1px solid var(--gg-colors-outline);
        height: 30px;
        min-height: 30px;
        width: 100%;
        padding: 0 3px;
        display: flex;
        align-items: center;
        gap: 6px;
        color: var(--gg-colors-foregroundMuted);
        font-size: 13px;
    }

    .no-changes {
        color: var(--gg-colors-foregroundMuted);
    }

    .changes {
        border-top: 1px solid var(--gg-colors-outline);
        display: flex;
        flex-direction: column;
        pointer-events: auto;
    }

    .change {
        font-size: small;
        margin: 0;
        pointer-events: auto;
    }

    .hunk {
        margin: 0;
        text-align: center;
        background: var(--gg-colors-surface);
    }

    .diff {
        margin: 0;
        background: var(--gg-colors-background);
        font-family: var(--gg-text-familyCode);
        font-size: var(--gg-text-sizeMd);
        user-select: text;
    }

    .add {
        color: var(--gg-colors-success);
    }

    .remove {
        color: var(--gg-colors-error);
    }

    .conflict-region {
        display: block;
        background: repeating-linear-gradient(
            120deg,
            transparent 0px,
            transparent 12px,
            var(--ctp-surface0) 12px,
            var(--ctp-surface0) 15px
        );
    }

    .conflict-marker {
        color: var(--ctp-overlay0);
    }

    .target {
        color: var(--gg-colors-primaryContent);
        background: var(--gg-colors-primary);
    }
</style>
