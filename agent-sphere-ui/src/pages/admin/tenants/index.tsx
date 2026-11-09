import { useIntl, useModel } from '@umijs/max';
import {
  Alert,
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
  groupRouterModelRouteId?: number | null;
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
  const [testing, setTesting] = useState(false);
  const [routeOpen, setRouteOpen] = useState(false);
  const [routeTenant, setRouteTenant] = useState<TenantItem | null>(null);
  const [routeOptions, setRouteOptions] = useState<any[]>([]);
  const [routeValue, setRouteValue] = useState<number | undefined>();
  const [routeLoading, setRouteLoading] = useState(false);
  const [routeSaving, setRouteSaving] = useState(false);
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
      defaultModel: {
        providerCompany: 'openai',
        maxInputTokens: 128000,
        maxOutputTokens: 4096,
        supportsAttachment: false,
      },
    });
    setOpen(true);
  };

  const openEdit = (tenant: TenantItem) => {
    setEditing(tenant);
    form.setFieldsValue(tenant);
    setOpen(true);
  };

  const openGroupRouterRoute = async (tenant: TenantItem) => {
    setRouteTenant(tenant);
    setRouteValue(tenant.groupRouterModelRouteId ?? undefined);
    setRouteOptions([]);
    setRouteOpen(true);
    setRouteLoading(true);
    try {
      const rows = await agentApi.admin.tenants.modelRoutes(tenant.id);
      setRouteOptions(Array.isArray(rows) ? rows : []);
    } catch {
      setRouteOptions([]);
    } finally {
      setRouteLoading(false);
    }
  };

  const saveGroupRouterRoute = async () => {
    if (!routeTenant) return;
    setRouteSaving(true);
    try {
      await agentApi.admin.tenants.updateGroupRouterRoute(
        routeTenant.id,
        routeValue ?? null,
      );
      message.success(loc('pages.save.success', 'Saved'));
      setRouteOpen(false);
      load();
    } catch (error: any) {
      message.error(error?.message || loc('pages.save.failed', 'Save failed'));
    } finally {
      setRouteSaving(false);
    }
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

  const testModel = async () => {
    const model = form.getFieldValue('defaultModel') || {};
    if (
      !model.providerCompany ||
      !model.baseUrl ||
      !model.apiKeyValue ||
      !model.modelName
    ) {
      message.warning(
        loc(
          'pages.admin.tenants.modelIncomplete',
          'Provider, base URL, API key and model name are required',
        ),
      );
      return;
    }
    setTesting(true);
    try {
      const res = await agentApi.admin.tenants.testModel({
        company: model.providerCompany,
        baseUrl: model.baseUrl,
        apiKey: model.apiKeyValue,
        modelName: model.modelName,
      });
      if (res?.ok) {
        message.success(
          loc('pages.admin.tenants.modelTestOk', 'Connection succeeded') +
            (res.latencyMs ? ` · ${res.latencyMs}ms` : ''),
        );
      } else {
        message.error(
          res?.message ||
            loc('pages.admin.tenants.modelTestFailed', 'Connection failed'),
        );
      }
    } catch (error: any) {
      if (!error?.errorFields) {
        message.error(
          error?.message ||
            loc('pages.admin.tenants.modelTestFailed', 'Connection failed'),
        );
      }
    } finally {
      setTesting(false);
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
        const groupRouterButton = (
          <Can code={PERM.adminTenantUpdate}>
            <Button
              type="link"
              size="small"
              onClick={() => openGroupRouterRoute(tenant)}
            >
              {loc('pages.admin.tenants.groupRouterShort', 'Group Router')}
            </Button>
          </Can>
        );
        if (isReservedTenant(tenant.id)) {
          return (
            <Space>
              <Tag>{loc('pages.admin.tenants.readOnly', 'Read-only')}</Tag>
              {groupRouterButton}
            </Space>
          );
        }
        return (
          <Space>
            <Can code={PERM.adminTenantUpdate}>
              <Button type="link" size="small" onClick={() => openEdit(tenant)}>
                {loc('pages.table.edit', 'Edit')}
              </Button>
            </Can>
            {groupRouterButton}
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
          {!editing && (
            <>
              <div
                style={{
                  margin: '8px 0 12px',
                  fontWeight: 600,
                  color: '#1677ff',
                }}
              >
                {loc('pages.admin.tenants.defaultModel', 'Default model')}
              </div>
              <Form.Item
                name={['defaultModel', 'providerName']}
                label={loc('pages.admin.tenants.providerName', 'Provider name')}
              >
                <Input maxLength={64} />
              </Form.Item>
              <Form.Item
                name={['defaultModel', 'providerCompany']}
                label={loc(
                  'pages.admin.tenants.providerCompany',
                  'Provider company',
                )}
                rules={[{ required: true }]}
              >
                <Input maxLength={64} placeholder="openai / qwen / deepseek" />
              </Form.Item>
              <Form.Item
                name={['defaultModel', 'baseUrl']}
                label={loc('pages.admin.tenants.baseUrl', 'Base URL')}
                rules={[{ required: true }]}
              >
                <Input maxLength={500} />
              </Form.Item>
              <Form.Item
                name={['defaultModel', 'providerConfig']}
                label={loc(
                  'pages.admin.tenants.providerConfig',
                  'Provider config (JSON)',
                )}
              >
                <Input.TextArea rows={2} maxLength={5000} />
              </Form.Item>
              <Form.Item
                name={['defaultModel', 'apiKeyName']}
                label={loc('pages.admin.tenants.apiKeyName', 'API key name')}
              >
                <Input maxLength={255} />
              </Form.Item>
              <Form.Item
                name={['defaultModel', 'apiKeyValue']}
                label={loc('pages.admin.tenants.apiKeyValue', 'API key')}
                rules={[{ required: true }]}
              >
                <Input.Password maxLength={2000} autoComplete="new-password" />
              </Form.Item>
              <Form.Item
                name={['defaultModel', 'modelName']}
                label={loc('pages.admin.tenants.modelName', 'Model name')}
                rules={[{ required: true }]}
              >
                <Input maxLength={64} />
              </Form.Item>
              <Form.Item
                name={['defaultModel', 'maxInputTokens']}
                label={loc(
                  'pages.admin.tenants.maxInputTokens',
                  'Max input tokens',
                )}
              >
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name={['defaultModel', 'maxOutputTokens']}
                label={loc(
                  'pages.admin.tenants.maxOutputTokens',
                  'Max output tokens',
                )}
              >
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
              <Button onClick={testModel} loading={testing}>
                {loc('pages.admin.tenants.testModel', 'Test connection')}
              </Button>
            </>
          )}
        </Form>
      </Modal>

      <Modal
        open={routeOpen}
        title={loc(
          'pages.admin.tenants.groupRouterRoute',
          'Group Router model route',
        )}
        onCancel={() => setRouteOpen(false)}
        onOk={saveGroupRouterRoute}
        confirmLoading={routeSaving}
        destroyOnHidden
      >
        <div style={{ marginBottom: 8, color: '#8b8b93', fontSize: 12 }}>
          {routeTenant
            ? `${routeTenant.name || routeTenant.code} (#${routeTenant.id})`
            : ''}
        </div>
        <Select
          allowClear
          showSearch
          optionFilterProp="label"
          loading={routeLoading}
          style={{ width: '100%' }}
          value={routeValue}
          onChange={(value) => setRouteValue(value as number | undefined)}
          placeholder={loc(
            'pages.admin.tenants.groupRouterRoutePlaceholder',
            'Select a route owned by this tenant',
          )}
          options={routeOptions.map((route) => ({
            value: route.id,
            label: `${route.modelName || route.modelName}${
              route.company ? ` · ${route.company}` : ''
            } (#${route.id})`,
          }))}
        />
      </Modal>
    </>
  );
}
