/// <reference types="svelte" />
/// <reference types="vite/client" />

interface Window {
    __GG_CLIENT_ID__?: string;
    __GG_EMBEDDED__?: boolean;
    __gg_openDiff?: (json: string) => void;
}
