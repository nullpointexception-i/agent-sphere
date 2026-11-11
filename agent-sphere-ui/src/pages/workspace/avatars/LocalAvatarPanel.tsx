import {
  App,
  Alert,
  Button,
  Input,
  Select,
  Space,
  Switch,
  Tag,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  type SuperAgent,
  superAgentApi,
} from '@/services/agentSphere/superAgent';
import {
  type LocalRuntime,
  localRuntimeApi,
} from '@/services/agentSphere/localRuntime';
import {
  type DesktopAgentProfile,
  getDesktopBridge,
} from '@/utils/desktopBridge';
import { tw } from '../i18n';
import { useStyles } from '../style';

const DEFAULT_LOCAL_PROFILE: Omit<
  DesktopAgentProfile,
  'agentId' | 'agentName'
> = {
  driver: 'OPENCODE',
  workspace: '',
  approvalPolicy: 'on-request',
  sandbox: 'workspace-write',
  autoRun: 'mention-only',
  enabled: true,
};

interface Props {
  agent: SuperAgent;
  onUpdated: (agent: SuperAgent) => void;
}

export function LocalAvatarPanel({ agent, onUpdated }: Props) {
  const { styles } = useStyles();
  const { message } = App.useApp();
  const bridge = getDesktopBridge();
  const [runtimes, setRuntimes] = useState<LocalRuntime[]>([]);
  const [runtimeId, setRuntimeId] = useState<number | undefined>(
    agent.localRuntimeId ?? undefined,
  );
  const [savingRuntime, setSavingRuntime] = useState(false);
  const [profile, setProfile] = useState<DesktopAgentProfile | null>(null);
  const [profileLoading, setProfileLoading] = useState(false);
  const [savingProfile, setSavingProfile] = useState(false);

  useEffect(() => {
    let active = true;
    void localRuntimeApi
      .list()
      .then((list) => {
        if (!active) return;
        setRuntimes(list ?? []);
        if (agent.localRuntimeId == null && (list ?? []).length === 1) {
          setRuntimeId(list[0].id);
        }
      })
      .catch(() => {
        if (active) setRuntimes([]);
      });
    return () => {
      active = false;
    };
  }, [agent.localRuntimeId]);

  useEffect(() => {
    if (!bridge) return;
    let active = true;
    setProfileLoading(true);
    void bridge.profiles
      .list()
      .then((list) => {
        if (!active) return;
        const found = (list ?? []).find((item) => item.agentId === agent.id);
        setProfile(
          found ?? {
            ...DEFAULT_LOCAL_PROFILE,
            agentId: agent.id,
            agentName: agent.name,
          },
        );
      })
      .catch(() => {
        if (active) setProfile(null);
      })
      .finally(() => {
        if (active) setProfileLoading(false);
      });
    return () => {
      active = false;
    };
  }, [agent.id, agent.name, bridge]);

  const selectedRuntime = useMemo(
    () => runtimes.find((item) => item.id === runtimeId),
    [runtimeId, runtimes],
  );
  const driverKind = selectedRuntime?.runtimeKind ?? 'OPENCODE';

  const autoRunOptions = useMemo(
    () => [
      {
        value: 'mention-only',
        label: tw('avatars.autoRun.mention-only', 'Only when mentioned'),
      },
      {
        value: 'confirm',
        label: tw('avatars.autoRun.confirm', 'Confirm every time'),
      },
      {
        value: 'always',
        label: tw('avatars.autoRun.always', 'Run automatically'),
      },
    ],
    [],
  );

  const approvalOptions = useMemo(
    () => [
      {
        value: 'on-request',
        label: tw('avatars.approval.on-request', 'On request'),
      },
      { value: 'untrusted', label: tw('avatars.approval.untrusted', 'Strict') },
      { value: 'never', label: tw('avatars.approval.never', 'Never') },
    ],
    [],
  );

  const sandboxOptions = useMemo(
    () => [
      {
        value: 'workspace-write',
        label: tw('avatars.sandbox.workspace-write', 'Workspace write'),
      },
      { value: 'read-only', label: tw('avatars.sandbox.read-only', 'Read only') },
      {
        value: 'danger-full-access',
        label: tw('avatars.sandbox.danger-full-access', 'Full access'),
      },
    ],
    [],
  );

  const saveRuntime = async () => {
    if (!runtimeId || savingRuntime) return;
    setSavingRuntime(true);
    try {
      const updated = await superAgentApi.updateLocalRuntime(agent.id, runtimeId);
      onUpdated(updated);
      if (bridge) {
        const list = await bridge.profiles.list();
        const existing = (list ?? []).find((item) => item.agentId === agent.id);
        if (existing && existing.driver !== driverKind) {
          await bridge.profiles.save({ ...existing, driver: driverKind });
          setProfile({ ...existing, driver: driverKind });
        }
      }
      message.success(tw('avatars.localRuntimeSaved', 'Local runtime updated'));
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setSavingRuntime(false);
    }
  };

  const patchProfile = (values: Partial<DesktopAgentProfile>) => {
    setProfile((previous) =>
      previous ? { ...previous, ...values } : previous,
    );
  };

  const chooseWorkspace = async () => {
    if (!bridge) return;
    const path = await bridge.profiles.pickWorkspace();
    if (path) patchProfile({ workspace: path });
  };

  const saveProfile = async () => {
    if (!bridge || !profile) return;
    if (!profile.workspace.trim()) {
      message.warning(tw('avatars.workspaceRequired', 'Choose a workspace'));
      return;
    }
    setSavingProfile(true);
    try {
      const next = await bridge.profiles.save({
        ...profile,
        agentId: agent.id,
        agentName: agent.name,
        driver: driverKind,
        workspace: profile.workspace.trim(),
      });
      const saved = (next ?? []).find((item) => item.agentId === agent.id);
      setProfile(saved ?? profile);
      message.success(tw('avatars.localProfileSaved', 'Local settings saved'));
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setSavingProfile(false);
    }
  };

  const removeBinding = async () => {
    if (!bridge) return;
    try {
      await bridge.profiles.remove(agent.id);
      setProfile({
        ...DEFAULT_LOCAL_PROFILE,
        agentId: agent.id,
        agentName: agent.name,
        driver: driverKind,
      });
      message.success(tw('avatars.bindingRemoved', 'Local binding removed'));
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : String(reason));
    }
  };

  return (
    <>
      <div className={styles.avatarDetailSection}>
        <div className={styles.avatarDetailLabel}>
          {tw('avatars.localRuntime', 'Local runtime')}
        </div>
        <Space.Compact style={{ width: '100%' }}>
          <Select
            style={{ flex: 1 }}
            value={runtimeId}
            placeholder={tw('avatars.localRuntimePlaceholder', 'Select a runtime')}
            onChange={(value: number) => setRuntimeId(value)}
            options={runtimes.map((item) => ({
              value: item.id,
              label: `${item.name} · ${item.runtimeKind}${item.online ? '' : ' (offline)'}`,
            }))}
          />
          <Button
            loading={savingRuntime}
            disabled={!runtimeId || runtimeId === agent.localRuntimeId}
            onClick={() => void saveRuntime()}
          >
            {tw('common.save', 'Save')}
          </Button>
        </Space.Compact>
        <div className={styles.fieldHint}>
          {selectedRuntime ? (
            <>
              <Tag color={selectedRuntime.online ? 'green' : 'default'}>
                {selectedRuntime.online
                  ? tw('avatars.runtimeOnline', 'Online')
                  : tw('avatars.runtimeOffline', 'Offline')}
              </Tag>
              {selectedRuntime.runtimeKind}
            </>
          ) : (
            tw('avatars.localRuntimeHint', 'Tasks run on this machine through the runtime.')
          )}
        </div>
      </div>

      <div className={styles.avatarDetailSection}>
        <div className={styles.avatarDetailLabel}>
          {tw('avatars.localConfig', 'Local settings')}
        </div>
        {!bridge ? (
          <Alert
            type="info"
            showIcon
            message={tw(
              'avatars.localConfigDesktopOnly',
              'Edit local settings in AgentSphere Desktop.',
            )}
          />
        ) : !profile ? (
          <div className={styles.fieldHint}>
            {profileLoading
              ? tw('common.loading', 'Loading...')
              : tw('avatars.localConfigMissing', 'Local settings are unavailable.')}
          </div>
        ) : (
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <div className={styles.avatarStatusRow}>
              <span className={styles.avatarDetailLabel}>Driver</span>
              <Tag color="blue">{driverKind}</Tag>
            </div>
            <Space.Compact style={{ width: '100%' }}>
              <Input
                value={profile.workspace}
                placeholder="D:\\projects\\demo"
                style={{ flex: 1 }}
                onChange={(event) =>
                  patchProfile({ workspace: event.target.value })
                }
              />
              <Button onClick={() => void chooseWorkspace()}>
                {tw('avatars.chooseWorkspace', 'Choose')}
              </Button>
            </Space.Compact>
            <Select
              style={{ width: '100%' }}
              value={profile.autoRun}
              onChange={(value: string) =>
                patchProfile({
                  autoRun: value as DesktopAgentProfile['autoRun'],
                })
              }
              options={autoRunOptions}
            />
            <Select
              style={{ width: '100%' }}
              value={profile.approvalPolicy}
              onChange={(value: string) =>
                patchProfile({
                  approvalPolicy:
                    value as DesktopAgentProfile['approvalPolicy'],
                })
              }
              options={approvalOptions}
            />
            <Select
              style={{ width: '100%' }}
              value={profile.sandbox}
              onChange={(value: string) =>
                patchProfile({ sandbox: value as DesktopAgentProfile['sandbox'] })
              }
              options={sandboxOptions}
            />
            <div className={styles.avatarStatusRow}>
              <span className={styles.avatarDetailLabel}>
                {tw('avatars.enabled', 'Enabled')}
              </span>
              <Switch
                checked={profile.enabled}
                onChange={(checked) => patchProfile({ enabled: checked })}
              />
            </div>
            <Space>
              <Button
                type="primary"
                loading={savingProfile}
                onClick={() => void saveProfile()}
              >
                {tw('avatars.saveLocal', 'Save local settings')}
              </Button>
              <Button danger type="text" onClick={() => void removeBinding()}>
                {tw('avatars.removeBinding', 'Remove binding')}
              </Button>
            </Space>
          </Space>
        )}
      </div>
    </>
  );
}
