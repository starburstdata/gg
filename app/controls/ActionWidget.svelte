<script lang="ts">
    import { dragOverWidget, hasModal } from "../stores";

    export let tip: string = "";
    export let onClick: (event: MouseEvent) => void;
    export let modal: boolean = false;
    export let secondary: boolean = false;
    export let primary: boolean = false;
    export let disabled: boolean = false;

    $: isDisabled = disabled || (!modal && $hasModal);
</script>

<button
    disabled={isDisabled}
    class:modal
    class:secondary
    class:primary
    on:click|stopPropagation={isDisabled ? undefined : onClick}
    on:dragenter={dragOverWidget}
    on:dragover={dragOverWidget}
    title={isDisabled ? "" : tip}>
    <slot />
</button>

<style>
    button {
        height: var(--ju-components-buttonHeight);
        font-size: 14px;
        padding: var(--ju-components-buttonPadding);

        outline: none;
        margin: 0;
        border: var(--ju-components-buttonBorder);
        border-radius: var(--ju-components-buttonRadius);
        box-shadow: var(--ju-components-buttonShadow);

        font-family: var(--ju-text-familyUi);
        display: flex;
        align-items: center;
        gap: 3px;

        cursor: pointer;
        transition: background var(--ju-components-transitionFast), box-shadow var(--ju-components-transitionFast), transform var(--ju-components-transitionFast);

        color: var(--ju-components-buttonForeground);
        background: var(--ju-components-buttonBackground);
    }

    button:not(:disabled) {
        &:hover {
            background: var(--ju-components-buttonHoverBackground);
            box-shadow: var(--ju-shadows-shadowMd);
        }
        &:focus-visible {
            border-color: var(--ju-colors-focusRing);
            border-width: 2px;
            padding: 0px 5px;
            text-decoration: underline;
        }
        &:active {
            margin: var(--active-margin);
            transform: var(--ju-components-buttonActiveTransform);
            box-shadow: var(--ju-components-buttonActiveShadow);
            &:focus-visible {
                padding: 1px 4px 0px 5px;
            }
        }
    }

    button.primary {
        color: var(--ju-colors-primaryContent);
        background: var(--ju-colors-primary);
        &:hover {
            background: var(--ju-components-buttonHoverBackground);
        }
    }

    button.secondary {
        color: var(--ju-components-buttonSecondaryForeground);
        background: var(--ju-components-buttonSecondaryBackground);
        &:hover {
            background: var(--ju-components-buttonSecondaryHoverBackground);
        }
    }

    button:disabled {
        background: var(--ju-components-buttonDisabledBackground);
        color: var(--ju-components-buttonDisabledForeground);
    }
</style>
