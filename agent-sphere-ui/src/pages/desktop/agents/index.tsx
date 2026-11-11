import {
  App,
  Button,
  Card,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  type SuperAgent,
  superAgentApi,
} from '@/services/agentSphere/superAgent';
import {
  type LocalRuntime,
  localRuntimeApi,
} from '@/services/agentSphere/localRuntime';
import { dictApi, type SystemDicEntry } from '@/services/agentSphere/dict';
import {
  type DesktopAgentProfile,
  type DesktopBridgeDriver,
  getDesktopBridge,
} from '@/utils/desktopBridge';
import { tw } from '../i18n';
import { DesktopPageNav } from '../DesktopPageNav';

const DEFAULT_PROFILE: Omit<DesktopAgentProfile, 'agentId' | 'agentName'> = {
  driver: 'OPENCODE',
  workspace: '',
  approvalPolicy: 'on-request',
  sandbox: 'workspace-write',
  autoRun: 'mention-only',
  enabled: true,
};

export default function DesktopAgentsPage() {
  const { message } = App.useApp();
  const bridge = getDesktopBridge();
  const [agents, setAgents] = useState<SuperAgent[]>([]);
  const [profiles, setProfiles] = useState<Record<number, DesktopAgentProfile>>(
    {},
  );
  const [drivers, setDrivers] = useState<DesktopBridgeDriver[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [runtimes, setRuntimes] = useState<LocalRuntime[]>([]);
  const [positions, setPositions] = useState<SystemDicEntry[]>([]);
  const [createForm] = Form.useForm();

  const localAgents = useMemo(
    () => agents.filter((agent) => agent.agentType === 'LOCAL'),
    [agents],
  );

  useEffect(() => {
    if (!bridge) return;
    setLoading(true);
    void (async () => {
      try {
        const [list, existing, detected] = await Promise.all([
          superAgentApi.list(),
          bridge.profiles.list(),
          bridge.drivers.detect(),
        ]);
        setAgents(list ?? []);
        setDrivers(detected ?? []);
        const map: Record<number, DesktopAgentProfile> = {};
        for (const profile of existing ?? []) map[profile.agentId] = profile;
        setProfiles(map);
      } catch (reason) {
        message.error(
          reason instanceof Error ? reason.message : String(reason),
        );
      } finally {
        setLoading(false);
      }
    })();
  }, [bridge, message]);

  const profileOf = (agent: SuperAgent): DesktopAgentProfile =>
    profiles[agent.id] ?? {
      ...DEFAULT_PROFILE,
      agentId: agent.id,
      agentName: agent.name,
    };

  const patch = (agent: SuperAgent, values: Partial<DesktopAgentProfile>) => {
    setProfiles((previous) => ({
      ...previous,
      [agent.id]: { ...profileOf(agent), ...values },
    }));
  };

  const pickWorkspace = async (agent: SuperAgent) => {
    if (!bridge) return;
    const path = await bridge.profiles.pickWorkspace();
    if (path) patch(agent, { workspace: path });
  };

  const save = async (agent: SuperAgent) => {
    if (!bridge) return;
    const profile = profileOf(agent);
    if (!profile.workspace.trim()) {
      message.warning(tw('agents.workspaceRequired', 'Choose a workspace'));
      return;
    }
    try {
      const next = await bridge.profiles.save({
        ...profile,
        agentName: agent.name,
        workspace: profile.workspace.trim(),
      });
      const map: Record<number, DesktopAgentProfile> = {};
      for (const row of next) map[row.agentId] = row;
      setProfiles(map);
      message.success(tw('agents.saved', 'Local avatar profile saved'));
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : String(reason));
    }
  };

  const reloadAgents = async () => {
    try {
      const list = await superAgentApi.list();
      setAgents(list ?? []);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : String(reason));
    }
  };

  const openCreate = () => {
    setCreateOpen(true);
  };

  useEffect(() => {
    if (!createOpen || !bridge) return;
    void (async () => {
      try {
        const [list, dictionary, settings] = await Promise.all([
          localRuntimeApi.list(),
          dictApi.listActive(),
          bridge.settings.get(),
        ]);
        setRuntimes(list ?? []);
        setPositions(dictionary ?? []);
        const preferred = (list ?? []).find(
          (item) => item.id === settings.runtimeId,
        );
        if (preferred) {
          createForm.setFieldValue('localRuntimeId', preferred.id);
        } else if ((list ?? []).length === 1) {
          createForm.setFieldValue('localRuntimeId', list[0].id);
        }
      } catch (reason) {
        message.error(
          reason instanceof Error ? reason.message : String(reason),
        );
      }
    })();
  }, [createOpen, createForm, bridge, message]);

  const createLocal = async () => {
    try {
      const values = await createForm.validateFields();
      setCreating(true);
      const created = await superAgentApi.create({
        name: values.name,
        positionCode: values.positionCode,
        agentType: 'LOCAL',
        localRuntimeId: values.localRuntimeId,
      });
      await superAgentApi
        .updateVisibility(created.id, true)
        .catch(() => undefined);
      message.success(tw('agents.created', 'Local avatar created'));
      setCreateOpen(false);
      createForm.resetFields();
      await reloadAgents();
    } catch (reason) {
      if (reason instanceof Error) message.error(reason.message);
    } finally {
      setCreating(false);
    }
  };

  const removeBinding = async (agent: SuperAgent) => {
    if (!bridge) return;
    try {
      const next = await bridge.profiles.remove(agent.id);
      const map: Record<number, DesktopAgentProfile> = {};
      for (const row of next) map[row.agentId] = row;
      setProfiles(map);
      message.success(tw('agents.removed', 'Local binding removed'));
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : String(reason));
    }
  };

  if (!bridge) {
    return (
      <div style={{ maxWidth: 960, margin: '60px auto', padding: 24 }}>
        <Empty
          description={tw(
            'setup.desktopOnly',
            'Open this page in AgentSphere Desktop',
          )}
        />
      </div>
    );
  }

  const driverOptions = ['OPENCODE', 'CODEX'].map((kind) => {
    const status = drivers.find((driver) => driver.kind === kind);
    return {
      value: kind,
      label: `${
        kind === 'OPENCODE'
          ? tw('runtime.opencode', 'opencode Desktop')
          : tw('runtime.codex', 'ChatGPT / Codex Desktop')
      }${
        status ? ` · ${tw(`runtime.state.${status.state}`, status.state)}` : ''
      }`,
    };
  });

  return (
    <div style={{ maxWidth: 1440, margin: '32px auto', padding: 24 }}>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <DesktopPageNav />
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'flex-start',
            gap: 16,
            flexWrap: 'wrap',
          }}
        >
          <div>
            <Typography.Title level={3} style={{ marginBottom: 4 }}>
              {tw('agents.title', 'Local avatar bindings')}
            </Typography.Title>
            <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
              {tw(
                'agents.description',
                'Choose the driver, workspace and execution policy for each local avatar.',
              )}
            </Typography.Paragraph>
          </div>
          <Button type="primary" onClick={openCreate}>
            {tw('agents.create', 'New local avatar')}
          </Button>
        </div>

        <Card size="small">
          <Typography.Text type="warning">
            {tw(
              'agents.warning',
              'Files in the workspace may be modified by the local avatar. Confirm the risk before selecting an existing project.',
            )}
          </Typography.Text>
        </Card>

        <Card size="small">
          <Table<SuperAgent>
            rowKey="id"
            size="small"
            loading={loading}
            dataSource={localAgents}
            pagination={false}
            locale={{
              emptyText: tw('agents.noLocal', 'No LOCAL avatars found'),
            }}
            columns={[
              {
                title: tw('agents.agent', 'Avatar'),
                dataIndex: 'name',
                width: 150,
                render: (value: string, agent) => (
                  <Space direction="vertical" size={0}>
                    <Typography.Text strong>{value}</Typography.Text>
                    <Tag color="blue">LOCAL</Tag>
                    <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                      #{agent.id}
                    </Typography.Text>
                  </Space>
                ),
              },
              {
                title: tw('agents.driver', 'Driver'),
                width: 190,
                render: (_value, agent) => (
                  <Select
                    value={profileOf(agent).driver}
                    options={driverOptions}
                    style={{ width: '100%' }}
                    onChange={(value: string) =>
                      patch(agent, {
                        driver: value as DesktopAgentProfile['driver'],
                      })
                    }
                  />
                ),
              },
              {
                title: tw('agents.workspace', 'Workspace'),
                width: 310,
                render: (_value, agent) => (
                  <Space.Compact style={{ width: '100%' }}>
                    <Input
                      value={profileOf(agent).workspace}
                      placeholder="D:\\projects\\demo"
                      style={{ flex: 1, minWidth: 200 }}
                      onChange={(event) =>
                        patch(agent, { workspace: event.target.value })
                      }
                    />
                    <Button onClick={() => void pickWorkspace(agent)}>
                      {tw('agents.pick', 'Choose')}
                    </Button>
                  </Space.Compact>
                ),
              },
              {
                title: tw(
                  'agents.model',
                  'Model (optional, empty = local default)',
                ),
                width: 160,
                render: (_value, agent) => (
                  <Input
                    value={profileOf(agent).model}
                    placeholder="provider/model"
                    onChange={(event) =>
                      patch(agent, { model: event.target.value })
                    }
                  />
                ),
              },
              {
                title: tw('agents.autoRun', 'Execution policy'),
                width: 140,
                render: (_value, agent) => (
                  <Select
                    value={profileOf(agent).autoRun}
                    style={{ width: '100%' }}
                    options={[
                      {
                        value: 'mention-only',
                        label: tw(
                          'agents.autoRun.mention-only',
                          'Only when mentioned',
                        ),
                      },
                      {
                        value: 'confirm',
                        label: tw(
                          'agents.autoRun.confirm',
                          'Confirm every time',
                        ),
                      },
                      {
                        value: 'always',
                        label: tw('agents.autoRun.always', 'Run automatically'),
                      },
                    ]}
                    onChange={(value: string) =>
                      patch(agent, {
                        autoRun: value as DesktopAgentProfile['autoRun'],
                      })
                    }
                  />
                ),
              },
              {
                title: tw('agents.approvalPolicy', 'Tool approvals'),
                width: 130,
                render: (_value, agent) => (
                  <Select
                    value={profileOf(agent).approvalPolicy}
                    style={{ width: '100%' }}
                    options={[
                      {
                        value: 'on-request',
                        label: tw('agents.approval.on-request', 'On request'),
                      },
                      {
                        value: 'untrusted',
                        label: tw('agents.approval.untrusted', 'Strict'),
                      },
                      {
                        value: 'never',
                        label: tw('agents.approval.never', 'Never'),
                      },
                    ]}
                    onChange={(value: string) =>
                      patch(agent, {
                        approvalPolicy:
                          value as DesktopAgentProfile['approvalPolicy'],
                      })
                    }
                  />
                ),
              },
              {
                title: tw('agents.sandbox', 'Sandbox'),
                width: 150,
                render: (_value, agent) => (
                  <Select
                    value={profileOf(agent).sandbox}
                    style={{ width: '100%' }}
                    options={[
                      {
                        value: 'workspace-write',
                        label: tw(
                          'agents.sandbox.workspace-write',
                          'Workspace write',
                        ),
                      },
                      {
                        value: 'read-only',
                        label: tw('agents.sandbox.read-only', 'Read only'),
                      },
                      {
                        value: 'danger-full-access',
                        label: tw(
                          'agents.sandbox.danger-full-access',
                          'Full access',
                        ),
                      },
                    ]}
                    onChange={(value: string) =>
                      patch(agent, {
                        sandbox: value as DesktopAgentProfile['sandbox'],
                      })
                    }
                  />
                ),
              },
              {
                title: tw('agents.enabled', 'Enabled'),
                width: 80,
                render: (_value, agent) => (
                  <Switch
                    checked={profileOf(agent).enabled}
                    onChange={(checked) => patch(agent, { enabled: checked })}
                  />
                ),
              },
              {
                title: '',
                width: 110,
                render: (_value, agent) => (
                  <Space direction="vertical" size={4} style={{ width: '100%' }}>
                    <Button
                      type="primary"
                      block
                      onClick={() => void save(agent)}
                    >
                      {tw('agents.save', 'Save')}
                    </Button>
                    <Popconfirm
                      title={tw(
                        'agents.removeConfirm',
                        'Remove the local binding for this avatar?',
                      )}
                      okText={tw('common.remove', 'Remove')}
                      cancelText={tw('common.cancel', 'Cancel')}
                      onConfirm={() => void removeBinding(agent)}
                    >
                      <Button type="text" danger size="small" block>
                        {tw('agents.removeBinding', 'Remove binding')}
                      </Button>
                    </Popconfirm>
                  </Space>
                ),
              },
            ]}
          />
        </Card>

        <Modal
          open={createOpen}
          title={tw('agents.createTitle', 'New local avatar')}
          okText={tw('agents.create', 'New local avatar')}
          cancelText={tw('common.cancel', 'Cancel')}
          confirmLoading={creating}
          onOk={() => void createLocal()}
          onCancel={() => setCreateOpen(false)}
          destroyOnClose
        >
          <Form form={createForm} layout="vertical" preserve={false}>
            <Form.Item
              name="name"
              label={tw('agents.createName', 'Name')}
              rules={[{ required: true, max: 128 }]}
            >
              <Input maxLength={32} />
            </Form.Item>
            <Form.Item
              name="positionCode"
              label={tw('agents.createPosition', 'Role')}
              rules={[{ required: true }]}
            >
              <Select
                options={positions.map((item) => ({
                  value: item.code,
                  label: item.nameCn ?? item.code,
                }))}
              />
            </Form.Item>
            <Form.Item
              name="localRuntimeId"
              label={tw('agents.createRuntime', 'Local runtime')}
              rules={[{ required: true }]}
            >
              <Select
                options={runtimes.map((item) => ({
                  value: item.id,
                  label: `${item.name} · ${item.runtimeKind}${item.online ? '' : ' (offline)'}`,
                }))}
              />
            </Form.Item>
          </Form>
        </Modal>
      </Space>
    </div>
  );
}
