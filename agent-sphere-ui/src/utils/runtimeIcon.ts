/** Official runtime artwork, served from `public/runtime`. */
export function runtimeIcon(kind?: string | null): string {
  return kind === 'CODEX' ? '/runtime/codex.png' : '/runtime/opencode.png';
}

export function runtimeLabel(kind?: string | null): string {
  return kind === 'CODEX' ? 'Codex' : 'opencode';
}
