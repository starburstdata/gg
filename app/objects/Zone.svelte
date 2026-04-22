<!--
@component
A drop target for direct-manipulation objects.
-->

<script lang="ts">
    import type { Operand } from "../messages/Operand";
    import BinaryMutator from "../mutators/BinaryMutator";
    import { currentSource, currentTarget, ignoreToggled } from "../stores";
    import { isEmbedded } from "../ipc";

    interface $$Slots {
        default: { target: boolean; hint: string | null };
    }

    export let operand: Operand | null;
    export let alwaysTarget: boolean = false;

    let dropHint: string | null = null;
    let target = false;
    let embedded = isEmbedded();
    $: target = match($currentTarget);

    function match(target: Operand | null): boolean {
        return (
            (operand && target == operand) ||
            (operand?.type == "Merge" && target?.type == "Merge" && operand.header.id.commit == target.header.id.commit)
        );
    }

    // shared logic for dragover / mouseenter
    function enterZone(canPreventDefault?: () => void) {
        let canDrop =
            operand == null
                ? { type: "no", hint: "" }
                : new BinaryMutator($currentSource!, operand, $ignoreToggled).canDrop();

        if (canDrop.type == "yes") {
            canPreventDefault?.();
            if (!match($currentTarget)) {
                $currentTarget = operand;
            }
            dropHint = null;
        } else if (canDrop.type == "maybe") {
            canPreventDefault?.();
            dropHint = canDrop.hint;
            if (alwaysTarget && !match($currentTarget)) {
                $currentTarget = operand;
            }
        }
    }

    // shared logic for dragleave / mouseleave
    function leaveZone() {
        $currentTarget = null;
        dropHint = null;
    }

    // shared logic for drop / mouseup
    function dropOnZone() {
        if (operand) {
            let mutator = new BinaryMutator($currentSource!, operand, $ignoreToggled);
            if (mutator.canDrop().type == "yes") {
                mutator.doDrop();
            }
        }

        $currentSource = null;
        $currentTarget = null;
        dropHint = null;
    }

    function onDragOver(event: DragEvent) {
        event.stopPropagation();
        enterZone(() => event.preventDefault());
    }

    function onDragLeave(event: DragEvent) {
        leaveZone();
    }

    function onDrop(event: DragEvent) {
        event.stopPropagation();
        dropOnZone();
    }

    // mouse-based equivalents for embedded JCEF contexts
    function onMouseEnter(event: MouseEvent) {
        if (!embedded || !$currentSource) return;
        event.stopPropagation();
        enterZone();
    }

    function onMouseLeave(event: MouseEvent) {
        if (!embedded || !$currentSource) return;
        leaveZone();
    }

    function onMouseUp(event: MouseEvent) {
        if (!embedded || !$currentSource) return;
        event.stopPropagation();
        dropOnZone();
    }
</script>

<div
    role="presentation"
    class="zone"
    class:hint={dropHint}
    on:dragenter={onDragOver}
    on:dragover={onDragOver}
    on:dragleave={onDragLeave}
    on:drop={onDrop}
    on:mouseenter={onMouseEnter}
    on:mouseleave={onMouseLeave}
    on:mouseup={onMouseUp}>
    <slot {target} hint={dropHint} />
</div>

<style>
    .zone {
        width: 100%;
        pointer-events: auto;
    }

    .hint {
        color: var(--ctp-peach);
    }
</style>
