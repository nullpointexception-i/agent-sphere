import { BankOutlined, ExclamationCircleOutlined } from '@ant-design/icons';
import { useIntl, useModel } from '@umijs/max';
import { App, Select, Tag, Tooltip } from 'antd';
import { useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { getStoredUser, setStoredUser } from '@/utils/auth';

interface UserTenant {
  tenantId: number;
  tenantName?: string;
  tenantStatus?: string;
  visibility?: string;
  isCurrent?: boolean;
}

/**
 * Top-bar tenant switcher. The current tenant decides where new rows are
 * written and which tenant's rows are visible, so it must be visible without
 * opening a menu.
 */
const TenantSwitcher: React.FC = () => {
  const intl = useIntl();
  const { message } = App.useApp();
  const { initialState } = useModel('@@initialState');
  const [tenants, setTenants] = useState<UserTenant[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    try {
      const rows = await agentApi.admin.tenants.mine();
      setTenants(rows || []);
    } catch {
      // Keep the last known value; a transient failure must not remove the switcher.
    }
  };

  useEffect(() => {
    if ((initialState as any)?.currentUser) {
      load();
    }
  }, [(initialState as any)?.currentUser?.userid]);

  const stored = getStoredUser();
  const current =
    tenants.find((t) => t.isCurrent) ||
    (stored?.currentTenantId
      ? {
          tenantId: stored.currentTenantId,
          tenantName: stored.currentTenantName,
          isCurrent: true,
        }
      : undefined);

  const loc = (id: string, text: string) =>
    intl.formatMessage({ id, defaultMessage: text });

  const switchTo = async (tenantId: number) => {
    setLoading(true);
    try {
      const result = await agentApi.admin.tenants.switch(tenantId);
      const stored = getStoredUser();
      if (stored) {
        setStoredUser({
          ...stored,
          currentTenantId: result.currentTenantId,
          currentTenantName: result.currentTenantName,
        });
      }
      message.success(loc('pages.tenantSwitcher.switched', 'Tenant switched'));
      window.location.reload();
    } catch (error: any) {
      message.error(
        error?.message ||
          loc('pages.tenantSwitcher.switchFailed', 'Switch failed'),
      );
      setLoading(false);
    }
  };

  if (!current) {
    return (
      <Tooltip
        title={loc(
          'pages.tenantSwitcher.noTenantHint',
          'No tenant is assigned. Business lists will be empty.',
        )}
      >
        <Tag
          icon={<ExclamationCircleOutlined />}
          color="warning"
          style={{ marginRight: 8 }}
        >
          {loc('pages.tenantSwitcher.noTenant', 'No tenant')}
        </Tag>
      </Tooltip>
    );
  }

  if (tenants.length <= 1) {
    return (
      <Tooltip title={loc('pages.tenantSwitcher.hint', 'Current tenant')}>
        <Tag icon={<BankOutlined />} color="blue" style={{ marginRight: 8 }}>
          {current.tenantName || current.tenantId}
        </Tag>
      </Tooltip>
    );
  }

  return (
    <Select
      size="middle"
      style={{ minWidth: 170, marginRight: 8 }}
      loading={loading}
      value={current.tenantId}
      placeholder={loc('pages.tenantSwitcher.placeholder', 'Select tenant')}
      options={tenants.map((tenant) => ({
        value: tenant.tenantId,
        label: `${tenant.tenantName || tenant.tenantId}${tenant.isCurrent ? ` (${loc('pages.tenantSwitcher.current', 'current')})` : ''}`,
        disabled: tenant.tenantStatus !== 'ACTIVE',
      }))}
      onOpenChange={(open) => {
        if (open) load();
      }}
      onChange={switchTo}
    />
  );
};

export default TenantSwitcher;
