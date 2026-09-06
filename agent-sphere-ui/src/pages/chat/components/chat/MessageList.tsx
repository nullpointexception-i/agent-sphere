import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  CopyOutlined,
  DownOutlined,
  EyeInvisibleOutlined,
  EyeOutlined,
  RightOutlined,
  RobotOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Bubble } from '@ant-design/x';
import type { BubbleItemType } from '@ant-design/x/es/bubble/interface';
import XMarkdown from '@ant-design/x-markdown';
import {
  Avatar,
  Button,
  Card,
  Input,
  Radio,
  Space,
  Tag,
  Typography,
} from 'antd';
import '@ant-design/x-markdown/es/XMarkdown/index.css';

import hljs from 'highlight.js';
import 'highlight.js/styles/github.css';
import { useIntl } from '@umijs/max';
import { useEffect, useMemo, useRef, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { useStyles } from '../../style';

/** 归一化澄清选项：兼容字符串数组与 {label,value} 对象数组，过滤空项与重复项 */
function normalizeClarificationOptions(
  raw: any,
): { label: string; value: string; description?: string }[] {
  if (!Array.isArray(raw)) return [];
  const seen = new Set<string>();
  const out: { label: string; value: string; description?: string }[] = [];
  for (const item of raw) {
    if (item && typeof item === 'object' && !Array.isArray(item)) {
      const label = String(item.label ?? item.value ?? '').trim();
      const value = String(item.value ?? item.label ?? '').trim();
      if (!label || seen.has(label)) continue;
      seen.add(label);
      out.push({
        label,
        value: value || label,
        description: item.description ? String(item.description) : undefined,
      });
    } else if (typeof item === 'string' && item.trim()) {
      const text = item.trim();
      if (seen.has(text)) continue;
      seen.add(text);
      out.push({ label: text, value: text });
    }
  }
  return out;
}

interface MessageListProps {
  messages: any[];
  collapsedKeys: Set<string>;
  onCollapsedKeysChange: (keys: Set<string>) => void;
  onCancelClarification?: (clarification: any) => void;
  /** 主 Agent live interaction 级 timeline（reasoning/reply/tool 交错）。 */
  mainTimeline?: any[];
  /** 主 Agent 历史 interaction 级 timeline（loadHistory 组装）。 */
  historyTimeline?: any[];
}

const COLLAPSE_THRESHOLD = 300;
const CODE_LIKE_PATTERN =
  /^(package |import |public |private |protected |class |interface |function |const |let |var |def |fun |#include|#define|<!DOCTYPE|<html|<svg|<xml)/m;
const STREAMING_IDLE = { hasNextChunk: false, enableAnimation: false };

/** 主 Agent thinking 气泡 max-height（内部滚动） */
const REASONING_MAX_HEIGHT = 400;
/** 子 Agent（skill）thinking 展开态 max-height（内部滚动） */
const SKILL_REASON_MAX_HEIGHT = 240;

/** 后端子 Agent 哨兵：`▶ Skill <skillId>: <name>`（skillId 为数字） */
const SKILL_REASON_MARKER_RE = /▶\s*Skill\s+(\d+)\s*:\s*([^\n]*)/;

export interface SkillReasonBlock {
  skillId: string;
  name: string;
  content: string;
}

/**
 * 把一条 reasoning 原始文本（可能内嵌 N 段子 Agent thinking）拆成
 * 「主 Agent 段 + 按时间序的子 Agent 段列表」。哨兵本身不保留在正文里。
 * 哨兵可能位于任意字符后（SSE delta 直接拼接，不保证换行），用 exec 顺序扫描切分。
 */
export function splitSkillSegments(raw: string): {
  main: string;
  blocks: SkillReasonBlock[];
} {
  if (!raw) return { main: '', blocks: [] };
  const blocks: SkillReasonBlock[] = [];
  let lastEnd = 0;
  let nextIsContent = false;
  const re = new RegExp(SKILL_REASON_MARKER_RE, 'g');
  let m: RegExpExecArray | null = re.exec(raw);
  let mainText = '';
  while (m !== null) {
    const markerStart = m.index;
    if (!nextIsContent && lastEnd < markerStart) {
      mainText += raw.substring(lastEnd, markerStart);
    } else if (nextIsContent && lastEnd < markerStart) {
      // 上一段技能正文
      blocks[blocks.length - 1].content += raw.substring(lastEnd, markerStart);
    }
    blocks.push({ skillId: m[1], name: m[2] || m[1], content: '' });
    nextIsContent = true;
    lastEnd = re.lastIndex;
    m = re.exec(raw);
  }
  if (nextIsContent && lastEnd < raw.length) {
    blocks[blocks.length - 1].content += raw.substring(lastEnd);
  } else if (!nextIsContent) {
    mainText = raw;
  }
  return { main: mainText.trim(), blocks };
}

/** bottom-anchored 滚动：仅当用户停留在容器底部附近时跟随最新内容。 */
const NEAR_BOTTOM_THRESHOLD = 24;

function isNearBottom(el: HTMLElement | null): boolean {
  if (!el) return false;
  return (
    el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM_THRESHOLD
  );
}

/** 在 rAF 内把滚动容器滚到底（等布局完成后执行）。 */
function scrollToBottom(el: HTMLElement | null) {
  if (!el) return;
  const raf = window.requestAnimationFrame(() => {
    if (el.isConnected) el.scrollTop = el.scrollHeight;
  });
  return raf;
}

/** 主 reasoning 气泡内嵌的子 Agent（skill）thinking：默认折叠，点击展开；展开态最大高度 + 内部滚动。 */
function SkillReasonBlocks({
  blocks,
  markdownComponents,
}: {
  blocks: SkillReasonBlock[];
  markdownComponents: any;
}) {
  const [openKeys, setOpenKeys] = useState<Record<string, boolean>>({});
  const blockRefs = useRef<Record<string, HTMLDivElement | null>>({});
  const prevOpenKeysRef = useRef<Record<string, boolean>>({});
  const contentFingerprint = blocks.map((b) => b.content.length).join('-');

  // 打开的子块：右侧内容增长时，若用户停留在底部则自动滚动到最新；
  // 刚展开的块（上一轮是折叠）强制滚到底，保证最新内容可见。
  useEffect(() => {
    Object.keys(openKeys).forEach((k) => {
      if (!openKeys[k]) return;
      const el = blockRefs.current[k];
      if (!el) return;
      if (isNearBottom(el) || !prevOpenKeysRef.current[k]) {
        scrollToBottom(el);
      }
    });
    prevOpenKeysRef.current = { ...openKeys };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openKeys, contentFingerprint]);

  return (
    <div style={{ marginTop: 8 }}>
      {blocks.map((b, i) => {
        const k = `${b.skillId}-${i}`;
        const isOpen = !!openKeys[k];
        return (
          <div
            key={k}
            style={{
              borderLeft: '2px solid #91caff',
              paddingLeft: 8,
              marginBottom: 6,
            }}
          >
            <button
              type="button"
              onClick={() => setOpenKeys((p) => ({ ...p, [k]: !p[k] }))}
              style={{
                cursor: 'pointer',
                background: 'none',
                border: 'none',
                padding: 0,
                fontSize: 12,
                color: '#1677ff',
                fontStyle: 'normal',
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
              }}
            >
              {isOpen ? <DownOutlined /> : <RightOutlined />}
              <span>⚙️ Skill {b.name}</span>
            </button>
            {isOpen && (
              <div
                ref={(el) => {
                  blockRefs.current[k] = el;
                }}
                style={{
                  maxHeight: SKILL_REASON_MAX_HEIGHT,
                  overflowY: 'auto',
                  marginTop: 4,
                }}
              >
                <XMarkdown
                  streaming={STREAMING_IDLE}
                  components={markdownComponents}
                >
                  {b.content || ' '}
                </XMarkdown>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

/** 主 reasoning 气泡内容：max-height 内部滚动 + 打开时 bottom-anchored 自动跟随最新。 */
function ReasoningContent({
  content,
  markdownComponents,
}: {
  content: string;
  markdownComponents: any;
}) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const split = splitSkillSegments(content);
  const lenKey = `${split.main.length}-${split.blocks.map((b) => b.content.length).join('-')}`;

  // 内容增长且用户停留在容器底部附近时自动滚到底（打开即跟随）
  useEffect(() => {
    if (isNearBottom(scrollRef.current)) {
      scrollToBottom(scrollRef.current);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lenKey]);

  const intl = useIntl();
  return (
    <div
      ref={scrollRef}
      style={{
        fontSize: 13,
        color: '#8c8c8c',
        fontStyle: 'italic',
        borderLeft: '2px solid #d9d9d9',
        paddingLeft: 8,
        margin: '4px 0',
        maxHeight: REASONING_MAX_HEIGHT,
        overflowY: 'auto',
      }}
    >
      <div
        style={{
          fontSize: 11,
          fontWeight: 500,
          color: '#bfbfbf',
          marginBottom: 2,
        }}
      >
        {intl.formatMessage({
          id: 'chat.reasoning.label',
          defaultMessage: 'Reasoning',
        })}
      </div>
      {split.main ? (
        <XMarkdown streaming={STREAMING_IDLE} components={markdownComponents}>
          {split.main}
        </XMarkdown>
      ) : null}
      <SkillReasonBlocks
        blocks={split.blocks}
        markdownComponents={markdownComponents}
      />
    </div>
  );
}

export default function MessageList({
  messages,
  collapsedKeys,
  onCollapsedKeysChange,
  onCancelClarification,
  mainTimeline = [],
  historyTimeline = [],
}: MessageListProps) {
  const intl = useIntl();
  const { styles } = useStyles();

  useEffect(() => {
    const id = 'oc-shuttle-keyframes';
    if (document.getElementById(id)) return;
    const style = document.createElement('style');
    style.id = id;
    style.textContent = `
      @keyframes ocShuttle {
        0%   { transform: translateX(0); }
        20%  { transform: translateX(26px); }
        50%  { transform: translateX(26px); }
        70%  { transform: translateX(0); }
        100% { transform: translateX(0); }
      }
    `;
    document.head.appendChild(style);
    return () => style.remove();
  }, []);

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
  };

  const toggleCollapse = (key: string) => {
    const next = new Set(collapsedKeys);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    onCollapsedKeysChange(next);
  };

  const maybeWrapCode = (content: string): string => {
    if (content.includes('```') || content.length < 20) return content;
    if (content.includes('\n') && CODE_LIKE_PATTERN.test(content)) {
      return '```java\n' + content + '\n```';
    }
    return content;
  };

  const markdownComponents = useMemo(
    () => ({
      code: ({ children, lang, className, inline, block }: any) => {
        const text = Array.isArray(children)
          ? children.join('')
          : String(children || '');
        const language =
          lang ||
          (className?.startsWith('language-') ? className.slice(9) : '');
        const isBlock = block !== undefined ? block : !inline;
        if (!isBlock) {
          return (
            <code
              style={{
                background: '#f0f0f0',
                padding: '2px 6px',
                borderRadius: 4,
                fontSize: '0.9em',
              }}
            >
              {children}
            </code>
          );
        }
        const preStyle: React.CSSProperties = {
          background: '#f6f8fa',
          padding: 16,
          borderRadius: 8,
          fontSize: 13,
          lineHeight: 1.5,
          margin: 0,
          whiteSpace: 'pre',
          overflowX: 'auto',
          overflowY: 'hidden',
          fontFamily:
            'ui-monospace,SFMono-Regular,SF Mono,Menlo,Consolas,monospace',
        };
        try {
          const highlighted =
            language && hljs.getLanguage(language)
              ? hljs.highlight(text, { language: language }).value
              : hljs.highlightAuto(text).value;
          return (
            <pre style={preStyle}>
              <code dangerouslySetInnerHTML={{ __html: highlighted }} />
            </pre>
          );
        } catch {
          return (
            <pre style={preStyle}>
              <code>{children}</code>
            </pre>
          );
        }
      },
      table: ({ children }: any) => (
        <div style={{ overflowX: 'auto', margin: '8px 0' }}>
          <table
            style={{
              borderCollapse: 'collapse',
              width: '100%',
              border: '1px solid #d9d9d9',
            }}
          >
            {children}
          </table>
        </div>
      ),
      th: ({ children }: any) => (
        <th
          style={{
            border: '1px solid #d9d9d9',
            padding: '8px 12px',
            textAlign: 'left',
            background: '#f5f5f5',
            fontWeight: 600,
          }}
        >
          {children}
        </th>
      ),
      td: ({ children }: any) => (
        <td
          style={{
            border: '1px solid #d9d9d9',
            padding: '8px 12px',
            textAlign: 'left',
          }}
        >
          {children}
        </td>
      ),
    }),
    [],
  );

  const roleConfig = useMemo(
    () => ({
      user: {
        placement: 'end' as const,
        avatar: <Avatar icon={<UserOutlined />} />,
        styles: { content: { background: '#e6f7e6', color: '#333' } },
        contentRender: (content: string) => {
          if (!content) return undefined;
          return (
            <div className={styles.markdown}>
              <XMarkdown
                streaming={STREAMING_IDLE}
                components={markdownComponents}
              >
                {maybeWrapCode(content)}
              </XMarkdown>
            </div>
          );
        },
      },
      ai: {
        placement: 'start' as const,
        variant: 'borderless',
        styles: { content: { maxWidth: '100%' } },
        avatar: (
          <Avatar
            icon={<RobotOutlined />}
            style={{ background: '#e6f4ff', color: '#1677ff' }}
          />
        ),
        contentRender: (content: string) => {
          if (!content) return undefined;
          return (
            <div className={styles.markdown}>
              <XMarkdown
                streaming={STREAMING_IDLE}
                components={markdownComponents}
              >
                {content}
              </XMarkdown>
            </div>
          );
        },
      },
      reasoning: () => ({
        placement: 'start' as const,
        avatar: null,
        contentRender: (content: string) => {
          if (!content) return null;
          return (
            <ReasoningContent
              content={content}
              markdownComponents={markdownComponents}
            />
          );
        },
      }),
      'reasoning-collapsed': () => ({
        placement: 'start' as const,
        avatar: null,
      }),
      // interaction 级 timeline 卡（内容已是 JSX）
      timeline: () => ({
        placement: 'start' as const,
        avatar: null,
        styles: { content: { background: 'transparent', maxWidth: '100%' } },
        contentRender: (content: any) => content,
      }),
    }),
    [markdownComponents, intl, styles.markdown],
  );

  const bubbleItems = useMemo<BubbleItemType[]>(() => {
    const visible = messages.filter(
      (m: any) =>
        // 推理已改为 interaction timeline 展示，不再渲染独立 reasoning 气泡
        m.role !== 'reasoning' &&
        ((m.content && m.content !== '{}') ||
          (m.clarifications && m.clarifications.length > 0)),
    );
    const visibleItems = visible
      .map((m: any, idx: number) => {
        const key = (m as any)._reasoningId
          ? `reasoning-${(m as any)._reasoningId}`
          : m.runId
            ? `run-${m.runId}`
            : `msg-${idx}`;
        const isLong = m.content && m.content.length > COLLAPSE_THRESHOLD;
        const isCollapsed = collapsedKeys.has(key);
        const isReasoning = m.role === 'reasoning';
        if (isReasoning) {
          if (isCollapsed) {
            return {
              key,
              role: 'reasoning-collapsed' as const,
              content: (
                <Button
                  type="link"
                  size="small"
                  style={{ padding: 0 }}
                  onClick={() => toggleCollapse(key)}
                >
                  <EyeOutlined />{' '}
                  {intl.formatMessage({
                    id: 'chat.reasoning.show',
                    defaultMessage: 'Show thinking',
                  })}
                </Button>
              ),
            };
          }
          const elapsed = m.ts ? Math.floor((Date.now() - m.ts) / 1000) : 0;
          const split = splitSkillSegments(m.content);
          return {
            key,
            role: 'reasoning',
            content: m.content,
            footer: (
              <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                <span style={{ fontSize: 11, color: '#bfbfbf', minWidth: 36 }}>
                  ⏱️ {elapsed}s
                </span>
                <Button
                  type="link"
                  size="small"
                  style={{ padding: 0 }}
                  onClick={() => toggleCollapse(key)}
                >
                  <EyeInvisibleOutlined />{' '}
                  {intl.formatMessage({
                    id: 'chat.reasoning.hide',
                    defaultMessage: 'Hide thinking',
                  })}
                </Button>
                <Button
                  type="text"
                  size="small"
                  icon={<CopyOutlined />}
                  onClick={() => handleCopy(split.main || m.content)}
                />
              </div>
            ),
          };
        }
        const item: BubbleItemType = {
          key,
          role: m.role,
          content:
            isLong && isCollapsed
              ? m.content.substring(0, COLLAPSE_THRESHOLD) + '...'
              : m.content,
          footer: (
            <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
              {(m as any)._pending && (
                <span
                  style={{
                    position: 'relative',
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 3,
                    width: 32,
                    height: 14,
                  }}
                >
                  {[0, 1, 2, 3].map((i) => (
                    <span
                      key={i}
                      style={{
                        width: 4,
                        height: 4,
                        borderRadius: '50%',
                        background: '#d9d9d9',
                      }}
                    />
                  ))}
                  <span
                    style={{
                      position: 'absolute',
                      top: 1,
                      left: 0,
                      width: 6,
                      height: 6,
                      borderRadius: 2,
                      background: '#1677ff',
                      animation: 'ocShuttle 1.5s ease-in-out infinite',
                    }}
                  />
                </span>
              )}
              {isLong && (
                <Button
                  type="link"
                  size="small"
                  style={{ padding: 0 }}
                  onClick={() => toggleCollapse(key)}
                >
                  {isCollapsed
                    ? intl.formatMessage({
                        id: 'chat.showMore',
                        defaultMessage: 'Show more',
                      })
                    : intl.formatMessage({
                        id: 'chat.showLess',
                        defaultMessage: 'Show less',
                      })}
                </Button>
              )}
              <Button
                type="text"
                size="small"
                icon={<CopyOutlined />}
                onClick={() => handleCopy(m.content)}
              />
            </div>
          ),
        };
        // 回合归属（后端 runId）→ 供按 run 锚定排序（跨回合不再依赖墙钟）
        (item as any)._runId = (m as any).runId ?? null;
        if (m.clarifications && m.clarifications.length > 0) {
          const clarifications = m.clarifications;
          item.contentRender = (_content: string) => (
            <div>
              {_content && (
                <div className={styles.markdown}>
                  <XMarkdown
                    streaming={STREAMING_IDLE}
                    components={markdownComponents}
                  >
                    {_content}
                  </XMarkdown>
                </div>
              )}
              {clarifications.map((c: any) => (
                <ClarificationCard
                  key={c.clarificationId || `${c.runId}-${c.title}`}
                  clarification={c}
                  onRespond={(resp) => {
                    agentApi.sessions
                      .clarify(c.sessionId, c.runId, resp, c.clarificationId)
                      .catch(() => {});
                  }}
                  onCancelClarification={onCancelClarification}
                />
              ))}
            </div>
          );
        }
        return item;
      })
      .filter(Boolean) as BubbleItemType[];

    // 合并 interaction timeline（历史 + live）进单一 Bubble.List 消息流，按时间排序。
    // mainTimeline（live）排在 historyTimeline 前，去重时优先保留 live 版本。
    const timelineItems: BubbleItemType[] = [
      ...mainTimeline.map(
        (entry) =>
          ({
            key: `mt-${entry.key}`,
            role: 'timeline',
            content: (
              <MainTimelineEntry
                entry={entry}
                markdownComponents={markdownComponents}
              />
            ),
            _runId: (entry as any).runId ?? null,
            _seq: (entry as any).seq ?? 0,
          }) as BubbleItemType,
      ),
      ...historyTimeline.map(
        (entry) =>
          ({
            key: `ht-${entry.key}`,
            role: 'timeline',
            content: (
              <MainTimelineEntry
                entry={entry}
                markdownComponents={markdownComponents}
              />
            ),
            _runId: (entry as any).runId ?? null,
            _seq: (entry as any).seq ?? 0,
          }) as BubbleItemType,
      ),
    ];

    const toTs = (v: any): number => {
      if (typeof v === 'number') return v;
      if (typeof v !== 'string' || !v) return 0;
      // 兼容后端 `YYYY-MM-DD HH:mm:ss[.ffffff]`（非 ISO，new Date 会 NaN）与 ISO 格式
      const iso = v.replace(' ', 'T');
      const ts = new Date(iso).getTime();
      if (Number.isFinite(ts)) return ts;
      // 兜底：手动解析
      const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})/.exec(v);
      if (!m) return 0;
      const [, y, mo, d, h, mi, s] = m.map(Number);
      return new Date(y, mo - 1, d, h, mi, s).getTime();
    };

    const orderOf = (item: any): number => {
      const m = item as any;
      if (m.role === 'timeline') {
        const t = m.content && m.content.props && m.content.props.entry;
        if (t && t.createdAt) return toTs(t.createdAt);
        if (t && t.ts) return toTs(t.ts);
        return 0;
      }
      return toTs(m.ts);
    };

    // 回合（run）锚定排序：跨回合先后以“后端 runId 升序”为准（后端单调），
    // 只有同一回合内部才用墙钟/createdAt 微排序——杜绝“新用户消息跑到上一回合回复之上”。
    const allItems = [...visibleItems, ...timelineItems];

    // 回合级去重：同一 run 的同类条目（按 role）只保留一份。
    // key 带 role：用户气泡与 AI（澄清/错误）气泡不再碰撞——否则澄清卡会被当成同 run 重复项误丢。
    // timeline 卡按 (runId, seq)；user 重复（live + 历史重建）仍去重。
    const dedupKeys = new Set<string>();
    const dedupedItems = allItems.filter((it) => {
      const rid = (it as any)._runId;
      if (rid == null) return true;
      const role = (it as any).role;
      const key =
        role === 'timeline'
          ? `t:${Number(rid)}:${(it as any)._seq ?? 0}`
          : `m:${Number(rid)}:${role}`;
      if (dedupKeys.has(key)) return false;
      dedupKeys.add(key);
      return true;
    });

    const knownRunIds = new Set<number>();
    for (const it of dedupedItems) {
      const rid = (it as any)._runId;
      if (rid != null) knownRunIds.add(Number(rid));
    }
    const runIndex = new Map<number, number>();
    [...knownRunIds]
      .sort((a, b) => a - b)
      .forEach((id, i) => {
        runIndex.set(id, i);
      });
    const runOrdinalOf = (item: any): number => {
      const rid = (item as any)._runId;
      if (rid == null) return Number.MAX_SAFE_INTEGER; // 未落库/未知 run → 视为当前最新，排末尾
      return runIndex.get(Number(rid)) ?? Number.MAX_SAFE_INTEGER;
    };

    const combined = dedupedItems
      .sort(
        (a, b) => runOrdinalOf(a) - runOrdinalOf(b) || orderOf(a) - orderOf(b),
      )
      .map(
        (item: any) =>
          ({
            ...item,
            _order: orderOf(item),
          }) as BubbleItemType,
      );

    return combined;
  }, [
    messages,
    collapsedKeys,
    intl,
    historyTimeline,
    mainTimeline,
    markdownComponents,
  ]);

  return (
    <>
      <Bubble.List
        items={bubbleItems}
        role={roleConfig}
        styles={{
          root: {
            maxWidth: 940,
            // 单一滚动容器（.messages）接手：列表高度随内容自然撑开，不做内部滚动
            flex: '0 0 auto',
            overflow: 'visible',
          },
        }}
      />
    </>
  );
}

/** 待渲染条目渲染前，先定义主 Agent interaction 单条组件。 */
export function MainTimelineEntry({
  entry,
  markdownComponents,
}: {
  entry: any;
  markdownComponents: any;
}) {
  const [reasonOpen, setReasonOpen] = useState<boolean>(true);
  return (
    <div>
      {entry.reason && entry.reason.trim() ? (
        <div
          style={{
            borderLeft: '2px solid #d9d9d9',
            paddingLeft: 8,
            fontSize: 13,
            color: '#8c8c8c',
            fontStyle: 'italic',
            margin: '2px 0',
          }}
        >
          <button
            type="button"
            onClick={() => setReasonOpen((o) => !o)}
            style={{
              cursor: 'pointer',
              background: 'none',
              border: 'none',
              padding: 0,
              fontSize: 12,
              color: '#1677ff',
              fontWeight: 500,
            }}
          >
            {reasonOpen ? '▾' : '▸'} Model Reason
          </button>
          {reasonOpen && (
            <div style={{ marginTop: 4, maxHeight: 240, overflowY: 'auto' }}>
              <XMarkdown
                streaming={STREAMING_IDLE}
                components={markdownComponents}
              >
                {entry.reason}
              </XMarkdown>
            </div>
          )}
        </div>
      ) : null}

      {entry.tools && entry.tools.length > 0 ? (
        <div style={{ margin: '2px 0' }}>
          {entry.tools.map((t: any) => (
            <div
              key={t.callId}
              style={{
                border: '1px solid #f0f0f0',
                borderRadius: 4,
                padding: '4px 8px',
                margin: '3px 0',
                background: '#fff',
                fontSize: 12,
                color: '#595959',
                display: 'flex',
                alignItems: 'center',
                gap: 6,
              }}
            >
              <span>🛠️</span>
              <span>{t.name}</span>
              <span style={{ marginLeft: 'auto', color: '#bfbfbf' }}>
                {t.status || ''}
              </span>
            </div>
          ))}
        </div>
      ) : null}

      {entry.reply ? (
        <div style={{ marginTop: 2 }}>
          <XMarkdown streaming={STREAMING_IDLE} components={markdownComponents}>
            {entry.reply}
          </XMarkdown>
        </div>
      ) : null}
    </div>
  );
}

function ClarificationCard({
  clarification,
  onRespond,
  onCancelClarification,
}: {
  clarification: any;
  onRespond: (response: string) => void;
  onCancelClarification?: (clarification: any) => void;
}) {
  const [optimisticValue, setOptimisticValue] = useState<string | null>(null);
  const [inputVal, setInputVal] = useState('');
  const intl = useIntl();

  const options = useMemo(
    () => normalizeClarificationOptions(clarification.options),
    [clarification.options],
  );

  const isResolved =
    clarification.status === 'responded' || optimisticValue !== null;
  const userValue = optimisticValue ?? clarification.userResponse;

  // Derive display label for the responded value
  const resolvedLabel = useMemo(() => {
    if (!userValue) return '';
    if (userValue === '__cancel__') {
      return (
        '❌ ' +
        intl.formatMessage({
          id: 'pages.chat.clarify.cancelled',
          defaultMessage: 'Cancelled',
        })
      );
    }
    if (clarification.type === 'choice' && options.length) {
      const opt = options.find(
        (o) => o.value === userValue || o.label === userValue,
      );
      return opt?.label || userValue;
    }
    if (clarification.type === 'confirm') {
      return userValue === 'confirmed'
        ? '✅ ' +
            intl.formatMessage({
              id: 'pages.chat.clarify.confirmed',
              defaultMessage: 'Confirmed',
            })
        : '❌ ' +
            intl.formatMessage({
              id: 'pages.chat.clarify.cancelled',
              defaultMessage: 'Cancelled',
            });
    }
    return userValue;
  }, [userValue, clarification.type, clarification.options, intl]);

  if (isResolved) {
    return (
      <Card
        size="small"
        style={{
          margin: '8px 0',
          background: '#f6ffed',
          borderLeft: '3px solid #52c41a',
        }}
      >
        <Space orientation="vertical" style={{ width: '100%' }}>
          <Space>
            <CheckCircleOutlined style={{ color: '#52c41a' }} />
            <Typography.Text strong>{clarification.title}</Typography.Text>
          </Space>
          {resolvedLabel && (
            <Typography.Text style={{ marginLeft: 22, color: '#555' }}>
              {resolvedLabel}
            </Typography.Text>
          )}
        </Space>
      </Card>
    );
  }

  const handleAction = (value: string) => {
    setOptimisticValue(value);
    onRespond(value);
  };

  const handleCancel = () => {
    setOptimisticValue('__cancel__');
    onCancelClarification?.(clarification);
  };

  if (clarification.type === 'choice' && options.length) {
    return (
      <Card
        size="small"
        title={clarification.title}
        style={{ margin: '8px 0', borderLeft: '3px solid #1677ff' }}
      >
        <Radio.Group
          onChange={(e) => handleAction(e.target.value)}
          value={undefined}
        >
          <Space orientation="vertical" style={{ width: '100%' }}>
            {options.map((opt, i) => (
              <Radio.Button
                key={`${opt.value}-${i}`}
                value={opt.value}
                style={{
                  width: '100%',
                  textAlign: 'left',
                  height: 'auto',
                  whiteSpace: 'normal',
                  padding: '8px 16px',
                }}
              >
                <div>
                  <div style={{ fontWeight: 500 }}>{opt.label}</div>
                  {opt.description && (
                    <div
                      style={{ fontSize: 12, color: '#8c8c8c', marginTop: 2 }}
                    >
                      {opt.description}
                    </div>
                  )}
                </div>
              </Radio.Button>
            ))}
          </Space>
        </Radio.Group>
        <div style={{ marginTop: 8 }}>
          <Button danger icon={<CloseCircleOutlined />} onClick={handleCancel}>
            {intl.formatMessage({
              id: 'pages.chat.clarify.cancel',
              defaultMessage: 'Cancel',
            })}
          </Button>
        </div>
      </Card>
    );
  }

  if (clarification.type === 'input') {
    return (
      <Card
        size="small"
        title={clarification.title}
        style={{ margin: '8px 0', borderLeft: '3px solid #1677ff' }}
      >
        <Space.Compact style={{ width: '100%' }}>
          <Input
            value={inputVal}
            onChange={(e) => setInputVal(e.target.value)}
            placeholder={intl.formatMessage({
              id: 'pages.chat.clarify.inputPlaceholder',
              defaultMessage: 'Type your response...',
            })}
            onPressEnter={() =>
              inputVal.trim() && handleAction(inputVal.trim())
            }
          />
          <Button
            type="primary"
            disabled={!inputVal.trim()}
            onClick={() => inputVal.trim() && handleAction(inputVal.trim())}
          >
            {intl.formatMessage({ id: 'pages.save', defaultMessage: 'Submit' })}
          </Button>
          <Button danger icon={<CloseCircleOutlined />} onClick={handleCancel}>
            {intl.formatMessage({
              id: 'pages.chat.clarify.cancel',
              defaultMessage: 'Cancel',
            })}
          </Button>
        </Space.Compact>
      </Card>
    );
  }

  return (
    <Card
      size="small"
      title={clarification.title}
      style={{ margin: '8px 0', borderLeft: '3px solid #1677ff' }}
    >
      <Space>
        <Button
          type="primary"
          icon={<CheckCircleOutlined />}
          onClick={() => handleAction('confirmed')}
        >
          {intl.formatMessage({
            id: 'pages.chat.clarify.confirm',
            defaultMessage: 'Confirm',
          })}
        </Button>
        <Button danger icon={<CloseCircleOutlined />} onClick={handleCancel}>
          {intl.formatMessage({
            id: 'pages.chat.clarify.cancel',
            defaultMessage: 'Cancel',
          })}
        </Button>
      </Space>
    </Card>
  );
}
