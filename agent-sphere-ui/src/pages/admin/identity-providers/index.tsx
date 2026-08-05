import { useIntl } from '@umijs/max';
import {
  App,
  Button,
  Form,
  Input,
  Modal,
  Space,
  Switch,
  Table,
  Tag,
} from 'antd';
import { useEffect, useState } from 'react';
import { Can } from '@/components/Can';
import { agentApi } from '@/services/agentSphere/api';

export default function AdminIdentityProviders() {
  const intl = useIntl();
  const { message, modal } = App.useApp();
  const [items, setItems] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [editOpen, setEditOpen] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [testingId, setTestingId] = useState<number | null>(null);

  const t = (id: string, defaultMessage?: string) =>
    intl.formatMessage({ id, defaultMessage });

  const loadItems = async (kw = keyword) => {
    setLoading(true);
    try {
      const res = await agentApi.admin.identityProviders.list(kw);
      setItems(res || []);
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadItems('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleOpenEdit = (record?: any) => {
    setEditing(record || null);
    form.setFieldsValue(
      record
        ? { ...record, clientSecret: undefined }
        : { type: 'OIDC', enabled: true },
    );
    setEditOpen(true);
  };

  const handleSave = async () => {
    setSubmitting(true);
    try {
      const values = await form.validateFields();
      if (editing) {
        const { id } = editing;
        await agentApi.admin.identityProviders.update(id, values);
      } else {
        await agentApi.admin.identityProviders.create(values);
      }
      message.success(t('pages.save.success', '已保存'));
      setEditOpen(false);
      form.resetFields();
      loadItems();
    } catch {
      message.error(t('pages.chat.saveFailed', '保存失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = (record: any) => {
    modal.confirm({
      title: intl.formatMessage(
        { id: 'pages.deleteConfirm.title', defaultMessage: '删除{name}' },
        { name: record.name },
      ),
      okType: 'danger',
      onOk: async () => {
        try {
          await agentApi.admin.identityProviders.delete(record.id);
          message.success(t('pages.document.deleted', '已删除'));
          loadItems();
        } catch {
          message.error(t('pages.chat.saveFailed', '保存失败'));
        }
      },
    });
  };

  const handleSetEnabled = async (record: any, enabled: boolean) => {
    try {
      await agentApi.admin.identityProviders.setEnabled(record.id, enabled);
      message.success(t('pages.save.success', '已保存'));
      loadItems();
    } catch {
      message.error(t('pages.chat.saveFailed', '保存失败'));
    }
  };

  const handleTestConnection = async (id: number) => {
    setTestingId(id);
    try {
      await agentApi.admin.identityProviders.testConnection(id);
      message.success(
        t(
          'pages.admin.identityProviders.testConnection.success',
          '连接测试通过',
        ),
      );
    } catch {
      message.error(
        t(
          'pages.admin.identityProviders.testConnection.failed',
          '连接测试失败',
        ),
      );
    } finally {
      setTestingId(null);
    }
  };

  const columns = [
    {
      title: t('pages.table.id', 'ID'),
      dataIndex: 'id',
      key: 'id',
      width: 60,
    },
    {
      title: t('pages.admin.identityProviders.name', '名称'),
      key: 'name',
      width: 180,
      ellipsis: true,
      render: (_: any, record: any) => (
        <Space size={4} orientation="vertical">
          <span>{record.name}</span>
          <span style={{ color: 'rgba(0,0,0,0.45)', fontSize: 12 }}>
            {record.code}
          </span>
        </Space>
      ),
    },
    {
      title: t('pages.admin.identityProviders.type', '类型'),
      dataIndex: 'type',
      key: 'type',
      width: 90,
      render: (type: string) => <Tag>{type || 'OIDC'}</Tag>,
    },
    {
      title: t('pages.admin.identityProviders.issuer', 'Issuer'),
      dataIndex: 'issuer',
      key: 'issuer',
      width: 260,
      ellipsis: true,
    },
    {
      title: t('pages.admin.identityProviders.enabled', '启用'),
      key: 'enabled',
      width: 80,
      render: (_: any, record: any) => (
        <Can code="admin:identity-provider:update">
          <Switch
            checked={record.enabled}
            onChange={(checked) => handleSetEnabled(record, checked)}
          />
        </Can>
      ),
    },
    {
      title: t('pages.table.created', '创建时间'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
    },
    {
      title: t('pages.table.actions', '操作'),
      key: 'actions',
      width: 180,
      render: (_: any, record: any) => (
        <Space>
          <Can code="admin:identity-provider:read">
            <Button
              type="link"
              size="small"
              loading={testingId === record.id}
              onClick={() => handleTestConnection(record.id)}
            >
              {t('pages.admin.identityProviders.testConnection', '测试连接')}
            </Button>
          </Can>
          <Can code="admin:identity-provider:update">
            <Button
              type="link"
              size="small"
              onClick={() => handleOpenEdit(record)}
            >
              {t('pages.table.edit', '编辑')}
            </Button>
          </Can>
          <Can code="admin:identity-provider:delete">
            <Button
              type="link"
              size="small"
              danger
              onClick={() => handleDelete(record)}
            >
              {t('pages.table.delete', '删除')}
            </Button>
          </Can>
        </Space>
      ),
    },
  ];

  return (
    <>
      <div
        style={{
          marginBottom: 16,
          display: 'flex',
          justifyContent: 'space-between',
        }}
      >
        <Input.Search
          allowClear
          placeholder={t(
            'pages.admin.identityProviders.search.placeholder',
            '搜索名称或标识',
          )}
          style={{ width: 280 }}
          onSearch={(value) => {
            setKeyword(value);
            loadItems(value);
          }}
        />
        <Can code="admin:identity-provider:create">
          <Button type="primary" onClick={() => handleOpenEdit()}>
            {t('pages.admin.identityProviders.create', '新建身份源')}
          </Button>
        </Can>
      </div>
      <Table
        rowKey="id"
        dataSource={items}
        columns={columns}
        loading={loading}
        pagination={false}
        scroll={{ x: 1010 }}
        size="small"
      />
      <Modal
        title={
          editing
            ? t('pages.admin.identityProviders.edit', '编辑身份源')
            : t('pages.admin.identityProviders.create', '新建身份源')
        }
        open={editOpen}
        onOk={handleSave}
        onCancel={() => {
          setEditOpen(false);
          form.resetFields();
        }}
        confirmLoading={submitting}
        width={640}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="code"
            label={t('pages.admin.identityProviders.code', '标识')}
            rules={[
              { required: true },
              {
                pattern: /^[a-z][a-z0-9-]*$/,
                message: t(
                  'pages.admin.permissions.codePattern',
                  '格式: lowercase letters/numbers/-',
                ),
              },
            ]}
          >
            <Input maxLength={64} disabled={!!editing} />
          </Form.Item>
          <Form.Item
            name="name"
            label={t('pages.admin.identityProviders.name', '名称')}
            rules={[{ required: true }]}
          >
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item
            name="issuer"
            label={t('pages.admin.identityProviders.issuer', 'Issuer')}
            rules={[{ required: true }]}
          >
            <Input maxLength={512} />
          </Form.Item>
          <Form.Item
            name="clientId"
            label={t('pages.admin.identityProviders.clientId', 'Client ID')}
            rules={[{ required: true }]}
          >
            <Input maxLength={256} />
          </Form.Item>
          <Form.Item
            name="clientSecret"
            label={t(
              'pages.admin.identityProviders.clientSecret',
              'Client Secret',
            )}
            rules={editing ? [] : [{ required: true }]}
            extra={
              editing
                ? t(
                    'pages.admin.identityProviders.clientSecret.keep',
                    '留空则保留原值',
                  )
                : undefined
            }
          >
            <Input.Password maxLength={1024} autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            name="authorizationEndpoint"
            label={t(
              'pages.admin.identityProviders.authorizationEndpoint',
              'Authorization Endpoint',
            )}
            rules={[{ required: true }]}
          >
            <Input maxLength={512} />
          </Form.Item>
          <Form.Item
            name="tokenEndpoint"
            label={t(
              'pages.admin.identityProviders.tokenEndpoint',
              'Token Endpoint',
            )}
            rules={[{ required: true }]}
          >
            <Input maxLength={512} />
          </Form.Item>
          <Form.Item
            name="jwksUrl"
            label={t('pages.admin.identityProviders.jwksUrl', 'JWKS URL')}
            rules={[{ required: true }]}
          >
            <Input maxLength={512} />
          </Form.Item>
          <Form.Item
            name="scopes"
            label={t('pages.admin.identityProviders.scopes', 'Scopes')}
          >
            <Input maxLength={512} placeholder="openid email profile" />
          </Form.Item>
          <Form.Item
            name="enabled"
            label={t('pages.admin.identityProviders.enabled', '启用')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="remark"
            label={t('pages.admin.identityProviders.remark', '备注')}
          >
            <Input.TextArea maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
