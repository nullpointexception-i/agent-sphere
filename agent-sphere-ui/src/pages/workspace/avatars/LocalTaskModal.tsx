import { App, Button, Modal, Table, Tag } from 'antd';
import { useEffect, useState } from 'react';
import {
  type DesktopBridgeTask,
  getDesktopBridge,
} from '@/utils/desktopBridge';
import { tw } from '../i18n';

const STATUS_COLOR: Record<string, string> = {
  RUNNING: 'blue',
  WAITING_APPROVAL: 'orange',
  COMPLETED: 'green',
  FAILED: 'red',
  CANCELLED: 'default',
};

interface Props {
  open: boolean;
  agentId?: number;
  agentName?: string;
  onClose: () => void;
}

export function LocalTaskModal({ open, agentId, agentName, onClose }: Props) {
  const bridge = getDesktopBridge();
  const { message } = App.useApp();
  const [tasks, setTasks] = useState<DesktopBridgeTask[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    if (!bridge) return;
    setLoading(true);
    try {
      const list = await bridge.tasks.list();
      setTasks(list ?? []);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) void load();
  }, [open]);

  const rows = tasks.filter(
    (task) => agentId == null || task.agentId == null || task.agentId === agentId,
  );

  const cancel = async (taskId: number) => {
    if (!bridge) return;
    try {
      await bridge.tasks.cancel(taskId);
      await load();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : String(reason));
    }
  };

  return (
    <Modal
      open={open}
      title={`${agentName ?? tw('nav.avatars', 'Avatars')} · ${tw(
        'tasks.title',
        'Tasks',
      )}`}
      footer={null}
      width={900}
      onCancel={onClose}
      destroyOnHidden
    >
      <Button size="small" onClick={() => void load()} style={{ marginBottom: 12 }}>
        {tw('tasks.refresh', 'Refresh')}
      </Button>
      <Table<DesktopBridgeTask>
        rowKey="taskId"
        size="small"
        loading={loading}
        dataSource={rows}
        pagination={{ pageSize: 10, hideOnSinglePage: true }}
        locale={{ emptyText: tw('tasks.empty', 'No tasks yet') }}
        columns={[
          {
            title: 'ID',
            dataIndex: 'taskId',
            width: 80,
          },
          {
            title: tw('tasks.thread', 'Conversation'),
            dataIndex: 'threadTitle',
            render: (value: string | undefined, task) =>
              value ?? `#${task.threadId ?? '-'}`,
          },
          {
            title: tw('tasks.status', 'Status'),
            dataIndex: 'status',
            width: 130,
            render: (value: string) => (
              <Tag color={STATUS_COLOR[value] ?? 'default'}>{value}</Tag>
            ),
          },
          {
            title: tw('tasks.startedAt', 'Started'),
            dataIndex: 'startedAt',
            width: 170,
            render: (value?: number) =>
              value ? new Date(value).toLocaleString() : '-',
          },
          {
            title: tw('tasks.error', 'Error'),
            dataIndex: 'error',
            ellipsis: true,
          },
          {
            title: '',
            width: 80,
            render: (_value, task) =>
              task.status === 'RUNNING' ? (
                <Button
                  size="small"
                  danger
                  type="text"
                  onClick={() => void cancel(task.taskId)}
                >
                  {tw('tasks.cancel', 'Cancel')}
                </Button>
              ) : null,
          },
        ]}
      />
    </Modal>
  );
}
