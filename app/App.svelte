<script lang="ts">
    import { parseRoute } from "./route.js";
    import Shell from "./Shell.svelte";
    import LogPane from "./LogPane.svelte";
    import RevisionPane from "./RevisionPane.svelte";
    import BoundQuery from "./controls/BoundQuery.svelte";
    import Pane from "./shell/Pane.svelte";
    import SetSpan from "./controls/SetSpan.svelte";

    let route = parseRoute();

    let splitFraction = 0.5;
    let twoPane: HTMLDivElement;
    let dragging = false;
    let vertical = false;

    // keep a minimum pane size so panes can't collapse entirely
    const MIN_PANE_PX = 120;
    const VERTICAL_BREAKPOINT = 600;

    // switch to top/bottom split when the container is narrow
    function observeWidth(node: HTMLElement) {
        let ro = new ResizeObserver(([entry]) => {
            vertical = entry.contentRect.width < VERTICAL_BREAKPOINT;
        });
        ro.observe(node);
        return { destroy: () => ro.disconnect() };
    }

    function startDrag(event: MouseEvent) {
        if (event.button !== 0) return;
        event.preventDefault();
        dragging = true;
        window.addEventListener("mousemove", onDrag);
        window.addEventListener("mouseup", endDrag);
    }

    function onDrag(event: MouseEvent) {
        if (!dragging || !twoPane) return;
        let rect = twoPane.getBoundingClientRect();
        if (vertical) {
            let y = event.clientY - rect.top;
            let min = MIN_PANE_PX;
            let max = rect.height - MIN_PANE_PX;
            if (max < min) return;
            splitFraction = Math.min(max, Math.max(min, y)) / rect.height;
        } else {
            let x = event.clientX - rect.left;
            let min = MIN_PANE_PX;
            let max = rect.width - MIN_PANE_PX;
            if (max < min) return;
            splitFraction = Math.min(max, Math.max(min, x)) / rect.width;
        }
    }

    function endDrag() {
        dragging = false;
        window.removeEventListener("mousemove", onDrag);
        window.removeEventListener("mouseup", endDrag);
    }
</script>

<Shell revsetOverride={route.type === "revision" ? route.revset : null}
       let:workspace let:selection>
    {#if route.type === "log"}
        {#key workspace.absolute_path}
            <LogPane query_choices={workspace.query_choices}
                     latest_query={route.revset ?? workspace.latest_query} />
        {/key}
    {:else if route.type === "revision"}
        <BoundQuery query={selection} let:data>
            {#if data.type == "Detail"}
                <RevisionPane revs={data} />
            {:else}
                <Pane>
                    <h2 slot="header">Not Found</h2>
                    <p slot="body">
                        Empty revision set <SetSpan set={data.set} />.
                    </p>
                </Pane>
            {/if}
            <Pane slot="error" let:message>
                <h2 slot="header">Error</h2>
                <p slot="body">{message}</p>
            </Pane>
            <Pane slot="wait">
                <h2 slot="header">Loading...</h2>
            </Pane>
        </BoundQuery>
    {:else}
        <div class="two-pane"
             bind:this={twoPane}
             use:observeWidth
             style={vertical
                 ? `grid-template-rows: ${splitFraction}fr 3px ${1 - splitFraction}fr; grid-template-columns: 1fr`
                 : `grid-template-columns: ${splitFraction}fr 3px ${1 - splitFraction}fr`}
             class:dragging
             class:vertical>
            {#key workspace.absolute_path}
                <LogPane query_choices={workspace.query_choices}
                         latest_query={workspace.latest_query} />
            {/key}

            <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
            <div class="separator"
                 role="separator"
                 aria-orientation={vertical ? "horizontal" : "vertical"}
                 on:mousedown={startDrag}>
                <div class="hit-area"></div>
            </div>

            <BoundQuery query={selection} let:data>
                {#if data.type == "Detail"}
                    <RevisionPane revs={data} />
                {:else}
                    <Pane>
                        <h2 slot="header">Not Found</h2>
                        <p slot="body">
                            Empty revision set <SetSpan set={data.set} />.
                        </p>
                    </Pane>
                {/if}
                <Pane slot="error" let:message>
                    <h2 slot="header">Error</h2>
                    <p slot="body">{message}</p>
                </Pane>
                <Pane slot="wait">
                    <h2 slot="header">Loading...</h2>
                </Pane>
            </BoundQuery>
        </div>
    {/if}
</Shell>

<style>
    .two-pane {
        display: grid;
        grid-template-columns: 1fr 3px 1fr;
        height: 100%;
        overflow: hidden;
    }

    .two-pane.dragging {
        cursor: col-resize;
        /* prevent iframes / text selection from eating pointer events mid-drag */
        user-select: none;
    }

    .two-pane.vertical.dragging {
        cursor: row-resize;
    }

    .separator {
        background: var(--ctp-overlay0);
        cursor: col-resize;
        position: relative;
        /* global.css sets div { pointer-events: none } for drop-transparency */
        pointer-events: auto;
    }

    .two-pane.vertical .separator {
        cursor: row-resize;
    }

    /* extends the hit target beyond the visual separator without affecting layout */
    .hit-area {
        position: absolute;
        inset: 0 -3px;
        z-index: 1;
        cursor: col-resize;
        pointer-events: auto;
    }

    .two-pane.vertical .hit-area {
        inset: -3px 0;
        cursor: row-resize;
    }

    .separator:hover,
    .two-pane.dragging .separator {
        background: var(--ctp-overlay1);
    }
</style>
