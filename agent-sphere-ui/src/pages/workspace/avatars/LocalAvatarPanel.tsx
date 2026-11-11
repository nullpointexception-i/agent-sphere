import {
  InfoCircleOutlined,
  LinkOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Input,
  Popover,
  Radio,
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
  type IssuedLocalRuntimeToken,
  type LocalRuntime,
  localRuntimeApi,
} from '@/services/agentSphere/localRuntime';
import {
  type DesktopAgentProfile,
  type DesktopBridgeDriver,
  type DesktopDriverKind,
  getDesktopBridge,
} from '@/utils/desktopBridge';
import { tw } from '../i18n';
import { useStyles } from '../style';

const RUNTIME_META: Record<
  DesktopDriverKind,
  { label: string; icon: string; description: string }
> = {
  OPENCODE: {
    label: 'opencode',
    icon: '/runtime/opencode.png',
    description: 'opencode Desktop',
  },
  CODEX: {
    label: 'Codex',
    icon: '/runtime/codex.png',
    description: 'ChatGPT / Codex Desktop',
  },
};

const RUNTIME_KINDS: DesktopDriverKind[] = ['OPENCODE', 'CODEX'];

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
  const [drivers, setDrivers] = useState<DesktopBridgeDriver[]>([]);
  const [connecting, setConnecting] = useState<DesktopDriverKind>();
  const [connectError, setConnectError] = useState<string>();
  const [profile, setProfile] = useState<DesktopAgentProfile | null>(null);
  const [profileLoading, setProfileLoading] = useState(false);
  const [savingProfile, setSavingProfile] = useState(false);

  const boundRuntime = useMemo(
    () => runtimes.find((item) => item.id === agent.localRuntimeId),
    [runtimes, agent.localRuntimeId],
  );

  const reloadRuntimes = async () => {
    try {
      setRuntimes((await localRuntimeApi.list()) ?? []);
    } catch {
      setRuntimes([]);
    }
  };

  useEffect(() => {
    void reloadRuntimes();
  }, [agent.localRuntimeId]);

  useEffect(() => {
    if (!bridge) return;
    void bridge.drivers
      .detect()
      .then(setDrivers)
      .catch(() => setDrivers([]));
  }, [bridge]);

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

  const driverOf = (kind: DesktopDriverKind) =>
    drivers.find((item) => item.kind === kind);

  const driverReady = (kind: DesktopDriverKind) =>
    driverOf(kind)?.state === 'READY';

  const waitUntilOnline = async (): Promise<boolean> => {
    if (!bridge) return false;
    for (let attempt = 0; attempt < 20; attempt += 1) {
      const status = await bridge.runtime.status();
      if (status.state === 'ONLINE') return true;
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    return false;
  };

  const createRuntime = async (
    kind: DesktopDriverKind,
    deviceKey: string,
  ): Promise<IssuedLocalRuntimeToken> => {
    try {
      return await localRuntimeApi.create({
        name: agent.name,
        runtimeKind: kind,
        deviceKey,
      });
    } catch (error) {
      // A previous attempt may have left an unbound record on this device.
      const [list, agents] = await Promise.all([
        localRuntimeApi.list().catch(() => []),
        superAgentApi.list(100).catch(() => []),
      ]);
      const bound = new Set(
        (agents ?? [])
          .map((item) => item.localRuntimeId)
          .filter((id): id is number => id != null),
      );
      const orphan = (list ?? []).find(
        (item) => !bound.has(item.id) && item.runtimeKind === kind,
      );
      if (!orphan) throw error;
      await localRuntimeApi.delete(orphan.id).catch(() => undefined);
      return localRuntimeApi.create({
        name: agent.name,
        runtimeKind: kind,
        deviceKey,
      });
    }
  };

  const connect = async (kind: DesktopDriverKind) => {
    if (!bridge || connecting) return;
    setConnecting(kind);
    setConnectError(undefined);
    try {
      const settings = await bridge.settings.get();

      // One runtime per device: drop the avatar's previous record first so the
      // stable device key can be reused.
      if (agent.localRuntimeId) {
        await localRuntimeApi.delete(agent.localRuntimeId).catch(() => undefined);
      }

      const issued = await createRuntime(kind, settings.deviceKey);
      const bound = await superAgentApi.updateLocalRuntime(agent.id, issued.id);
      onUpdated(bound);

      await bridge.runtime.configure({
        serverUrl: settings.serverUrl,
        runtimeId: issued.id,
        deviceToken: issued.token,
        runtimeName: issued.name,
      });

      const online = await waitUntilOnline();
      if (!online) {
        throw new Error(
          tw(
            'avatars.connectTimeout',
            'Connection timed out. Retry or use manual connect.',
          ),
        );
      }

      const profiles = await bridge.profiles.list();
      const existing = (profiles ?? []).find((item) => item.agentId === agent.id);
      if (existing && existing.driver !== kind) {
        await bridge.profiles.save({ ...existing, driver: kind });
        setProfile({ ...existing, driver: kind });
      }

      await reloadRuntimes();
      message.success(tw('avatars.runtimeConnected', 'Local runtime connected'));
    } catch (reason) {
      setConnectError(
        reason instanceof Error ? reason.message : String(reason),
      );
      await reloadRuntimes();
    } finally {
      setConnecting(undefined);
    }
  };

  const patchProfile = (values: Partial<DesktopAgentProfile>) => {
    setProfile((previous) => (previous ? { ...previous, ...values } : previous));
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
      const driver = boundRuntime?.runtimeKind ?? profile.driver;
      const next = await bridge.profiles.save({
        ...profile,
        agentId: agent.id,
        agentName: agent.name,
        driver,
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
      });
      message.success(tw('avatars.bindingRemoved', 'Local binding removed'));
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : String(reason));
    }
  };

  const selectedKind =
    (boundRuntime?.runtimeKind as DesktopDriverKind | undefined) ??
    (connecting ?? undefined);

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

  return (
    <>
      <div className={styles.avatarDetailSection}>
        <div className={styles.avatarDetailLabel}>
          {tw('avatars.localRuntime', 'Local runtime')}
        </div>

        {!bridge ? (
          <Alert
            type="info"
            showIcon
            message={tw(
              'avatars.runtimeDesktopOnly',
              'Connect the local runtime in AgentSphere Desktop.',
            )}
            description={
              boundRuntime
                ? `${boundRuntime.name} · ${boundRuntime.runtimeKind} · ${
                    boundRuntime.online
                      ? tw('avatars.runtimeOnline', 'Online')
                      : tw('avatars.runtimeOffline', 'Offline')
                  }`
                : undefined
            }
          />
        ) : (
          <>
            <Radio.Group
              value={selectedKind}
              disabled={Boolean(connecting)}
              onChange={(event) =>
                void connect(event.target.value as DesktopDriverKind)
              }
              style={{ display: 'flex', flexDirection: 'column', gap: 8 }}
            >
              {RUNTIME_KINDS.map((kind) => {
                const driver = driverOf(kind);
                const ready = driverReady(kind);
                const meta = RUNTIME_META[kind];
                return (
                  <Radio key={kind} value={kind} disabled={!ready}>
                    <Space size={8}>
                      <img
                        src={meta.icon}
                        alt={meta.label}
                        width={18}
                        height={18}
                        style={{ borderRadius: 4, display: 'block' }}
                      />
                      <span>{meta.label}</span>
                      {!ready ? (
                        <Tag>{tw('avatars.runtimeMissing', 'Not detected')}</Tag>
                      ) : null}
                      <Popover
                        content={
                          <div style={{ fontSize: 12, lineHeight: 1.7 }}>
                            <div>
                              {tw('avatars.runtimeVersion', 'Version')}:{' '}
                              {driver?.version ?? '-'}
                            </div>
                            <div>
                              {tw('avatars.runtimePath', 'Path')}:{' '}
                              {driver?.path ?? '-'}
                            </div>
                            <div>
                              {tw('avatars.runtimeState', 'State')}:{' '}
                              {driver?.state ?? '-'}
                            </div>
                          </div>
                        }
                      >
                        <InfoCircleOutlined style={{ color: '#8c8c8c' }} />
                      </Popover>
                    </Space>
                  </Radio>
                );
              })}
            </Radio.Group>

            <div className={styles.fieldHint}>
              {connecting ? (
                <Space size={6}>
                  <LinkOutlined />
                  {tw('avatars.runtimeConnecting', 'Connecting...')}
                </Space>
              ) : boundRuntime ? (
                <Space size={8} wrap>
                  <Tag color={boundRuntime.online ? 'green' : 'default'}>
                    {boundRuntime.online
                      ? tw('avatars.runtimeOnline', 'Online')
                      : tw('avatars.runtimeOffline', 'Offline')}
                  </Tag>
                  <span>{boundRuntime.name}</span>
                  <Tag color="blue">{boundRuntime.runtimeKind}</Tag>
                  {boundRuntime.runtimeVersion ? (
                    <span>v{boundRuntime.runtimeVersion}</span>
                  ) : null}
                  {!boundRuntime.online ? (
                    <Button
                      size="small"
                      type="primary"
                      onClick={() =>
                        void connect(
                          boundRuntime.runtimeKind as DesktopDriverKind,
                        )
                      }
                    >
                      {tw('avatars.reconnect', 'Connect')}
                    </Button>
                  ) : null}
                </Space>
              ) : (
                tw(
                  'avatars.runtimeEmpty',
                  'Choose a runtime to connect this device.',
                )
              )}
            </div>

            {connectError ? (
              <Alert
                type="error"
                showIcon
                message={connectError}
                action={
                  <Button
                    size="small"
                    onClick={() =>
                      void connect(
                        (boundRuntime?.runtimeKind as DesktopDriverKind) ??
                          (drivers.find((item) => item.state === 'READY')
                            ?.kind as DesktopDriverKind) ??
                          'OPENCODE',
                      )
                    }
                  >
                    {tw('avatars.manualConnect', 'Manual connect')}
                  </Button>
                }
              />
            ) : null}
          </>
        )}
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
              <span className={styles.avatarDetailLabel}>
                {tw('avatars.driver', 'Driver')}
              </span>
              <Tag color="blue">
                {boundRuntime?.runtimeKind ?? profile.driver}
              </Tag>
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
                patchProfile({
                  sandbox: value as DesktopAgentProfile['sandbox'],
                })
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
