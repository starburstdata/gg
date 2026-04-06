<script lang="ts">
    import { hasModal } from "../stores";
</script>

<section inert={$hasModal}>
    <div class="header">
        <slot name="header" />
    </div>
    <div class="body">
        <slot name="body" />
    </div>
</section>

<style>
    section {
        display: flex;
        flex-direction: column;
        height: 100%;
        overflow: hidden;
        background: var(--ju-colors-background);
    }

    .header {
        border-bottom: var(--ju-components-borderSubtle);
        font-family: var(--ju-text-familyUi);
        padding: 6px 6px 3px 6px;
        background: var(--ju-colors-background);
    }

    .body {
        padding: 3px 6px 6px 6px;
        display: grid;
        overflow: hidden;
    }
</style>
