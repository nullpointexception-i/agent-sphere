import { useIntl } from '@umijs/max';
import {
  App,
  Button,
  Checkbox,
  Form,
  Input,
  Modal,
  Space,
  Table,
  Typography,
} from 'antd';
import { useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { Can } from '@/components/Can';

export default function AdminRoles() {
  const intl = useIntl();
  const { message } = App.useApp();
  const [roles, setRoles] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [permModalRole, setPermModalRole] = useState<any>(null);
  const [permTree, setPermTree] = useState<any[]>([]);
  const [checkedPermIds, setCheckedPermIds] = useState<number[]>([]);

  const loadRoles = async (p = page) => {
    setLoading(true);
    try {
      const res = await agentApi.admin.roles.list(p, 10);
      setRoles(res.records || []);
      setTotal(res.total || 0);
    } catch {
      setRoles([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadRoles();
  }, []);

  const handleCreate = async () => {
    setSubmitting(true);
    try {
      const values = await createForm.validateFields();
      await agentApi.admin.roles.create(values);
      message.success(
        intl.formatMessage({
          id: 'pages.save.success',
          defaultMessage: '已保存',
        }),
      );
      setCreateOpen(false);
      createForm.resetFields();
      loadRoles(1);
    } catch {
      message.error(
        intl.formatMessage({
          id: 'pages.chat.saveFailed',
          defaultMessage: '保存失败',
        }),
      );
    } finally {
      setSubmitting(false);
    }
  };

  const handleOpenPermModal = async (role: any) => {
    setPermModalRole(role);
    try {
      const tree = await agentApi.admin.permissions.tree();
      setPermTree(tree || []);
    } catch {
      setPermTree([]);
    }
    try {
      const data = await agentApi.admin.permissions.listByRole(role.id);
      const ids: number[] = [];
      const collect = (items: any[]) => {
        for (const item of items) {
          if (item.children && item.children.length > 0) collect(item.children);
          if (item.assigned) ids.push(item.id);
        }
      };
      collect(data);
      setCheckedPermIds(ids);
    } catch {
      setCheckedPermIds([]);
    }
  };

  const handleSavePerms = async () => {
    if (!permModalRole) return;
    try {
      await agentApi.admin.roles.assignPermissions(
        permModalRole.id,
        checkedPermIds,
      );
      message.success(
        intl.formatMessage({
          id: 'pages.save.success',
          defaultMessage: '已保存',
        }),
      );
      setPermModalRole(null);
    } catch {
      message.error(
        intl.formatMessage({
          id: 'pages.chat.saveFailed',
          defaultMessage: '保存失败',
        }),
      );
    }
  };

  const columns = [
    {
      title: intl.formatMessage({
        id: 'pages.table.name',
        defaultMessage: '名称',
      }),
      dataIndex: 'name',
      key: 'name',
    },
    { title: '编码', dataIndex: 'code', key: 'code' },
    {
      title: intl.formatMessage({
        id: 'pages.table.description',
        defaultMessage: '描述',
      }),
      dataIndex: 'description',
      key: 'description',
    },
    {
      title: intl.formatMessage({
        id: 'pages.table.created',
        defaultMessage: '创建时间',
      }),
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (t: string) => (t ? new Date(t).toLocaleString() : '-'),
    },
    {
      title: intl.formatMessage({
        id: 'pages.table.actions',
        defaultMessage: '操作',
      }),
      key: 'actions',
      render: (_: any, record: any) => (
        <Space>
          <Can code="admin:role:assign">
            <Button
              type="link"
              size="small"
              onClick={() => handleOpenPermModal(record)}
            >
              {intl.formatMessage({
                id: 'pages.admin.roles.assignPermission',
                defaultMessage: '分配权限',
              })}
            </Button>
          </Can>
          <Can code="admin:role:delete">
            <Button
              type="link"
              size="small"
              danger
              onClick={async () => {
                try {
                  await agentApi.admin.roles.delete(record.id);
                  message.success(
                    intl.formatMessage({
                      id: 'pages.document.deleted',
                      defaultMessage: '已删除',
                    }),
                  );
                  loadRoles();
                } catch {
                  message.error(
                    intl.formatMessage({
                      id: 'pages.chat.saveFailed',
                      defaultMessage: '保存失败',
                    }),
                  );
                }
              }}
            >
              {intl.formatMessage({
                id: 'pages.table.delete',
                defaultMessage: '删除',
              })}
            </Button>
          </Can>
        </Space>
      ),
    },
  ];

  const renderPermNode = (node: any, depth: number): React.ReactNode => {
    const children = node.children || [];

    if (children.length === 0) {
      if (node.type !== 'BUTTON') return null;
      return (
        <div key={node.id} style={{ marginBottom: 2, marginLeft: depth * 16 }}>
          <Checkbox
            checked={checkedPermIds.includes(node.id)}
            onChange={(e) => {
              setCheckedPermIds((prev) =>
                e.target.checked ? [...prev, node.id] : prev.filter((k) => k !== node.id),
              );
            }}
          >
            {node.name}
            <Typography.Text type="secondary" style={{ fontSize: 11, marginLeft: 4 }}>{node.code}</Typography.Text>
          </Checkbox>
        </div>
      );
    }

    const childRendered = children
      .map((c: any) => renderPermNode(c, depth + 1))
      .filter(Boolean);

    if (depth === 0) {
      if (childRendered.length === 0) return null;
      return (
        <div key={node.id} style={{ marginBottom: 16 }}>
          <Typography.Text strong style={{ fontSize: 14, display: 'block', marginBottom: 8 }}>
            {node.name}
          </Typography.Text>
          {childRendered}
        </div>
      );
    }

    return (
      <div key={node.id} style={{ marginBottom: 10, marginLeft: 16 }}>
        <Typography.Text strong style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>
          {node.name}
        </Typography.Text>
        {childRendered}
      </div>
    );
  };

  return (
    <>
      <div style={{ marginBottom: 16 }}>
        <Can code="admin:role:create">
          <Button type="primary" onClick={() => setCreateOpen(true)}>
            {intl.formatMessage({
              id: 'pages.admin.roles.create',
              defaultMessage: '新建角色',
            })}
          </Button>
        </Can>
      </div>
      <Table
        rowKey="id"
        dataSource={roles}
        columns={columns}
        loading={loading}
        pagination={{
          current: page,
          total,
          pageSize: 10,
          onChange: (p) => {
            setPage(p);
            loadRoles(p);
          },
          showSizeChanger: false,
        }}
        size="small"
      />
      <Modal
        title={intl.formatMessage({
          id: 'pages.admin.roles.create',
          defaultMessage: '新建角色',
        })}
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => {
          setCreateOpen(false);
          createForm.resetFields();
        }}
        confirmLoading={submitting}
      >
        <Form form={createForm} layout="vertical">
          <Form.Item
            name="name"
            label={intl.formatMessage({
              id: 'pages.form.name',
              defaultMessage: '名称',
            })}
            rules={[{ required: true }]}
          >
            <Input maxLength={50} />
          </Form.Item>
          <Form.Item
            name="code"
            label="编码"
            rules={[
              {
                required: true,
                pattern: /^[A-Z_]+$/,
                message: '仅允许大写字母和下划线',
              },
            ]}
          >
            <Input maxLength={50} />
          </Form.Item>
          <Form.Item
            name="description"
            label={intl.formatMessage({
              id: 'pages.form.description',
              defaultMessage: '描述',
            })}
          >
            <Input.TextArea maxLength={255} />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={intl.formatMessage({
          id: 'pages.admin.roles.assignPermission',
          defaultMessage: '分配权限',
        })}
        open={!!permModalRole}
        onOk={handleSavePerms}
        onCancel={() => setPermModalRole(null)}
        width={560}
      >
        <div style={{ maxHeight: 400, overflow: 'auto' }}>
          {permTree.map((p: any) => renderPermNode(p, 0))}
        </div>
      </Modal>
    </>
  );
}
