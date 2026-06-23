import { Button, Dropdown, Tooltip } from 'antd';
import { CheckOutlined, DownOutlined, ToolOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { useMemo } from 'react';
import { useStyles } from '../../style';

interface HeaderProps {
  currentSession: any;
  instances: any[];
  selectedModelRouteId?: number;
  onModelRouteChange: (id: number | undefined) => void;
  modelRoutes: any[];
  sseConnected: boolean;
  sessionPanelOpen: boolean;
  onTogglePanel: () => void;
}

export default function Header({
  currentSession, instances,
  selectedModelRouteId, onModelRouteChange,
  modelRoutes, sseConnected,
  sessionPanelOpen, onTogglePanel,
}: HeaderProps) {
  const intl = useIntl();
  const { styles } = useStyles();

  const currentInstanceName = useMemo(() => {
    if (!currentSession?.agentInstanceId) return '';
    const inst = instances.find((i: any) => i.id === currentSession.agentInstanceId);
    return inst?.name || '';
  }, [currentSession?.agentInstanceId, instances]);

  const selectedModelName = useMemo(() => {
    if (!selectedModelRouteId) return '';
    const r = modelRoutes.find((r: any) => r.id === selectedModelRouteId);
    return r ? (r.providerName ? `${r.providerName} / ${r.modelName}` : r.modelName) : '';
  }, [selectedModelRouteId, modelRoutes]);

  const modelMenuItems = useMemo(() =>
    modelRoutes.map((r: any) => ({
      key: String(r.id),
      label: r.providerName ? `${r.providerName} / ${r.modelName}` : r.modelName,
      icon: selectedModelRouteId === r.id ? <CheckOutlined /> : undefined,
      disabled: r.apiKeyConfigured === false,
      title: r.apiKeyConfigured === false ? intl.formatMessage({ id: 'pages.instances.routeNoApiKey', defaultMessage: 'The provider for this route has no API key configured' }) : undefined,
      onClick: () => onModelRouteChange(r.id),
    })),
    [modelRoutes, selectedModelRouteId, onModelRouteChange],
  );

  return (
    <div className={styles.header}>
      <div style={{ overflow: 'hidden', flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 11, color: '#8c8c8c', lineHeight: '16px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {currentInstanceName}
        </div>
        <strong style={{ lineHeight: '22px' }}>{currentSession.title}</strong>
      </div>
      <Dropdown
        menu={{ items: modelMenuItems, selectedKeys: selectedModelRouteId ? [String(selectedModelRouteId)] : [] }}
        trigger={['click']}
      >
        <Tooltip title={intl.formatMessage({ id: 'pages.chat.modelPlaceholder', defaultMessage: 'Model' })}>
          <Button
            size="small"
            type="text"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 4, flexShrink: 0 }}
          >
            <span style={{
              color: selectedModelName ? undefined : '#999',
              fontWeight: 600,
              fontSize: 15,
            }}>
              {selectedModelName || intl.formatMessage({ id: 'pages.chat.modelPlaceholder', defaultMessage: 'Model' })}
            </span>
            <DownOutlined style={{ fontSize: 10 }} />
          </Button>
        </Tooltip>
      </Dropdown>
      {sseConnected && <span className={styles.statusDot} />}
      <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
        {!sessionPanelOpen && (
          <Button
            size="small"
            icon={<ToolOutlined />}
            onClick={onTogglePanel}
          >
            {intl.formatMessage({ id: 'pages.chat.sessionPanel', defaultMessage: 'Session Panel' })}
          </Button>
        )}
      </div>
    </div>
  );
}
