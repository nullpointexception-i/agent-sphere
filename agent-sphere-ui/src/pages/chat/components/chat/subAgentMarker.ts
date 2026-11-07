/**
 * Sub-agent protocol prefix compatibility table.
 *
 * After the backend's unified delegate refactor, neutral prefixes are used: nodeName `agent:`,
 * reasoning marker `▶ Agent `, publishId `agent-`; historical sessions may still use the old
 * `skill:` / `▶ Skill ` / `skill-`. Both prefix sets are registered here so that sub-agent
 * segments accept old and new alike, keeping historical sessions from breaking.
 */
export const SUB_AGENT_NODE_NAME_PREFIXES = ['agent:', 'skill:'] as const;
export const SUB_AGENT_MARKER_PREFIXES = ['▶ Agent ', '▶ Skill '] as const;
export const SUB_AGENT_PUBLISH_ID_PREFIXES = ['agent-', 'skill-'] as const;

/** Strip the sub-agent sentinel prefix from the start of a title/display name (tolerates historical `▶ Skill ` data). */
export function stripSubAgentMarkerPrefix(text?: string | null): string {
  const raw = text ?? '';
  for (const prefix of SUB_AGENT_MARKER_PREFIXES) {
    if (raw.startsWith(prefix)) return raw.slice(prefix.length);
  }
  return raw;
}

/**
 * Strip the sub-agent LLM first-frame reasoning sentinel line (`<marker><id>: <name>\n<body>`).
 * Accepts both the old `▶ Skill ` and the new `▶ Agent `; with no known prefix, strip by the
 * first line (preserving existing behavior).
 */
export function stripSubAgentSentinel(delta: string): string {
  const marker = SUB_AGENT_MARKER_PREFIXES.find((prefix) =>
    delta.startsWith(prefix),
  );
  if (!marker) {
    const nl = delta.indexOf('\n');
    return nl >= 0 ? delta.slice(nl + 1) : delta;
  }
  const nl = delta.indexOf('\n', marker.length);
  return nl >= 0 ? delta.slice(nl + 1) : '';
}
