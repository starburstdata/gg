<script lang="ts">
    import Icon from "./Icon.svelte";
    import type { Snippet } from "svelte";

    export let icon: string;
    export let label: string;
    export let tip: string = "";
    export let onClick: (e: MouseEvent) => void;
    export let disabled: boolean = false;
    export let dropdown: Snippet<[{ close: () => void }]> | undefined = undefined;

    let root: HTMLElement;
    let open = false;

    function onWindowClick(event: MouseEvent) {
        if (open && !root.contains(event.target as Node)) {
            open = false;
        }
    }

    function onKeyDown(event: KeyboardEvent) {
        if (open && event.key === "Escape") {
            open = false;
        }
    }

    function close() {
        open = false;
    }
</script>

<svelte:window on:click={onWindowClick} on:keydown={onKeyDown} />

<div class="toolbar-action" bind:this={root}>
    <button
        class="body"
        {disabled}
        title={disabled ? "" : tip}
        on:click|stopPropagation={disabled ? undefined : onClick}>
        <span class="icon-slot"><Icon name={icon} /></span>
        <span class="label">{label}</span>
    </button>
    {#if dropdown}
        <button
            class="chevron"
            aria-haspopup="menu"
            aria-expanded={open}
            on:click={() => (open = !open)}>
            <Icon name="chevron-down" />
        </button>
        {#if open}
            <div class="dropdown" role="menu">
                {@render dropdown({ close })}
            </div>
        {/if}
    {/if}
</div>

<style>
    .toolbar-action {
        position: relative;
        display: flex;
        align-items: stretch;
        height: 100%;
    }

    .body {
        display: grid;
        grid-template-rows: 1fr auto;
        gap: 2px;
        padding: 6px 8px;
        justify-items: center;
        align-items: start;
        min-height: 0;

        background: transparent;
        border: none;
        box-shadow: none;
        color: var(--gg-colors-foreground);
        font-family: var(--gg-text-familyUi);
        cursor: pointer;
        transition: background var(--gg-components-transitionFast);
    }

    .body:not(:disabled):hover {
        background: var(--gg-colors-surfaceAlt);
    }

    .body:disabled {
        background: transparent;
        color: var(--gg-colors-outlineStrong);
        cursor: default;
    }

    .icon-slot {
        display: flex;
        align-items: center;
        justify-content: center;
        min-height: 0;
        aspect-ratio: 1 / 1;
        height: 100%;
    }

    /* override Icon.svelte's fixed 16×16 so the icon scales with toolbar height */
    .icon-slot :global(svg.feather) {
        width: auto;
        height: 100%;
        max-width: 100%;
        min-width: 0;
    }

    .label {
        font-size: var(--gg-text-sizeSm);
        line-height: var(--gg-text-lineHeightTight);
        white-space: nowrap;
    }

    .chevron {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 14px;
        height: 100%;
        padding: 0;
        background: transparent;
        border: none;
        box-shadow: none;
        color: var(--gg-colors-foreground);
        cursor: pointer;
        transition: background var(--gg-components-transitionFast);
    }

    .chevron:hover {
        background: var(--gg-colors-surfaceAlt);
    }

    /* keep chevron icon small regardless of toolbar height */
    .chevron :global(svg.feather) {
        width: 10px;
        height: 10px;
        min-width: 10px;
    }

    .dropdown {
        position: absolute;
        top: 100%;
        left: 0;
        z-index: 100;
        background: var(--gg-colors-background);
        border: 1px solid var(--gg-colors-surfaceStrong);
        border-radius: var(--gg-components-radiusSm);
        box-shadow: var(--gg-shadows-shadowMd);
        min-width: 140px;
        padding: 2px 0;
    }

    .dropdown :global(button) {
        display: flex;
        width: 100%;
        padding: 6px 12px;
        background: transparent;
        border: none;
        box-shadow: none;
        color: var(--gg-colors-foreground);
        font-family: var(--gg-text-familyUi);
        font-size: var(--gg-text-sizeMd);
        text-align: left;
        white-space: nowrap;
        cursor: pointer;
        transition: background var(--gg-components-transitionFast);
    }

    .dropdown :global(button:hover) {
        background: var(--gg-colors-surface);
    }
</style>
