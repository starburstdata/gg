<script lang="ts">
    import type { RevHeader } from "./messages/RevHeader";
    import { currentContext } from "./stores.js";

    export let header: RevHeader;

    let context = false;
    $: context = $currentContext?.type == "Revision" && header == $currentContext.header;
    $: wcClass = header.working_copy_of != null ? "other-wc" : "wc";
</script>

{#if header.is_immutable}
    {#if header.is_working_copy}
        <circle class={wcClass} class:context cx="9" cy="15" r="6" />
    {:else}
        <circle class:context cx="9" cy="15" r="6" />
    {/if}
{:else}
    <circle class:context cx="9" cy="15" r="6" class="mutable" />
    {#if header.is_working_copy}
        <circle class={wcClass} class:context cx="9" cy="15" r="3" />
    {/if}
{/if}

<style>
    circle {
        pointer-events: none;
        stroke: var(--ju-colors-immutableStroke);
        fill: var(--ju-colors-immutableFill);
    }

    .wc {
        stroke: var(--ju-colors-workingCopyStroke);
        fill: var(--ju-colors-workingCopyFill);
    }

    .other-wc {
        stroke: var(--ju-colors-warning);
        fill: var(--ju-colors-warning);
    }

    .context {
        stroke: var(--ju-colors-accent);
        fill: var(--ju-colors-accent);
    }

    .mutable {
        fill: var(--ju-colors-mutableFill);
    }
</style>
