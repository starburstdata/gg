<script lang="ts">
    export let context: boolean;
    export let target: boolean;
    export let disconnected: boolean = false;
    export let immobile: boolean = false;
    export let tip: string = "";
</script>

<div class="chip" class:context class:target class:disconnected class:immobile title={tip}>
    <slot />
</div>

<style>
    .chip {
        pointer-events: auto;

        font-family: var(--ju-text-familyCode);
        font-size: smaller;

        height: 24px;
        line-height: 16px;

        display: flex;
        align-items: center;
        border: var(--ju-components-borderSubtle);
        border-radius: 12px;
        transition: background var(--ju-components-transitionFast);
        padding: 0 6px;
        background: var(--ju-colors-surface);
        white-space: nowrap;
        gap: 3px;
    }

    .context {
        color: inherit;
        border-color: currentColor;
    }

    .target {
        background: var(--ju-colors-selectionBackground);
        color: var(--ju-colors-selectionForeground);
    }

    .disconnected {
        border-style: dashed;
    }

    .immobile {
        border-style: none;
    }
</style>
