import { useIntl, useModel } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Space,
  Table,
  Tag,
  TreeSelect,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { Can } from '@/components/Can';
import { PERM } from '@/constants/permissions';
import { isReservedTenant } from '@/constants/tenant';
import { agentApi } from '@/services/agentSphere/api';
import { localizeApiError } from '@/utils/errorMessage';

interface TenantItem {
  id: number;
  code: string;
  name: string;
  pid?: number | null;
  parentName?: string;
  sortOrder?: number;
  status: string;
  remark?: string;
  manageable?: boolean;
}

interface TenantTreeNode {
  value: number;
  title: string;
  children?: TenantTreeNode[];
}

/** Build a parent/child tree from the flat tenant list, excluding the edited subtree. */
function buildTenantTree(
  items: TenantItem[],
  excludeId?: number,
): TenantTreeNode[] {
  const excluded = new Set<number>();
  if (excludeId != null) {
    const collect = (id: number) => {
      excluded.add(id);
      items
        .filter((item) => item.pid === id)
        .forEach((item) => {
          collect(item.id);
        });
    };
    collect(excludeId);
  }

  const ids = new Set(items.map((item) => item.id));
  const childrenByParent = new Map<number, TenantItem[]>();
  for (const item of items) {
    const parentId = item.pid ?? 0;
    if (!childrenByParent.has(parentId)) {
      childrenByParent.set(parentId, []);
    }
    childrenByParent.get(parentId)?.push(item);
  }

  const build = (parentId: number): TenantTreeNode[] =>
    (childrenByParent.get(parentId) || [])
      .filter((item) => !excluded.has(item.id))
      .map((item) => ({
        value: item.id,
        title: item.name,
        children: build(item.id),
      }));

  const roots = items.filter(
    (item) =>
      !excluded.has(item.id) && (item.pid == null || !ids.has(item.pid)),
  );
  return roots.map((item) => ({
    value: item.id,
    title: item.name,
    children: build(item.id),
  }));
}

export default function AdminTenants() {
  const intl = useIntl();
  const { message, modal } = App.useApp();
  const { initialState } = useModel('@@initialState');
  const [tenants, setTenants] = useState<TenantItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<TenantItem | null>(null);
  const [saving, setSaving] = useState(false);
  const [currentVisibility, setCurrentVisibility] = useState<
    'SELF_ONLY' | 'TENANT' | 'TENANT_SUBTREE' | null
  >(null);
  const [form] = Form.useForm();

  const loc = (id: string, text: string) =>
    intl.formatMessage({ id, defaultMessage: text });

  const isPlatformAdmin = initialState?.currentUser?.access === 'admin';
  const canCreateChild =
    isPlatformAdmin ||
    currentVisibility == null ||
    currentVisibility === 'TENANT_SUBTREE';
  const subtreeHint = loc(
    'pages.admin.tenants.subtreeRequired',
    'The current visibility does not include descendants. Set the visibility to "Current tenant and descendants" in Tenants and roles before creating a child tenant.',
  );

  const load = async () => {
    setLoading(true);
    try {
      setTenants((await agentApi.admin.tenants.list()) || []);
    } catch {
      setTenants([]);
    } finally {
      setLoading(false);
    }
    try {
      const assignments = await agentApi.admin.tenants.mine();
      const current = (assignments || []).find((item: any) => item.isCurrent);
      setCurrentVisibility(current?.visibility ?? null);
    } catch {
      setCurrentVisibility(null);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const byId = useMemo(
    () => new Map(tenants.map((tenant) => [tenant.id, tenant])),
    [tenants],
  );

  const parentTreeData = useMemo(
    () => buildTenantTree(tenants, editing?.id),
    [tenants, editing],
  );

  const openCreate = () => {
    if (!canCreateChild) {
      message.warning(subtreeHint);
      return;
    }
    setEditing(null);
    form.resetFields();
    // Do not hard-code the platform tenant id: a tenant administrator only has
    // their own subtree in the list, so the platform tenant option may not exist.
    const defaultParent = tenants.find((tenant) => tenant.status === 'ACTIVE');
    form.setFieldsValue({
      pid: defaultParent?.id,
      sortOrder: 0,
    });
    setOpen(true);
  };

  const openEdit = (tenant: TenantItem) => {
    setEditing(tenant);
    form.setFieldsValue(tenant);
    setOpen(true);
  };

  const save = async () => {
    setSaving(true);
    try {
      const values = await form.validateFields();
      if (editing) {
        await agentApi.admin.tenants.update(editing.id, values);
      } else {
        await agentApi.admin.tenants.create(values);
      }
      message.success(loc('pages.save.success', 'Saved'));
      setOpen(false);
      load();
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(
        localizeApiError(
          error?.response?.data ?? error?.data,
          error?.message || loc('pages.chat.saveFailed', 'Save failed'),
        ),
      );
    } finally {
      setSaving(false);
    }
  };

  const toggleStatus = async (tenant: TenantItem) => {
    const next = tenant.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    try {
      await agentApi.admin.tenants.updateStatus(tenant.id, next);
      message.success(loc('pages.save.success', 'Saved'));
      load();
    } catch (error: any) {
      message.error(
        error?.message || loc('pages.chat.saveFailed', 'Save failed'),
      );
    }
  };

  const remove = (tenant: TenantItem) => {
    modal.confirm({
      title: loc('pages.admin.tenants.deleteConfirm', 'Delete this tenant?'),
      content: tenant.name,
      onOk: async () => {
        await agentApi.admin.tenants.remove(tenant.id);
        message.success(loc('pages.document.deleted', 'Deleted'));
        load();
      },
    });
  };

  const columns = [
    {
      title: loc('pages.admin.tenants.name', 'Name'),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: loc('pages.admin.tenants.code', 'Code'),
      dataIndex: 'code',
      key: 'code',
    },
    {
      title: loc('pages.admin.tenants.parent', 'Parent'),
      dataIndex: 'pid',
      key: 'pid',
      render: (pid: number | null, record: TenantItem) =>
        record.parentName || (pid ? byId.get(pid)?.name || pid : '-'),
    },
    {
      title: loc('pages.table.status', 'Status'),
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => (
        <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{status}</Tag>
      ),
    },
    {
      title: loc('pages.table.actions', 'Actions'),
      key: 'actions',
      render: (_: any, tenant: TenantItem) => {
        if (isReservedTenant(tenant.id)) {
          return <Tag>{loc('pages.admin.tenants.readOnly', 'Read-only')}</Tag>;
        }
        return (
          <Space>
            <Can code={PERM.adminTenantUpdate}>
              <Button type="link" size="small" onClick={() => openEdit(tenant)}>
                {loc('pages.table.edit', 'Edit')}
              </Button>
            </Can>
            <Can code={PERM.adminTenantUpdate}>
              <Button
                type="link"
                size="small"
                onClick={() => toggleStatus(tenant)}
              >
                {tenant.status === 'ACTIVE'
                  ? loc('pages.admin.tenants.disable', 'Disable')
                  : loc('pages.admin.tenants.enable', 'Enable')}
              </Button>
            </Can>
            <Can code={PERM.adminTenantDelete}>
              <Button
                type="link"
                size="small"
                danger
                onClick={() => remove(tenant)}
              >
                {loc('pages.table.delete', 'Delete')}
              </Button>
            </Can>
          </Space>
        );
      },
    },
  ];

  return (
    <>
      <div style={{ marginBottom: 16 }}>
        {!canCreateChild && (
          <Alert
            type="warning"
            showIcon
            title={subtreeHint}
            style={{ marginBottom: 12 }}
          />
        )}
        <Can code={PERM.adminTenantCreate}>
          <Button
            type="primary"
            onClick={openCreate}
            disabled={!canCreateChild}
          >
            {loc('pages.admin.tenants.create', 'New Tenant')}
          </Button>
        </Can>
      </div>
      <Table
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={tenants}
        columns={columns as any}
        pagination={false}
      />
      <Modal
        title={
          editing
            ? loc('pages.admin.tenants.edit', 'Edit Tenant')
            : loc('pages.admin.tenants.create', 'New Tenant')
        }
        open={open}
        onCancel={() => setOpen(false)}
        onOk={save}
        confirmLoading={saving}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label={loc('pages.admin.tenants.name', 'Name')}
            rules={[{ required: true }]}
          >
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item
            name="code"
            label={loc('pages.admin.tenants.code', 'Code')}
            rules={[{ required: true }]}
          >
            <Input maxLength={50} />
          </Form.Item>
          <Form.Item
            name="pid"
            label={loc('pages.admin.tenants.parent', 'Parent')}
            rules={editing?.pid == null ? [] : [{ required: true }]}
          >
            <TreeSelect
              treeData={parentTreeData}
              treeDefaultExpandAll
              showSearch
              treeNodeFilterProp="title"
              placeholder={loc(
                'pages.admin.tenants.parentPlaceholder',
                'Select a parent tenant',
              )}
            />
          </Form.Item>
          <Form.Item
            name="sortOrder"
            label={loc('pages.admin.tenants.sortOrder', 'Sort')}
          >
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="remark"
            label={loc('pages.form.description', 'Description')}
          >
            <Input.TextArea maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
