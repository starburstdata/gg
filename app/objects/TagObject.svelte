<script lang="ts">
    import type { RevHeader } from "../messages/RevHeader";
    import type { StoreRef } from "../messages/StoreRef";
    import type { Operand } from "../messages/Operand";
    import Chip from "../controls/Chip.svelte";
    import Icon from "../controls/Icon.svelte";
    import { currentContext, hasMenu } from "../stores";
    import { trigger, isTauri } from "../ipc";

    export let header: RevHeader;
    export let ref: Extract<StoreRef, { type: "Tag" }>;

    let operand: Operand = { type: "Ref", header, ref };

    function onContextMenu(event: MouseEvent) {
        event.preventDefault();
        event.stopPropagation();
        currentContext.set(operand);
        if (isTauri()) {
            trigger("forward_context_menu", { context: operand });
        } else {
            hasMenu.set({ x: event.clientX, y: event.clientY });
        }
    }
</script>

<div role="presentation" on:contextmenu={onContextMenu}>
    <Chip context={false} target={false} immobile tip="tag">
        <Icon name="tag" state="change" />
        <span>{ref.tag_name}</span>
    </Chip>
</div>
