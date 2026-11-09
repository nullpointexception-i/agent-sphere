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
import {
  App,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Pagination,
  Select,
  Table,
  Tag,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import { Can } from '@/components/Can';
import { PERM } from '@/constants/permissions';
import { useCan } from '@/hooks/usePermission';
import { agentApi } from '@/services/agentSphere/api';
import { labelWithRule } from '@/utils/labelWithRule';
import ApiKeyDrawer from './drawers/ApiKeyDrawer';
import ModelRouteDrawer from './drawers/ModelRouteDrawer';
import { useStyles } from './style';

export default function ModelProviders() {
  const { message, modal } = App.useApp();
  const intl = useIntl();
  const { styles } = useStyles();
  const [providers, setProviders] = useState<any[]>([]);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(12);
  const [total, setTotal] = useState(0);
  const [systemOpen, setSystemOpen] = useState(false);
  const [systemLoading, setSystemLoading] = useState(false);
  const [systemRoutes, setSystemRoutes] = useState<any[]>([]);
  const [systemEdit, setSystemEdit] = useState<any>(null);
  const [systemDraft, setSystemDraft] = useState<any>({});
  const [systemSaving, setSystemSaving] = useState(false);
  const [providerPool, setProviderPool] = useState<any[]>([]);

  const [providerModal, setProviderModal] = useState(false);
  const [editingProvider, setEditingProvider] = useState<any>(null);
  const [providerForm] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [viewProvider, setViewProvider] = useState<any>(null);

  const [drawerProvider, setDrawerProvider] = useState<any>(null);
  const autoOpenedRef = useRef(false);
  const [keyDrawerOpen, setKeyDrawerOpen] = useState(false);
  const [routeDrawerOpen, setRouteDrawerOpen] = useState(false);

  const canEditProvider = useCan('model:provider:update');
  const canDeleteProvider = useCan('model:provider:delete');

  const loadProviders = (nextPage = page, nextSize = pageSize) =>
    agentApi.modelProviders
      .page({ page: nextPage, size: nextSize })
      .then((result: any) => {
        setProviders(result?.records || []);
        setTotal(Number(result?.total || 0));
      })
      .catch(() => {});

  const location = useLocation();

  useEffect(() => {
    loadProviders(page, pageSize);
  }, [page, pageSize]);

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

  const openSystemRoutes = async () => {
    setSystemOpen(true);
    setSystemLoading(true);
    try {
      const [routes, providers] = await Promise.all([
        agentApi.routes.listSystem(),
        agentApi.modelProviders.list(),
      ]);
      setSystemRoutes(Array.isArray(routes) ? routes : []);
      setProviderPool(Array.isArray(providers) ? providers : []);
    } catch {
      setSystemRoutes([]);
    } finally {
      setSystemLoading(false);
    }
  };

  const beginEditSystemRoute = (route: any) => {
    setSystemEdit(route);
    setSystemDraft({
      providerId: route.providerId,
      modelName: route.modelName,
    });
  };

  const saveSystemRoute = async () => {
    if (!systemEdit) return;
    setSystemSaving(true);
    try {
      await agentApi.routes.update(systemEdit.id, {
        providerId: systemDraft.providerId,
        modelName: systemDraft.modelName,
        maxInputTokens: systemEdit.maxInputTokens,
        maxOutputTokens: systemEdit.maxOutputTokens,
        company: systemEdit.company,
        completionProtocol: systemEdit.completionProtocol,
        weight: systemEdit.weight,
      });
      message.success(
        intl.formatMessage({
          id: 'pages.save.success',
          defaultMessage: 'Saved',
        }),
      );
      setSystemEdit(null);
      await openSystemRoutes();
    } catch (error: any) {
      message.error(
        error?.message ||
          intl.formatMessage({
            id: 'pages.save.failed',
            defaultMessage: 'Save failed',
          }),
      );
    } finally {
      setSystemSaving(false);
    }
  };

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
        <>
          <Button onClick={openSystemRoutes} style={{ marginRight: 8 }}>
            System routes
          </Button>
          <Can code={PERM.modelProviderCreate}>
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
        </>
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
              </div>,
              ...(canEditProvider
                ? [
                    <div
                      key="edit"
                      style={{
                        display: 'flex',
                        flexDirection: 'column',
                        alignItems: 'center',
                        gap: 2,
                      }}
                      onClick={() => {
                        setEditingProvider(item);
                        providerForm.resetFields();
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
                  ]
                : []),
              ...(canDeleteProvider
                ? [
                    <div
                      key="delete"
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
                    </div>,
                  ]
                : []),
            ].filter(Boolean)}
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
      <div
        style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 12 }}
      >
        <Pagination
          current={page}
          pageSize={pageSize}
          total={total}
          showSizeChanger
          pageSizeOptions={[12, 24, 48]}
          onChange={(nextPage, nextSize) => {
            setPage(nextPage);
            setPageSize(nextSize);
          }}
        />
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

      <Modal
        open={systemOpen}
        title="System model routes"
        width={880}
        footer={null}
        onCancel={() => {
          setSystemOpen(false);
          setSystemEdit(null);
        }}
        destroyOnHidden
      >
        <Table
          rowKey="id"
          size="small"
          loading={systemLoading}
          dataSource={systemRoutes}
          pagination={false}
          columns={[
            { title: 'Tenant', dataIndex: 'tenantId', width: 80 },
            { title: 'Model', dataIndex: 'modelName' },
            { title: 'Provider', dataIndex: 'providerName', width: 180 },
            {
              title: 'Key',
              dataIndex: 'apiKeyConfigured',
              width: 70,
              render: (value: any) => (value ? 'OK' : '-'),
            },
            { title: 'Updated', dataIndex: 'updatedAt', width: 170 },
            {
              title: '',
              width: 70,
              render: (_: any, row: any) => (
                <Button
                  type="link"
                  size="small"
                  onClick={() => beginEditSystemRoute(row)}
                >
                  Edit
                </Button>
              ),
            },
          ]}
        />
      </Modal>

      <Modal
        open={Boolean(systemEdit)}
        title="Edit system route"
        onCancel={() => setSystemEdit(null)}
        onOk={saveSystemRoute}
        confirmLoading={systemSaving}
        destroyOnHidden
      >
        <div style={{ marginBottom: 8, color: '#8b8b93', fontSize: 12 }}>
          Tenant #{systemEdit?.tenantId}
        </div>
        <Input
          value={systemDraft.modelName}
          placeholder="Model name"
          style={{ marginBottom: 8 }}
          onChange={(event) =>
            setSystemDraft({ ...systemDraft, modelName: event.target.value })
          }
        />
        <Select
          style={{ width: '100%' }}
          value={systemDraft.providerId}
          onChange={(value) =>
            setSystemDraft({ ...systemDraft, providerId: value })
          }
          options={providerPool
            .filter(
              (provider) =>
                Number(provider.tenantId) === Number(systemEdit?.tenantId),
            )
            .map((provider) => ({
              value: provider.id,
              label: `${provider.name} (#${provider.id})`,
            }))}
        />
      </Modal>
    </PageContainer>
  );
}
