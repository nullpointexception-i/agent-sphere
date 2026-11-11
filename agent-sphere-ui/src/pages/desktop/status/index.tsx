import { App, Button, Card, Empty, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  type DesktopBridgeApproval,
  type DesktopBridgeDriver,
  type DesktopBridgeStatus,
  type DesktopBridgeTask,
  getDesktopBridge,
} from '@/utils/desktopBridge';
import { tw } from '../i18n';

const STATE_COLOR: Record<string, string> = {
  ONLINE: 'green',
  CONNECTING: 'blue',
  OFFLINE: 'red',
  PAUSED: 'orange',
  UNCONFIGURED: 'default',
  READY: 'green',
  MISSING: 'default',
  AUTH_REQUIRED: 'orange',
  VERSION_MISMATCH: 'red',
  ERROR: 'red',
};

export default function DesktopStatusPage() {
  const { message } = App.useApp();
  const bridge = getDesktopBridge();
  const [runtime, setRuntime] = useState<DesktopBridgeStatus>();
  const [drivers, setDrivers] = useState<DesktopBridgeDriver[]>([]);
  const [tasks, setTasks] = useState<DesktopBridgeTask[]>([]);
  const [approvals, setApprovals] = useState<DesktopBridgeApproval[]>([]);

  const refresh = useCallback(async () => {
    if (!bridge) return;
    const [status, detected, taskList, approvalList] = await Promise.all([
      bridge.runtime.status(),
      bridge.drivers.detect(),
      bridge.tasks.list(),
      bridge.approvals.list(),
    ]);
    setRuntime(status);
    setDrivers(detected);
    setTasks(taskList);
    setApprovals(approvalList);
  }, [bridge]);

  useEffect(() => {
    if (!bridge) return;
    void refresh();
    const offRuntime = bridge.events.onRuntime((status) => setRuntime(status));
    const offTask = bridge.events.onTask((task) => {
      setTasks((previous) => {
        const next = previous.filter((item) => item.taskId !== task.taskId);
        return [task, ...next];
      });
    });
    const offApproval = bridge.approvals.onRequest((request) => {
      setApprovals((previous) => {
        const next = previous.filter((item) => item.id !== request.id);
        return [request, ...next];
      });
    });
    return () => {
      offRuntime();
      offTask();
      offApproval();
    };
  }, [bridge, refresh]);

  const respond = async (
    id: string,
    decision: 'accept' | 'acceptForSession' | 'decline',
  ) => {
    if (!bridge) return;
    await bridge.approvals.respond(id, decision);
    setApprovals((previous) => previous.filter((item) => item.id !== id));
    message.success(tw('approvals.responded', 'Approval submitted'));
  };

  const cancel = async (taskId: number) => {
    if (!bridge) return;
    await bridge.tasks.cancel(taskId);
    await refresh();
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

  return (
    <div style={{ maxWidth: 1100, margin: '32px auto', padding: 24 }}>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Space style={{ justifyContent: 'space-between', width: '100%' }}>
          <div>
            <Typography.Title level={3} style={{ marginBottom: 4 }}>
              {tw('status.title', 'Local runtime')}
            </Typography.Title>
            <Space>
              <Tag
                color={
                  STATE_COLOR[runtime?.state ?? 'UNCONFIGURED'] ?? 'default'
                }
              >
                {tw(
                  `status.${runtime?.state ?? 'UNCONFIGURED'}`,
                  runtime?.state ?? 'UNCONFIGURED',
                )}
              </Tag>
              <Typography.Text type="secondary">
                {tw('tray.running', 'Running tasks: {count}', {
                  count: runtime?.runningTasks ?? 0,
                })}
              </Typography.Text>
            </Space>
          </div>
          <Space>
            <Button onClick={() => window.location.assign('/desktop/agents')}>
              {tw('agents.title', 'Local avatar bindings')}
            </Button>
            <Button onClick={() => window.location.assign('/desktop/setup')}>
              {tw('settings.title', 'Desktop settings')}
            </Button>
            <Button onClick={() => void refresh()}>
              {tw('status.refresh', 'Refresh')}
            </Button>
          </Space>
        </Space>

        <Card title={tw('runtime.title', 'Local runtimes')} size="small">
          {drivers.length === 0 ? (
            <Empty description={tw('status.none', 'Nothing detected yet')} />
          ) : (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              {drivers.map((driver) => (
                <Space key={driver.kind} align="start">
                  <Tag color={STATE_COLOR[driver.state] ?? 'default'}>
                    {tw(`runtime.state.${driver.state}`, driver.state)}
                  </Tag>
                  <div>
                    <Typography.Text strong>
                      {driver.kind === 'OPENCODE'
                        ? tw('runtime.opencode', 'opencode Desktop')
                        : tw('runtime.codex', 'ChatGPT / Codex Desktop')}
                    </Typography.Text>
                    <div>
                      <Typography.Text type="secondary">
                        {driver.version
                          ? tw('runtime.version', 'Version {version}', {
                              version: driver.version,
                            })
                          : (driver.message ?? tw('common.unknown', 'Unknown'))}
                      </Typography.Text>
                    </div>
                    {driver.path ? (
                      <Typography.Text type="secondary">
                        {tw('runtime.path', 'Path: {path}', {
                          path: driver.path,
                        })}
                      </Typography.Text>
                    ) : null}
                  </div>
                </Space>
              ))}
            </Space>
          )}
        </Card>

        <Card title={tw('tasks.title', 'Tasks')} size="small">
          <Table<DesktopBridgeTask>
            rowKey="taskId"
            size="small"
            pagination={false}
            dataSource={tasks}
            locale={{ emptyText: tw('tasks.empty', 'No tasks yet') }}
            columns={[
              {
                title: tw('tasks.task', 'Task'),
                dataIndex: 'taskId',
                width: 90,
              },
              {
                title: tw('tasks.agent', 'Avatar'),
                dataIndex: 'agentName',
                render: (value: string | undefined) => value || '-',
              },
              {
                title: tw('tasks.thread', 'Thread'),
                dataIndex: 'threadTitle',
                render: (value: string | undefined) => value || '-',
              },
              {
                title: tw('tasks.status', 'Status'),
                dataIndex: 'status',
                width: 150,
                render: (value: string) => (
                  <Tag color={STATE_COLOR[value] ?? 'default'}>
                    {tw(`tasks.state.${value}`, value)}
                  </Tag>
                ),
              },
              {
                title: tw('tasks.error', 'Error'),
                dataIndex: 'error',
                ellipsis: true,
                render: (value: string | undefined) => value || '-',
              },
              {
                title: '',
                width: 90,
                render: (_value, task) =>
                  [
                    'QUEUED',
                    'RECEIVED',
                    'RUNNING',
                    'WAITING_APPROVAL',
                  ].includes(task.status) ? (
                    <Button
                      size="small"
                      danger
                      onClick={() => void cancel(task.taskId)}
                    >
                      {tw('tasks.cancel', 'Cancel')}
                    </Button>
                  ) : null,
              },
            ]}
          />
        </Card>

        <Card title={tw('approvals.title', 'Pending approvals')} size="small">
          {approvals.length === 0 ? (
            <Empty
              description={tw('approvals.empty', 'No pending approvals')}
            />
          ) : (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              {approvals.map((approval) => (
                <Card key={approval.id} size="small">
                  <Space
                    direction="vertical"
                    size={8}
                    style={{ width: '100%' }}
                  >
                    <Typography.Text strong>{approval.title}</Typography.Text>
                    <Typography.Paragraph
                      style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}
                    >
                      {approval.detail}
                    </Typography.Paragraph>
                    <Space>
                      <Button
                        type="primary"
                        onClick={() => void respond(approval.id, 'accept')}
                      >
                        {tw('approvals.allowOnce', 'Allow once')}
                      </Button>
                      <Button
                        onClick={() =>
                          void respond(approval.id, 'acceptForSession')
                        }
                      >
                        {tw('approvals.allowSession', 'Allow for session')}
                      </Button>
                      <Button
                        danger
                        onClick={() => void respond(approval.id, 'decline')}
                      >
                        {tw('approvals.deny', 'Deny')}
                      </Button>
                    </Space>
                  </Space>
                </Card>
              ))}
            </Space>
          )}
        </Card>
      </Space>
    </div>
  );
}
