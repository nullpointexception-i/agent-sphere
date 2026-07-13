import {
  BranchesOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  KeyOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { useIntl, useLocation } from '@umijs/max';
import { App, Button, Card, Form, Input, Modal, Tag } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { Can } from '@/components/Can';
import { agentApi } from '@/services/agentSphere/api';
import { labelWithRule } from '@/utils/labelWithRule';
import ApiKeyDrawer from './drawers/ApiKeyDrawer';
import ModelRouteDrawer from './drawers/ModelRouteDrawer';
import { useStyles } from './style';

export default function ModelProviders() {
  const { message, modal } = App.useApp();
  let intl: any;
  try {
    intl = useIntl();
  } catch {
    intl = {
      formatMessage: ({ id, defaultMessage }: any) => defaultMessage || id,
    };
  }
  const { styles } = useStyles();
  const [providers, setProviders] = useState<any[]>([]);

  const [providerModal, setProviderModal] = useState(false);
  const [editingProvider, setEditingProvider] = useState<any>(null);
  const [providerForm] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [viewProvider, setViewProvider] = useState<any>(null);

  const [drawerProvider, setDrawerProvider] = useState<any>(null);
  const autoOpenedRef = useRef(false);
  const [keyDrawerOpen, setKeyDrawerOpen] = useState(false);
  const [routeDrawerOpen, setRouteDrawerOpen] = useState(false);

  const loadProviders = () =>
    agentApi.modelProviders
      .list()
      .then(setProviders)
      .catch(() => {});

  const location = useLocation();

  useEffect(() => {
    loadProviders();
  }, []);

  useEffect(() => {
    if (autoOpenedRef.current || providers.length === 0) return;
    const params = new URLSearchParams(location.search);
    const providerId = params.get('openApiKeys');
    if (providerId) {
      const id = Number(providerId);
      if (!isNaN(id)) {
        const provider = providers.find((p: any) => p.id === id);
        if (provider) {
          setDrawerProvider(provider);
          setKeyDrawerOpen(true);
          autoOpenedRef.current = true;
        }
      }
    }
  }, [location.search, providers]);

  const submitProvider = async () => {
    setSubmitting(true);
    try {
      const values = await providerForm.validateFields();
      if (editingProvider) {
        await agentApi.modelProviders.update(editingProvider.id, values);
        message.success('Updated');
      } else {
        await agentApi.modelProviders.create(values);
        message.success('Created');
      }
      setProviderModal(false);
      setEditingProvider(null);
      providerForm.resetFields();
      loadProviders();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <PageContainer
      title={false}
      extra={
        <Can code="model:provider:create">
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              setEditingProvider(null);
              providerForm.resetFields();
              setProviderModal(true);
            }}
          >
            {intl.formatMessage({
              id: 'pages.modal.newProvider',
              defaultMessage: 'New Provider',
            })}
          </Button>
        </Can>
      }
    >
      <div className={styles.cardGrid}>
        {providers.map((item) => (
          <Card
            key={item.id}
            className={styles.card}
            size="small"
            actions={[
              <div
                key="keys"
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  gap: 2,
                }}
                onClick={() => {
                  setDrawerProvider(item);
                  setKeyDrawerOpen(true);
                }}
              >
                <KeyOutlined />
                <span style={{ fontSize: 11 }}>
                  {intl.formatMessage({ id: 'pages.models.apiKeys' })}
                </span>
              </div>,
              <div
                key="routes"
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  gap: 2,
                }}
                onClick={() => {
                  setDrawerProvider(item);
                  setRouteDrawerOpen(true);
                }}
              >
                <BranchesOutlined />
                <span style={{ fontSize: 11 }}>
                  {intl.formatMessage({ id: 'pages.models.modelRoutes' })}
                </span>
              </div>,
              <Can key="edit" code="model:provider:update">
              <div
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  gap: 2,
                }}
                onClick={() => {
                  setEditingProvider(item);
                  providerForm.setFieldsValue(item);
                  setProviderModal(true);
                }}
              >
                <EditOutlined />
                <span style={{ fontSize: 11 }}>
                  {intl.formatMessage({
                    id: 'pages.table.edit',
                    defaultMessage: 'Edit',
                  })}
                </span>
              </div>,
              <div
                key="view"
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  gap: 2,
                }}
                onClick={() => setViewProvider(item)}
              >
                <EyeOutlined />
                <span style={{ fontSize: 11 }}>
                  {intl.formatMessage({
                    id: 'pages.table.view',
                    defaultMessage: 'View',
                  })}
                </span>
              </div>
              </Can>,
              <Can key="delete" code="model:provider:delete">
              <div
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  gap: 2,
                }}
                onClick={() => {
                  modal.confirm({
                    title: intl.formatMessage(
                      {
                        id: 'pages.deleteConfirm.title',
                        defaultMessage: 'Delete {name}',
                      },
                      { name: item.name },
                    ),
                    okType: 'danger',
                    onOk: async () => {
                      await agentApi.modelProviders.delete(item.id);
                      message.success('Deleted');
                      loadProviders();
                    },
                  });
                }}
              >
                <DeleteOutlined />
                <span style={{ fontSize: 11 }}>
                  {intl.formatMessage({
                    id: 'pages.table.delete',
                    defaultMessage: 'Delete',
                  })}
                </span>
              </div>
              </Can>,
            ]}
          >
            <Card.Meta
              title={
                <div className={styles.cardTitle}>
                  <span className={styles.cardName}>{item.name}</span>
                  <Tag color={item.status === 'ENABLED' ? 'green' : undefined}>
                    {item.status}
                  </Tag>
                </div>
              }
              description={
                <div className={styles.cardDesc}>
                  <div>{item.baseUrl || '-'}</div>
                </div>
              }
            />
          </Card>
        ))}
      </div>

      <Modal
        title={
          editingProvider
            ? intl.formatMessage({
                id: 'pages.modal.editProvider',
                defaultMessage: 'Edit Provider',
              })
            : intl.formatMessage({
                id: 'pages.modal.newProvider',
                defaultMessage: 'New Provider',
              })
        }
        open={providerModal}
        onOk={submitProvider}
        onCancel={() => {
          setProviderModal(false);
          setEditingProvider(null);
        }}
        confirmLoading={submitting}
      >
        <Form form={providerForm} layout="vertical">
          <Form.Item
            name="name"
            label={labelWithRule(
              intl.formatMessage({ id: 'pages.form.name' }),
              intl.formatMessage({ id: 'pages.hint.name' }),
            )}
            rules={[{ required: true }]}
          >
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item
            name="baseUrl"
            label={labelWithRule(
              intl.formatMessage({ id: 'pages.models.baseUrl' }),
              intl.formatMessage({ id: 'pages.hint.url' }),
            )}
            rules={[{ required: true }]}
          >
            <Input maxLength={500} />
          </Form.Item>
          <Form.Item
            name="config"
            label={labelWithRule(
              intl.formatMessage({ id: 'pages.models.config' }),
              intl.formatMessage({ id: 'pages.hint.text' }),
            )}
          >
            <Input.TextArea rows={3} maxLength={5000} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={viewProvider?.name}
        open={!!viewProvider}
        onCancel={() => setViewProvider(null)}
        footer={null}
        width={560}
      >
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <tbody>
            {[
              {
                label: intl.formatMessage({ id: 'pages.table.id' }),
                value: viewProvider?.id,
              },
              {
                label: intl.formatMessage({ id: 'pages.table.name' }),
                value: viewProvider?.name,
              },
              {
                label: intl.formatMessage({ id: 'pages.models.baseUrl' }),
                value: viewProvider?.baseUrl,
              },
              {
                label: intl.formatMessage({ id: 'pages.models.config' }),
                value: viewProvider?.config,
              },
              {
                label: intl.formatMessage({ id: 'pages.table.status' }),
                value: viewProvider?.status,
              },
              {
                label: intl.formatMessage({
                  id: 'pages.table.createdBy',
                  defaultMessage: 'Created By',
                }),
                value: viewProvider?.createdBy || '-',
              },
              {
                label: intl.formatMessage({ id: 'pages.table.created' }),
                value: viewProvider?.createdAt || '-',
              },
              {
                label: intl.formatMessage({
                  id: 'pages.table.updatedBy',
                  defaultMessage: 'Updated By',
                }),
                value: viewProvider?.updatedBy || '-',
              },
              {
                label: intl.formatMessage({
                  id: 'pages.table.updatedAt',
                  defaultMessage: 'Updated At',
                }),
                value: viewProvider?.updatedAt || '-',
              },
            ].map((row) => (
              <tr key={row.label}>
                <td
                  style={{
                    padding: '8px 12px',
                    fontWeight: 500,
                    color: '#8c8c8c',
                    borderBottom: '1px solid #f0f0f0',
                    width: 120,
                    verticalAlign: 'top',
                  }}
                >
                  {row.label}
                </td>
                <td
                  style={{
                    padding: '8px 12px',
                    borderBottom: '1px solid #f0f0f0',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-all',
                  }}
                >
                  {row.value || '-'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Modal>

      <ApiKeyDrawer
        open={keyDrawerOpen}
        providerId={drawerProvider?.id ?? null}
        currentApiKeyId={drawerProvider?.apiKeyId}
        onActiveKeyChange={async () => {
          loadProviders();
          if (drawerProvider?.id) {
            const updated = await agentApi.modelProviders.get(
              drawerProvider.id,
            );
            setDrawerProvider(updated);
          }
        }}
        onClose={() => setKeyDrawerOpen(false)}
      />
      <ModelRouteDrawer
        open={routeDrawerOpen}
        providerId={drawerProvider?.id ?? null}
        onClose={() => setRouteDrawerOpen(false)}
      />
    </PageContainer>
  );
}
