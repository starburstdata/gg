<script lang="ts">
    import { dragOverWidget } from "../stores";
    import Icon from "./Icon.svelte";

    export let tip: string = "";
    export let checked: boolean;
    export let secondary: boolean = false;
    export let disabled: boolean = false;
    export let on: string;
    export let off: string;

    $: isDisabled = disabled;

    function toggle() {
        if (!isDisabled) {
            checked = !checked;
        }
    }
</script>

<button
    {disabled}
    class:secondary
    class:checked
    on:click={toggle}
    on:dragenter={dragOverWidget}
    on:dragover={dragOverWidget}
    title={isDisabled ? "" : tip}>
    <Icon name={checked ? on : off} />
</button>

<style>
    button {
        height: var(--ju-components-buttonHeight);
        font-size: 16px;
        padding: 1px 3px;

        outline: none;
        margin: 0;
        border-width: 1px;
        border-radius: var(--ju-components-radiusSm);
        border-color: var(--ju-colors-outline);
        box-shadow: var(--ju-shadows-shadowSm);

        background: var(--ju-colors-success);
        color: black;

        font-family: var(--ju-text-familyUi);
        display: flex;
        align-items: center;

        cursor: pointer;
        transition: background var(--ju-components-transitionFast), box-shadow var(--ju-components-transitionFast), transform var(--ju-components-transitionFast);
    }

    button:not(:disabled) {
        &:hover {
            background: var(--ju-colors-success);
            box-shadow: var(--ju-shadows-shadowMd);
        }
        &:focus-visible {
            border-color: var(--ju-colors-focusRing);
            border-width: 2px;
            padding: 0px 2px;
            text-decoration: underline;
        }
        &:active {
            margin: var(--active-margin);
            transform: var(--ju-components-buttonActiveTransform);
            box-shadow: var(--ju-components-buttonActiveShadow);
            &:focus-visible {
                padding: 1px 1px 0px 2px;
            }
        }
    }

    button.checked:not(:disabled) {
        margin: var(--active-margin);
        box-shadow: var(--ju-components-buttonActiveShadow);
        background: var(--ju-colors-error);
        &:focus-visible {
            padding: 1px 1px 0px 2px;
        }
    }

    button.secondary {
        background: var(--ju-colors-surfaceStrong);
        &:hover {
            background: var(--ju-colors-foregroundSubtle);
        }
    }

    button:disabled {
        background: var(--ju-colors-surface);
        color: var(--ju-colors-foregroundSubtle);
    }
</style>
