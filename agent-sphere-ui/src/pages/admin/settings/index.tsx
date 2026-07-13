import { useIntl } from '@umijs/max';
import { App, Button, Collapse, Form, Input, Modal, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { useCan } from '@/hooks/usePermission';
import { agentApi } from '@/services/agentSphere/api';
import { useStyles } from './style';

interface ConfigItem {
  configGroup: string;
  configKey: string;
  configValue: string;
  isSecret: boolean;
  description: string;
}

const GROUP_LABELS: Record<string, string> = {
  security: 'pages.admin.settings.group.security',
  chrome: 'pages.admin.settings.group.chrome',
  'web-read': 'pages.admin.settings.group.web-read',
  'rate-limit': 'pages.admin.settings.group.rate-limit',
};

export default function AdminSettings() {
  const intl = useIntl();
  const { styles } = useStyles();
  const { message, modal } = App.useApp();
  const [configs, setConfigs] = useState<ConfigItem[]>([]);
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editingConfig, setEditingConfig] = useState<ConfigItem | null>(null);
  const [editValue, setEditValue] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const canUpdate = useCan('admin:settings:update');
  const canRegenerate = useCan('admin:settings:regenerate-aes');

  const loadConfigs = () => {
    agentApi.admin
      .listConfigs()
      .then((data) => setConfigs(data))
      .catch(() => {});
  };

  useEffect(() => {
    loadConfigs();
  }, []);

  const handleSave = async () => {
    if (!editingConfig) return;
    setSubmitting(true);
    try {
      await agentApi.admin.updateConfig(editingConfig.configKey, editValue);
      message.success(intl.formatMessage({ id: 'pages.save.success' }));
      setEditModalOpen(false);
      loadConfigs();
    } catch {
      message.error(intl.formatMessage({ id: 'pages.chat.saveFailed' }));
    } finally {
      setSubmitting(false);
    }
  };

  const handleRegenerate = () => {
    modal.confirm({
      title: intl.formatMessage({
        id: 'pages.admin.settings.regenerate.confirm',
      }),
      okText: intl.formatMessage({ id: 'pages.save' }),
      cancelText: intl.formatMessage({ id: 'pages.cancel' }),
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const res = await agentApi.admin.regenerateAesKey();
          message.success(
            res.message ||
              intl.formatMessage({
                id: 'pages.admin.settings.regenerate.success',
              }),
          );
          loadConfigs();
        } catch {
          message.error(intl.formatMessage({ id: 'pages.chat.saveFailed' }));
        }
      },
    });
  };

  const groupedConfigs = configs.reduce<Record<string, ConfigItem[]>>(
    (acc, c) => {
      const group = c.configGroup || 'other';
      if (!acc[group]) acc[group] = [];
      acc[group].push(c);
      return acc;
    },
    {},
  );

  const items = Object.entries(groupedConfigs).map(([group, items]) => ({
    key: group,
    label: intl.formatMessage({ id: GROUP_LABELS[group] || group }),
    children: items.map((config) => (
      <div key={config.configKey} className={styles.configItem}>
        <div className={styles.configLabel}>
          <div className={styles.configName}>
            {config.configKey}
            {config.isSecret && (
              <Tag color="red" style={{ marginLeft: 8 }}>
                Secret
              </Tag>
            )}
          </div>
          <div className={styles.configDesc}>{config.description}</div>
        </div>
        <div className={styles.configValue}>
          {config.configKey === 'crypto.aes-key' ? (
            <span style={{ color: 'rgba(0,0,0,0.25)' }}>
              {config.configValue || '(未设置)'}
            </span>
          ) : (
            config.configValue || (
              <span style={{ color: 'rgba(0,0,0,0.25)' }}>(empty)</span>
            )
          )}
        </div>
        {canUpdate && (
          <Button
            type="link"
            onClick={() => {
              setEditingConfig(config);
              setEditValue('');
              setEditModalOpen(true);
            }}
          >
            {intl.formatMessage({ id: 'pages.table.edit' })}
          </Button>
        )}
      </div>
    )),
    extra:
      group === 'security' && canRegenerate ? (
        <Button
          className={styles.dangerBtn}
          size="small"
          onClick={handleRegenerate}
        >
          {intl.formatMessage({ id: 'pages.admin.settings.regenerate.btn' })}
        </Button>
      ) : null,
  }));

  return (
    <>
      <Collapse defaultActiveKey={Object.keys(groupedConfigs)} items={items} />
      <Modal
        title={intl.formatMessage({ id: 'pages.table.edit' })}
        open={editModalOpen}
        onOk={handleSave}
        onCancel={() => setEditModalOpen(false)}
        confirmLoading={submitting}
      >
        <Form layout="vertical">
          <Form.Item
            label={intl.formatMessage({
              id: 'pages.admin.settings.edit.label',
            })}
          >
            <Input
              placeholder={
                editingConfig?.isSecret
                  ? intl.formatMessage({
                      id: 'pages.admin.settings.edit.secret.placeholder',
                    })
                  : undefined
              }
              value={editValue}
              onChange={(e) => setEditValue(e.target.value)}
              type={editingConfig?.isSecret ? 'password' : 'text'}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
