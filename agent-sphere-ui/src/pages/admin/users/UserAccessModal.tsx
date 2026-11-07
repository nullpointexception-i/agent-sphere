import { useIntl } from '@umijs/max';
import {
  App,
  Checkbox,
  Modal,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';

type Visibility = 'SELF_ONLY' | 'TENANT' | 'TENANT_SUBTREE';

/** Platform roles are stored with tenant_id = NULL and belong to this tenant. */
const PLATFORM_TENANT_ID = 1;

interface RoleOption {
  id: number;
  code: string;
  name?: string;
  tenantId?: number | null;
  platformRole?: boolean;
}

interface AccessRow {
  tenantId: number;
  tenantName: string;
  tenantStatus?: string;
  manageable: boolean;
  assigned: boolean;
  visibility: Visibility;
  isCurrent: boolean;
  roleIds: number[];
}

interface Props {
  open: boolean;
  userId: number | null;
  userName?: string;
  onCancel: () => void;
  onSaved: () => void;
}

const flatten = (nodes: any[], depth = 0): any[] =>
  (nodes || []).flatMap((node) => [
    { ...node, depth },
    ...flatten(node.children || [], depth + 1),
  ]);

const roleTenantId = (role: RoleOption): number | null =>
  role.platformRole ? PLATFORM_TENANT_ID : (role.tenantId ?? null);

/**
 * One dialog for tenant membership, visibility, default tenant and the roles
 * granted inside each tenants. Tenant and role grants are saved together so a
 * role never ends up in a tenant the user does not belong to.
 */
export default function UserAccessModal({
  open,
  userId,
  userName,
  onCancel,
  onSaved,
}: Props) {
  const intl = useIntl();
  const { message } = App.useApp();
  const [rows, setRows] = useState<AccessRow[]>([]);
  const [roles, setRoles] = useState<RoleOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const loc = (id: string, text: string) =>
    intl.formatMessage({ id, defaultMessage: text });

  useEffect(() => {
    if (!open || !userId) return;
    setLoading(true);
    Promise.all([
      agentApi.admin.tenants.tree(),
      agentApi.admin.tenants.userAssignments(userId),
      agentApi.admin.roles.listAll(),
      agentApi.admin.roles.listByUser(userId),
    ])
      .then(([tree, assignments, allRoles, userRoles]) => {
        const assigned = new Map<number, any>(
          (assignments || []).map((a: any) => [a.tenantId, a]),
        );
        const roleIdsByTenant = new Map<number, number[]>();
        for (const role of userRoles || []) {
          const tenantId = roleTenantId(role);
          if (tenantId == null) continue;
          const list = roleIdsByTenant.get(tenantId) ?? [];
          list.push(role.id);
          roleIdsByTenant.set(tenantId, list);
        }
        const next = flatten(tree).map((tenant) => {
          const existing = assigned.get(tenant.id);
          return {
            tenantId: tenant.id,
            tenantName: `${'  '.repeat(tenant.depth)}${tenant.name}`,
            tenantStatus: tenant.status,
            manageable: tenant.manageable !== false,
            assigned: !!existing,
            visibility: (existing?.visibility || 'TENANT') as Visibility,
            isCurrent: existing?.isCurrent === true,
            roleIds: roleIdsByTenant.get(tenant.id) ?? [],
          } as AccessRow;
        });
        setRows(next);
        setRoles(allRoles || []);
      })
      .catch(() => {
        setRows([]);
        setRoles([]);
      })
      .finally(() => setLoading(false));
  }, [open, userId]);

  const roleOptionsByTenant = useMemo(() => {
    const grouped = new Map<number, RoleOption[]>();
    for (const role of roles) {
      const tenantId = roleTenantId(role);
      if (tenantId == null) continue;
      const list = grouped.get(tenantId) ?? [];
      list.push(role);
      grouped.set(tenantId, list);
    }
    return grouped;
  }, [roles]);

  const currentCount = useMemo(
    () => rows.filter((r) => r.assigned && r.isCurrent).length,
    [rows],
  );

  const patch = (tenantId: number, changes: Partial<AccessRow>) => {
    setRows((prev) =>
      prev.map((row) => {
        if (row.tenantId !== tenantId) return row;
        const next = { ...row, ...changes };
        if (!next.assigned) {
          next.isCurrent = false;
          next.roleIds = [];
        }
        return next;
      }),
    );
  };

  const save = async () => {
    if (!userId) return;
    const selected = rows.filter((r) => r.assigned);
    if (selected.length === 0) {
      message.error(
        loc('pages.tenantAssign.atLeastOne', 'Select at least one tenant'),
      );
      return;
    }
    if (selected.filter((r) => r.isCurrent).length !== 1) {
      message.error(
        loc(
          'pages.tenantAssign.oneCurrent',
          'Select exactly one current tenant',
        ),
      );
      return;
    }
    const roleIds = [
      ...new Set(selected.flatMap((row) => row.roleIds)),
    ] as number[];
    setSaving(true);
    try {
      await agentApi.admin.tenants.assign({
        userId,
        assignments: selected.map((row) => ({
          tenantId: row.tenantId,
          visibility: row.visibility,
          isCurrent: row.isCurrent,
        })),
      });
      await agentApi.admin.roles.assignUser(userId, roleIds);
      message.success(loc('pages.save.success', 'Saved'));
      onSaved();
    } catch (error: any) {
      message.error(
        error?.message || loc('pages.chat.saveFailed', 'Save failed'),
      );
    } finally {
      setSaving(false);
    }
  };

  const columns = [
    {
      title: loc('pages.tenantAssign.assigned', 'Assigned'),
      width: 70,
      render: (_: any, row: AccessRow) => (
        <Checkbox
          checked={row.assigned}
          disabled={!row.manageable}
          onChange={(e) => patch(row.tenantId, { assigned: e.target.checked })}
        />
      ),
    },
    {
      title: loc('pages.tenantAssign.tenant', 'Tenant'),
      dataIndex: 'tenantName',
      key: 'tenantName',
      width: 210,
      render: (name: string, row: AccessRow) => (
        <Space>
          <span>{name}</span>
          {row.tenantStatus !== 'ACTIVE' && <Tag>{row.tenantStatus}</Tag>}
        </Space>
      ),
    },
    {
      title: loc('pages.tenantAssign.visibility', 'Visibility'),
      width: 240,
      render: (_: any, row: AccessRow) => (
        <Radio.Group
          value={row.visibility}
          disabled={!row.assigned || !row.manageable}
          onChange={(e) => patch(row.tenantId, { visibility: e.target.value })}
        >
          <Space direction="vertical" size={0}>
            <Radio value="SELF_ONLY">
              {loc('pages.tenantAssign.selfOnly', 'Only own rows')}
            </Radio>
            <Radio value="TENANT">
              {loc('pages.tenantAssign.tenantWide', 'Current tenant')}
            </Radio>
            <Radio value="TENANT_SUBTREE">
              {loc(
                'pages.tenantAssign.subtree',
                'Current tenant and descendants',
              )}
            </Radio>
          </Space>
        </Radio.Group>
      ),
    },
    {
      title: loc('pages.tenantAssign.defaultTenant', 'Default tenant'),
      width: 100,
      render: (_: any, row: AccessRow) => (
        <Radio
          checked={row.isCurrent}
          disabled={!row.assigned || !row.manageable}
          onChange={() =>
            setRows((prev) =>
              prev.map((item) => ({
                ...item,
                isCurrent: item.tenantId === row.tenantId,
              })),
            )
          }
        />
      ),
    },
    {
      title: loc('pages.userAccess.roles', 'Tenant roles'),
      render: (_: any, row: AccessRow) => {
        const options = (roleOptionsByTenant.get(row.tenantId) ?? []).map(
          (role) => ({
            value: role.id,
            label: `${role.name || role.code} (${role.code})`,
          }),
        );
        return (
          <Select
            mode="multiple"
            allowClear
            style={{ width: '100%' }}
            placeholder={loc(
              'pages.userAccess.rolesPlaceholder',
              'Select roles',
            )}
            value={row.roleIds}
            disabled={!row.assigned || !row.manageable}
            options={options}
            optionFilterProp="label"
            maxTagCount="responsive"
            onChange={(vals) =>
              patch(row.tenantId, { roleIds: vals as number[] })
            }
          />
        );
      },
    },
  ];

  return (
    <Modal
      title={`${loc('pages.userAccess.title', 'Tenants and roles')}${userName ? ` - ${userName}` : ''}`}
      open={open}
      width={1000}
      onCancel={onCancel}
      onOk={save}
      confirmLoading={saving}
      destroyOnClose
    >
      <Typography.Paragraph type="secondary">
        {loc(
          'pages.userAccess.tip',
          'Checked means the tenant is assigned. Roles are granted inside their source tenant; the default tenant decides where new data belongs.',
        )}
      </Typography.Paragraph>
      <Table
        rowKey="tenantId"
        size="small"
        loading={loading}
        dataSource={rows}
        columns={columns as any}
        pagination={false}
        scroll={{ y: 420 }}
      />
      {currentCount > 1 && (
        <Typography.Text type="danger">
          {loc(
            'pages.tenantAssign.oneCurrent',
            'Select exactly one current tenant',
          )}
        </Typography.Text>
      )}
    </Modal>
  );
}
