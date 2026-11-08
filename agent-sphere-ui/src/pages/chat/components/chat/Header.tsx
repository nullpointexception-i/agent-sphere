import {
  CheckOutlined,
  DownOutlined,
  ToolOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { App, Button, Dropdown, Tooltip } from 'antd';
import { useMemo } from 'react';
import { useStyles } from '../../style';

interface HeaderProps {
  currentSession: any;
  instances: any[];
  selectedModelRouteId?: number;
  onModelRouteChange: (id: number | undefined) => void;
  modelRoutes: any[];
  /** Capability view for /runtime/{sid}/reasoning; null means not loaded or unsupported. */
  reasoningCapability?: any;
  reasoningEffort?: string;
  onReasoningChange: (level: string) => void;
  sseConnected: boolean;
  sessionPanelOpen: boolean;
  onTogglePanel: () => void;
  /** Training mode has no session panel; hide the toggle entirely. */
  hideSessionPanel?: boolean;
}

export default function Header({
  currentSession,
  instances,
  selectedModelRouteId,
  onModelRouteChange,
  modelRoutes,
  reasoningCapability,
  reasoningEffort,
  onReasoningChange,
  sseConnected,
  sessionPanelOpen,
  onTogglePanel,
  hideSessionPanel = false,
}: HeaderProps) {
  const intl = useIntl();
  const { message } = App.useApp();
  const { styles } = useStyles();

  const currentInstanceName = useMemo(() => {
    if (!currentSession?.agentInstanceId) return '';
    const inst = instances.find(
      (i: any) => i.id === currentSession.agentInstanceId,
    );
    return inst?.name || '';
  }, [currentSession?.agentInstanceId, instances]);

  const selectedModelName = useMemo(() => {
    if (!selectedModelRouteId) return '';
    const r = modelRoutes.find((r: any) => r.id === selectedModelRouteId);
    return r
      ? r.providerName
        ? `${r.providerName} / ${r.modelName}`
        : r.modelName
      : '';
  }, [selectedModelRouteId, modelRoutes]);

  const modelMenuItems = useMemo(() => {
    const sorted = modelRoutes
      .slice()
      .sort(
        (a: any, b: any) =>
          (a.providerName || '').localeCompare(b.providerName || '') ||
          (a.modelName || '').localeCompare(b.modelName || ''),
      );

    const noKeyMsg = intl.formatMessage({
      id: 'pages.instances.routeNoApiKey',
      defaultMessage: 'The provider for this route has no API key configured',
    });
    const items: any[] = [];
    let lastProvider = '';
    for (const r of sorted) {
      const provider = r.providerName || '';
      if (provider && provider !== lastProvider) {
        items.push({
          key: `group_${provider}`,
          label: (
            <span style={{ fontWeight: 600, fontSize: 12, color: '#999' }}>
              {provider}
            </span>
          ),
          disabled: true,
        });
        lastProvider = provider;
      }
      const noApiKey = r.apiKeyConfigured === false;
      items.push({
        key: String(r.id),
        label: (
          <span
            title={noApiKey ? noKeyMsg : undefined}
            style={
              noApiKey ? { color: '#bfbfbf', cursor: 'not-allowed' } : undefined
            }
          >
            {provider ? `    ${r.modelName}` : r.modelName}
          </span>
        ),
        icon: selectedModelRouteId === r.id ? <CheckOutlined /> : undefined,
        onClick: () => {
          if (noApiKey) {
            message.warning(noKeyMsg);
            return;
          }
          onModelRouteChange(r.id);
        },
      });
    }
    return items;
  }, [modelRoutes, selectedModelRouteId, onModelRouteChange, intl, message]);

  const reasoningSupported = Boolean(reasoningCapability?.supported);
  const reasoningLocked = Boolean(reasoningCapability?.running);

  const levelLabel = (value: string) =>
    intl.formatMessage({
      id: `pages.chat.reasoning.${value}`,
      defaultMessage: value,
    });

  const reasoningMenuItems = useMemo(() => {
    if (!reasoningSupported) return [];
    const effectiveMax = reasoningCapability?.effectiveMaxOutputTokens;
    const infeasibleMsg = intl.formatMessage({
      id: 'pages.chat.reasoning.infeasible',
      defaultMessage: 'Output limit is too low; the answer may be truncated',
    });
    return (reasoningCapability?.levels || []).map((level: any) => {
      const infeasible = level.feasible === false;
      return {
        key: level.value,
        icon: reasoningEffort === level.value ? <CheckOutlined /> : undefined,
        label: (
          <span style={infeasible ? { color: '#d46b08' } : undefined}>
            {levelLabel(level.value)}
            {infeasible && (
              <WarningOutlined style={{ marginLeft: 6, color: '#faad14' }} />
            )}
          </span>
        ),
        onClick: () => {
          if (infeasible) {
            message.warning(
              intl.formatMessage(
                {
                  id: 'pages.chat.reasoning.infeasibleHint',
                  defaultMessage:
                    'This level suggests at least {tokens} output tokens, current limit is {max}',
                },
                {
                  tokens: level.minOutputTokens,
                  max: effectiveMax ?? '-',
                },
              ),
            );
          }
          onReasoningChange(level.value);
        },
      };
    });
  }, [
    reasoningSupported,
    reasoningCapability,
    reasoningEffort,
    onReasoningChange,
    intl,
    message,
  ]);

  return (
    <div className={styles.header}>
      <div style={{ overflow: 'hidden', flex: 1, minWidth: 0 }}>
        <div
          style={{
            fontSize: 11,
            color: '#8c8c8c',
            lineHeight: '16px',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {currentInstanceName}
        </div>
        <strong style={{ lineHeight: '22px' }}>{currentSession.title}</strong>
      </div>
      <Dropdown
        menu={{
          items: modelMenuItems,
          selectedKeys: selectedModelRouteId
            ? [String(selectedModelRouteId)]
            : [],
        }}
        trigger={['click']}
      >
        <Tooltip
          title={intl.formatMessage({
            id: 'pages.chat.modelPlaceholder',
            defaultMessage: 'Model',
          })}
        >
          <Button
            size="small"
            type="text"
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
              flexShrink: 0,
            }}
          >
            <span
              style={{
                color: selectedModelName ? undefined : '#999',
                fontWeight: 600,
                fontSize: 15,
              }}
            >
              {selectedModelName ||
                intl.formatMessage({
                  id: 'pages.chat.modelPlaceholder',
                  defaultMessage: 'Model',
                })}
            </span>
            <DownOutlined style={{ fontSize: 10 }} />
          </Button>
        </Tooltip>
      </Dropdown>
      {reasoningSupported && (
        <Tooltip
          title={
            reasoningLocked
              ? intl.formatMessage({
                  id: 'pages.chat.reasoning.locked',
                  defaultMessage: 'Reasoning cannot be changed while running',
                })
              : `${intl.formatMessage(
                  {
                    id: 'pages.chat.reasoning.outputLimit',
                    defaultMessage: 'Output limit {tokens} tokens',
                  },
                  {
                    tokens:
                      reasoningCapability?.effectiveMaxOutputTokens ?? '-',
                  },
                )} · ${intl.formatMessage(
                  {
                    id: 'pages.chat.reasoning.reserve',
                    defaultMessage:
                      'About {tokens} tokens reserved for the answer',
                  },
                  {
                    tokens: reasoningCapability?.answerReserveTokens ?? 0,
                  },
                )}`
          }
        >
          <Dropdown
            menu={{
              items: reasoningMenuItems,
              selectedKeys: reasoningEffort ? [reasoningEffort] : [],
            }}
            trigger={['click']}
            disabled={reasoningLocked}
          >
            <Button
              size="small"
              type="text"
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                flexShrink: 0,
              }}
            >
              <span style={{ fontSize: 12, color: '#8c8c8c' }}>
                {intl.formatMessage({
                  id: 'pages.chat.reasoning',
                  defaultMessage: 'Reasoning',
                })}
              </span>
              <span style={{ fontWeight: 600, fontSize: 13 }}>
                {reasoningEffort
                  ? levelLabel(reasoningEffort)
                  : intl.formatMessage({
                      id: 'pages.chat.reasoning.default',
                      defaultMessage: 'Default',
                    })}
              </span>
              <DownOutlined style={{ fontSize: 10 }} />
            </Button>
          </Dropdown>
        </Tooltip>
      )}
      {sseConnected && <span className={styles.statusDot} />}
      <div
        style={{
          marginLeft: 'auto',
          display: 'flex',
          gap: 8,
          alignItems: 'center',
        }}
      >
        {!hideSessionPanel && !sessionPanelOpen && (
          <Button size="small" icon={<ToolOutlined />} onClick={onTogglePanel}>
            {intl.formatMessage({
              id: 'pages.chat.sessionPanel',
              defaultMessage: 'Session Panel',
            })}
          </Button>
        )}
      </div>
    </div>
  );
}
