import { PlusOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { App, Button, Form, Modal, Select, Table, Tag, Typography } from 'antd';
import { useState } from 'react';
import { type ItemIssue, itemName } from './serialize';
import TemplateItemForm from './TemplateItemForm';
import {
  TEMPLATE_TYPES,
  type TemplateItem,
  type TemplateItemType,
  TYPE_LABELS,
} from './types';

interface Props {
  items: TemplateItem[];
  issues: ItemIssue[];
  onChange: (items: TemplateItem[]) => void;
}

function itemSummary(item: TemplateItem): string {
  switch (item.type) {
    case 'model_provider':
      return (item.baseUrl as string) ?? '';
    case 'api_key':
      return (item.provider as string) ?? '';
    case 'model_route': {
      const parts = [
        item.provider as string,
        item.company as string,
        item.weight !== undefined ? `weight=${item.weight}` : '',
        item.supportsAttachment ? 'Attachment' : '',
      ].filter(Boolean);
      return parts.join(' · ');
    }
    case 'completions':
    case 'instance': {
      const parts = [
        (item.businessType as string) ?? '',
        item.route ? `route=${item.route}` : '',
      ].filter(Boolean);
      return parts.join(' · ');
    }
    case 'mcp':
      return (item.serverUrl as string) ?? '';
    case 'skill':
      return (item.description as string) ?? 'v1';
    case 'document':
      return (item.contentType as string) ?? 'text';
    default:
      return '';
  }
}

function labelOf(
  intl: ReturnType<typeof useIntl>,
  id: string,
  def: string,
): string {
  return intl.formatMessage({ id, defaultMessage: def });
}

export default function TemplateList({ items, issues, onChange }: Props) {
  const intl = useIntl();
  const { modal } = App.useApp();
  const [createOpen, setCreateOpen] = useState(false);
  const [draftType, setDraftType] = useState<TemplateItemType>(
    TEMPLATE_TYPES[0],
  );
  const [formOpen, setFormOpen] = useState(false);
  const [editingType, setEditingType] = useState<TemplateItemType | null>(null);
  const [editing, setEditing] = useState<TemplateItem | null>(null);

  const providers = Array.from(
    new Set(
      items
        .filter((i) => i.type === 'model_provider')
        .map((i) => i.name as string)
        .filter(Boolean),
    ),
  );
  const routes = Array.from(
    new Set(
      items
        .filter((i) => i.type === 'model_route')
        .map((i) => i.modelName as string)
        .filter(Boolean),
    ),
  );
  const issueByKey = new Map<string, ItemIssue[]>();
  issues.forEach((issue) => {
    const key =
      itemName(issue.item) ||
      `type:${issue.item.type}:${JSON.stringify(issue.item)}`;
    if (!issueByKey.has(key)) issueByKey.set(key, []);
    issueByKey.get(key)?.push(issue);
  });

  const handleRemove = (item: TemplateItem) => {
    const name = itemName(item) || (item.type as string);
    modal.confirm({
      title: labelOf(
        intl,
        'pages.admin.settings.template.delete.confirm',
        'Delete this item?',
      ),
      content: name,
      okText: labelOf(intl, 'pages.save', 'Save'),
      cancelText: labelOf(intl, 'pages.cancel', 'Cancel'),
      okButtonProps: { danger: true },
      onOk: () => onChange(items.filter((i) => i !== item)),
    });
  };

  const columns = [
    {
      title: labelOf(intl, 'pages.admin.settings.template.col.type', 'Type'),
      dataIndex: 'type',
      width: 150,
      render: (type: string) => (
        <Tag color="blue">
          {intl.formatMessage({
            id: TYPE_LABELS[type]?.id,
            defaultMessage: type,
          })}
        </Tag>
      ),
    },
    {
      title: labelOf(intl, 'pages.admin.settings.template.col.name', 'Name'),
      dataIndex: 'name',
      width: 180,
      ellipsis: true,
      render: (_: unknown, record: TemplateItem) =>
        itemName(record) || <span style={{ color: '#999' }}>-</span>,
    },
    {
      title: labelOf(
        intl,
        'pages.admin.settings.template.col.config',
        'Config',
      ),
      dataIndex: 'config',
      ellipsis: true,
      render: (_: unknown, record: TemplateItem) => itemSummary(record) || '-',
    },
    {
      title: labelOf(
        intl,
        'pages.admin.settings.template.col.status',
        'Status',
      ),
      dataIndex: 'status',
      width: 220,
      render: (_: unknown, record: TemplateItem) => {
        const key =
          itemName(record) || `type:${record.type}:${JSON.stringify(record)}`;
        const list = issueByKey.get(key);
        if (!list || list.length === 0) {
          return (
            <Typography.Text type="success">
              {labelOf(intl, 'pages.admin.settings.template.status.ok', 'OK')}
            </Typography.Text>
          );
        }
        return (
          <Typography.Text type="warning" style={{ fontSize: 12 }}>
            {list.map((issue) => issue.message).join('；')}
          </Typography.Text>
        );
      },
    },
    {
      title: labelOf(
        intl,
        'pages.admin.settings.template.col.action',
        'Actions',
      ),
      width: 130,
      render: (_: unknown, record: TemplateItem) => (
        <>
          <Button
            type="link"
            size="small"
            onClick={() => {
              setEditingType(record.type as TemplateItemType);
              setEditing(record);
              setFormOpen(true);
            }}
          >
            {labelOf(intl, 'pages.table.edit', 'Edit')}
          </Button>
          <Button
            type="link"
            size="small"
            danger
            onClick={() => handleRemove(record)}
          >
            {labelOf(intl, 'pages.table.delete', 'Delete')}
          </Button>
        </>
      ),
    },
  ];

  return (
    <>
      <div style={{ marginBottom: 12 }}>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            setDraftType(TEMPLATE_TYPES[0]);
            setCreateOpen(true);
          }}
        >
          {labelOf(intl, 'pages.admin.settings.template.add', 'Add')}
        </Button>
        <span style={{ marginLeft: 12, color: '#999', fontSize: 12 }}>
          {labelOf(intl, 'pages.admin.settings.template.count', 'Total')}{' '}
          {items.length}{' '}
          {labelOf(intl, 'pages.admin.settings.template.items', 'items')}
        </span>
      </div>
      <Table
        rowKey={(record: TemplateItem) =>
          `${record.type}:${itemName(record)}:${items.indexOf(record)}`
        }
        columns={columns}
        dataSource={items}
        pagination={false}
        size="middle"
      />
      <Modal
        title={labelOf(
          intl,
          'pages.admin.settings.template.selectType',
          'Select item type',
        )}
        open={createOpen}
        onOk={() => {
          setEditingType(draftType);
          setEditing(null);
          setCreateOpen(false);
          setFormOpen(true);
        }}
        onCancel={() => setCreateOpen(false)}
        okText={labelOf(intl, 'pages.save', 'Save')}
        cancelText={labelOf(intl, 'pages.cancel', 'Cancel')}
      >
        <Form.Item
          label={labelOf(
            intl,
            'pages.admin.settings.template.col.type',
            'Type',
          )}
          required
        >
          <Select
            value={draftType}
            onChange={(v) => setDraftType(v)}
            options={TEMPLATE_TYPES.map((t) => ({
              value: t,
              label: intl.formatMessage({
                id: TYPE_LABELS[t]?.id,
                defaultMessage: t,
              }),
            }))}
          />
        </Form.Item>
      </Modal>
      <TemplateItemForm
        open={formOpen}
        type={editingType ?? TEMPLATE_TYPES[0]}
        initial={editing}
        providers={providers}
        routes={routes}
        onCancel={() => {
          setFormOpen(false);
          setEditing(null);
          setEditingType(null);
        }}
        onSubmit={(item) => {
          const next = editing
            ? items.map((i) => (i === editing ? item : i))
            : [...items, item];
          onChange(next);
          setFormOpen(false);
          setEditing(null);
          setEditingType(null);
        }}
      />
    </>
  );
}
