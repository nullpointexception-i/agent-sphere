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
  Tag,
} from 'antd';
import { useEffect, useState } from 'react';
import { Can } from '@/components/Can';
import {
  DIC_TYPE_POSITION,
  dictApi,
  type SystemDicEntry,
} from '@/services/agentSphere/dict';

export default function AdminDictionaries() {
  const intl = useIntl();
  const { message, modal } = App.useApp();
  const [items, setItems] = useState<SystemDicEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [typeFilter, setTypeFilter] = useState(DIC_TYPE_POSITION);
  const [editOpen, setEditOpen] = useState(false);
  const [editing, setEditing] = useState<SystemDicEntry | null>(null);
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  const t = (id: string, defaultMessage: string) =>
    intl.formatMessage({ id, defaultMessage });

  const load = async (kw = keyword, type = typeFilter) => {
    setLoading(true);
    try {
      const rows = await dictApi.list(type, kw || undefined);
      setItems(rows || []);
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load('', DIC_TYPE_POSITION);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const openEdit = (record?: SystemDicEntry) => {
    setEditing(record || null);
    form.setFieldsValue(
      record || {
        dicType: typeFilter || DIC_TYPE_POSITION,
        sortNo: 0,
        status: 'ACTIVE',
      },
    );
    setEditOpen(true);
  };

  const handleSave = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing?.id) {
        await dictApi.update(editing.id, values);
        message.success(t('pages.admin.dictionaries.updated', 'Updated'));
      } else {
        await dictApi.create(values);
        message.success(t('pages.admin.dictionaries.created', 'Created'));
      }
      setEditOpen(false);
      form.resetFields();
      await load();
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = (record: SystemDicEntry) => {
    if (!record.id) return;
    modal.confirm({
      title: t(
        'pages.admin.dictionaries.deleteTitle',
        'Delete this dictionary entry?',
      ),
      content: t(
        'pages.admin.dictionaries.deleteContent',
        'The option disappears from dropdowns. Existing avatars are not affected.',
      ),
      okText: t('pages.admin.dictionaries.delete', 'Delete'),
      okType: 'danger',
      cancelText: t('pages.admin.dictionaries.cancel', 'Cancel'),
      onOk: async () => {
        await dictApi.delete(record.id as number);
        message.success(t('pages.admin.dictionaries.deleted', 'Deleted'));
        await load();
      },
    });
  };

  const columns = [
    {
      title: t('pages.admin.dictionaries.code', 'Code'),
      dataIndex: 'code',
      width: 140,
    },
    {
      title: t('pages.admin.dictionaries.nameCn', 'Chinese name'),
      dataIndex: 'nameCn',
      ellipsis: true,
    },
    {
      title: t('pages.admin.dictionaries.nameEn', 'English name'),
      dataIndex: 'nameEn',
      ellipsis: true,
    },
    {
      title: t('pages.admin.dictionaries.sortNo', 'Sort'),
      dataIndex: 'sortNo',
      width: 80,
    },
    {
      title: t('pages.admin.dictionaries.status', 'Status'),
      dataIndex: 'status',
      width: 100,
      render: (value: string) => (
        <Tag color={value === 'ACTIVE' ? 'green' : 'default'}>
          {value === 'ACTIVE'
            ? t('pages.admin.dictionaries.active', 'Active')
            : t('pages.admin.dictionaries.disabled', 'Disabled')}
        </Tag>
      ),
    },
    {
      title: t('pages.admin.dictionaries.action', 'Actions'),
      valueType: 'option',
      width: 150,
      render: (_: unknown, record: SystemDicEntry) => (
        <Space size={4}>
          <Can code={PERM.adminDictionaryUpdate}>
            <Button type="link" size="small" onClick={() => openEdit(record)}>
              {t('pages.admin.dictionaries.edit', 'Edit')}
            </Button>
          </Can>
          <Can code={PERM.adminDictionaryDelete}>
            <Button
              type="link"
              size="small"
              danger
              onClick={() => handleDelete(record)}
            >
              {t('pages.admin.dictionaries.delete', 'Delete')}
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
          gap: 12,
        }}
      >
        <Space>
          <Select
            value={typeFilter}
            style={{ width: 160 }}
            onChange={(value) => {
              setTypeFilter(value);
              load(keyword, value);
            }}
            options={[
              {
                value: DIC_TYPE_POSITION,
                label: t('pages.admin.dictionaries.typePosition', 'Roles'),
              },
            ]}
          />
          <Input.Search
            allowClear
            placeholder={t(
              'pages.admin.dictionaries.searchPlaceholder',
              'Search code or name',
            )}
            style={{ width: 280 }}
            onSearch={(value) => {
              setKeyword(value);
              load(value);
            }}
          />
        </Space>
        <Can code={PERM.adminDictionaryCreate}>
          <Button type="primary" onClick={() => openEdit()}>
            {t('pages.admin.dictionaries.create', 'New entry')}
          </Button>
        </Can>
      </div>
      <Table
        rowKey="id"
        dataSource={items}
        columns={columns}
        loading={loading}
        pagination={false}
        size="small"
        scroll={{ x: 760 }}
      />
      <Modal
        title={
          editing
            ? t('pages.admin.dictionaries.editTitle', 'Edit dictionary entry')
            : t('pages.admin.dictionaries.createTitle', 'New dictionary entry')
        }
        open={editOpen}
        onOk={handleSave}
        onCancel={() => {
          setEditOpen(false);
          form.resetFields();
        }}
        confirmLoading={submitting}
        width={560}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item name="dicType" label="Type" rules={[{ required: true }]}>
            <Input maxLength={64} disabled />
          </Form.Item>
          <Form.Item
            name="code"
            label={t('pages.admin.dictionaries.code', 'Code')}
            rules={[
              { required: true, max: 64 },
              {
                pattern: /^[A-Za-z0-9_-]+$/,
                message: t(
                  'pages.admin.dictionaries.codePattern',
                  'Only letters, digits, underscores and hyphens are allowed',
                ),
              },
            ]}
          >
            <Input maxLength={64} disabled={!!editing} />
          </Form.Item>
          <Form.Item
            name="nameCn"
            label={t('pages.admin.dictionaries.nameCn', 'Chinese name')}
            rules={[{ required: true, max: 128 }]}
          >
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item
            name="nameEn"
            label={t('pages.admin.dictionaries.nameEn', 'English name')}
            rules={[{ required: true, max: 128 }]}
          >
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item
            name="sortNo"
            label={t('pages.admin.dictionaries.sortNo', 'Sort')}
          >
            <InputNumber min={0} max={9999} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="status"
            label={t('pages.admin.dictionaries.status', 'Status')}
          >
            <Select
              options={[
                {
                  value: 'ACTIVE',
                  label: t('pages.admin.dictionaries.active', 'Active'),
                },
                {
                  value: 'DISABLED',
                  label: t('pages.admin.dictionaries.disabled', 'Disabled'),
                },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="remark"
            label={t('pages.admin.dictionaries.remark', 'Remark')}
          >
            <Input.TextArea rows={2} maxLength={512} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
