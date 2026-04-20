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

    // keep a minimum pane width so panes can't collapse entirely
    const MIN_PANE_PX = 120;

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
        let x = event.clientX - rect.left;
        let min = MIN_PANE_PX;
        let max = rect.width - MIN_PANE_PX;
        if (max < min) return;
        let clamped = Math.min(max, Math.max(min, x));
        splitFraction = clamped / rect.width;
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
             style="grid-template-columns: {splitFraction}fr 3px {1 - splitFraction}fr"
             class:dragging>
            {#key workspace.absolute_path}
                <LogPane query_choices={workspace.query_choices}
                         latest_query={workspace.latest_query} />
            {/key}

            <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
            <div class="separator"
                 role="separator"
                 aria-orientation="vertical"
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

    .separator {
        background: var(--ctp-overlay0);
        cursor: col-resize;
        position: relative;
        /* global.css sets div { pointer-events: none } for drop-transparency */
        pointer-events: auto;
    }

    /* extends the hit target beyond the visual 3px column without affecting layout */
    .hit-area {
        position: absolute;
        inset: 0 -3px;
        z-index: 1;
        cursor: col-resize;
        pointer-events: auto;
    }

    .separator:hover,
    .two-pane.dragging .separator {
        background: var(--ctp-overlay1);
    }
</style>
