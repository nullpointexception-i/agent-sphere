import { Select } from 'antd';
import { useEffect, useState } from 'react';
import {
  type SuperAgent,
  superAgentApi,
} from '@/services/agentSphere/superAgent';
import { agentApi } from '@/services/agentSphere/api';
import { App } from 'antd';
import { tw } from '../i18n';
import { useStyles } from '../style';

/** Cloud avatars resolve their model through the managed Router/Worker instances. */
export function CloudAvatarPanel({ agent }: { agent: SuperAgent }) {
  const { styles } = useStyles();
  const { message } = App.useApp();
  const [routes, setRoutes] = useState<any[]>([]);
  const [routeId, setRouteId] = useState<number | undefined>();
  const [mixed, setMixed] = useState(false);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let active = true;
    setLoading(true);
    void (async () => {
      try {
        const [list, instances] = await Promise.all([
          agentApi.routes.listAll(),
          superAgentApi.instances(agent.id).catch(() => null),
        ]);
        if (!active) return;
        setRoutes(list ?? []);
        const routerRoute = (instances as any)?.routerInstance?.modelRouteId;
        const workerRoute = (instances as any)?.workerInstance?.modelRouteId;
        const same = routerRoute != null && routerRoute === workerRoute;
        setRouteId(same ? routerRoute : undefined);
        setMixed(!same && (routerRoute != null || workerRoute != null));
      } catch {
        if (active) {
          setRoutes([]);
          setRouteId(undefined);
        }
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [agent.id]);

  const changeRoute = async (value: number) => {
    if (saving) return;
    const route = routes.find((item) => item.id === value);
    if (route?.apiKeyConfigured === false) {
      message.warning(tw('avatars.modelNoKey', 'This route has no API key'));
      return;
    }
    setSaving(true);
    try {
      await superAgentApi.updateModelRoute(agent.id, value);
      setRouteId(value);
      setMixed(false);
      message.success(tw('avatars.modelSaved', 'Model route updated'));
    } catch {
      message.error(tw('common.saveFailed', 'Save failed'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className={styles.avatarDetailSection}>
      <div className={styles.avatarDetailLabel}>
        {tw('avatars.model', 'Model')}
      </div>
      <Select
        className={styles.softField}
        loading={loading}
        value={routeId}
        placeholder={
          mixed
            ? tw(
                'avatars.modelMixed',
                'Router and Worker use different routes',
              )
            : tw('avatars.modelPlaceholder', 'Select a model route')
        }
        disabled={saving}
        onChange={(value: number) => void changeRoute(value)}
        options={routes.map((route) => ({
          value: route.id,
          disabled: route.apiKeyConfigured === false,
          label: `${route.providerName ? `${route.providerName} · ` : ''}${route.modelName}`,
        }))}
        showSearch
        optionFilterProp="label"
      />
      <div className={styles.fieldHint}>
        {tw(
          'avatars.modelHint',
          'Applies to both Router and Worker; takes effect on the next run.',
        )}
      </div>
    </div>
  );
}
