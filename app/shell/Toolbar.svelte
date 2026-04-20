<script lang="ts">
    import ToolbarAction from "../controls/ToolbarAction.svelte";
    import { mutate } from "../ipc";
    import type { GitFetch } from "../messages/GitFetch";
    import type { GitPush } from "../messages/GitPush";
    import type { UndoOperation } from "../messages/UndoOperation";
    import type { RepoConfig } from "../messages/RepoConfig";
    import { ignoreToggled, hasModal, selectionHeaders, currentMutation, progressEvent } from "../stores";
    import RevisionMutator from "../mutators/RevisionMutator";
    import ToolbarProgress from "./ToolbarProgress.svelte";

    export let config: RepoConfig;

    $: workspace = config.type === "Workspace" ? config : null;
    $: remotes = workspace?.git_remotes ?? [];
    $: defaultRemote = remotes.includes("origin") ? "origin" : remotes[0] ?? "";
    $: headers = $selectionHeaders ?? [];
    $: mutator = headers.length > 0 ? new RevisionMutator(headers, $ignoreToggled) : null;
    $: newest = headers[0] ?? null;
    $: oldest = headers.length > 0 ? headers[headers.length - 1] : null;
    $: newestImmutable = newest?.is_immutable && !$ignoreToggled;
    $: oldestImmutable = oldest?.is_immutable && !$ignoreToggled;

    function onPush(remote_name: string) {
        mutate<GitPush>(
            "git_push",
            { refspec: { type: "AllBookmarks", remote_name }, input: null },
            { operation: `Pushing to ${remote_name}...` },
        );
    }

    function onFetch(remote_name: string) {
        mutate<GitFetch>(
            "git_fetch",
            { refspec: { type: "AllBookmarks", remote_name }, input: null },
            { operation: `Fetching from ${remote_name}...` },
        );
    }

    function onUndo() {
        mutate<UndoOperation>("undo_operation", null);
    }
</script>

<div class="toolbar" inert={$hasModal}>
    {#if workspace}
        {#snippet pushRemotes({ close }: { close: () => void })}
            {#each remotes as remote}
                <button on:click={() => { onPush(remote); close(); }}>Push to {remote}</button>
            {/each}
        {/snippet}

        {#snippet fetchRemotes({ close }: { close: () => void })}
            {#each remotes as remote}
                <button on:click={() => { onFetch(remote); close(); }}>Fetch from {remote}</button>
            {/each}
        {/snippet}

        <!-- Push / Fetch -->
        <div class="toolbar-group">
            {#if remotes.length > 0}
                <ToolbarAction
                    icon="upload-cloud"
                    label="Push"
                    tip="Push to {defaultRemote}"
                    onClick={() => onPush(defaultRemote)}
                    dropdown={remotes.length > 1 ? pushRemotes : undefined} />
                <ToolbarAction
                    icon="download-cloud"
                    label="Fetch"
                    tip="Fetch from {defaultRemote}"
                    onClick={() => onFetch(defaultRemote)}
                    dropdown={remotes.length > 1 ? fetchRemotes : undefined} />
            {/if}
        </div>

        <div class="toolbar-separator"></div>

        <!-- Revision actions -->
        <div class="toolbar-group">
            {#if mutator && newest}
                <ToolbarAction
                    icon="edit-2"
                    label="Edit"
                    tip="Edit (make working copy)"
                    onClick={mutator.onEdit}
                    disabled={newestImmutable || newest.is_working_copy} />
            {/if}
            {#if mutator}
                <ToolbarAction icon="plus-square" label="New" tip="New (create child)" onClick={mutator.onNewChild} />
            {/if}
        </div>

        <div class="toolbar-separator"></div>

        <!-- Squash / Restore -->
        <div class="toolbar-group">
            {#if mutator && oldest}
                <ToolbarAction
                    icon="upload"
                    label="Squash"
                    tip="Squash (move changes to parent)"
                    onClick={mutator.onSquash}
                    disabled={oldestImmutable || oldest.parent_ids.length != 1} />
            {/if}
            {#if mutator && newest}
                <ToolbarAction
                    icon="download"
                    label="Restore"
                    tip="Restore (copy changes from parent)"
                    onClick={mutator.onRestore}
                    disabled={newestImmutable || newest.parent_ids.length != 1 || headers.length > 1} />
            {/if}
        </div>

        <div class="toolbar-separator"></div>

        <!-- Undo -->
        <div class="toolbar-group">
            <ToolbarAction icon="rotate-ccw" label="Undo" tip="Undo latest operation" onClick={onUndo} />
        </div>

        {#if $currentMutation?.type === "wait" && $progressEvent !== undefined}
            <ToolbarProgress progress={$progressEvent} />
        {/if}
    {/if}
</div>

<style>
    .toolbar {
        grid-area: toolbar;
        display: flex;
        align-items: stretch;
        gap: 2px;
        padding: 0 8px;
        background: var(--gg-colors-surfaceDeep);
        border-bottom: 1px solid var(--gg-colors-surfaceAlt);
        font-family: var(--gg-text-familyUi);
    }

    .toolbar-group {
        display: flex;
        align-items: stretch;
        gap: 2px;
        padding: 0 4px;
    }

    .toolbar-separator {
        width: 1px;
        align-self: stretch;
        margin: 6px 0;
        background: var(--gg-colors-surfaceStrong);
        flex-shrink: 0;
    }
</style>
