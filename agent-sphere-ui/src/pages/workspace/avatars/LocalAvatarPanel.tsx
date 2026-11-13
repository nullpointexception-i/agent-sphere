import { InfoCircleOutlined, LinkOutlined } from '@ant-design/icons';
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
  Tooltip,
  Typography,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  type IssuedLocalRuntimeToken,
  type LocalRuntime,
  localRuntimeApi,
} from '@/services/agentSphere/localRuntime';
import {
  type SuperAgent,
  superAgentApi,
} from '@/services/agentSphere/superAgent';
import {
  type DesktopAgentProfile,
  type DesktopBridgeDriver,
  type DesktopBridgeModel,
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
  const [models, setModels] = useState<DesktopBridgeModel[]>([]);
  const [modelsLoading, setModelsLoading] = useState(false);

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

  const effectiveKind = (boundRuntime?.runtimeKind ??
    connecting ??
    profile?.driver ??
    'OPENCODE') as DesktopDriverKind;

  useEffect(() => {
    if (!bridge || effectiveKind !== 'OPENCODE') {
      setModels([]);
      return;
    }
    let active = true;
    setModelsLoading(true);
    void bridge.drivers
      .models('OPENCODE')
      .then((list) => {
        if (active) setModels(list ?? []);
      })
      .catch(() => {
        if (active) setModels([]);
      })
      .finally(() => {
        if (active) setModelsLoading(false);
      });
    return () => {
      active = false;
    };
  }, [bridge, effectiveKind]);

  const driverOf = (kind: DesktopDriverKind) =>
    drivers.find((item) => item.kind === kind);

  const isUsableDriverState = (state?: string) =>
    state === 'READY' || state === 'VERSION_MISMATCH';

  const driverReady = (kind: DesktopDriverKind) =>
    isUsableDriverState(driverOf(kind)?.state);

  const recordOf = (kind: DesktopDriverKind) =>
    boundRuntime?.runtimeKind === kind
      ? boundRuntime
      : runtimes.find((item) => item.runtimeKind === kind);

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
        await localRuntimeApi
          .delete(agent.localRuntimeId)
          .catch(() => undefined);
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

      const probe = (await bridge.drivers.detect()).find(
        (item) => item.kind === kind,
      );
      if (!isUsableDriverState(probe?.state)) {
        throw new Error(
          tw(
            'avatars.probeFailed',
            'Runtime query failed. Retry or use manual connect.',
          ),
        );
      }

      const profiles = await bridge.profiles.list();
      const existing = (profiles ?? []).find(
        (item) => item.agentId === agent.id,
      );
      if (existing && existing.driver !== kind) {
        await bridge.profiles.save({ ...existing, driver: kind });
        setProfile({ ...existing, driver: kind });
      }

      await reloadRuntimes();
      message.success(
        tw('avatars.runtimeConnected', 'Local runtime connected'),
      );
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
      const list = boundRuntime
        ? runtimes
        : await localRuntimeApi.list().catch(() => runtimes);
      const bound = list.find((item) => item.id === agent.localRuntimeId);
      const driver =
        bound?.runtimeKind ?? boundRuntime?.runtimeKind ?? profile.driver;
      const next = await bridge.profiles.save({
        ...profile,
        agentId: agent.id,
        agentName: agent.name,
        driver,
        model: profile.model?.trim() || undefined,
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
    connecting ??
    undefined;

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
      {
        value: 'read-only',
        label: tw('avatars.sandbox.read-only', 'Read only'),
      },
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
                const record = recordOf(kind);
                const online = Boolean(record?.online);
                const dotTitle = record
                  ? `${meta.label} · ${
                      online
                        ? tw('avatars.runtimeOnline', 'Online')
                        : tw('avatars.runtimeOffline', 'Offline')
                    }${record.runtimeVersion ? ` · v${record.runtimeVersion}` : ''}`
                  : tw('avatars.runtimeNotConnected', 'Not connected');
                return (
                  <Radio
                    key={kind}
                    value={kind}
                    disabled={!ready}
                    className={styles.runtimeOption}
                  >
                    <Space size={8}>
                      <img
                        src={meta.icon}
                        alt={meta.label}
                        width={18}
                        height={18}
                        style={{ borderRadius: 4, display: 'block' }}
                      />
                      <span>{meta.label}</span>
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
                              {driver?.state === 'VERSION_MISMATCH'
                                ? tw(
                                    'avatars.runtimeVersionMismatch',
                                    'Version mismatch',
                                  )
                                : (driver?.state ?? '-')}
                            </div>
                            {driver?.state === 'VERSION_MISMATCH' &&
                            driver.message ? (
                              <div style={{ color: '#faad14' }}>
                                {driver.message}
                              </div>
                            ) : null}
                          </div>
                        }
                      >
                        <InfoCircleOutlined style={{ color: '#8c8c8c' }} />
                      </Popover>
                      <Tooltip title={dotTitle}>
                        <span
                          className={styles.runtimeDot}
                          style={{
                            background: online ? '#52c41a' : '#d9d9d9',
                          }}
                        />
                      </Tooltip>
                      {driver?.state === 'VERSION_MISMATCH' ? (
                        <Tooltip title={driver.message}>
                          <Tag color="warning">
                            {tw(
                              'avatars.runtimeVersionMismatch',
                              'Version mismatch',
                            )}
                          </Tag>
                        </Tooltip>
                      ) : !ready ? (
                        <Tag>
                          {tw('avatars.runtimeMissing', 'Not detected')}
                        </Tag>
                      ) : null}
                      {boundRuntime?.runtimeKind === kind &&
                      !online &&
                      !connecting ? (
                        <Button
                          type="link"
                          size="small"
                          style={{ padding: 0 }}
                          onClick={(event) => {
                            event.preventDefault();
                            event.stopPropagation();
                            void connect(kind);
                          }}
                        >
                          {tw('avatars.reconnect', 'Connect')}
                        </Button>
                      ) : null}
                    </Space>
                  </Radio>
                );
              })}
            </Radio.Group>

            {connecting ? (
              <div className={styles.fieldHint}>
                <Space size={6}>
                  <LinkOutlined />
                  {tw('avatars.runtimeConnecting', 'Connecting...')}
                </Space>
              </div>
            ) : !boundRuntime ? (
              <div className={styles.fieldHint}>
                {tw(
                  'avatars.runtimeEmpty',
                  'Choose a runtime to connect this device.',
                )}
              </div>
            ) : null}

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
                          (drivers.find((item) =>
                            isUsableDriverState(item.state),
                          )?.kind as DesktopDriverKind) ??
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
              : tw(
                  'avatars.localConfigMissing',
                  'Local settings are unavailable.',
                )}
          </div>
        ) : (
          <>
            <div className={styles.avatarForm}>
              <span className={styles.avatarFormLabel}>
                {tw('avatars.driver', 'Driver')}
              </span>
              <Tag color="blue">
                {boundRuntime?.runtimeKind ?? profile.driver}
              </Tag>

              {effectiveKind === 'OPENCODE' ? (
                <>
                  <span className={styles.avatarFormLabel}>
                    {tw('avatars.model', 'Model')}
                  </span>
                  <Select
                    showSearch
                    allowClear
                    loading={modelsLoading}
                    value={profile.model || undefined}
                    placeholder={tw(
                      'avatars.localModelPlaceholder',
                      'opencode-go/deepseek-v4.1-flash',
                    )}
                    optionFilterProp="label"
                    onChange={(value: string | undefined) =>
                      patchProfile({ model: value ?? '' })
                    }
                    options={models.map((item) => ({
                      value: `${item.providerID}/${item.id}`,
                      label: `${item.name || item.id} · ${item.providerID}`,
                    }))}
                  />
                  <div
                    className={styles.fieldHint}
                    style={{ gridColumn: '1 / -1', marginTop: -4 }}
                  >
                    {tw(
                      'avatars.localModelHint',
                      'Leave empty to use the opencode default model.',
                    )}
                  </div>
                </>
              ) : null}

              <span className={styles.avatarFormLabel}>
                {tw('avatars.workspace', 'Workspace')}
              </span>
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

              <span className={styles.avatarFormLabel}>
                {tw('avatars.autoRun', 'Execution policy')}
              </span>
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

              <span className={styles.avatarFormLabel}>
                {tw('avatars.approvalPolicy', 'Tool approvals')}
              </span>
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

              <span className={styles.avatarFormLabel}>
                {tw('avatars.sandbox', 'Sandbox')}
              </span>
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

              <span className={styles.avatarFormLabel}>
                {tw('avatars.enabled', 'Enabled')}
              </span>
              <Switch
                checked={profile.enabled}
                onChange={(checked) => patchProfile({ enabled: checked })}
              />
              {!profile.workspace.trim() ? (
                <div style={{ gridColumn: '1 / -1' }}>
                  <Typography.Text type="warning" style={{ fontSize: 12 }}>
                    {tw(
                      'avatars.localConfigUnbound',
                      'Choose a workspace and save; tasks will fail until then.',
                    )}
                  </Typography.Text>
                </div>
              ) : null}
            </div>
            <Space style={{ marginTop: 12 }}>
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
          </>
        )}
      </div>
    </>
  );
}
