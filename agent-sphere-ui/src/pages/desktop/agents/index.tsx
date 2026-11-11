import {
  App,
  Button,
  Card,
  Empty,
  Input,
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
    <div style={{ maxWidth: 1280, margin: '32px auto', padding: 24 }}>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <DesktopPageNav />
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
                width: 180,
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
                width: 220,
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
                render: (_value, agent) => (
                  <Space.Compact style={{ width: '100%' }}>
                    <Input
                      value={profileOf(agent).workspace}
                      placeholder="D:\\projects\\demo"
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
                title: tw('agents.model', 'Model (optional)'),
                width: 180,
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
                width: 160,
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
                width: 150,
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
                width: 170,
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
                width: 90,
                render: (_value, agent) => (
                  <Switch
                    checked={profileOf(agent).enabled}
                    onChange={(checked) => patch(agent, { enabled: checked })}
                  />
                ),
              },
              {
                title: '',
                width: 90,
                render: (_value, agent) => (
                  <Button type="primary" onClick={() => void save(agent)}>
                    {tw('agents.save', 'Save')}
                  </Button>
                ),
              },
            ]}
          />
        </Card>
      </Space>
    </div>
  );
}
