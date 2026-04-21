<script lang="ts">
    import { afterUpdate, onDestroy, onMount } from "svelte";
    import type { RevHeader } from "../messages/RevHeader";
    import type { StoreRef } from "../messages/StoreRef";
    import type { Operand } from "../messages/Operand";
    import { ignoreToggled, currentTarget, revisionSelectEvent } from "../stores.js";
    import Chip from "../controls/Chip.svelte";
    import Icon from "../controls/Icon.svelte";
    import IdSpan from "../controls/IdSpan.svelte";
    import BookmarkObject from "./BookmarkObject.svelte";
    import Object from "./Object.svelte";
    import Zone from "./Zone.svelte";
    import RevisionMutator from "../mutators/RevisionMutator";
    import TagObject from "./TagObject.svelte";
    import AuthorSpan from "../controls/AuthorSpan.svelte";
    import WorkspaceObject from "./WorkspaceObject.svelte";

    export let header: RevHeader;
    export let child: RevHeader | null = null;
    export let selected: boolean; // same as the imported event, but parent may want to force a value
    export let noBookmarks: boolean = false;
    export let hiddenForks: string[] = [];
    export let onClick: ((header: RevHeader) => void) | undefined = undefined;
    export let onShiftClick: ((header: RevHeader) => void) | undefined = undefined;

    let forkMenu: { label: string; x: number; y: number } | null = null;

    function onForkContextMenu(event: MouseEvent, label: string) {
        event.preventDefault();
        event.stopPropagation();
        forkMenu = { label, x: event.clientX, y: event.clientY };
    }

    function copyForkName() {
        if (forkMenu) {
            navigator.clipboard.writeText(forkMenu.label);
            forkMenu = null;
        }
    }

    $: operand = (child ? { type: "Parent", header, child } : { type: "Revision", header }) as Operand;

    // Refs overflow: chips render in visual order forks → bookmarks → tags → workspace.
    // When space is tight, drop priority (first to hide → last): workspace, forks,
    // tags, bookmarks. Each category that loses any chip emits a "…" placeholder.
    const GAP = 3;
    // Minimum inline size we want the description column to keep before we start
    // letting chips eat into it. Below this the title would just be an ellipsis.
    const MIN_DESC_WIDTH = 120;
    // Placeholders all look identical (icon + "…"), so we use a single estimate
    // rather than measuring them — it keeps the widths array aligned with the
    // count of actual chips, regardless of which placeholders the plan emits.
    const PLACEHOLDER_WIDTH = 36;

    type Bookmark = Extract<StoreRef, { type: "LocalBookmark" | "RemoteBookmark" }>;
    type Tag = Extract<StoreRef, { type: "Tag" }>;

    $: bookmarkRefs = header.refs.filter(
        (ref): ref is Bookmark =>
            ref.type !== "Tag" && (ref.type === "LocalBookmark" || !ref.is_synced || !ref.is_tracked),
    );
    $: tagRefs = header.refs.filter((ref): ref is Tag => ref.type === "Tag");
    $: hasWorkspace = !child && header.working_copy_of != null;

    // Key that changes when the chip set (not just display) changes. We re-measure
    // widths only when this changes, so applying the plan doesn't cause a loop.
    $: contentKey = JSON.stringify({
        f: hiddenForks,
        b: bookmarkRefs.map((r) =>
            r.type === "LocalBookmark" ? r.bookmark_name : `${r.bookmark_name}@${r.remote_name}`,
        ),
        t: tagRefs.map((r) => r.tag_name),
        w: hasWorkspace ? header.working_copy_of : null,
    });

    let layoutEl: HTMLElement | undefined;
    let emailEl: HTMLElement | undefined;
    let refsEl: HTMLElement | undefined;
    let availableWidth = Infinity;
    let chipWidths: number[] = [];
    let measuredKey = "";
    let resizeObserver: ResizeObserver | undefined;

    onMount(() => {
        if (layoutEl) {
            recomputeAvailable();
            resizeObserver = new ResizeObserver(() => recomputeAvailable());
            resizeObserver.observe(layoutEl);
        }
        scheduleMeasure();
    });

    onDestroy(() => resizeObserver?.disconnect());

    function recomputeAvailable() {
        if (!layoutEl) return;
        let layoutW = layoutEl.clientWidth;
        // IdSpan renders as the first element in the flex row
        let idEl = layoutEl.firstElementChild as HTMLElement | null;
        let idW = idEl?.getBoundingClientRect().width ?? 0;
        // offsetParent is null when display:none — author column hides below 1680px
        let emailVisible = emailEl != null && emailEl.offsetParent != null;
        let emailW = emailVisible ? emailEl!.getBoundingClientRect().width : 0;
        let gap = window.matchMedia("(min-width: 1680px)").matches ? 9 : 6;
        let gapCount = 2 + (emailVisible ? 1 : 0);
        availableWidth = Math.max(0, layoutW - idW - emailW - MIN_DESC_WIDTH - gapCount * gap);
    }

    afterUpdate(() => {
        if (contentKey === measuredKey) return;
        // Reset to pre-measurement plan so all real chips are visible again before
        // we measure — otherwise previously-hidden chips would give widths of 0.
        if (chipWidths.length !== 0) {
            chipWidths = [];
        }
        scheduleMeasure();
    });

    let measurePending = false;
    function scheduleMeasure() {
        if (measurePending) return;
        measurePending = true;
        requestAnimationFrame(() => {
            measurePending = false;
            if (!refsEl) return;
            recomputeAvailable();
            let els = refsEl.querySelectorAll<HTMLElement>("[data-chip]");
            let next = Array.from(els).map((el) => el.getBoundingClientRect().width);
            // Don't persist zeros — it means layout hasn't finished yet and we'll
            // get another afterUpdate to try again.
            if (next.some((w) => w === 0)) return;
            chipWidths = next;
            measuredKey = contentKey;
        });
    }

    interface Plan {
        forkShown: boolean[];
        bookmarkShown: boolean[];
        tagShown: boolean[];
        showWorkspace: boolean;
        forkPlaceholder: boolean;
        bookmarkPlaceholder: boolean;
        tagPlaceholder: boolean;
    }

    $: hiddenForkTip = hiddenForks.filter((_, i) => plan.forkShown[i] === false).join("\n");
    $: hiddenBookmarkTip = bookmarkRefs
        .filter((_, i) => plan.bookmarkShown[i] === false)
        .map((r) => (r.type === "LocalBookmark" ? r.bookmark_name : `${r.bookmark_name}@${r.remote_name}`))
        .join("\n");
    $: hiddenTagTip = tagRefs
        .filter((_, i) => plan.tagShown[i] === false)
        .map((r) => r.tag_name)
        .join("\n");

    $: plan = computePlan(
        availableWidth,
        hiddenForks.length,
        bookmarkRefs.length,
        tagRefs.length,
        hasWorkspace,
        chipWidths,
    );

    function computePlan(
        avail: number,
        nForks: number,
        nBookmarks: number,
        nTags: number,
        hasWs: boolean,
        widths: number[],
    ): Plan {
        let forkShown = Array(nForks).fill(true);
        let bookmarkShown = Array(nBookmarks).fill(true);
        let tagShown = Array(nTags).fill(true);
        let showWorkspace = hasWs;

        // widths are the measured widths of REAL chips only, in DOM order:
        // forks, bookmarks, tags, workspace?. Placeholders use PLACEHOLDER_WIDTH.
        let expected = nForks + nBookmarks + nTags + (hasWs ? 1 : 0);
        if (widths.length !== expected || !isFinite(avail)) {
            // Pre-measurement: show everything so actual chips render and can be measured.
            return {
                forkShown,
                bookmarkShown,
                tagShown,
                showWorkspace,
                forkPlaceholder: false,
                bookmarkPlaceholder: false,
                tagPlaceholder: false,
            };
        }

        let idx = 0;
        let forkW = widths.slice(idx, idx + nForks);
        idx += nForks;
        let bookmarkW = widths.slice(idx, idx + nBookmarks);
        idx += nBookmarks;
        let tagW = widths.slice(idx, idx + nTags);
        idx += nTags;
        let workspaceW = hasWs ? widths[idx++] : 0;

        function leftmostVisibleIdx(shown: boolean[]): number {
            for (let i = 0; i < shown.length; i++) if (shown[i]) return i;
            return -1;
        }

        function countShown(arr: boolean[]): number {
            let c = 0;
            for (let v of arr) if (v) c++;
            return c;
        }

        function totalWidth(): number {
            let sum = 0;
            let count = 0;
            for (let i = 0; i < forkShown.length; i++)
                if (forkShown[i]) {
                    sum += forkW[i];
                    count++;
                }
            if (countShown(forkShown) < nForks) {
                sum += PLACEHOLDER_WIDTH;
                count++;
            }
            for (let i = 0; i < bookmarkShown.length; i++)
                if (bookmarkShown[i]) {
                    sum += bookmarkW[i];
                    count++;
                }
            if (countShown(bookmarkShown) < nBookmarks) {
                sum += PLACEHOLDER_WIDTH;
                count++;
            }
            for (let i = 0; i < tagShown.length; i++)
                if (tagShown[i]) {
                    sum += tagW[i];
                    count++;
                }
            if (countShown(tagShown) < nTags) {
                sum += PLACEHOLDER_WIDTH;
                count++;
            }
            if (showWorkspace) {
                sum += workspaceW;
                count++;
            }
            if (count > 1) sum += (count - 1) * GAP;
            return sum;
        }

        // Drop in priority order: workspace → forks → tags → bookmarks.
        // Within each category, drop leftmost first so the "…" placeholder
        // renders on the left, replacing the collapsed prefix.
        while (totalWidth() > avail) {
            if (showWorkspace) {
                showWorkspace = false;
                continue;
            }
            let i = leftmostVisibleIdx(forkShown);
            if (i >= 0) {
                forkShown[i] = false;
                continue;
            }
            i = leftmostVisibleIdx(tagShown);
            if (i >= 0) {
                tagShown[i] = false;
                continue;
            }
            i = leftmostVisibleIdx(bookmarkShown);
            if (i >= 0) {
                bookmarkShown[i] = false;
                continue;
            }
            break;
        }

        return {
            forkShown,
            bookmarkShown,
            tagShown,
            showWorkspace,
            forkPlaceholder: countShown(forkShown) < nForks,
            bookmarkPlaceholder: countShown(bookmarkShown) < nBookmarks,
            tagPlaceholder: countShown(tagShown) < nTags,
        };
    }

    /*
     * Select this revision by default, or callback to a list that needs more complex behaviour.
     */
    function onSelect(event: CustomEvent<MouseEvent>) {
        if (event.detail.shiftKey) {
            if (onShiftClick) {
                onShiftClick(header);
            } else {
                revisionSelectEvent.set({ from: header.id, to: header.id });
            }
        } else {
            if (onClick) {
                onClick(header);
            } else {
                revisionSelectEvent.set({ from: header.id, to: header.id });
            }
        }
    }

    function onEdit() {
        new RevisionMutator([header], $ignoreToggled).onEdit();
    }
</script>

<Object
    {operand}
    suffix={header.id.commit.prefix}
    conflicted={header.has_conflict}
    {selected}
    label={header.description.lines[0]}
    on:click={onSelect}
    on:dblclick={onEdit}
    let:context
    let:hint={dragHint}>
    {#if child}
        <!-- Parents aren't a drop target -->
        <div class="layout">
            <IdSpan
                id={header.id.change}
                pronoun={context ||
                    ($currentTarget?.type == "Merge" &&
                        $currentTarget.header.parent_ids.findIndex((id) => id.hex == header.id.commit.hex) != -1)} />

            <span class="text desc truncate" class:indescribable={!context && header.description.lines[0] == ""}>
                {dragHint ?? (header.description.lines[0] == "" ? "(no description set)" : header.description.lines[0])}
            </span>

            <span class="refs">
                {#each header.refs as ref}
                    {#if ref.type != "Tag"}
                        {#if !noBookmarks && (ref.type == "LocalBookmark" || !ref.is_synced || !ref.is_tracked)}
                            <div>
                                <BookmarkObject {header} {ref} />
                            </div>
                        {/if}
                    {:else}
                        <div>
                            <TagObject {header} {ref} />
                        </div>
                    {/if}
                {/each}
                {#if header.working_copy_of}
                    <div>
                        <WorkspaceObject name={header.working_copy_of} />
                    </div>
                {/if}
            </span>

            <span class="email"><AuthorSpan author={header.author} /></span>
        </div>
    {:else}
        <Zone {operand} let:target let:hint={dropHint}>
            <div class="layout" class:target bind:this={layoutEl}>
                <IdSpan id={header.id.change} pronoun={context || target || dropHint != null} />

                <span class="text desc truncate" class:indescribable={!context && header.description.lines[0] == ""}>
                    {dragHint ??
                        dropHint ??
                        (header.description.lines[0] == "" ? "(no description set)" : header.description.lines[0])}
                </span>

                <span class="refs" bind:this={refsEl}>
                    {#if plan.forkPlaceholder}
                        <div>
                            <Chip context={false} target={false} immobile tip={hiddenForkTip}>
                                <Icon name="git-branch" state="change" />
                                <span>…</span>
                            </Chip>
                        </div>
                    {/if}
                    {#each hiddenForks as label, i}
                        <div data-chip role="presentation" style:display={plan.forkShown[i] !== false ? null : "none"} on:contextmenu={(e) => onForkContextMenu(e, label)}>
                            <Chip
                                context={false}
                                target={false}
                                immobile
                                tip={`${label} forks here but is outside the current revset`}>
                                <Icon name="git-branch" state="change" />
                                <span>{label}</span>
                            </Chip>
                        </div>
                    {/each}
                    {#if plan.bookmarkPlaceholder}
                        <div>
                            <Chip context={false} target={false} immobile tip={hiddenBookmarkTip}>
                                <Icon name="bookmark" state="change" />
                                <span>…</span>
                            </Chip>
                        </div>
                    {/if}
                    {#each bookmarkRefs as ref, i}
                        <div data-chip style:display={plan.bookmarkShown[i] !== false ? null : "none"}>
                            <BookmarkObject {header} {ref} />
                        </div>
                    {/each}
                    {#if plan.tagPlaceholder}
                        <div>
                            <Chip context={false} target={false} immobile tip={hiddenTagTip}>
                                <Icon name="tag" state="change" />
                                <span>…</span>
                            </Chip>
                        </div>
                    {/if}
                    {#each tagRefs as ref, i}
                        <div data-chip style:display={plan.tagShown[i] !== false ? null : "none"}>
                            <TagObject {header} {ref} />
                        </div>
                    {/each}
                    {#if hasWorkspace && header.working_copy_of}
                        <div data-chip style:display={plan.showWorkspace ? null : "none"}>
                            <WorkspaceObject name={header.working_copy_of} />
                        </div>
                    {/if}
                </span>

                <span class="email" bind:this={emailEl}><AuthorSpan author={header.author} /></span>
            </div>
        </Zone>
    {/if}
</Object>

<svelte:window on:click={() => (forkMenu = null)} />

{#if forkMenu}
    <div
        class="fork-menu"
        style="left: {forkMenu.x}px; top: {forkMenu.y}px;"
        role="menu"
        tabindex="0"
        on:click|stopPropagation
        on:keydown={(e) => e.key === "Escape" && (forkMenu = null)}>
        <button on:click={copyForkName}>Copy name</button>
    </div>
{/if}

<style>
    .layout {
        pointer-events: auto;
        /* layout summary components along a text line */
        width: 100%;
        height: 30px;
        display: flex;
        align-items: baseline;
        gap: 6px;
        border-bottom: 1px solid var(--gg-colors-surface);

        /* skip past svg lines when used in a graph */
        padding-left: var(--leftpad);
    }

    .layout.target {
        background: var(--gg-colors-accent);
        color: black;
    }

    .layout > :global(span) {
        line-height: 27px;
    }

    .desc {
        grid-area: desc;
        font-family: var(--gg-text-familyUi);
        flex: 1 1 0;
        min-width: 0;
    }

    .desc.indescribable {
        color: var(--gg-colors-foregroundMuted);
    }

    .email {
        display: none;
        text-align: right;
        font-family: var(--gg-text-familyUi);
        flex: 0 0 auto;
    }

    .refs {
        flex: 0 0 auto;
        align-self: center;
        display: flex;
        justify-content: end;
        gap: 3px;
        color: var(--gg-colors-foreground);
        min-width: 0;
        overflow: hidden;
    }


    /* multiple elements can have these */
    .truncate {
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .text {
        pointer-events: none;
    }

    @media (width >= 1680px) {
        .layout {
            gap: 9px;
        }

        .email {
            display: initial;
        }
    }

    .fork-menu {
        position: fixed;
        z-index: 1000;
        background: var(--ctp-surface0);
        border: 1px solid var(--ctp-overlay0);
        border-radius: 3px;
        box-shadow: 2px 2px var(--ctp-text);

        button {
            width: 100%;
            display: block;
            border: none;
            padding: 4px 12px;
            text-align: left;
            background: none;
            color: var(--ctp-text);
            font-family: var(--stack-industrial);
            cursor: pointer;

            &:hover {
                background: var(--ctp-flamingo);
                color: black;
            }
        }
    }
</style>
