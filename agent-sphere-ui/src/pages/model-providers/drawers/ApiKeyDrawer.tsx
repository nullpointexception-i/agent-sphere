import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { App, Button, Drawer, Form, Input, Modal, Radio, Table } from 'antd';
import { useEffect, useState } from 'react';
import { Can } from '@/components/Can';
import { agentApi } from '@/services/agentSphere/api';
import { formatTime } from '@/utils/format';
import { labelWithRule } from '@/utils/labelWithRule';

interface Props {
  open: boolean;
  providerId: number | null;
  currentApiKeyId?: number | null;
  onActiveKeyChange?: () => void;
  onClose: () => void;
}

export default function ApiKeyDrawer({
  open,
  providerId,
  currentApiKeyId,
  onActiveKeyChange,
  onClose,
}: Props) {
  const { message, modal } = App.useApp();
  let intl: any;
  try {
    intl = useIntl();
  } catch {
    intl = {
      formatMessage: ({ id, defaultMessage }: any) => defaultMessage || id,
    };
  }
  const [keys, setKeys] = useState<any[]>([]);
  const [editingKey, setEditingKey] = useState<any>(null);
  const [keyFormOpen, setKeyFormOpen] = useState(false);
  const [keyForm] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  const loadKeys = () => {
    if (providerId)
      agentApi.apiKeys
        .listByProvider(providerId)
        .then(setKeys)
        .catch(() => setKeys([]));
  };

  useEffect(() => {
    if (open && providerId) loadKeys();
  }, [open, providerId]);

  const submitKey = async () => {
    setSubmitting(true);
    try {
      const values = await keyForm.validateFields();
      const payload = { ...values, providerId: Number(values.providerId) };
      if (editingKey) {
        await agentApi.apiKeys.update(editingKey.id, payload);
        message.success('Updated');
      } else {
        await agentApi.apiKeys.create(payload);
        message.success('Created');
      }
      setKeyFormOpen(false);
      setEditingKey(null);
      keyForm.resetFields();
      loadKeys();
    } finally {
      setSubmitting(false);
    }
  };

  const columns = [
    {
      title: intl.formatMessage({
        id: 'pages.table.activeKey',
        defaultMessage: '默认',
      }),
      key: 'activeKey',
      width: 60,
      render: (_: any, record: any) => (
        <Can code="model:apikey:set-active">
          <Radio
            checked={record.id === currentApiKeyId}
            onClick={() => {
              if (record.id === currentApiKeyId) {
                agentApi.modelProviders
                  .setActiveKey(providerId!, null)
                  .then(() => {
                    message.success(
                      intl.formatMessage({
                        id: 'pages.models.keyUnset',
                        defaultMessage: '已取消',
                      }),
                    );
                    onActiveKeyChange?.();
                    loadKeys();
                  });
              } else {
                agentApi.modelProviders
                  .setActiveKey(providerId!, record.id)
                  .then(() => {
                    message.success(
                      intl.formatMessage({
                        id: 'pages.models.keySetSuccess',
                        defaultMessage: '已设为当前密钥',
                      }),
                    );
                    onActiveKeyChange?.();
                    loadKeys();
                  });
              }
            }}
          />
        </Can>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.table.alias' }),
      dataIndex: 'alias',
      key: 'alias',
    },
    {
      title: intl.formatMessage({ id: 'pages.table.keyValue' }),
      dataIndex: 'keyValue',
      key: 'keyValue',
      ellipsis: true,
    },
    {
      title: intl.formatMessage({ id: 'pages.table.expires' }),
      dataIndex: 'expiresAt',
      key: 'expiresAt',
      render: (v: any) => formatTime(v),
    },
    {
      title: intl.formatMessage({ id: 'pages.table.actions' }),
      key: 'actions',
      width: 120,
      render: (_: any, record: any) => (
        <>
          <Can code="model:apikey:update">
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setEditingKey(record);
                keyForm.setFieldsValue(record);
                setKeyFormOpen(true);
              }}
            />
          </Can>
          <Can code="model:apikey:delete">
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
                    { name: 'API key' },
                  ),
                  content: intl.formatMessage(
                    {
                      id: 'pages.deleteConfirm.content',
                      defaultMessage:
                        'Are you sure you want to delete this {name}?',
                    },
                    { name: 'API key' },
                  ),
                  okType: 'danger',
                  onOk: async () => {
                    await agentApi.apiKeys.delete(record.id);
                    message.success('Deleted');
                    loadKeys();
                  },
                });
              }}
            />
          </Can>
        </>
      ),
    },
  ];

  return (
    <Drawer
      title={intl.formatMessage({
        id: 'pages.models.apiKeys',
        defaultMessage: 'API Keys',
      })}
      open={open}
      onClose={onClose}
      size="large"
      extra={
        <Can code="model:apikey:create">
          <Button
            size="small"
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              setEditingKey(null);
              keyForm.resetFields();
              keyForm.setFieldValue('providerId', String(providerId));
              setKeyFormOpen(true);
            }}
          >
            {intl.formatMessage({
              id: 'pages.form.new',
              defaultMessage: 'New',
            })}
          </Button>
        </Can>
      }
    >
      <div
        style={{
          marginBottom: 8,
          fontSize: 12,
          color: '#8c8c8c',
          padding: '4px 0',
        }}
      >
        {intl.formatMessage({
          id: 'pages.models.apiKeyHint',
          defaultMessage: '不设置 API Key，路由则无法为 instance 配置模型',
        })}
      </div>
      <Table
        rowKey="id"
        dataSource={keys}
        columns={columns}
        size="small"
        pagination={false}
      />
      <Modal
        title={
          editingKey
            ? intl.formatMessage({
                id: 'pages.modal.editApiKey',
                defaultMessage: 'Edit API Key',
              })
            : intl.formatMessage({
                id: 'pages.modal.newApiKey',
                defaultMessage: 'New API Key',
              })
        }
        open={keyFormOpen}
        onCancel={() => {
          setKeyFormOpen(false);
          setEditingKey(null);
        }}
        onOk={submitKey}
        confirmLoading={submitting}
      >
        <Form form={keyForm} layout="vertical">
          <Form.Item
            name="providerId"
            label="Provider"
            hidden
            rules={[{ required: true }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="alias"
            label={labelWithRule(
              intl.formatMessage({ id: 'pages.table.alias' }),
              intl.formatMessage({ id: 'pages.hint.description' }),
            )}
          >
            <Input maxLength={255} />
          </Form.Item>
          <Form.Item
            name="keyValue"
            label={labelWithRule(
              intl.formatMessage({ id: 'pages.table.keyValue' }),
              intl.formatMessage({ id: 'pages.hint.keyValue' }),
            )}
            rules={[{ required: true }]}
          >
            <Input.TextArea rows={2} maxLength={2000} />
          </Form.Item>
        </Form>
      </Modal>
    </Drawer>
  );
}
