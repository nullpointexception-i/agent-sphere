import { App, Button, Modal, Select, Tooltip } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';

interface SetModelRouteModalProps {
  open: boolean;
  instance: any | null;
  onClose: () => void;
  onSuccess: () => void;
}

export default function SetModelRouteModal({ open, instance, onClose, onSuccess }: SetModelRouteModalProps) {
  const intl = useIntl();
  const { message } = App.useApp();
  const [routes, setRoutes] = useState<any[]>([]);
  const [selectedRouteId, setSelectedRouteId] = useState<number | undefined>(undefined);
  const [selectOpen, setSelectOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (open && instance) {
      setSelectedRouteId(instance.modelRouteId ?? undefined);
      setSelectOpen(true);
      agentApi.routes.listAll().then(setRoutes).catch(() => {});
    }
  }, [open, instance]);

  const handleRefresh = () => {
    agentApi.routes.listAll().then((data) => { setRoutes(data); setSelectOpen(true); }).catch(() => {});
  };

  return (
    <Modal
      title={intl.formatMessage({ id: 'pages.instances.defaultModelRoute', defaultMessage: 'Default Model Route' })}
      open={open}
      onCancel={() => { onClose(); }}
      onOk={async () => {
        if (!instance) return;
        setSubmitting(true);
        try {
          await agentApi.instances.setModelRoute(instance.id, selectedRouteId ?? null);
          message.success(intl.formatMessage({ id: 'pages.save.success', defaultMessage: 'Saved' }));
          onClose();
          onSuccess();
        } finally {
          setSubmitting(false);
        }
      }}
      confirmLoading={submitting}
    >
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <Select
          allowClear
          style={{ flex: 1 }}
          placeholder={intl.formatMessage({ id: 'pages.instances.selectRoute', defaultMessage: 'Select route' })}
          value={selectedRouteId}
          open={selectOpen}
          onOpenChange={setSelectOpen}
          onChange={(v) => { setSelectedRouteId(v); setSelectOpen(false); }}
        >
          {routes.map((r: any) => (
            <Select.Option key={r.id} value={r.id} disabled={r.apiKeyConfigured === false}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', width: '100%' }}>
                <span>{r.providerName ? `${r.providerName} / ${r.modelName}` : r.modelName} (#{r.id})</span>
                {r.apiKeyConfigured === false && (
                  <Button size="small" type="link" style={{ flexShrink: 0, padding: 0 }} onClick={(e) => { e.stopPropagation(); window.open(`/models?openApiKeys=${r.providerId}`, '_blank'); }}>
                    {intl.formatMessage({ id: 'pages.instances.configureApiKey', defaultMessage: '配置API Key' })}
                  </Button>
                )}
              </div>
            </Select.Option>
          ))}
        </Select>
        <Tooltip title={intl.formatMessage({ id: 'pages.instances.refreshRoutes', defaultMessage: '刷新路由' })}>
          <Button icon={<ReloadOutlined />} onClick={handleRefresh} />
        </Tooltip>
      </div>
    </Modal>
  );
}
