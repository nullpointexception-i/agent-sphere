import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { getLocale, useIntl } from '@umijs/max';
import { App, Button, Drawer, Form, Input, Modal, Select, Table } from 'antd';
import { useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { labelWithRule } from '@/utils/labelWithRule';

interface Props {
  open: boolean;
  providerId: number | null;
  onClose: () => void;
}

export default function ModelRouteDrawer({ open, providerId, onClose }: Props) {
  const { message, modal } = App.useApp();
  let intl: any;
  try {
    intl = useIntl();
  } catch {
    intl = {
      formatMessage: ({ id, defaultMessage }: any) => defaultMessage || id,
    };
  }
  const [routes, setRoutes] = useState<any[]>([]);
  const [editingRoute, setEditingRoute] = useState<any>(null);
  const [routeFormOpen, setRouteFormOpen] = useState(false);
  const [routeForm] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [viewRoute, setViewRoute] = useState<any>(null);
  const [companies, setCompanies] = useState<any[]>([]);

  useEffect(() => {
    agentApi.modelProviders
      .getCompanies()
      .then(setCompanies)
      .catch(() => {});
  }, []);

  const loadRoutes = () => {
    if (providerId)
      agentApi.routes
        .listByProvider(providerId)
        .then(setRoutes)
        .catch(() => setRoutes([]));
  };

  useEffect(() => {
    if (open && providerId) loadRoutes();
  }, [open, providerId]);

  const submitRoute = async () => {
    setSubmitting(true);
    try {
      const values = await routeForm.validateFields();
      const payload = {
        ...values,
        providerId: Number(values.providerId),
        weight: Number(values.weight),
        fallbackIds: values.fallbackIds?.join(',') || '',
        maxInputTokens: values.maxInputTokens
          ? Number(values.maxInputTokens) * 1000
          : null,
        maxOutputTokens: values.maxOutputTokens
          ? Number(values.maxOutputTokens) * 1000
          : null,
      };
      if (editingRoute) {
        await agentApi.routes.update(editingRoute.id, payload);
        message.success('Updated');
      } else {
        await agentApi.routes.create(payload);
        message.success('Created');
      }
      setRouteFormOpen(false);
      setEditingRoute(null);
      routeForm.resetFields();
      loadRoutes();
    } finally {
      setSubmitting(false);
    }
  };

  const columns = [
    {
      title: intl.formatMessage({ id: 'pages.table.id' }),
      dataIndex: 'id',
      key: 'id',
      width: 60,
    },
    {
      title: intl.formatMessage({ id: 'pages.table.modelName' }),
      dataIndex: 'modelName',
      key: 'modelName',
      ellipsis: true,
    },
    {
      title: intl.formatMessage({ id: 'pages.table.weight' }),
      dataIndex: 'weight',
      key: 'weight',
      width: 80,
    },
    {
      title: intl.formatMessage({
        id: 'pages.models.fallbackModels',
        defaultMessage: 'Fallback Models',
      }),
      dataIndex: 'fallbackNames',
      key: 'fallbackNames',
      ellipsis: true,
    },
    {
      title: intl.formatMessage({ id: 'pages.table.actions' }),
      key: 'actions',
      width: 120,
      render: (_: any, record: any) => (
        <>
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => setViewRoute(record)}
          />
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => {
              setEditingRoute(record);
              routeForm.setFieldsValue({
                ...record,
                company: record.company,
                weight: String(record.weight),
                fallbackIds: record.fallbackIds
                  ? String(record.fallbackIds)
                      .split(',')
                      .filter(Boolean)
                      .map(Number)
                  : [],
                maxInputTokens: record.maxInputTokens
                  ? String(record.maxInputTokens / 1000)
                  : '',
                maxOutputTokens: record.maxOutputTokens
                  ? String(record.maxOutputTokens / 1000)
                  : '',
              });
              setRouteFormOpen(true);
            }}
          />
          <Button
            type="link"
            danger
            size="small"
            icon={<DeleteOutlined />}
            onClick={() => {
              modal.confirm({
                title: intl.formatMessage(
                  {
                    id: 'pages.deleteConfirm.title',
                    defaultMessage: 'Delete {name}',
                  },
                  { name: 'route' },
                ),
                content: intl.formatMessage(
                  {
                    id: 'pages.deleteConfirm.content',
                    defaultMessage:
                      'Are you sure you want to delete this {name}?',
                  },
                  { name: 'route' },
                ),
                okType: 'danger',
                onOk: async () => {
                  await agentApi.routes.delete(record.id);
                  message.success('Deleted');
                  loadRoutes();
                },
              });
            }}
          />
        </>
      ),
    },
  ];

  return (
    <Drawer
      title={intl.formatMessage({
        id: 'pages.models.modelRoutes',
        defaultMessage: 'Model Routes',
      })}
      open={open}
      onClose={onClose}
      size="large"
      extra={
        <Button
          size="small"
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            setEditingRoute(null);
            routeForm.resetFields();
            routeForm.setFieldValue('providerId', String(providerId));
            setRouteFormOpen(true);
          }}
        >
          {intl.formatMessage({ id: 'pages.form.new', defaultMessage: 'New' })}
        </Button>
      }
    >
      <Table
        rowKey="id"
        dataSource={routes}
        columns={columns}
        size="small"
        pagination={false}
      />
      <Modal
        title={
          editingRoute
            ? intl.formatMessage({
                id: 'pages.modal.editRoute',
                defaultMessage: 'Edit Route',
              })
            : intl.formatMessage({
                id: 'pages.modal.newRoute',
                defaultMessage: 'New Route',
              })
        }
        open={routeFormOpen}
        onCancel={() => {
          setRouteFormOpen(false);
          setEditingRoute(null);
        }}
        onOk={submitRoute}
        confirmLoading={submitting}
      >
        <Form form={routeForm} layout="vertical">
          <Form.Item
            name="providerId"
            label="Provider"
            hidden
            rules={[{ required: true }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="modelName"
            label={labelWithRule(
              intl.formatMessage({ id: 'pages.table.modelName' }),
              intl.formatMessage({ id: 'pages.hint.name' }),
            )}
            rules={[{ required: true }]}
          >
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item
            name="weight"
            label={intl.formatMessage({ id: 'pages.table.weight' })}
            initialValue="100"
          >
            <Input maxLength={255} />
          </Form.Item>
          <Form.Item
            name="company"
            label={intl.formatMessage({
              id: 'pages.models.company',
              defaultMessage: 'Company',
            })}
          >
            <Select
              placeholder={intl.formatMessage({
                id: 'pages.models.selectCompany',
                defaultMessage: 'Select company',
              })}
              allowClear
              options={companies.map((c: any) => {
                const isZh = getLocale()?.startsWith('zh') || false;
                return {
                  value: c.value,
                  label: isZh
                    ? `${c.nameCn} (${c.nameEn})`
                    : `${c.nameEn} (${c.nameCn})`,
                };
              })}
            />
          </Form.Item>
          <Form.Item
            name="fallbackIds"
            label={labelWithRule(
              intl.formatMessage({ id: 'pages.models.fallbackIds' }),
              intl.formatMessage({ id: 'pages.hint.description' }),
            )}
          >
            <Select
              mode="multiple"
              placeholder={intl.formatMessage({
                id: 'pages.models.selectFallback',
                defaultMessage: 'Select fallback routes',
              })}
              options={routes
                .filter((r) => !editingRoute || r.id !== editingRoute.id)
                .map((r) => ({
                  label: `${r.modelName} (ID: ${r.id})`,
                  value: r.id,
                }))}
            />
          </Form.Item>
          <Form.Item
            name="maxInputTokens"
            label={intl.formatMessage({ id: 'pages.models.maxInputTokens' })}
          >
            <Input
              maxLength={255}
              placeholder={intl.formatMessage({
                id: 'pages.models.maxInputPlaceholder',
              })}
            />
          </Form.Item>
          <Form.Item
            name="maxOutputTokens"
            label={intl.formatMessage({ id: 'pages.models.maxOutputTokens' })}
          >
            <Input
              maxLength={255}
              placeholder={intl.formatMessage({
                id: 'pages.models.maxOutputPlaceholder',
              })}
            />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={viewRoute?.modelName}
        open={!!viewRoute}
        onCancel={() => setViewRoute(null)}
        footer={null}
        width={560}
      >
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <tbody>
            {[
              {
                label: intl.formatMessage({ id: 'pages.table.id' }),
                value: viewRoute?.id,
              },
              {
                label: intl.formatMessage({ id: 'pages.table.modelName' }),
                value: viewRoute?.modelName,
              },
              {
                label: intl.formatMessage({
                  id: 'pages.models.company',
                  defaultMessage: 'Company',
                }),
                value: viewRoute?.company || '-',
              },
              {
                label: intl.formatMessage({ id: 'pages.table.weight' }),
                value: viewRoute?.weight,
              },
              {
                label: intl.formatMessage({
                  id: 'pages.models.fallbackModels',
                  defaultMessage: 'Fallback Models',
                }),
                value: viewRoute?.fallbackNames || '-',
              },
              {
                label: intl.formatMessage({
                  id: 'pages.models.maxInputTokens',
                }),
                value: viewRoute?.maxInputTokens
                  ? `${(viewRoute.maxInputTokens / 1000).toLocaleString()}k`
                  : '-',
              },
              {
                label: intl.formatMessage({
                  id: 'pages.models.maxOutputTokens',
                }),
                value: viewRoute?.maxOutputTokens
                  ? `${(viewRoute.maxOutputTokens / 1000).toLocaleString()}k`
                  : '-',
              },
              {
                label: intl.formatMessage({ id: 'pages.table.status' }),
                value: viewRoute?.status,
              },
              {
                label: intl.formatMessage({
                  id: 'pages.table.createdBy',
                  defaultMessage: 'Created By',
                }),
                value: viewRoute?.createdBy || '-',
              },
              {
                label: intl.formatMessage({ id: 'pages.table.created' }),
                value: viewRoute?.createdAt || '-',
              },
              {
                label: intl.formatMessage({
                  id: 'pages.table.updatedBy',
                  defaultMessage: 'Updated By',
                }),
                value: viewRoute?.updatedBy || '-',
              },
              {
                label: intl.formatMessage({
                  id: 'pages.table.updatedAt',
                  defaultMessage: 'Updated At',
                }),
                value: viewRoute?.updatedAt || '-',
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
    </Drawer>
  );
}
