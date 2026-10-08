import { useIntl } from '@umijs/max';
import { App, Button, Collapse, Form, Input, Modal, Tag, Upload } from 'antd';
import { useEffect, useState } from 'react';
import { useCan } from '@/hooks/usePermission';
import { useRecordedTask } from '@/hooks/useRecordedTask';
import { agentApi } from '@/services/agentSphere/api';
import type {
  SessionCleanupRun,
  SkillSyncRun,
} from '@/services/agentSphere/api';
import ResourceTemplateEditor from './resourceTemplate/ResourceTemplateEditor';
import SessionCleanupRunPanel from './sessionCleanup';
import SessionCleanupRunsDrawer from './sessionCleanupRuns';
import SkillSyncRunPanel from './skillSync';
import SkillSyncRunsDrawer from './skillSyncRuns';
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
  plugin: 'pages.admin.settings.group.plugin',
  sso: 'pages.admin.settings.group.sso',
  user: 'pages.admin.settings.group.user',
  llm: 'pages.admin.settings.group.llm',
  session: 'pages.admin.settings.group.session',
  skill: 'pages.admin.settings.group.skill',
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
  const [templateOpen, setTemplateOpen] = useState(false);
  const [runsOpen, setRunsOpen] = useState(false);
  const [cleanupRunId, setCleanupRunId] = useState<number | null>(null);
  const [cleanupSubmitting, setCleanupSubmitting] = useState(false);
  const { record: cleanupRun } = useRecordedTask<SessionCleanupRun>(
    cleanupRunId,
    (id) => agentApi.admin.getSessionCleanupRun(id),
  );
  const [syncRunsOpen, setSyncRunsOpen] = useState(false);
  const [syncRunId, setSyncRunId] = useState<number | null>(null);
  const [syncSubmitting, setSyncSubmitting] = useState(false);
  const { record: syncRun } = useRecordedTask<SkillSyncRun>(syncRunId, (id) =>
    agentApi.admin.getSkillSyncRun(id),
  );

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

  const handleUploadPlugin = async (file: File) => {
    try {
      await agentApi.admin.uploadPlugin(file);
      message.success(
        intl.formatMessage({
          id: 'pages.admin.settings.plugin.upload.success',
          defaultMessage: '上传成功',
        }),
      );
      loadConfigs();
    } catch (e: any) {
      message.error(
        e?.response?.data?.message ||
          intl.formatMessage({
            id: 'pages.chat.saveFailed',
            defaultMessage: '保存失败',
          }),
      );
    }
    return false;
  };

  /**
   * 提交一轮清理：先看有没有正在跑的任务，有就直接接管那条记录（不重复提交），
   * 否则异步提交并打开面板轮询。
   *
   * 清理是异步的（后端立刻返回 RUNNING 记录），所以这里不等待结果 ——
   * 进度与结果都由面板轮询执行记录得到。
   */
  const submitCleanup = async (dryRun: boolean) => {
    setCleanupSubmitting(true);
    try {
      const existing = await findRunningRun();
      if (existing) {
        setCleanupRunId(existing.id);
        message.info(
          intl.formatMessage({
            id: 'pages.admin.settings.cleanup.alreadyRunning',
            defaultMessage: '已有清理在执行，切换到它的进度',
          }),
        );
        return;
      }
      const run = await agentApi.admin.cleanupSessions(dryRun);
      setCleanupRunId(run?.id ?? null);
    } catch {
      message.error(intl.formatMessage({ id: 'pages.chat.saveFailed' }));
    } finally {
      setCleanupSubmitting(false);
    }
  };

  /** 是否已有 RUNNING 的记录（用于禁用按钮 + 接续展示）。 */
  const findRunningRun = async () => {
    const page = await agentApi.admin.listSessionCleanupRuns({
      status: 'RUNNING',
      page: 1,
      size: 1,
    });
    return page?.records?.[0];
  };

  /**
   * 提交一轮 Skill Hub 同步，形状同 {@link submitCleanup}。
   *
   * <p>它是系统级定时任务的手动触发入口（与清理同处一格），所以同样先接管已在跑的那条记录，
   * 避免重复提交 —— 定时轮是 5 分钟一轮，点得再快也不该堆出并发。
   */
  const submitSkillSync = async () => {
    setSyncSubmitting(true);
    try {
      const running = await agentApi.admin.listSkillSyncRuns({
        status: 'RUNNING',
        page: 1,
        size: 1,
      });
      const existing = running?.records?.[0];
      if (existing) {
        setSyncRunId(existing.id);
        message.info(
          intl.formatMessage({
            id: 'pages.admin.settings.skillSync.alreadyRunning',
            defaultMessage: '已有同步在执行，切换到它的进度',
          }),
        );
        return;
      }
      const run = await agentApi.admin.syncSkillNow();
      setSyncRunId(run?.id ?? null);
    } catch {
      message.error(intl.formatMessage({ id: 'pages.chat.saveFailed' }));
    } finally {
      setSyncSubmitting(false);
    }
  };

  const handleDeletePlugin = () => {
    modal.confirm({
      title: intl.formatMessage({
        id: 'pages.admin.settings.plugin.delete.confirm',
        defaultMessage: '确认删除已托管的插件安装包吗？删除后下载入口将隐藏。',
      }),
      okText: intl.formatMessage({ id: 'pages.save' }),
      cancelText: intl.formatMessage({ id: 'pages.cancel' }),
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await agentApi.admin.deletePlugin();
          message.success(
            intl.formatMessage({
              id: 'pages.admin.settings.plugin.delete.success',
              defaultMessage: '已删除',
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
                {intl.formatMessage({
                  id: 'pages.admin.settings.secret',
                  defaultMessage: 'Secret',
                })}
              </Tag>
            )}
          </div>
          <div className={styles.configDesc}>{config.description}</div>
        </div>
        <div className={styles.configValue}>
          {config.configKey === 'crypto.aes-key' ? (
            <span style={{ color: 'rgba(0,0,0,0.25)' }}>
              {config.configValue ||
                intl.formatMessage({
                  id: 'pages.admin.settings.notSet',
                  defaultMessage: '(未设置)',
                })}
            </span>
          ) : (
            config.configValue || (
              <span style={{ color: 'rgba(0,0,0,0.25)' }}>
                {intl.formatMessage({
                  id: 'pages.admin.settings.empty',
                  defaultMessage: '(空)',
                })}
              </span>
            )
          )}
        </div>
        {canUpdate && (
          <Button
            type="link"
            onClick={() => {
              setEditingConfig(config);
              if (config.configKey === 'user.resource-template') {
                setTemplateOpen(true);
                return;
              }
              setEditValue(config.configValue || '');
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
      ) : group === 'session' && canUpdate ? (
        <div style={{ display: 'flex', gap: 8 }}>
          <Button
            size="small"
            loading={cleanupSubmitting}
            onClick={() => submitCleanup(true)}
          >
            {intl.formatMessage({
              id: 'pages.admin.settings.cleanup.preview.btn',
              defaultMessage: '预演清理',
            })}
          </Button>
          <Button
            size="small"
            danger
            loading={cleanupSubmitting}
            onClick={() => submitCleanup(false)}
          >
            {intl.formatMessage({
              id: 'pages.admin.settings.cleanup.real.btn',
              defaultMessage: '立即清理',
            })}
          </Button>
          <Button size="small" onClick={() => setRunsOpen(true)}>
            {intl.formatMessage({
              id: 'pages.admin.settings.cleanup.runs.btn',
              defaultMessage: '执行记录',
            })}
          </Button>
        </div>
      ) : group === 'skill' && canUpdate ? (
        <div style={{ display: 'flex', gap: 8 }}>
          <Button
            size="small"
            loading={syncSubmitting}
            onClick={() => submitSkillSync()}
          >
            {intl.formatMessage({
              id: 'pages.admin.settings.skillSync.trigger.btn',
              defaultMessage: '检查更新',
            })}
          </Button>
          <Button size="small" onClick={() => setSyncRunsOpen(true)}>
            {intl.formatMessage({
              id: 'pages.admin.settings.skillSync.runs.btn',
              defaultMessage: '执行记录',
            })}
          </Button>
        </div>
      ) : group === 'plugin' && canUpdate ? (
        <div style={{ display: 'flex', gap: 8 }}>
          <Upload
            accept=".zip"
            showUploadList={false}
            beforeUpload={(file) => handleUploadPlugin(file)}
          >
            <Button size="small" type="primary">
              {intl.formatMessage({
                id: 'pages.admin.settings.plugin.upload.btn',
                defaultMessage: '上传安装包',
              })}
            </Button>
          </Upload>
          <Button size="small" danger onClick={handleDeletePlugin}>
            {intl.formatMessage({
              id: 'pages.admin.settings.plugin.delete.btn',
              defaultMessage: '删除',
            })}
          </Button>
        </div>
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
      <SessionCleanupRunPanel
        open={cleanupRunId != null}
        run={cleanupRun}
        submitting={cleanupSubmitting}
        onClose={() => setCleanupRunId(null)}
        onConfirm={() => submitCleanup(false)}
      />
      <SessionCleanupRunsDrawer
        open={runsOpen}
        onClose={() => setRunsOpen(false)}
      />
      <SkillSyncRunPanel
        open={syncRunId != null}
        run={syncRun}
        onClose={() => setSyncRunId(null)}
      />
      <SkillSyncRunsDrawer
        open={syncRunsOpen}
        onClose={() => setSyncRunsOpen(false)}
      />
      <ResourceTemplateEditor
        open={templateOpen}
        initialValue={editingConfig?.configValue || ''}
        onClose={() => setTemplateOpen(false)}
        onSaved={async (jsonText) => {
          await agentApi.admin.updateConfig('user.resource-template', jsonText);
          await loadConfigs();
          setTemplateOpen(false);
        }}
      />
    </>
  );
}
