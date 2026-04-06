<script lang="ts">
    import type { EnhancedLine } from "./GraphLog.svelte";
    import type { Operand } from "./messages/Operand";
    import Zone from "./objects/Zone.svelte";
    import { currentTarget } from "./stores";

    export let line: EnhancedLine;

    let isMerge = line.type == "ToIntersection";
    let allowEarlyBreak = line.type == "FromNode";
    let c1 = line.source[0];
    let c2 = line.target[0];
    let r1 = line.source[1];
    let r2 = line.target[1];

    let operand: Operand = { type: "Parent", header: line.parent, child: line.child };

    // draw path downward, from child to parent
    let path: string;

    let childY = r1 * 30 + 21;
    let parentY = r2 * 30 + 9;

    type Block = { x: number; y: number; w: number; h: number };
    let blocks: Block[] = [];

    if (isMerge && c1 != c2) {
        // instead of a parent, we have a mergepoint
        let childX = c1 * 18 + 9;
        let mergeX = c2 * 18 + 9;
        let midY = c2 > c1 ? childY + 9 : parentY - 9;
        let radius = c2 > c1 ? 6 : -6;
        let sweep = c2 > c1 ? 0 : 1;
        path = `M${childX},${childY}
            L${childX},${midY - 6}
            A6,6,0,0,${sweep},${childX + radius},${midY}
            L${mergeX - radius},${midY}
            A6,6,0,0,${1 - sweep},${mergeX},${midY + 6}
            L${mergeX},${parentY}`;

        blocks.push({
            x: c1 < c2 ? c1 * 18 + 2 : c2 * 18 + 2,
            y: midY - 8,
            w: c1 < c2 ? (c2 - c1 + 1) * 18 - 5 : (c1 - c2 + 1) * 18 - 5,
            h: 14,
        });
        // vertical segment at child column
        let topH = (midY - 6) - childY;
        if (topH > 2) {
            blocks.push({ x: c1 * 18 + 2, y: childY, w: 14, h: topH });
        }
        // vertical segment at merge column
        let bottomH = parentY - (midY + 6);
        if (bottomH > 2) {
            blocks.push({ x: c2 * 18 + 2, y: midY + 6, w: 14, h: bottomH });
        }
    } else if (line.type == "FromNode" && line.via != null && line.via != c1) {
        // rescue sweep with three columns: source → via (bend near source
        // row) → target (bend near target row). The long vertical at `via`
        // (an empty lane) keeps the curve clear of circles on both source
        // and target columns. Used when the rescue has to reach back to
        // the allocator's column (e.g. the stem belongs to a distant
        // octopus-merge parent). Must be checked before the same-column
        // branch: when source and target share a column (the rescue lands
        // the stem back on its own origin lane), a straight vertical at
        // that column would slice through every sibling's circle in between.
        // Cascading rescues where the stem was already at `via` (via == c1)
        // fall through to the 2-column curve below.
        let childX = c1 * 18 + 9;
        let viaX = line.via * 18 + 9;
        let parentX = c2 * 18 + 9;
        let topMidY = childY + 9;
        let botMidY = r2 * 30;
        let topRadius = viaX > childX ? 6 : -6;
        let topSweep = viaX > childX ? 0 : 1;
        let botRadius = parentX > viaX ? 6 : -6;
        let botSweep = parentX > viaX ? 0 : 1;
        path = `M${childX},${childY}
            L${childX},${topMidY - 6}
            A6,6,0,0,${topSweep},${childX + topRadius},${topMidY}
            L${viaX - topRadius},${topMidY}
            A6,6,0,0,${1 - topSweep},${viaX},${topMidY + 6}
            L${viaX},${botMidY - 6}
            A6,6,0,0,${botSweep},${viaX + botRadius},${botMidY}
            L${parentX - botRadius},${botMidY}
            A6,6,0,0,${1 - botSweep},${parentX},${botMidY + 6}
            L${parentX},${parentY}`;

        // top horizontal between source and via
        blocks.push({
            x: childX < viaX ? childX + 7 : viaX + 7,
            y: topMidY - 8,
            w: childX < viaX ? viaX - childX - 14 : childX - viaX - 14,
            h: 14,
        });
        // long vertical at via
        let viaH = (botMidY - 6) - (topMidY + 6);
        if (viaH > 2) {
            blocks.push({ x: line.via * 18 + 2, y: topMidY + 6, w: 14, h: viaH });
        }
        // bottom horizontal between via and target
        blocks.push({
            x: viaX < parentX ? viaX + 7 : parentX + 7,
            y: botMidY - 8,
            w: viaX < parentX ? parentX - viaX - 14 : viaX - parentX - 14,
            h: 14,
        });
    } else if (c1 == c2) {
        // same-column, straight line
        let x = c1 * 18 + 9;

        path = `M${x},${childY} L${x},${parentY}`;

        // same-row lines (a redirected ToIntersection after a rescue) have no
        // vertical extent, so don't emit a backdrop with negative height.
        let h = (r2 - r1) * 30 - 12;
        if (h > 0) {
            blocks.push({
                x: c1 * 18 + 2,
                y: r1 * 30 + 21,
                w: 14,
                h,
            });
        }
    } else {
        // different-column, curved line
        let childX = c1 * 18 + 9;
        let parentX = c2 * 18 + 9;
        // rescue FromNodes (only emitted right-to-left) bend near the target
        // row boundary rather than the source row — otherwise the vertical at
        // the target column would span the rescuing commit's circle. regular
        // ToNode curves still bend early near the source so they read as
        // descending from the child toward the parent.
        let midY = allowEarlyBreak && c1 > c2 ? r2 * 30 : childY + 9;
        let radius = c2 > c1 ? 6 : -6;
        let sweep = c2 > c1 ? 0 : 1;
        path = `M${childX},${childY}
            L${childX},${midY - 6}
            A6,6,0,0,${sweep},${childX + radius},${midY}
            L${parentX - radius},${midY}
            A6,6,0,0,${1 - sweep},${parentX},${midY + 6}
            L${parentX},${parentY}`;

        // horizontal segment
        blocks.push({
            x: c1 < c2 ? c1 * 18 + 16 : c2 * 18 + 16,
            y: midY - 8,
            w: c1 < c2 ? (c2 - c1) * 18 - 14 : (c1 - c2) * 18 - 14,
            h: 14,
        });
        // vertical segment at child column
        let topH = (midY - 6) - childY;
        if (topH > 2) {
            blocks.push({ x: c1 * 18 + 2, y: childY, w: 14, h: topH });
        }
        // vertical segment at parent column
        let bottomH = parentY - (midY + 6);
        if (bottomH > 2) {
            blocks.push({ x: c2 * 18 + 2, y: midY + 6, w: 14, h: bottomH });
        }
    }
</script>

{#if !line.indirect}
    {#each blocks as block}
        <foreignObject x={block.x} y={block.y} width={block.w} height={block.h}>
            <Zone {operand} let:target>
                <div class="backdrop" class:target></div>
            </Zone>
        </foreignObject>
    {/each}
{/if}

<path d={path} fill="none" stroke-dasharray={line.indirect ? "1,2" : "none"} class:target={$currentTarget == operand} />

<style>
    path {
        pointer-events: none;
        stroke: var(--ju-colors-primary);
    }

    foreignObject > :global(*) {
        height: 100%;
    }

    .backdrop {
        width: 100%;
        height: 100%;
    }

    .target {
        stroke: black;
        background-color: var(--ju-colors-accent);
    }
</style>
