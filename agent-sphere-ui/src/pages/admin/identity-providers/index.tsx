import { PERM } from '@/constants/permissions';
import { useIntl } from '@umijs/max';
import {
  App,
  Button,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
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
  const [roles, setRoles] = useState<any[]>([]);
  const [defaultTemplate, setDefaultTemplate] = useState<string | null>(null);
  const [sampleOpen, setSampleOpen] = useState(false);

  const t = (id: string, defaultMessage?: string) =>
    intl.formatMessage({ id, defaultMessage });

  const sampleJson = useMemo(() => {
    if (!defaultTemplate) return '';
    try {
      return JSON.stringify(JSON.parse(defaultTemplate), null, 2);
    } catch {
      return defaultTemplate;
    }
  }, [defaultTemplate]);

  const handleCopySample = async () => {
    const text = sampleJson || defaultTemplate || '';
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      const ta = document.createElement('textarea');
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
    }
    message.success(t('pages.admin.copy.success', 'Copied'));
  };

  useEffect(() => {
    agentApi.admin.roles
      .listAll()
      .then(setRoles)
      .catch(() => {});
    agentApi.admin.identityProviders
      .getDefaultResourceTemplate()
      .then((res: any) => setDefaultTemplate(res?.template ?? null))
      .catch(() => setDefaultTemplate(null));
  }, []);

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
      message.success(t('pages.save.success', 'Saved'));
      setEditOpen(false);
      form.resetFields();
      loadItems();
    } catch {
      message.error(t('pages.chat.saveFailed', 'Save failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = (record: any) => {
    modal.confirm({
      title: intl.formatMessage(
        { id: 'pages.deleteConfirm.title', defaultMessage: 'Delete {name}' },
        { name: record.name },
      ),
      okType: 'danger',
      onOk: async () => {
        try {
          await agentApi.admin.identityProviders.delete(record.id);
          message.success(t('pages.document.deleted', 'Deleted'));
          loadItems();
        } catch {
          message.error(t('pages.chat.saveFailed', 'Save failed'));
        }
      },
    });
  };

  const handleSetEnabled = async (record: any, enabled: boolean) => {
    try {
      await agentApi.admin.identityProviders.setEnabled(record.id, enabled);
      message.success(t('pages.save.success', 'Saved'));
      loadItems();
    } catch {
      message.error(t('pages.chat.saveFailed', 'Save failed'));
    }
  };

  const handleTestConnection = async (id: number) => {
    setTestingId(id);
    try {
      await agentApi.admin.identityProviders.testConnection(id);
      message.success(
        t(
          'pages.admin.identityProviders.testConnection.success',
          'Connection test passed',
        ),
      );
    } catch {
      message.error(
        t(
          'pages.admin.identityProviders.testConnection.failed',
          'Connection test failed',
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
      title: t('pages.admin.identityProviders.name', 'Name'),
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
      title: t('pages.admin.identityProviders.type', 'Type'),
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
      title: t('pages.admin.identityProviders.enabled', 'Enabled'),
      key: 'enabled',
      width: 80,
      render: (_: any, record: any) => (
        <Can code={PERM.adminIdentityProviderUpdate}>
          <Switch
            checked={record.enabled}
            onChange={(checked) => handleSetEnabled(record, checked)}
          />
        </Can>
      ),
    },
    {
      title: t('pages.table.created', 'Created'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
    },
    {
      title: t('pages.table.actions', 'Actions'),
      key: 'actions',
      width: 180,
      render: (_: any, record: any) => (
        <Space>
          <Can code={PERM.adminIdentityProviderRead}>
            <Button
              type="link"
              size="small"
              loading={testingId === record.id}
              onClick={() => handleTestConnection(record.id)}
            >
              {t(
                'pages.admin.identityProviders.testConnection',
                'Test Connection',
              )}
            </Button>
          </Can>
          <Can code={PERM.adminIdentityProviderUpdate}>
            <Button
              type="link"
              size="small"
              onClick={() => handleOpenEdit(record)}
            >
              {t('pages.table.edit', 'Edit')}
            </Button>
          </Can>
          <Can code={PERM.adminIdentityProviderDelete}>
            <Button
              type="link"
              size="small"
              danger
              onClick={() => handleDelete(record)}
            >
              {t('pages.table.delete', 'Delete')}
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
            'Search name or code',
          )}
          style={{ width: 280 }}
          onSearch={(value) => {
            setKeyword(value);
            loadItems(value);
          }}
        />
        <Can code={PERM.adminIdentityProviderCreate}>
          <Button type="primary" onClick={() => handleOpenEdit()}>
            {t('pages.admin.identityProviders.create', 'New Identity Provider')}
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
            ? t('pages.admin.identityProviders.edit', 'Edit Identity Provider')
            : t('pages.admin.identityProviders.create', 'New Identity Provider')
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
            label={t('pages.admin.identityProviders.code', 'Code')}
            rules={[
              { required: true },
              {
                pattern: /^[a-z][a-z0-9-]*$/,
                message: t(
                  'pages.admin.permissions.codePattern',
                  'Format: module:action',
                ),
              },
            ]}
          >
            <Input maxLength={64} disabled={!!editing} />
          </Form.Item>
          <Form.Item
            name="name"
            label={t('pages.admin.identityProviders.name', 'Name')}
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
                    'Leave empty to keep current value',
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
            label={t('pages.admin.identityProviders.enabled', 'Enabled')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="remark"
            label={t('pages.admin.identityProviders.remark', 'Remark')}
          >
            <Input.TextArea maxLength={500} />
          </Form.Item>
          <Form.Item
            name="defaultRoleId"
            label={t(
              'pages.admin.identityProviders.defaultRole',
              'Default Role',
            )}
            extra={t(
              'pages.admin.identityProviders.defaultRole.extra',
              'Granted to new SSO users (replaces default USER role)',
            )}
          >
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder={t(
                'pages.admin.identityProviders.defaultRole.placeholder',
                'Select default role',
              )}
              options={roles.map((r) => ({
                value: r.id,
                label: r.name ? `${r.name} (${r.code})` : `#${r.id}`,
              }))}
            />
          </Form.Item>
          <Form.Item
            name="resourceTemplate"
            label={
              <Space size={4}>
                <span>
                  {t(
                    'pages.admin.identityProviders.resourceTemplate',
                    'Resource Template (JSON)',
                  )}
                </span>
                <Button
                  type="link"
                  size="small"
                  style={{ padding: 0, height: 'auto' }}
                  onClick={() => setSampleOpen(true)}
                >
                  {t('pages.admin.identityProviders.viewSample', 'View sample')}
                </Button>
              </Space>
            }
            extra={t(
              'pages.admin.identityProviders.resourceTemplate.extra',
              'Empty uses the default template; each user gets their own resource copy on first login',
            )}
          >
            <Input.TextArea
              rows={6}
              maxLength={20000}
              placeholder='[{"type":"model_provider","name":"DeepSeek",...}]'
            />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={t(
          'pages.admin.identityProviders.sample.title',
          'Default Resource Template Sample',
        )}
        open={sampleOpen}
        onCancel={() => setSampleOpen(false)}
        width="90vw"
        footer={
          <Space>
            <Button onClick={() => setSampleOpen(false)}>
              {t('pages.table.close', 'Close')}
            </Button>
            <Button type="primary" onClick={handleCopySample}>
              {t('pages.admin.identityProviders.copy', 'Copy JSON')}
            </Button>
          </Space>
        }
        styles={{
          body: {
            maxHeight: 'calc(100vh - 200px)',
            overflow: 'auto',
          },
        }}
      >
        <pre
          style={{
            margin: 0,
            fontSize: 12,
            lineHeight: 1.6,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-all',
          }}
        >
          {defaultTemplate === null
            ? t('pages.admin.loading', 'Loading…')
            : sampleJson}
        </pre>
      </Modal>
    </>
  );
}
