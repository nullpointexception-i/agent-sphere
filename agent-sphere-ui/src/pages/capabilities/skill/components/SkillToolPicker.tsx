import { DeleteOutlined, PlusOutlined, ToolOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import {
  Button,
  Checkbox,
  Collapse,
  Input,
  Modal,
  Switch,
  Tabs,
  Tag,
  Tooltip,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';

export const WILDCARD_ALL = '*';

export interface ToolOption {
  /** 唯一工具引用：builtin:<internalName> / cli:<capabilityId> / skill:<skillId> / mcp:<id>:<nativeToolName> */
  ref: string;
  label: string;
  sub?: string;
  /** builtin:工具名；cli/skill/mcp:描述等 */
  extra?: string;
  disabled?: boolean;
}

interface Props {
  value?: string[];
  onChange?: (value: string[]) => void;
}

/** 解析 mcp.toolDefinitions（兼容两种结构：直接数组 / {tool:{name}}包裹）。 */
function parseMcpTools(
  toolDefinitions?: string,
): { name: string; description?: string }[] {
  if (!toolDefinitions) return [];
  try {
    const raw = JSON.parse(toolDefinitions);
    const arr = Array.isArray(raw) ? raw : [];
    return arr
      .map((t: any) => {
        const inner = t && typeof t === 'object' && t.tool ? t.tool : t;
        const name = inner?.name;
        if (!name) return null;
        return {
          name,
          ...(inner?.description ? { description: inner.description } : {}),
        };
      })
      .filter(Boolean) as { name: string; description?: string }[];
  } catch {
    return [];
  }
}

export default function SkillToolPicker({ value = [], onChange }: Props) {
  const intl = useIntl();
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState('builtin');
  // 数据缓存（Modal 打开后加载一次）
  const [builtinTools, setBuiltinTools] = useState<ToolOption[]>([]);
  const [cliTools, setCliTools] = useState<ToolOption[]>([]);
  const [skillTools, setSkillTools] = useState<ToolOption[]>([]);
  const [mcpTools, setMcpTools] = useState<ToolOption[]>([]);
  const [loaded, setLoaded] = useState(false);
  // 高级手输
  const [manualInput, setManualInput] = useState('');

  const selected = useMemo(() => new Set(value ?? []), [value]);
  const allowAll = selected.has(WILDCARD_ALL);

  const toggle = (ref: string) => {
    const next = new Set(selected);
    if (next.has(ref)) next.delete(ref);
    else next.add(ref);
    onChange?.(Array.from(next));
  };

  const load = async () => {
    if (loaded) return;
    setLoaded(true);
    const [builtin, cli, skill, mcp] = await Promise.all([
      agentApi.builtin.list().catch(() => []),
      agentApi.cli.list({ page: 1, size: 9999 }).catch(() => ({ records: [] })),
      agentApi.skill
        .list({ page: 1, size: 9999 })
        .catch(() => ({ records: [] })),
      agentApi.mcp.list({ page: 1, size: 9999 }).catch(() => ({ records: [] })),
    ]);
    setBuiltinTools(
      (builtin as any[]).map((b: any) => ({
        ref: `builtin:${b.name}`,
        label: b.displayNameCn || b.displayNameEn || b.name,
        sub: b.name,
        extra: b.description,
      })),
    );
    const cliRecords = cli?.records || [];
    setCliTools(
      cliRecords.map((c: any) => ({
        ref: `cli:${c.id}`,
        label: c.name,
        extra: c.commandTemplate,
      })),
    );
    const skillRecords = skill?.records || [];
    setSkillTools(
      skillRecords.map((s: any) => ({
        ref: `skill:${s.id}`,
        label: s.name,
        extra: s.description,
        disabled: s.status === 'DISABLED',
      })),
    );
    const mcpRecords = mcp?.records || [];
    const mcpItems: ToolOption[] = [];
    for (const m of mcpRecords) {
      const tools = parseMcpTools(m.toolDefinitions);
      if (tools.length === 0) {
        mcpItems.push({
          ref: `mcp-disabled-${m.id}`,
          label: m.name,
          sub: '未发现工具',
          disabled: true,
        });
        continue;
      }
      for (const t of tools) {
        mcpItems.push({
          ref: `mcp:${m.id}:${t.name}`,
          label: `${m.name} / ${t.name}`,
          sub: `mcp:${m.id}:${t.name}`,
          extra: t.description,
        });
      }
    }
    setMcpTools(mcpItems);
  };

  useEffect(() => {
    if (open) {
      void load();
    }
  }, [open]);

  const addManual = () => {
    const ref = manualInput.trim();
    if (!ref) return;
    if (ref !== WILDCARD_ALL && !/^(builtin|cli|skill|mcp):.+/.test(ref)) {
      return;
    }
    const next = new Set(selected);
    next.add(ref);
    onChange?.(Array.from(next));
    setManualInput('');
  };

  const renderList = (options: ToolOption[]) => (
    <div style={{ maxHeight: 360, overflow: 'auto' }}>
      {options.length === 0 ? (
        <div style={{ color: '#999', padding: 16 }}>暂无可用项</div>
      ) : (
        options.map((o) => (
          <div
            key={o.ref}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              padding: '6px 4px',
              borderBottom: '1px solid #f5f5f5',
              opacity: o.disabled ? 0.5 : 1,
            }}
          >
            <Checkbox
              checked={!o.disabled && selected.has(o.ref)}
              disabled={o.disabled}
              onChange={() => !o.disabled && toggle(o.ref)}
            />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                {o.label}
                {o.disabled ? (
                  <Tag color="default">未发现工具或已禁用</Tag>
                ) : (
                  <Tag color="blue">{o.ref}</Tag>
                )}
              </div>
              {o.extra ? (
                <div
                  style={{
                    fontSize: 12,
                    color: '#999',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {o.extra}
                </div>
              ) : null}
            </div>
          </div>
        ))
      )}
    </div>
  );

  const tabItems = [
    {
      key: 'builtin',
      label: '内置工具',
      children: renderList(builtinTools),
    },
    {
      key: 'cli',
      label: 'CLI',
      children: renderList(cliTools),
    },
    {
      key: 'skill',
      label: 'Skill',
      children: renderList(skillTools),
    },
    {
      key: 'mcp',
      label: 'MCP',
      children: renderList(mcpTools),
    },
  ];

  const refLabel = (ref: string): { text: string; color: string } => {
    if (ref === WILDCARD_ALL) return { text: '允许全部 *', color: 'gold' };
    const find = [
      ...builtinTools,
      ...cliTools,
      ...skillTools,
      ...mcpTools,
    ].find((o) => o.ref === ref);
    if (find && !find.disabled) return { text: find.label, color: 'blue' };
    return { text: ref, color: 'default' };
  };

  return (
    <>
      <div
        style={{
          border: '1px solid #d9d9d9',
          borderRadius: 8,
          padding: 8,
          minHeight: 40,
          display: 'flex',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 6,
        }}
      >
        {selected.size === 0 ? (
          <span style={{ color: '#999', fontSize: 13 }}>
            未选择工具（默认不允许调用任何工具）
          </span>
        ) : (
          Array.from(selected).map((ref) => {
            const { text, color } = refLabel(ref);
            return (
              <Tag
                key={ref}
                color={color}
                closable={!allowAll}
                onClose={() => toggle(ref)}
              >
                {text}
              </Tag>
            );
          })
        )}
        <Button
          size="small"
          icon={<ToolOutlined />}
          onClick={() => setOpen(true)}
        >
          选取工具
        </Button>
      </div>
      <Modal
        title="选择允许调用的工具 (allowTools)"
        open={open}
        onCancel={() => setOpen(false)}
        footer={null}
        width={640}
      >
        <div
          style={{
            marginBottom: 12,
            display: 'flex',
            alignItems: 'center',
            gap: 12,
          }}
        >
          <span>允许全部工具 *</span>
          <Switch
            checked={allowAll}
            onChange={(checked) => {
              if (checked) onChange?.(['*']);
              else onChange?.([]);
            }}
          />
          <span style={{ color: '#999', fontSize: 12 }}>
            选择后 Skill 子 Agent
            仅可见/可执行所选工具；未选择则禁止调用任何工具。
          </span>
        </div>
        <Tabs items={tabItems} activeKey={tab} onChange={setTab} />
        <Collapse
          ghost
          items={[
            {
              key: 'manual',
              label: '高级：手动输入引用',
              children: (
                <div style={{ display: 'flex', gap: 8 }}>
                  <Input
                    value={manualInput}
                    placeholder="builtin:<internalName> / cli:<id> / skill:<id> / mcp:<id>:<name> / *"
                    onChange={(e) => setManualInput(e.target.value)}
                    onPressEnter={addManual}
                  />
                  <Button
                    type="primary"
                    icon={<PlusOutlined />}
                    onClick={addManual}
                  >
                    添加
                  </Button>
                </div>
              ),
            },
          ]}
        />
        <div style={{ marginTop: 8, color: '#999', fontSize: 12 }}>
          已选择 {selected.size} 项；可在上方标签点击 × 移除。
        </div>
      </Modal>
    </>
  );
}
