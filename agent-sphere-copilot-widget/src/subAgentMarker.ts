/**
 * 子 Agent 协议前缀兼容表。
 *
 * 后端统一 delegate 重构后使用中性前缀：nodeName `agent:`、reasoning marker `▶ Agent `、
 * publishId `agent-`；历史会话仍可能是旧的 `skill:` / `▶ Skill ` / `skill-`。
 * 这里同时登记两套前缀，识别子 Agent 段时新旧都要接受，避免历史会话断裂。
 */
export const SUB_AGENT_NODE_NAME_PREFIXES = ['agent:', 'skill:'] as const;
export const SUB_AGENT_MARKER_PREFIXES = ['▶ Agent ', '▶ Skill '] as const;
export const SUB_AGENT_PUBLISH_ID_PREFIXES = ['agent-', 'skill-'] as const;

/** 去掉标题/显示名开头的子 Agent 哨兵前缀（兼容历史 `▶ Skill ` 数据）。 */
export function stripSubAgentMarkerPrefix(text?: string | null): string {
  const raw = text ?? '';
  for (const prefix of SUB_AGENT_MARKER_PREFIXES) {
    if (raw.startsWith(prefix)) return raw.slice(prefix.length);
  }
  return raw;
}

/**
 * 剥离子 Agent LLM 首帧 reasoning 哨兵行（`<marker><id>: <name>\n<body>`）。
 * 同时接受旧 `▶ Skill ` 与新 `▶ Agent `；无已知前缀时按首行剥离（保持既有行为）。
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
