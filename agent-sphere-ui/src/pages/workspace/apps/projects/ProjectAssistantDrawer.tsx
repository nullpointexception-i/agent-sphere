import {
  BulbOutlined,
  ReloadOutlined,
  RobotOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import {
  App,
  Avatar,
  Badge,
  Button,
  Drawer,
  Empty,
  Input,
  List,
  Space,
  Spin,
  Switch,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { SessionChat } from '@/pages/chat/SessionChat';
import {
  type ProjectAssistantAvatarVO,
  type ProjectAssistantInboxVO,
  type ProjectAssistantSettingsVO,
  type ProjectBaselineVO,
  type ProjectContextEventVO,
  projectAssistantApi,
} from '@/services/agentSphere/project';
import { tw } from '../../i18n';

const TAB_CHAT = 'chat';
const TAB_BASELINE = 'baseline';
const TAB_INBOX = 'inbox';
const TAB_AVATARS = 'avatars';

const BASELINE_STATUS_COLOR: Record<string, string> = {
  RUNNING: 'processing',
  COMPLETED: 'green',
  FAILED: 'red',
  CANCELLED: 'orange',
  PENDING: 'blue',
};

interface ProjectAssistantDrawerProps {
  open: boolean;
  projectId?: number;
  projectName?: string;
  initialTab?: string;
  onClose: () => void;
}

export function ProjectAssistantDrawer({
  open,
  projectId,
  projectName,
  initialTab,
  onClose,
}: ProjectAssistantDrawerProps) {
  const { message } = App.useApp();
  const [activeTab, setActiveTab] = useState(initialTab || TAB_CHAT);
  const [sessionId, setSessionId] = useState<number | null>(null);
  const [sessionLoading, setSessionLoading] = useState(false);
  const [sessionError, setSessionError] = useState(false);
  const [baseline, setBaseline] = useState<ProjectBaselineVO | null>(null);
  const [contextEvents, setContextEvents] = useState<ProjectContextEventVO[]>(
    [],
  );
  const [baselineLoading, setBaselineLoading] = useState(false);
  const [settings, setSettings] = useState<ProjectAssistantSettingsVO | null>(
    null,
  );
  const [inbox, setInbox] = useState<ProjectAssistantInboxVO[]>([]);
  const [inboxLoading, setInboxLoading] = useState(false);
  const [avatars, setAvatars] = useState<ProjectAssistantAvatarVO[]>([]);
  const [answeringId, setAnsweringId] = useState<string | null>(null);
  const [answerText, setAnswerText] = useState('');
  const [analyzing, setAnalyzing] = useState(false);

  useEffect(() => {
    if (open && initialTab) {
      setActiveTab(initialTab);
    }
  }, [open, initialTab]);

  const loadSession = useCallback(async () => {
    if (!projectId) return;
    setSessionLoading(true);
    setSessionError(false);
    try {
      const session = await projectAssistantApi.session(projectId);
      setSessionId(session.sessionId);
    } catch {
      setSessionError(true);
      message.error(
        tw(
          'projects.assistant.sessionFailed',
          'Failed to open the assistant session',
        ),
      );
    } finally {
      setSessionLoading(false);
    }
  }, [projectId, message]);

  const loadBaseline = useCallback(async () => {
    if (!projectId) return;
    setBaselineLoading(true);
    try {
      const [status, events] = await Promise.all([
        projectAssistantApi.baseline(projectId),
        projectAssistantApi.context(projectId, 30),
      ]);
      setBaseline(status);
      setContextEvents(events || []);
    } catch {
      message.error(
        tw(
          'projects.assistant.loadFailed',
          'Failed to load the project assistant',
        ),
      );
    } finally {
      setBaselineLoading(false);
    }
  }, [projectId, message]);

  const loadInbox = useCallback(async () => {
    setInboxLoading(true);
    try {
      setInbox(await projectAssistantApi.inbox());
    } catch {
      setInbox([]);
    } finally {
      setInboxLoading(false);
    }
  }, []);

  const loadAvatars = useCallback(async () => {
    if (!projectId) return;
    try {
      setAvatars(await projectAssistantApi.avatars(projectId));
    } catch {
      setAvatars([]);
    }
  }, [projectId]);

  const loadSettings = useCallback(async () => {
    if (!projectId) return;
    try {
      setSettings(await projectAssistantApi.settings(projectId));
    } catch {
      setSettings(null);
    }
  }, [projectId]);

  useEffect(() => {
    if (!open || !projectId) return;
    setSessionId(null);
    void loadSession();
    void loadBaseline();
    void loadInbox();
    void loadAvatars();
    void loadSettings();
  }, [
    open,
    projectId,
    loadSession,
    loadBaseline,
    loadInbox,
    loadAvatars,
    loadSettings,
  ]);

  const analyze = async () => {
    if (!projectId) return;
    setAnalyzing(true);
    try {
      await projectAssistantApi.analyzeBaseline(projectId);
      message.success(
        tw('projects.assistant.analyzeStarted', 'Analysis started'),
      );
      window.setTimeout(() => {
        void loadBaseline();
      }, 4000);
    } catch {
      message.error(
        tw(
          'projects.assistant.loadFailed',
          'Failed to load the project assistant',
        ),
      );
    } finally {
      setAnalyzing(false);
    }
  };

  const toggleAutoAnalyze = async (checked: boolean) => {
    if (!projectId) return;
    try {
      await projectAssistantApi.updateSettings(projectId, checked);
      setSettings((previous) => ({
        ...previous,
        projectId,
        autoAnalyze: checked,
      }));
      window.setTimeout(() => {
        void loadBaseline();
      }, 800);
    } catch {
      message.error(
        tw('projects.assistant.settingsFailed', 'Failed to save settings'),
      );
    }
  };

  const submitAnswer = async (item: ProjectAssistantInboxVO) => {
    if (!answerText.trim()) return;
    try {
      await projectAssistantApi.answer(item.clarificationId, answerText.trim());
      message.success(tw('projects.assistant.answered', 'Answered'));
      setAnsweringId(null);
      setAnswerText('');
      void loadInbox();
    } catch {
      message.error(tw('projects.assistant.answerFailed', 'Failed to answer'));
    }
  };

  const baselineTab = (
    <div
      style={{
        height: 'calc(100vh - 170px)',
        overflow: 'auto',
        padding: 16,
        boxSizing: 'border-box',
      }}
    >
      <Space
        style={{
          marginBottom: 12,
          width: '100%',
          justifyContent: 'space-between',
        }}
      >
        <Space>
          <Button
            type="primary"
            icon={<BulbOutlined />}
            loading={analyzing}
            onClick={() => void analyze()}
          >
            {analyzing
              ? tw('projects.assistant.analyzing', 'Analyzing')
              : tw('projects.assistant.analyze', 'Analyze now')}
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => void loadBaseline()}>
            {tw('projects.assistant.refresh', 'Refresh')}
          </Button>
        </Space>
        <Space>
          <span>{tw('projects.assistant.autoAnalyze', 'Auto analysis')}</span>
          <Switch
            checked={Boolean(settings?.autoAnalyze)}
            onChange={(checked) => void toggleAutoAnalyze(checked)}
          />
        </Space>
      </Space>
      <Typography.Paragraph type="secondary" style={{ marginTop: -4 }}>
        {tw(
          'projects.assistant.autoAnalyzeHint',
          'Collects incremental changes and analyzes them in micro-batches; off by default',
        )}
      </Typography.Paragraph>
      <Spin spinning={baselineLoading}>
        <Space size={8} wrap style={{ marginBottom: 12 }}>
          <Tag color={baseline?.autoAnalyze ? 'green' : 'default'}>
            {baseline?.autoAnalyze
              ? tw('projects.assistant.baseline.on', 'Collecting changes')
              : tw('projects.assistant.baseline.off', 'Collection paused')}
          </Tag>
          <Tag>
            {tw('projects.assistant.baseline.pending', 'Pending events')}:{' '}
            {baseline?.pendingEvents ?? 0}
          </Tag>
          <Tag color={baseline?.runningBatches ? 'processing' : 'default'}>
            {tw('projects.assistant.baseline.running', 'Running batches')}:{' '}
            {baseline?.runningBatches ?? 0}
          </Tag>
          {baseline?.lastBatchStatus ? (
            <Tag
              color={
                BASELINE_STATUS_COLOR[baseline.lastBatchStatus] || 'default'
              }
            >
              {tw('projects.assistant.baseline.last', 'Last batch')}:{' '}
              {baseline.lastBatchStatus}
            </Tag>
          ) : null}
          {baseline?.lastBatchAt ? (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {baseline.lastBatchAt}
            </Typography.Text>
          ) : null}
        </Space>
        {baseline?.lastBatchError ? (
          <Typography.Paragraph type="danger" style={{ fontSize: 12 }}>
            {baseline.lastBatchError}
          </Typography.Paragraph>
        ) : null}
        {contextEvents.length === 0 ? (
          <Empty
            description={tw(
              'projects.assistant.noContext',
              'No project activity yet',
            )}
          />
        ) : (
          <List
            dataSource={contextEvents}
            renderItem={(item) => (
              <List.Item>
                <List.Item.Meta
                  title={
                    <Space wrap>
                      <Tag>{item.eventType}</Tag>
                      {item.requirementId ? (
                        <Typography.Text type="secondary">
                          #{item.requirementId}
                        </Typography.Text>
                      ) : null}
                      {item.createdAt ? (
                        <Typography.Text
                          type="secondary"
                          style={{ fontSize: 12 }}
                        >
                          {item.createdAt}
                        </Typography.Text>
                      ) : null}
                    </Space>
                  }
                  description={
                    <div style={{ whiteSpace: 'pre-wrap' }}>{item.summary}</div>
                  }
                />
              </List.Item>
            )}
          />
        )}
      </Spin>
    </div>
  );

  const inboxTab = (
    <div
      style={{
        height: 'calc(100vh - 170px)',
        overflow: 'auto',
        padding: 16,
        boxSizing: 'border-box',
      }}
    >
      <Button
        icon={<ReloadOutlined />}
        style={{ marginBottom: 12 }}
        onClick={() => void loadInbox()}
      >
        {tw('projects.assistant.refresh', 'Refresh')}
      </Button>
      <Spin spinning={inboxLoading}>
        {inbox.length === 0 ? (
          <Empty
            description={tw(
              'projects.assistant.noInbox',
              'No questions waiting for you',
            )}
          />
        ) : (
          <List
            dataSource={inbox}
            renderItem={(item) => (
              <List.Item>
                <List.Item.Meta
                  title={
                    <Space wrap>
                      <Tag color="processing">{item.projectName}</Tag>
                      <span>{item.title}</span>
                      {item.expiresAt ? (
                        <Typography.Text
                          type="secondary"
                          style={{ fontSize: 12 }}
                        >
                          {tw('projects.assistant.expiresAt', 'Expires at')}:{' '}
                          {item.expiresAt}
                        </Typography.Text>
                      ) : null}
                    </Space>
                  }
                  description={
                    <div>
                      {item.description ? <div>{item.description}</div> : null}
                      {item.requesterName ? (
                        <Typography.Text
                          type="secondary"
                          style={{ fontSize: 12 }}
                        >
                          {tw('projects.assistant.requester', 'Asked by')}:{' '}
                          {item.requesterName}
                        </Typography.Text>
                      ) : null}
                      {answeringId === item.clarificationId ? (
                        <Space.Compact style={{ width: '100%', marginTop: 8 }}>
                          <Input
                            value={answerText}
                            placeholder={tw(
                              'projects.assistant.answerPlaceholder',
                              'Type your answer...',
                            )}
                            onChange={(event) =>
                              setAnswerText(event.target.value)
                            }
                            onPressEnter={() => void submitAnswer(item)}
                          />
                          <Button
                            type="primary"
                            onClick={() => void submitAnswer(item)}
                          >
                            {tw('projects.assistant.answer', 'Answer')}
                          </Button>
                        </Space.Compact>
                      ) : (
                        <Button
                          type="link"
                          style={{ paddingLeft: 0 }}
                          onClick={() => {
                            setAnsweringId(item.clarificationId);
                            setAnswerText('');
                          }}
                        >
                          {tw('projects.assistant.answer', 'Answer')}
                        </Button>
                      )}
                    </div>
                  }
                />
              </List.Item>
            )}
          />
        )}
      </Spin>
    </div>
  );

  const avatarTab = (
    <div
      style={{
        height: 'calc(100vh - 170px)',
        overflow: 'auto',
        padding: 16,
        boxSizing: 'border-box',
      }}
    >
      {avatars.length === 0 ? (
        <Empty
          description={tw(
            'projects.assistant.noAvatars',
            'No avatars available',
          )}
        />
      ) : (
        <List
          dataSource={avatars}
          renderItem={(item) => (
            <List.Item>
              <List.Item.Meta
                avatar={<Avatar icon={<TeamOutlined />} />}
                title={
                  <Space wrap>
                    <span>{item.name}</span>
                    {item.ownedByRequester ? (
                      <Tag color="green">
                        {tw('projects.assistant.owned', 'My avatar')}
                      </Tag>
                    ) : (
                      <Tag>
                        {tw('projects.assistant.public', 'Public avatar')}
                      </Tag>
                    )}
                  </Space>
                }
                description={
                  <Typography.Text type="secondary">
                    {item.ownerName || item.ownerUserId} · {item.status}
                  </Typography.Text>
                }
              />
            </List.Item>
          )}
        />
      )}
    </div>
  );

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={900}
      destroyOnClose
      rootClassName="project-assistant-drawer"
      title={
        <Space>
          <RobotOutlined />
          <span>{tw('projects.assistant.title', 'Project Assistant')}</span>
          {projectName ? <Tag>{projectName}</Tag> : null}
          {inbox.length > 0 ? (
            <Badge
              count={inbox.length}
              title={tw('projects.assistant.inboxCount', '{count} waiting', {
                count: inbox.length,
              })}
            />
          ) : null}
        </Space>
      }
      styles={{
        body: {
          padding: 0,
          overflow: 'hidden',
          display: 'flex',
          flexDirection: 'column',
        },
      }}
    >
      <style>{`
        .project-assistant-drawer .ant-drawer-content-wrapper,
        .project-assistant-drawer .ant-drawer-content,
        .project-assistant-drawer .ant-drawer-wrapper-body {
          height: 100%;
        }
        .project-assistant-drawer .ant-drawer-content {
          display: flex;
          flex-direction: column;
        }
        .project-assistant-drawer .ant-drawer-body {
          flex: 1;
          display: flex;
          flex-direction: column;
          min-height: 0;
          overflow: hidden;
        }
        .project-assistant-drawer .ant-tabs {
          flex: 1;
          min-height: 0;
          display: flex;
          flex-direction: column;
        }
        .project-assistant-drawer .ant-tabs-content-holder {
          flex: 1;
          min-height: 0;
        }
      `}</style>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        style={{
          flex: 1,
          minHeight: 0,
          display: 'flex',
          flexDirection: 'column',
        }}
        tabBarStyle={{ margin: 0, padding: '0 16px' }}
        items={[
          {
            key: TAB_CHAT,
            label: tw('projects.assistant.chat', 'Chat'),
            children: (
              <div
                style={{
                  height: 'calc(100vh - 160px)',
                  minHeight: 420,
                  display: 'flex',
                  flexDirection: 'column',
                  overflow: 'hidden',
                }}
              >
                {sessionLoading ? (
                  <div style={{ padding: 48, textAlign: 'center' }}>
                    <Spin />
                  </div>
                ) : !sessionId ? (
                  <Empty
                    style={{ padding: 48 }}
                    description={
                      sessionError
                        ? tw(
                            'projects.assistant.sessionFailed',
                            'Failed to open the assistant session',
                          )
                        : tw('projects.loading', 'Loading')
                    }
                  >
                    <Button
                      icon={<ReloadOutlined />}
                      onClick={() => void loadSession()}
                    >
                      {tw('projects.assistant.refresh', 'Refresh')}
                    </Button>
                  </Empty>
                ) : (
                  <SessionChat sessionId={sessionId} trainingMode embedded />
                )}
              </div>
            ),
          },
          {
            key: TAB_BASELINE,
            label: tw('projects.assistant.baseline', 'Baseline'),
            children: baselineTab,
          },
          {
            key: TAB_INBOX,
            label: (
              <Badge count={inbox.length} size="small" offset={[8, -2]}>
                {tw('projects.assistant.inbox', 'Waiting for me')}
              </Badge>
            ),
            children: inboxTab,
          },
          {
            key: TAB_AVATARS,
            label: tw('projects.assistant.avatars', 'Avatars'),
            children: avatarTab,
          },
        ]}
      />
    </Drawer>
  );
}
