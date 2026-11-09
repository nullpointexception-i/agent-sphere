import {
  ArrowLeftOutlined,
  ExperimentOutlined,
  ReloadOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { App, Avatar, Button, Result, Spin, Tag } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import type {
  SuperAgent,
  SuperAgentInstances,
  TrainingSession,
} from '@/services/agentSphere/superAgent';
import { superAgentApi } from '@/services/agentSphere/superAgent';
import { SessionChat } from '../../chat/SessionChat';
import CapabilityEditDrawer from '../../instances/components/CapabilityEditDrawer';
import { tw } from '../i18n';
import { avatarStyle, initials } from '../shared';
import { useStyles } from './style';

interface Props {
  agent: SuperAgent;
  onExit: () => void;
}

const CAP_TYPES = ['skill', 'mcp', 'mcp_hub', 'cli', 'builtin'] as const;

const CAP_LABELS: Record<string, string> = {
  skill: 'Skills',
  mcp: 'MCP',
  mcp_hub: 'MCP Hub',
  cli: 'CLI',
  builtin: 'Builtin',
};

export function TrainingPanel({ agent, onExit }: Props) {
  const { styles } = useStyles();
  const { message, modal } = App.useApp();
  const [session, setSession] = useState<TrainingSession | null>(null);
  const [instances, setInstances] = useState<SuperAgentInstances | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [capOpen, setCapOpen] = useState(false);
  const [capCounts, setCapCounts] = useState<Record<string, number>>({});
  const [resetting, setResetting] = useState(false);
  const [sseConnected, setSseConnected] = useState(false);

  const workerInstanceId =
    instances?.workerInstance?.id ?? session?.workerInstanceId;
  const workerRecord = useMemo(
    () =>
      workerInstanceId
        ? {
            id: workerInstanceId,
            name: `${agent.name} Worker`,
            instanceRole: 'WORKER',
          }
        : null,
    [workerInstanceId, agent.name],
  );

  const load = async () => {
    setLoading(true);
    setError(false);
    try {
      const [training, instanceInfo] = await Promise.all([
        superAgentApi.trainingSession(agent.id),
        superAgentApi.instances(agent.id).catch(() => null),
      ]);
      setSession(training);
      setInstances(instanceInfo);
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  };

  const loadCapabilities = async (instanceId?: number) => {
    if (!instanceId) {
      setCapCounts({});
      return;
    }
    try {
      const caps = await agentApi.instanceCapabilities.listFull(instanceId);
      const counts: Record<string, number> = {};
      CAP_TYPES.forEach((type) => {
        counts[type] = (caps ?? []).filter(
          (cap: any) => cap.capabilityType === type,
        ).length;
      });
      setCapCounts(counts);
    } catch {
      setCapCounts({});
    }
  };

  useEffect(() => {
    void load();
  }, [agent.id]);

  useEffect(() => {
    void loadCapabilities(workerInstanceId);
  }, [workerInstanceId]);

  const totalCaps = useMemo(
    () => Object.values(capCounts).reduce((sum, value) => sum + value, 0),
    [capCounts],
  );

  const reset = () => {
    if (!session) return;
    modal.confirm({
      title: tw('training.resetTitle', 'Start a new training session?'),
      content: tw(
        'training.resetContent',
        'The current training context will be closed. Capability bindings are kept.',
      ),
      okText: tw('training.resetOk', 'New session'),
      cancelText: tw('common.cancel', 'Cancel'),
      onOk: async () => {
        setResetting(true);
        try {
          await superAgentApi.resetTrainingSession(agent.id, session.sessionId);
          const next = await superAgentApi.trainingSession(agent.id);
          setSession(next);
        } catch {
          message.error(
            tw('training.resetFailed', 'Failed to reset the session'),
          );
        } finally {
          setResetting(false);
        }
      },
    });
  };

  return (
    <div className={styles.overlay}>
      <div className={styles.header}>
        <button
          type="button"
          className={styles.back}
          onClick={onExit}
          title={tw('training.exit', 'Exit training')}
        >
          <ArrowLeftOutlined />
        </button>
        <Avatar size={38} style={avatarStyle(agent.name)} src={undefined}>
          {initials(agent.name)}
        </Avatar>
        <div className={styles.headerText}>
          <div className={styles.headerName}>{agent.name}</div>
          <div className={styles.headerSub}>
            {tw(
              'training.subtitle',
              'Talk directly to the Worker and evolve its capabilities',
            )}
          </div>
        </div>
        <Tag color="purple" icon={<ExperimentOutlined />}>
          {tw('training.mode', 'Training')}
        </Tag>
        <Tag color="blue">Worker</Tag>
        <Tag color={sseConnected ? 'green' : 'orange'}>
          {sseConnected
            ? tw('training.sseConnected', 'Live connected')
            : tw('training.sseReconnecting', 'Reconnecting…')}
        </Tag>
        <div className={styles.headerActions}>
          <Button
            icon={<SettingOutlined />}
            onClick={() => setCapOpen(true)}
            disabled={!workerInstanceId}
          >
            {tw('training.capabilities', 'Capabilities')} ({totalCaps})
          </Button>
          <Button
            icon={<ReloadOutlined />}
            loading={resetting}
            onClick={reset}
            disabled={!session}
          >
            {tw('training.newSession', 'New session')}
          </Button>
          <Button onClick={onExit}>
            {tw('training.exit', 'Exit training')}
          </Button>
        </div>
      </div>

      {loading ? (
        <div
          style={{
            flex: 1,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Spin />
        </div>
      ) : error || !session ? (
        <Result
          status="warning"
          title={tw('training.loadFailed', 'Failed to open training mode')}
          extra={
            <Button type="primary" onClick={() => void load()}>
              {tw('common.retry', 'Retry')}
            </Button>
          }
        />
      ) : (
        <div className={styles.body}>
          <div className={styles.chat}>
            <SessionChat
              key={session.sessionId}
              sessionId={session.sessionId}
              trainingMode
              onSseStatusChange={setSseConnected}
            />
          </div>
          <aside className={styles.rail}>
            <div className={styles.railTitle}>
              {tw('training.workerCapabilities', 'Worker capabilities')}
            </div>
            <div className={styles.railHint}>
              {tw(
                'training.workerHint',
                'Training uses the same Worker instance as production tasks. Changes apply to the next run.',
              )}
            </div>
            <div className={styles.warning}>
              {tw(
                'training.impact',
                'Capability edits affect this avatar’s real Worker tasks.',
              )}
            </div>
            {CAP_TYPES.map((type) => (
              <div key={type} className={styles.capRow}>
                <span>{CAP_LABELS[type]}</span>
                <span className={styles.capCount}>{capCounts[type] ?? 0}</span>
              </div>
            ))}
            <Button
              block
              type="primary"
              icon={<SettingOutlined />}
              disabled={!workerInstanceId}
              onClick={() => setCapOpen(true)}
            >
              {tw('training.configure', 'Configure capabilities')}
            </Button>
            {!workerInstanceId ? (
              <div className={styles.railHint}>
                {tw(
                  'training.workerMissing',
                  'Worker instance is still loading; reopen this panel if it does not appear.',
                )}
              </div>
            ) : null}
          </aside>
        </div>
      )}

      {workerRecord ? (
        <CapabilityEditDrawer
          record={workerRecord}
          open={capOpen}
          onClose={() => setCapOpen(false)}
          onSaved={() => void loadCapabilities(workerInstanceId)}
        />
      ) : null}
    </div>
  );
}
