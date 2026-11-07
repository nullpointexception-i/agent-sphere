import { DEFAULT_PAGE_SIZE } from '@/constants/pagination';
import { PERM } from '@/constants/permissions';
import { useIntl } from '@umijs/max';
import {
  App,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
} from 'antd';
import { useEffect, useState } from 'react';
import { Can } from '@/components/Can';
import { agentApi } from '@/services/agentSphere/api';

const PERM_TYPES = ['MENU', 'BUTTON'];

export default function AdminPermissions() {
  const intl = useIntl();
  const { message } = App.useApp();
  const [items, setItems] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [allParents, setAllParents] = useState<any[]>([]);

  const loadItems = async (p = page) => {
    setLoading(true);
    try {
      const res = await agentApi.admin.permissions.list(p, 10);
      setItems(res.records || []);
      setTotal(res.total || 0);
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  };

  const loadParents = async () => {
    try {
      const res = await agentApi.admin.permissions.list(1, 200);
      setAllParents((res.records || []).filter((r: any) => r.type === 'MENU'));
    } catch {
      setAllParents([]);
    }
  };

  useEffect(() => {
    loadItems();
    loadParents();
  }, []);

  const handleOpenEdit = (record?: any) => {
    setEditing(record || null);
    form.setFieldsValue(record || { type: 'BUTTON', sort: 0 });
    setEditOpen(true);
  };

  const handleSave = async () => {
    setSubmitting(true);
    try {
      const values = await form.validateFields();
      if (editing) {
        await agentApi.admin.permissions.update(editing.id, values);
      } else {
        await agentApi.admin.permissions.create(values);
      }
      message.success(
        intl.formatMessage({
          id: 'pages.save.success',
          defaultMessage: 'Saved',
        }),
      );
      setEditOpen(false);
      form.resetFields();
      loadItems(1);
    } catch {
      message.error(
        intl.formatMessage({
          id: 'pages.chat.saveFailed',
          defaultMessage: 'Save failed',
        }),
      );
    } finally {
      setSubmitting(false);
    }
  };

  const columns = [
    {
      title: intl.formatMessage({ id: 'pages.table.id', defaultMessage: 'ID' }),
      dataIndex: 'id',
      key: 'id',
      width: 60,
    },
    {
      title: intl.formatMessage({
        id: 'pages.form.name',
        defaultMessage: 'Name',
      }),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: intl.formatMessage({
        id: 'pages.table.code',
        defaultMessage: 'Code',
      }),
      dataIndex: 'code',
      key: 'code',
    },
    {
      title: intl.formatMessage({
        id: 'pages.table.type',
        defaultMessage: 'Type',
      }),
      dataIndex: 'type',
      key: 'type',
      width: 80,
    },
    {
      title: intl.formatMessage({
        id: 'pages.table.sort',
        defaultMessage: 'Sort',
      }),
      dataIndex: 'sort',
      key: 'sort',
      width: 60,
    },
    {
      title: intl.formatMessage({
        id: 'pages.table.actions',
        defaultMessage: 'Actions',
      }),
      key: 'actions',
      width: 140,
      render: (_: any, record: any) => (
        <Space>
          <Can code={PERM.adminPermissionUpdate}>
            <Button
              type="link"
              size="small"
              onClick={() => handleOpenEdit(record)}
            >
              {intl.formatMessage({
                id: 'pages.table.edit',
                defaultMessage: 'Edit',
              })}
            </Button>
          </Can>
          <Can code={PERM.adminPermissionDelete}>
            <Button
              type="link"
              size="small"
              danger
              onClick={async () => {
                try {
                  await agentApi.admin.permissions.delete(record.id);
                  message.success(
                    intl.formatMessage({
                      id: 'pages.document.deleted',
                      defaultMessage: 'Deleted',
                    }),
                  );
                  loadItems();
                } catch {
                  message.error(
                    intl.formatMessage({
                      id: 'pages.chat.saveFailed',
                      defaultMessage: 'Save failed',
                    }),
                  );
                }
              }}
            >
              {intl.formatMessage({
                id: 'pages.table.delete',
                defaultMessage: 'Delete',
              })}
            </Button>
          </Can>
        </Space>
      ),
    },
  ];

  return (
    <>
      <div style={{ marginBottom: 16 }}>
        <Can code={PERM.adminPermissionCreate}>
          <Button type="primary" onClick={() => handleOpenEdit()}>
            {intl.formatMessage({
              id: 'pages.admin.permissions.create',
              defaultMessage: 'New Permission',
            })}
          </Button>
        </Can>
      </div>
      <Table
        rowKey="id"
        dataSource={items}
        columns={columns}
        loading={loading}
        pagination={{
          current: page,
          total,
          pageSize: DEFAULT_PAGE_SIZE,
          onChange: (p) => {
            setPage(p);
            loadItems(p);
          },
          showSizeChanger: false,
        }}
        size="small"
      />
      <Modal
        title={
          editing
            ? intl.formatMessage({
                id: 'pages.table.edit',
                defaultMessage: 'Edit',
              })
            : intl.formatMessage({
                id: 'pages.admin.permissions.create',
                defaultMessage: 'New Permission',
              })
        }
        open={editOpen}
        onOk={handleSave}
        onCancel={() => {
          setEditOpen(false);
          form.resetFields();
        }}
        confirmLoading={submitting}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label={intl.formatMessage({
              id: 'pages.form.name',
              defaultMessage: 'Name',
            })}
            rules={[{ required: true }]}
          >
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item
            name="code"
            label={intl.formatMessage({
              id: 'pages.form.code',
              defaultMessage: 'Code',
            })}
            rules={[
              { required: true },
              {
                pattern: /^[a-z][a-z0-9:*]*$/,
                message: intl.formatMessage({
                  id: 'pages.admin.permissions.codePattern',
                  defaultMessage: 'Format: module:action',
                }),
              },
            ]}
          >
            <Input maxLength={100} disabled={!!editing} />
          </Form.Item>
          <Form.Item
            name="type"
            label={intl.formatMessage({
              id: 'pages.form.type',
              defaultMessage: 'Type',
            })}
            rules={[{ required: true }]}
          >
            <Select options={PERM_TYPES.map((t) => ({ label: t, value: t }))} />
          </Form.Item>
          <Form.Item
            name="parentId"
            label={intl.formatMessage({
              id: 'pages.admin.permissions.parent',
              defaultMessage: 'Parent',
            })}
          >
            <Select
              allowClear
              placeholder={intl.formatMessage({
                id: 'pages.admin.permissions.parentPlaceholder',
                defaultMessage: 'None (top-level)',
              })}
              options={allParents.map((p: any) => ({
                label: `${p.name} (${p.code})`,
                value: p.id,
              }))}
            />
          </Form.Item>
          <Form.Item
            name="sort"
            label={intl.formatMessage({
              id: 'pages.form.sort',
              defaultMessage: 'Sort',
            })}
          >
            <InputNumber min={0} max={999} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="description"
            label={intl.formatMessage({
              id: 'pages.form.description',
              defaultMessage: 'Description',
            })}
          >
            <Input.TextArea maxLength={255} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
