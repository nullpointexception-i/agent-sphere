import {
  AppstoreOutlined,
  AudioOutlined,
  BellOutlined,
  CheckOutlined,
  CloseOutlined,
  CloudServerOutlined,
  ContactsOutlined,
  FileTextOutlined,
  GlobalOutlined,
  HomeOutlined,
  LogoutOutlined,
  MessageOutlined,
  PlusOutlined,
  ProjectOutlined,
  RobotOutlined,
  SettingOutlined,
  StarFilled,
  TeamOutlined,
  UnorderedListOutlined,
  UserOutlined,
} from '@ant-design/icons';
import {
  getLocale,
  history,
  setLocale,
  request as umiRequest,
  useIntl,
  useModel,
} from '@umijs/max';
import {
  App,
  Avatar,
  Badge,
  Button,
  Dropdown,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { type ReactNode, useEffect, useRef, useState } from 'react';
import { ServerSettingsModal } from '@/components/ServerSettings';
import { API_PATHS } from '@/constants/endpoints';
import { STATUS } from '@/constants/status';
import type { ImStreamPayload } from '@/hooks/useReconnectingSse';
import { agentApi } from '@/services/agentSphere/api';
import { dictApi, type SystemDicEntry } from '@/services/agentSphere/dict';
import { type ImConversation, imApi } from '@/services/agentSphere/im';
import {
  type SuperAgent,
  superAgentApi,
} from '@/services/agentSphere/superAgent';
import { clearStoredUser, getStoredUser } from '@/utils/auth';
import { getDesktopBridge } from '@/utils/desktopBridge';
import { getAppVersion, isDesktop } from '@/utils/runtimeEnv';
import { applyAgentStreamEvent } from './agentStreamStore';
import { ApprovalInboxDrawer } from './approval/ApprovalInboxDrawer';
import { usePendingApprovals } from './approval/usePendingApprovals';
import { AppCenter } from './apps/AppCenter';
import { ProjectWorkspace } from './apps/projects/ProjectWorkspace';
import { AvatarDetailDrawer } from './avatars/AvatarDetailDrawer';
import { LocalTaskModal } from './avatars/LocalTaskModal';
import { ConversationPane } from './chat/ConversationPane';
import { ImStreamProvider, useImStream } from './chat/ImStreamProvider';
import { useChatStyles } from './chat/style';
import {
  conversationAvatar,
  conversationDisplayName,
  conversationPreview,
  isSelfConversation,
  isSelfUser,
  listTime,
  mergeConversation,
  positionLabel,
  sortConversations,
  statusLabel,
} from './conversationModel';
import { DocumentsTab } from './documents/DocumentsTab';
import { tw } from './i18n';
import { NotificationBell } from './notifications/NotificationBell';
import { avatarStyle, displayName, initials } from './shared';
import { useStyles } from './style';
import { TrainingPanel } from './training/TrainingPanel';
import {
  parseWorkspaceQuery,
  type Tab,
  toWorkspaceSearch,
} from './workspaceQuery';

export default function WorkspacePage() {
  const { styles } = useStyles();
  const desktopShell = isDesktop();
  useIntl();
  const { initialState } = useModel('@@initialState');
  const me = getStoredUser() || initialState?.currentUser;
  const [workspaceQuery, setWorkspaceQuery] = useState(() =>
    parseWorkspaceQuery(history.location.search),
  );
  const tab = workspaceQuery.tab;
  const setTabState = (next: Tab) => {
    setWorkspaceQuery((current) => ({ ...current, tab: next }));
  };
  const [conversationId, setConversationId] = useState<number | null>(null);
  const [approvalOpen, setApprovalOpen] = useState(false);
  const [trainingAgent, setTrainingAgent] = useState<SuperAgent | null>(null);
  const {
    items: pendingApprovals,
    loading: approvalsLoading,
    reload: reloadApprovals,
    count: pendingApprovalCount,
  } = usePendingApprovals();

  const setTab = (next: Tab) => {
    setTabState(next);
    const current = parseWorkspaceQuery(history.location.search);
    const pathname = history.location.pathname || '/workspace';
    history.replace(
      `${pathname}${toWorkspaceSearch({ ...current, tab: next })}`,
    );
  };

  const openChat = (id: number) => {
    setConversationId(id);
    setTab('chat');
  };

  useEffect(() => {
    const unlisten = history.listen(({ location }) => {
      setWorkspaceQuery(parseWorkspaceQuery(location.search));
    });
    return () => {
      unlisten();
    };
  }, []);

  return (
    <ImStreamProvider>
      <div
        className={`${styles.backdrop} ${desktopShell ? styles.backdropDesktop : ''}`}
      >
        <div
          className={`${styles.app} ${desktopShell ? styles.appDesktop : ''}`}
        >
          <SideRail
            tab={tab}
            onChange={setTab}
            me={me}
            styles={styles}
            pendingCount={pendingApprovalCount}
            onOpenApprovals={() => setApprovalOpen(true)}
          />
          <div className={styles.main} id="workspace-main">
            {tab === 'chat' ? (
              <ChatTab
                conversationId={conversationId}
                onOpenChat={setConversationId}
                onCloseChat={() => setConversationId(null)}
              />
            ) : (
              <>
                <TopBar me={me} />
                <div className={styles.content}>
                  {tab === 'home' ? (
                    <HomeTab
                      onOpenAvatars={() => setTab('avatars')}
                      onOpenChatList={() => setTab('chat')}
                      onOpenChat={openChat}
                    />
                  ) : null}
                  {tab === 'avatars' ? (
                    <AvatarsTab
                      onOpenChat={openChat}
                      onStartTraining={(agent) => setTrainingAgent(agent)}
                    />
                  ) : null}
                  {tab === 'projects' ? <ProjectWorkspace /> : null}
                  {tab === 'apps' ? (
                    <AppCenter
                      initialAppKey={workspaceQuery.appKey}
                      initialAppId={workspaceQuery.appId}
                      initialSceneKey={workspaceQuery.sceneKey}
                      initialSceneId={workspaceQuery.sceneId}
                      initialProjectId={workspaceQuery.projectId}
                      initialRequirementId={workspaceQuery.requirementId}
                    />
                  ) : null}
                  {tab === 'contacts' ? (
                    <ContactsTab onOpenChat={openChat} />
                  ) : null}
                  {tab === 'documents' ? <DocumentsTab /> : null}
                  {tab === 'me' ? (
                    <MeTab me={me} onOpenAvatars={() => setTab('avatars')} />
                  ) : null}
                </div>
              </>
            )}
          </div>
        </div>
      </div>
      {trainingAgent ? (
        <TrainingPanel
          agent={trainingAgent}
          onExit={() => setTrainingAgent(null)}
        />
      ) : null}
      <ApprovalInboxDrawer
        open={approvalOpen}
        items={pendingApprovals}
        loading={approvalsLoading}
        onClose={() => setApprovalOpen(false)}
        onReload={() => void reloadApprovals()}
      />
    </ImStreamProvider>
  );
}

function TopBar({ me }: { me: any }) {
  const { styles } = useStyles();
  const locale = getLocale();
  return (
    <div className={styles.topBar}>
      <div className={styles.topRight}>
        <NotificationBell className={styles.langButton} />
        <button
          type="button"
          className={styles.langButton}
          onClick={() => {
            window.location.href = '/dashboard';
          }}
        >
          <AppstoreOutlined /> {tw('app.operations', 'console')}
        </button>
        <Dropdown
          trigger={['click']}
          menu={{
            selectedKeys: [locale],
            items: [
              {
                key: 'zh-CN',
                label: tw('language.zh', 'Simplified Chinese'),
              },
              { key: 'en-US', label: tw('language.en', 'English') },
            ],
            onClick: ({ key }) => setLocale(key, false),
          }}
        >
          <button type="button" className={styles.langButton}>
            <GlobalOutlined />
            {locale === 'en-US'
              ? tw('language.en', 'English')
              : tw('language.zhShort', 'Chinese')}
          </button>
        </Dropdown>
        <span className={styles.online}>
          <span className={styles.dot} /> {tw('app.online', 'Online')}
        </span>
        <Avatar size={30} src={me?.avatar} style={avatarStyle(displayName(me))}>
          {initials(displayName(me))}
        </Avatar>
      </div>
    </div>
  );
}

function SideRail({
  tab,
  onChange,
  me,
  styles,
  pendingCount,
  onOpenApprovals,
}: {
  tab: Tab;
  onChange: (tab: Tab) => void;
  me: any;
  styles: any;
  pendingCount: number;
  onOpenApprovals: () => void;
}) {
  const items: { key: Tab; label: string; icon: ReactNode }[] = [
    { key: 'home', label: tw('nav.home', 'Home'), icon: <HomeOutlined /> },
    {
      key: 'avatars',
      label: tw('nav.avatars', 'Avatars'),
      icon: <TeamOutlined />,
    },
    { key: 'chat', label: tw('nav.chat', 'Chats'), icon: <MessageOutlined /> },
    {
      key: 'projects',
      label: tw('nav.projects', 'Projects'),
      icon: <ProjectOutlined />,
    },
    {
      key: 'apps',
      label: tw('nav.apps', 'Apps'),
      icon: <AppstoreOutlined />,
    },
    {
      key: 'documents',
      label: tw('nav.documents', 'Documents'),
      icon: <FileTextOutlined />,
    },
    {
      key: 'contacts',
      label: tw('nav.contacts', 'Contacts'),
      icon: <ContactsOutlined />,
    },
    { key: 'me', label: tw('nav.me', 'Me'), icon: <UserOutlined /> },
  ];
  return (
    <div className={styles.rail}>
      <img className={styles.railLogo} src="/logo.svg" alt="AgentSphere" />
      {items.map((item) => (
        <div
          key={item.key}
          className={`${styles.railItem} ${tab === item.key ? styles.railItemActive : ''}`}
          title={item.label}
          onClick={() => onChange(item.key)}
        >
          <span className={styles.railIcon}>{item.icon}</span>
          <span className={styles.railLabel}>{item.label}</span>
        </div>
      ))}
      <div className={styles.railSpacer} />
      <div
        className={styles.railItem}
        title={tw('approval.title', 'Pending approvals')}
        onClick={onOpenApprovals}
      >
        <span className={styles.railIcon}>
          <Badge count={pendingCount} size="small" offset={[2, -2]}>
            <BellOutlined />
          </Badge>
        </span>
        <span className={styles.railLabel}>
          {tw('approval.short', 'Approvals')}
        </span>
      </div>
      <div
        className={styles.railItem}
        title={tw('app.operations', 'console')}
        onClick={() => {
          window.location.href = '/dashboard';
        }}
      >
        <span className={styles.railIcon}>
          <AppstoreOutlined />
        </span>
        <span className={styles.railLabel}>
          {tw('app.operations', 'console')}
        </span>
      </div>
      <div className={styles.railUser} title={displayName(me)}>
        <Avatar
          className={styles.railAvatar}
          size={34}
          src={me?.avatar}
          style={avatarStyle(displayName(me))}
          onClick={() => onChange('me')}
        >
          {initials(displayName(me))}
        </Avatar>
        <div className={styles.railUserName}>{displayName(me)}</div>
      </div>
    </div>
  );
}

function HomeTab({
  onOpenAvatars,
  onOpenChatList,
  onOpenChat,
}: {
  onOpenAvatars: () => void;
  onOpenChatList: () => void;
  onOpenChat: (id: number) => void;
}) {
  const { styles } = useStyles();
  const [loading, setLoading] = useState(true);
  const [agents, setAgents] = useState<SuperAgent[]>([]);
  const [conversations, setConversations] = useState<ImConversation[]>([]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    Promise.all([superAgentApi.list(20), imApi.conversations({ limit: 20 })])
      .then(([agentList, conversationPage]) => {
        if (!active) return;
        setAgents(agentList || []);
        setConversations([
          ...(conversationPage?.pinned || []),
          ...(conversationPage?.rows || []),
        ]);
      })
      .catch(() => {
        if (!active) return;
        setAgents([]);
        setConversations([]);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const recent = conversations.slice(0, 5);

  return (
    <div className={styles.page}>
      <div className={styles.hero}>
        <div className={styles.heroTitle}>
          {tw('home.heroTitle', 'Start your AI digital employee journey')}
        </div>
        <div className={styles.heroText}>
          {tw(
            'home.heroDesc',
            'Create digital avatars to chat, answer questions and help complete work.',
          )}
        </div>
        <Button
          className={styles.heroButton}
          type="primary"
          icon={<PlusOutlined />}
          onClick={onOpenAvatars}
        >
          {tw('home.create', 'Create my avatar')}
        </Button>
        <div className={styles.heroBot}>
          <UserOutlined />
          <StarFilled className={styles.heroBotSpark} />
        </div>
      </div>

      <Spin spinning={loading}>
        <div className={styles.section}>
          <div className={styles.sectionHead}>
            <span className={styles.sectionTitle}>
              {tw('home.myAvatars', 'My avatars')}
            </span>
            <span className={styles.sectionMore} onClick={onOpenAvatars}>
              {tw('home.viewAll', 'View all ›')}
            </span>
          </div>
          <div className={styles.listCard}>
            {agents.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={tw('home.noAvatars', 'No avatars yet')}
              />
            ) : (
              agents.slice(0, 3).map((agent) => (
                <div
                  key={agent.id}
                  className={styles.row}
                  onClick={onOpenAvatars}
                >
                  <Avatar
                    className={styles.avatar}
                    size={42}
                    src={(agent as any).image}
                    style={avatarStyle(agent.name)}
                  >
                    {initials(agent.name)}
                  </Avatar>
                  <div className={styles.rowBody}>
                    <div className={styles.rowTitle}>
                      {agent.name}
                      <span
                        className={
                          agent.status === STATUS.ACTIVE
                            ? styles.statusText
                            : styles.statusPaused
                        }
                      >
                        {statusLabel(agent.status)}
                      </span>
                    </div>
                    <div className={styles.rowDesc}>
                      {positionLabel(agent)
                        ? `${positionLabel(agent)} · ${agent.description || tw('common.noDescription', 'No description')}`
                        : agent.description || tw('nav.avatars', 'Avatars')}
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>

        <div className={styles.section}>
          <div className={styles.sectionHead}>
            <span className={styles.sectionTitle}>
              {tw('home.recent', 'Recent chats')}
            </span>
            <span className={styles.sectionMore} onClick={onOpenChatList}>
              {tw('home.all', 'All ›')}
            </span>
          </div>
          <div className={styles.listCard}>
            {recent.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={tw('home.noConversations', 'No conversations')}
              />
            ) : (
              recent.map((conversation) => (
                <ConversationRow
                  key={conversation.id}
                  conversation={conversation}
                  onClick={() => onOpenChat(conversation.id)}
                />
              ))
            )}
          </div>
        </div>
      </Spin>
    </div>
  );
}

function ConversationRow({
  conversation,
  onClick,
}: {
  conversation: ImConversation;
  onClick: () => void;
}) {
  const { styles } = useStyles();
  const me = getStoredUser();
  const title = conversationDisplayName(conversation, me);
  return (
    <div className={styles.row} onClick={onClick}>
      <Avatar
        className={styles.avatar}
        size={42}
        shape={conversation.type === 'GROUP' ? 'square' : 'circle'}
        src={conversationAvatar(conversation, me)}
        style={avatarStyle(title)}
      >
        {initials(title)}
      </Avatar>
      <div className={styles.rowBody}>
        <div className={styles.rowTitle}>
          <span className={styles.conversationNameText}>{title}</span>
          {isSelfConversation(conversation, me) ? (
            <span className={styles.selfTag}>{tw('common.meTag', 'Me')}</span>
          ) : null}
          <span className={styles.rowTime}>
            {listTime(conversation.lastMessageAt)}
          </span>
        </div>
        <div className={styles.rowDesc}>
          {conversationPreview(conversation)}
        </div>
      </div>
      {conversation.unread ? (
        <span className={styles.unread}>{conversation.unread}</span>
      ) : null}
    </div>
  );
}

function AvatarsTab({
  onOpenChat,
  onStartTraining,
}: {
  onOpenChat: (id: number) => void;
  onStartTraining: (agent: SuperAgent) => void;
}) {
  const { styles } = useStyles();
  const { message } = App.useApp();
  const [loading, setLoading] = useState(true);
  const [agents, setAgents] = useState<SuperAgent[]>([]);
  const [positions, setPositions] = useState<SystemDicEntry[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();
  const nameValue = Form.useWatch('name', form);
  const [editingAgent, setEditingAgent] = useState<SuperAgent | null>(null);
  const [taskAgent, setTaskAgent] = useState<SuperAgent | null>(null);
  const bridge = getDesktopBridge();

  const load = () => {
    setLoading(true);
    superAgentApi
      .list(100)
      .then(setAgents)
      .catch(() => setAgents([]))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  useEffect(() => {
    dictApi
      .listActive()
      .then(setPositions)
      .catch(() => setPositions([]));
  }, []);

  const positionOptions = positions.map((item) => ({
    value: item.code,
    label: getLocale() === 'en-US' ? item.nameEn : item.nameCn,
  }));

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const local = isDesktop() && values.agentType === 'LOCAL';
      const created = await superAgentApi.create({
        name: values.name,
        description: values.description,
        positionCode: values.positionCode,
        ...(local ? { agentType: 'LOCAL' as const } : {}),
      });
      if (local) {
        await superAgentApi
          .updateVisibility(created.id, true)
          .catch(() => undefined);
      }
      message.success(tw('avatars.created', 'Created'));
      setCreateOpen(false);
      form.resetFields();
      load();
    } finally {
      setSubmitting(false);
    }
  };

  const openAgent = (agent: SuperAgent) => {
    setEditingAgent(agent);
  };

  return (
    <div className={styles.page}>
      <div className={styles.pageHead}>
        <div>
          <div className={styles.pageTitle}>
            {tw('avatars.title', 'My avatars')}
          </div>
          <div className={styles.pageSubtitle}>
            {tw('avatars.subtitle', 'Create avatars to chat and collaborate')}
          </div>
        </div>
        <Button
          type="primary"
          className={styles.primaryAction}
          icon={<PlusOutlined />}
          onClick={() => {
            form.resetFields();
            setCreateOpen(true);
          }}
        >
          {tw('avatars.create', 'Create avatar')}
        </Button>
      </div>

      <Spin spinning={loading}>
        <div className={styles.avatarGrid}>
          {agents.length === 0 ? (
            <div className={styles.emptyBlock}>
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={tw('home.noAvatars', 'No avatars yet')}
              />
            </div>
          ) : (
            agents.map((agent) => (
              <div
                key={agent.id}
                className={styles.avatarCard}
                onClick={() => openAgent(agent)}
              >
                <Avatar
                  className={styles.avatar}
                  size={46}
                  style={avatarStyle(agent.name)}
                >
                  {initials(agent.name)}
                </Avatar>
                <div className={styles.rowBody}>
                  <div className={styles.avatarNameRow}>
                    <span className={styles.avatarName}>{agent.name}</span>
                    {bridge && agent.agentType === 'LOCAL' ? (
                      <Tooltip title={tw('tasks.open', 'Tasks')}>
                        <Button
                          size="small"
                          type="text"
                          shape="circle"
                          icon={<UnorderedListOutlined />}
                          onClick={(event) => {
                            event.stopPropagation();
                            setTaskAgent(agent);
                          }}
                        />
                      </Tooltip>
                    ) : null}
                  </div>
                  <div className={styles.avatarTags}>
                    <Tag
                      color={agent.agentType === 'LOCAL' ? 'blue' : 'default'}
                    >
                      {agent.agentType === 'LOCAL'
                        ? tw('avatars.typeLocal', 'Local')
                        : tw('avatars.typeCloud', 'Cloud')}
                    </Tag>
                    <Tag
                      color={
                        agent.status === STATUS.ACTIVE ? 'green' : 'default'
                      }
                    >
                      {statusLabel(agent.status)}
                    </Tag>
                    {positionLabel(agent) ? (
                      <Tag color="purple">{positionLabel(agent)}</Tag>
                    ) : null}
                    {agent.publicVisible ? (
                      <Tag color="cyan">
                        {tw('avatars.publicTag', 'Public')}
                      </Tag>
                    ) : null}
                  </div>
                  <div className={styles.rowDesc}>
                    {agent.description || tw('nav.avatars', 'Avatars')}
                  </div>
                </div>
              </div>
            ))
          )}
        </div>
      </Spin>

      <Modal
        className={styles.createModal}
        open={createOpen}
        footer={null}
        closable={false}
        width={520}
        onCancel={() => setCreateOpen(false)}
        destroyOnHidden
      >
        <div className={styles.createHero}>
          <button
            type="button"
            className={styles.createClose}
            onClick={() => setCreateOpen(false)}
          >
            <CloseOutlined />
          </button>
          <div className={styles.createHeroText}>
            <div className={styles.createTitle}>
              {tw('avatars.createTitle', 'Create avatar')}
            </div>
            <div className={styles.createSubtitle}>
              {tw(
                'avatars.createSubtitle',
                'Give it a name and responsibility to start working for you',
              )}
            </div>
          </div>
          <div className={styles.createOrb}>
            {nameValue ? (
              <span className={styles.createOrbInitial}>
                {initials(nameValue)}
              </span>
            ) : (
              <>
                <UserOutlined className={styles.createOrbUser} />
                <StarFilled className={styles.createOrbSpark} />
              </>
            )}
          </div>
        </div>

        <Form
          form={form}
          layout="vertical"
          className={styles.createBody}
          initialValues={{ agentType: 'CLOUD' }}
        >
          {isDesktop() ? (
            <div className={styles.fieldBlock}>
              <div className={styles.fieldLabel}>
                {tw('avatars.type', 'Type')}{' '}
                <span className={styles.required}>*</span>
              </div>
              <Form.Item name="agentType" style={{ marginBottom: 0 }}>
                <Select
                  className={styles.softField}
                  options={[
                    {
                      value: 'CLOUD',
                      label: tw('avatars.typeCloud', 'Cloud'),
                    },
                    {
                      value: 'LOCAL',
                      label: tw('avatars.typeLocal', 'Local'),
                    },
                  ]}
                />
              </Form.Item>
              <div className={styles.fieldHint}>
                {tw(
                  'avatars.typeHint',
                  'Local avatars run on this machine through the desktop runtime.',
                )}
              </div>
            </div>
          ) : null}

          <div className={styles.fieldBlock}>
            <div className={styles.fieldLabel}>
              {tw('avatars.name', 'Name')}{' '}
              <span className={styles.required}>*</span>
            </div>
            <Form.Item
              name="name"
              rules={[{ required: true, max: 128 }]}
              style={{ marginBottom: 0 }}
            >
              <Input
                className={styles.softField}
                maxLength={32}
                placeholder={tw(
                  'avatars.namePlaceholder',
                  'e.g. Product assistant',
                )}
              />
            </Form.Item>
            <div className={styles.fieldHint}>
              {tw('avatars.nameHint', 'Up to 32 characters')}
            </div>
          </div>

          <div className={styles.fieldBlock}>
            <div className={styles.fieldLabel}>
              {tw('avatars.position', 'Role')}{' '}
              <span className={styles.required}>*</span>
            </div>
            <Form.Item
              name="positionCode"
              rules={[
                {
                  required: true,
                  message: tw('avatars.positionRequired', 'Select a role'),
                },
              ]}
              style={{ marginBottom: 0 }}
            >
              <Select
                className={styles.softField}
                placeholder={tw('avatars.positionPlaceholder', 'Select a role')}
                options={positionOptions}
              />
            </Form.Item>
          </div>

          <div className={styles.fieldBlock} style={{ marginBottom: 20 }}>
            <div className={styles.fieldLabel}>
              {tw('avatars.desc', 'Responsibility')}{' '}
              <span className={styles.optional}>
                {tw('common.optional', 'Optional')}
              </span>
            </div>
            <Form.Item name="description" style={{ marginBottom: 0 }}>
              <Input.TextArea
                className={styles.softTextarea}
                rows={4}
                maxLength={200}
                showCount
                placeholder={tw(
                  'avatars.descPlaceholder',
                  'Briefly describe what it is good at',
                )}
              />
            </Form.Item>
          </div>

          <div className={styles.createNote}>
            <RobotOutlined />
            <span>
              {tw(
                'avatars.createNote',
                'Takes effect immediately. Pause, edit or delete it anytime.',
              )}
            </span>
          </div>
        </Form>

        <div className={styles.createFooter}>
          <Button
            className={styles.ghostPill}
            onClick={() => setCreateOpen(false)}
          >
            {tw('common.cancel', 'Cancel')}
          </Button>
          <Button
            type="primary"
            className={styles.primaryPill}
            loading={submitting}
            onClick={submit}
          >
            {tw('avatars.createAction', 'Create avatar')}
          </Button>
        </div>
      </Modal>

      <AvatarDetailDrawer
        agent={editingAgent}
        open={editingAgent != null}
        positionOptions={positionOptions}
        onClose={() => setEditingAgent(null)}
        onChanged={load}
        onUpdated={setEditingAgent}
        onOpenChat={onOpenChat}
        onStartTraining={onStartTraining}
      />

      <LocalTaskModal
        open={taskAgent != null}
        agentId={taskAgent?.id}
        agentName={taskAgent?.name}
        onClose={() => setTaskAgent(null)}
      />
    </div>
  );
}

function ChatTab({
  conversationId,
  onOpenChat,
  onCloseChat,
}: {
  conversationId: number | null;
  onOpenChat: (id: number) => void;
  onCloseChat: () => void;
}) {
  const { styles: baseStyles } = useStyles();
  const { styles: chatStyles } = useChatStyles();
  const styles = { ...baseStyles, ...chatStyles };
  const { message, modal } = App.useApp();
  const me = getStoredUser();
  const [conversations, setConversations] = useState<ImConversation[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshKey, setRefreshKey] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [nextCursor, setNextCursor] = useState<string | undefined>();
  const [loadingMore, setLoadingMore] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [groupOpen, setGroupOpen] = useState(false);
  const [groupKeyword, setGroupKeyword] = useState('');
  const [groupMembers, setGroupMembers] = useState<number[]>([]);
  const [contacts, setContacts] = useState<any[]>([]);
  const [contactsLoading, setContactsLoading] = useState(false);
  const [creatingGroup, setCreatingGroup] = useState(false);
  const { event: streamEvent } = useImStream();
  const loadedRef = useRef(false);
  const creatingRef = useRef(false);
  const activeIdRef = useRef<number | null>(conversationId);
  const streamNonceRef = useRef(streamEvent?.nonce ?? 0);

  const reloadList = () => setRefreshKey((value) => value + 1);

  const applyStreamEvent = (type: string, event: ImStreamPayload) => {
    const id = event.conversationId;
    if (!id || type === 'heartbeat') return;
    // Agent streaming fragments go to the per-conversation store so token-level
    // updates never re-render the whole workspace page.
    if (type === 'agent_stream') {
      applyAgentStreamEvent(event);
      return;
    }

    if (type === 'conversation_hidden') {
      setConversations((previous) => previous.filter((item) => item.id !== id));
      if (activeIdRef.current === id) {
        onCloseChat();
      }
      return;
    }

    if (type === 'message_created') {
      if (!conversations.some((item) => item.id === id)) {
        imApi
          .conversation(id)
          .then((conversation) =>
            setConversations((previous) =>
              mergeConversation(previous, conversation),
            ),
          )
          .catch(() => {});
        return;
      }
      setConversations((previous) =>
        sortConversations(
          previous.map((item) =>
            item.id === id
              ? {
                  ...item,
                  unread:
                    item.id === activeIdRef.current
                      ? 0
                      : (item.unread || 0) + 1,
                  lastMessageAt: event.createdAt || new Date().toISOString(),
                  lastMessage: {
                    id: event.messageId || 0,
                    conversationId: id,
                    seq: event.seq || 0,
                    senderType: '',
                    type: 'TEXT',
                    content: {
                      text:
                        event.previewText ||
                        tw('chat.newMessage', 'New message'),
                    },
                    createdAt: event.createdAt,
                  },
                }
              : item,
          ),
        ),
      );
      return;
    }

    if (type === 'unread_changed') {
      setConversations((previous) =>
        previous.map((item) =>
          item.id === id ? { ...item, unread: event.unread ?? 0 } : item,
        ),
      );
      return;
    }

    if (type === 'conversation_setting_changed') {
      setConversations((previous) =>
        sortConversations(
          previous.map((item) =>
            item.id === id
              ? {
                  ...item,
                  pinned: event.pinned ?? item.pinned,
                  muted: event.muted ?? item.muted,
                  remark: event.remark ?? item.remark,
                }
              : item,
          ),
        ),
      );
      return;
    }

    if (type === 'message_updated' || type === 'message_recalled') {
      setConversations((previous) =>
        previous.map((item) =>
          item.id === id &&
          item.lastMessage &&
          event.messageId != null &&
          item.lastMessage.id === event.messageId
            ? {
                ...item,
                lastMessage: {
                  ...item.lastMessage,
                  content: {
                    text:
                      type === 'message_recalled'
                        ? tw('chat.messageRecalled', '[Message recalled]')
                        : event.previewText || '',
                  },
                },
              }
            : item,
        ),
      );
      return;
    }

    if (
      type === 'conversation_created' ||
      type === 'conversation_updated' ||
      type === 'message_pinned' ||
      type === 'message_unpinned' ||
      type === 'participant_changed'
    ) {
      if (
        type === 'conversation_created' &&
        conversations.some((item) => item.id === id)
      ) {
        return;
      }
      imApi
        .conversation(id)
        .then((conversation) =>
          setConversations((previous) =>
            mergeConversation(previous, conversation),
          ),
        )
        .catch(() => {});
    }
  };

  useEffect(() => {
    if (!streamEvent || streamEvent.nonce === streamNonceRef.current) return;
    streamNonceRef.current = streamEvent.nonce;
    applyStreamEvent(streamEvent.type, streamEvent.data);
    // The list updater intentionally uses the latest event only; functional
    // state updates inside applyStreamEvent keep concurrent events safe.
  }, [streamEvent]);

  const openConversation = (id: number, lastSeq?: number) => {
    setConversations((previous) =>
      previous.map((item) => (item.id === id ? { ...item, unread: 0 } : item)),
    );
    if (lastSeq) {
      imApi.markRead(id, lastSeq).catch(() => {});
    }
    onOpenChat(id);
  };

  const pinConversation = async (conversation: ImConversation) => {
    try {
      const updated = await imApi.updateSetting(conversation.id, {
        pinned: !conversation.pinned,
      });
      setConversations((previous) =>
        sortConversations(
          previous.map((item) =>
            item.id === conversation.id
              ? { ...item, pinned: updated.pinned }
              : item,
          ),
        ),
      );
    } catch {
      message.error(tw('chat.actionFailed', 'Operation failed'));
    }
  };

  const removeConversation = (conversation: ImConversation) => {
    modal.confirm({
      title: tw('conversation.deleteTitle', 'Delete "{name}"?', {
        name: conversationDisplayName(conversation, me),
      }),
      content: tw(
        'conversation.deleteContent',
        'This only removes the chat from your list. Other members are not affected.',
      ),
      okText: tw('common.delete', 'Delete'),
      okType: 'danger',
      cancelText: tw('common.cancel', 'Cancel'),
      onOk: async () => {
        await imApi.deleteConversation(conversation.id);
        setConversations((previous) =>
          previous.filter((item) => item.id !== conversation.id),
        );
        if (activeIdRef.current === conversation.id) {
          onCloseChat();
        }
        message.success(tw('conversation.deleted', 'Deleted'));
      },
    });
  };

  const handleConversationAction = (
    conversation: ImConversation,
    key: string,
  ) => {
    if (key === 'pin' || key === 'unpin') {
      pinConversation(conversation);
    } else if (key === 'delete') {
      removeConversation(conversation);
    }
  };

  const openGroupCreate = () => {
    setGroupOpen(true);
    setGroupKeyword('');
    setGroupMembers([]);
    if (contacts.length > 0) return;
    setContactsLoading(true);
    agentApi.workspaceContacts
      .users({ page: 1, size: 200 })
      .then((page: any) => setContacts(page.records || page || []))
      .catch(() => setContacts([]))
      .finally(() => setContactsLoading(false));
  };

  const createGroup = async () => {
    if (creatingRef.current) return;
    if (groupMembers.length === 0) {
      message.warning(
        tw('chat.selectMembersWarning', 'Select at least one member'),
      );
      return;
    }
    creatingRef.current = true;
    const title =
      contacts
        .filter((user) => groupMembers.includes(user.id))
        .map(displayName)
        .join('、')
        .slice(0, 64) || tw('chat.groupDefaultName', 'Group');
    setCreatingGroup(true);
    try {
      const conversation = await imApi.createConversation({
        type: 'GROUP',
        title,
        participantUserIds: groupMembers.map(Number),
      });
      setGroupOpen(false);
      setGroupMembers([]);
      setConversations((previous) => mergeConversation(previous, conversation));
      openConversation(conversation.id, conversation.lastSeq);
      reloadList();
    } catch {
      message.error(tw('chat.createFailed', 'Failed to create group'));
    } finally {
      creatingRef.current = false;
      setCreatingGroup(false);
    }
  };

  const selectedContacts = contacts.filter((user) =>
    groupMembers.includes(user.id),
  );
  const groupKeywordValue = groupKeyword.trim().toLowerCase();
  const filteredContacts = contacts.filter((user) => {
    if (isSelfUser(user, me)) return false;
    if (!groupKeywordValue) return true;
    const text = `${displayName(user)} ${user.username || ''}`;
    return text.toLowerCase().includes(groupKeywordValue);
  });
  const groupedContacts = filteredContacts.reduce<Record<string, any[]>>(
    (groups, user) => {
      const name = displayName(user);
      const key = /^[a-z]/i.test(name) ? name[0].toUpperCase() : '#';
      if (!groups[key]) groups[key] = [];
      groups[key].push(user);
      return groups;
    },
    {},
  );
  const contactGroups = Object.keys(groupedContacts).sort((left, right) => {
    if (left === '#') return 1;
    if (right === '#') return -1;
    return left.localeCompare(right);
  });
  const toggleMember = (id: number) => {
    setGroupMembers((previous) =>
      previous.includes(id)
        ? previous.filter((item) => item !== id)
        : [...previous, id],
    );
  };

  useEffect(() => {
    const tick = () => {
      if (document.visibilityState === 'visible') reloadList();
    };
    const timer = window.setInterval(tick, 45_000);
    const onVisible = () => {
      if (document.visibilityState === 'visible') reloadList();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, []);

  useEffect(() => {
    activeIdRef.current = conversationId;
  }, [conversationId]);

  useEffect(() => {
    if (!loadedRef.current) setLoading(true);
    imApi
      .conversations({ limit: 50 })
      .then((page) => {
        const rows = [...(page?.pinned || []), ...(page?.rows || [])];
        setConversations((previous) => {
          const pageIds = new Set(rows.map((item) => item.id));
          const retained = previous.filter((item) => !pageIds.has(item.id));
          return sortConversations(
            [...rows, ...retained].map((item) =>
              item.id === activeIdRef.current ? { ...item, unread: 0 } : item,
            ),
          );
        });
        setHasMore(Boolean(page?.hasMore));
        setNextCursor(page?.nextCursor);
      })
      .catch(() => setConversations([]))
      .finally(() => {
        loadedRef.current = true;
        setLoading(false);
      });
  }, [refreshKey]);

  const loadMore = async () => {
    if (!hasMore || !nextCursor || loadingMore) return;
    setLoadingMore(true);
    try {
      const page = await imApi.conversations({
        limit: 50,
        cursor: nextCursor,
      });
      const existing = new Set(conversations.map((item) => item.id));
      const appended = (page?.rows || []).filter(
        (item) => !existing.has(item.id),
      );
      setConversations((previous) =>
        sortConversations([...previous, ...appended]),
      );
      setHasMore(Boolean(page?.hasMore));
      setNextCursor(page?.nextCursor);
    } finally {
      setLoadingMore(false);
    }
  };

  useEffect(() => {
    if (conversations.length === 0) return;
    if (
      conversationId == null ||
      !conversations.some((item) => item.id === conversationId)
    ) {
      openConversation(conversations[0].id, conversations[0].lastSeq);
    }
  }, [conversations, conversationId]);

  const normalizedKeyword = keyword.trim().toLowerCase();
  const filtered = conversations.filter((conversation) => {
    if (!normalizedKeyword) return true;
    return (
      conversationDisplayName(conversation, me)
        .toLowerCase()
        .includes(normalizedKeyword) ||
      conversationPreview(conversation)
        .toLowerCase()
        .includes(normalizedKeyword)
    );
  });

  return (
    <div className={styles.chatLayout}>
      <div className={styles.conversationPanel}>
        <div className={styles.conversationHeader}>
          <div className={styles.searchRow}>
            <Input.Search
              allowClear
              placeholder={tw('chat.searchPlaceholder', 'Search chats')}
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
            />
            <button
              type="button"
              className={styles.addCircle}
              title={tw('chat.newGroupTitle', 'New group')}
              onClick={openGroupCreate}
            >
              <PlusOutlined />
            </button>
          </div>
        </div>
        <div
          className={styles.conversationScroll}
          onScroll={(event) => {
            const element = event.currentTarget;
            if (
              hasMore &&
              !loadingMore &&
              element.scrollHeight - element.scrollTop - element.clientHeight <
                80
            ) {
              loadMore();
            }
          }}
        >
          <Spin spinning={loading}>
            {filtered.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={
                  normalizedKeyword
                    ? tw('conversation.noResults', 'No matching chats')
                    : tw('conversation.empty', 'No chats')
                }
              />
            ) : (
              filtered.map((conversation) => {
                const title = conversationDisplayName(conversation, me);
                return (
                  <Dropdown
                    key={conversation.id}
                    trigger={['contextMenu']}
                    menu={{
                      items: [
                        {
                          key: conversation.pinned ? 'unpin' : 'pin',
                          label: conversation.pinned
                            ? tw('conversation.unpin', 'Unpin')
                            : tw('conversation.pin', 'Pin'),
                        },
                        { type: 'divider' },
                        {
                          key: 'delete',
                          label: tw('common.delete', 'Delete'),
                          danger: true,
                        },
                      ],
                      onClick: ({ key }) =>
                        handleConversationAction(conversation, key),
                    }}
                  >
                    <div
                      className={`${styles.conversationRow} ${
                        conversation.pinned ? styles.conversationRowPinned : ''
                      } ${
                        conversationId === conversation.id
                          ? styles.conversationRowActive
                          : ''
                      }`}
                      onClick={() =>
                        openConversation(conversation.id, conversation.lastSeq)
                      }
                    >
                      <Avatar
                        size={42}
                        shape={
                          conversation.type === 'GROUP' ? 'square' : 'circle'
                        }
                        src={conversationAvatar(conversation, me)}
                        style={avatarStyle(title)}
                      >
                        {initials(title)}
                      </Avatar>
                      <div className={styles.rowBody}>
                        <div
                          className={`${styles.rowTitle} ${styles.chatListTitle}`}
                        >
                          <span className={styles.conversationNameText}>
                            {title}
                          </span>
                          {isSelfConversation(conversation, me) ? (
                            <span className={styles.selfTag}>
                              {tw('common.meTag', 'Me')}
                            </span>
                          ) : null}
                          <span className={styles.rowTime}>
                            {listTime(conversation.lastMessageAt)}
                          </span>
                        </div>
                        <div className={styles.rowDesc}>
                          {conversationPreview(conversation)}
                        </div>
                      </div>
                      {conversation.unread ? (
                        <span className={styles.unread}>
                          {conversation.unread}
                        </span>
                      ) : null}
                    </div>
                  </Dropdown>
                );
              })
            )}
            {loadingMore ? (
              <div className={styles.loadMoreHint}>
                {tw('common.loading', 'Loading...')}
              </div>
            ) : null}
          </Spin>
        </div>
      </div>
      <div className={styles.chatPanel}>
        {conversationId != null ? (
          <ConversationPane
            conversationId={conversationId}
            refreshKey={refreshKey}
            streamEvent={streamEvent}
            onChanged={reloadList}
          />
        ) : (
          <div className={styles.chatEmpty}>
            {tw('conversation.select', 'Select a chat to start')}
          </div>
        )}
      </div>

      <Modal
        className={styles.groupModal}
        open={groupOpen}
        footer={null}
        closable={false}
        width={900}
        onCancel={() => setGroupOpen(false)}
        destroyOnHidden
      >
        <div className={styles.groupModalBody}>
          <div className={styles.groupLeft}>
            <div className={styles.groupSearch}>
              <Input
                allowClear
                value={groupKeyword}
                placeholder={tw('common.search', 'Search')}
                suffix={<AudioOutlined />}
                onChange={(event) => setGroupKeyword(event.target.value)}
              />
            </div>
            <Spin className={styles.groupSpin} spinning={contactsLoading}>
              <div className={styles.groupList}>
                {contactGroups.length === 0 ? (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={
                      groupKeywordValue
                        ? tw('chat.noMatchedContacts', 'No matching contacts')
                        : tw('chat.noContacts', 'No contacts')
                    }
                  />
                ) : (
                  contactGroups.map((key) => (
                    <div key={key}>
                      <div className={styles.groupSectionLabel}>
                        {key === '#' ? tw('chat.other', 'Other') : key}
                      </div>
                      {groupedContacts[key].map((user) => {
                        const selected = groupMembers.includes(user.id);
                        return (
                          <div
                            key={user.id}
                            className={`${styles.groupContactRow} ${
                              selected ? styles.groupContactRowActive : ''
                            }`}
                            onClick={() => toggleMember(user.id)}
                          >
                            <span
                              className={`${styles.groupCheck} ${
                                selected ? styles.groupCheckActive : ''
                              }`}
                            >
                              {selected ? <CheckOutlined /> : null}
                            </span>
                            <Avatar
                              size={38}
                              src={user.avatar}
                              style={avatarStyle(displayName(user))}
                            >
                              {initials(displayName(user))}
                            </Avatar>
                            <span className={styles.groupContactName}>
                              {displayName(user)}
                            </span>
                          </div>
                        );
                      })}
                    </div>
                  ))
                )}
              </div>
            </Spin>
          </div>
          <div className={styles.groupRight}>
            <div className={styles.groupRightTitle}>
              {tw('chat.newGroupTitle', 'New group')}
            </div>
            <div className={styles.groupSelectedList}>
              {selectedContacts.length === 0 ? (
                <div className={styles.groupRightEmpty}>
                  {tw('chat.selectMembers', 'Select group members')}
                </div>
              ) : (
                selectedContacts.map((user) => (
                  <div key={user.id}>
                    <div className={styles.groupSelectedRow}>
                      <Avatar
                        size={34}
                        src={user.avatar}
                        style={avatarStyle(displayName(user))}
                      >
                        {initials(displayName(user))}
                      </Avatar>
                      <span className={styles.groupSelectedName}>
                        {displayName(user)}
                      </span>
                      <button
                        type="button"
                        className={styles.groupRemove}
                        onClick={() => toggleMember(user.id)}
                      >
                        <CloseOutlined />
                      </button>
                    </div>
                  </div>
                ))
              )}
            </div>
            <div className={styles.groupFooter}>
              <Button
                className={styles.groupConfirm}
                disabled={groupMembers.length === 0}
                loading={creatingGroup}
                onClick={createGroup}
              >
                {tw('chat.done', 'Done')}
              </Button>
              <Button
                className={styles.groupCancel}
                onClick={() => setGroupOpen(false)}
              >
                {tw('common.cancel', 'Cancel')}
              </Button>
            </div>
          </div>
        </div>
      </Modal>
    </div>
  );
}

function ContactsTab({ onOpenChat }: { onOpenChat: (id: number) => void }) {
  const { styles } = useStyles();
  const { message } = App.useApp();
  const me = getStoredUser();
  const [keyword, setKeyword] = useState('');
  const [users, setUsers] = useState<any[]>([]);
  const [groups, setGroups] = useState<ImConversation[]>([]);
  const [agents, setAgents] = useState<SuperAgent[]>([]);
  const [agentsLoading, setAgentsLoading] = useState(true);
  const [loading, setLoading] = useState(true);

  const loadUsers = () => {
    setLoading(true);
    agentApi.workspaceContacts
      .users({ keyword: keyword || undefined, page: 1, size: 50 })
      .then((page: any) => setUsers(page.records || page || []))
      .catch(() => setUsers([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    imApi
      .conversations({ limit: 100, type: 'GROUP' })
      .then((page) =>
        setGroups([...(page?.pinned || []), ...(page?.rows || [])]),
      )
      .catch(() => setGroups([]));
  }, []);

  useEffect(() => {
    setAgentsLoading(true);
    superAgentApi
      .list(100)
      .then(setAgents)
      .catch(() => setAgents([]))
      .finally(() => setAgentsLoading(false));
  }, []);

  useEffect(loadUsers, [keyword]);

  const normalizedKeyword = keyword.trim().toLowerCase();
  const filteredAgents = agents.filter((agent) => {
    if (!normalizedKeyword) return true;
    return `${agent.name} ${agent.description || ''} ${positionLabel(agent)}`
      .toLowerCase()
      .includes(normalizedKeyword);
  });
  const filteredGroups = groups.filter((group) => {
    if (!normalizedKeyword) return true;
    return (
      conversationDisplayName(group, me)
        .toLowerCase()
        .includes(normalizedKeyword) ||
      conversationPreview(group).toLowerCase().includes(normalizedKeyword)
    );
  });

  const openUser = async (user: any) => {
    const self = isSelfUser(user, me);
    try {
      const conversation = await imApi.createConversation({
        type: 'DM',
        title: self ? tw('conversation.self', 'Myself') : displayName(user),
        participantUserIds: [user.id],
      });
      onOpenChat(conversation.id);
    } catch {
      message.error(tw('contacts.createDmFailed', 'Failed to create chat'));
    }
  };

  const openAgentChat = async (agent: SuperAgent) => {
    if (agent.status !== STATUS.ACTIVE) {
      message.warning(
        tw(
          'avatars.chatPaused',
          'This avatar is paused. Resume it before chatting.',
        ),
      );
      return;
    }
    try {
      const conversation = await imApi.createAgentDm(agent.id);
      onOpenChat(conversation.id);
    } catch {
      message.error(tw('contacts.createDmFailed', 'Failed to create chat'));
    }
  };

  return (
    <div className={styles.page}>
      <div className={styles.pageHead}>
        <div>
          <div className={styles.pageTitle}>
            {tw('contacts.title', 'Contacts')}
          </div>
          <div className={styles.pageSubtitle}>
            {tw(
              'contacts.subtitle',
              'Find a colleague or avatar and start chatting',
            )}
          </div>
        </div>
      </div>
      <Input.Search
        allowClear
        placeholder={tw(
          'contacts.searchPlaceholder',
          'Search contacts or groups',
        )}
        value={keyword}
        onChange={(event) => setKeyword(event.target.value)}
        style={{ marginBottom: 14 }}
      />

      <div className={styles.contactsGrid}>
        <div className={styles.contactsPanel}>
          <div className={styles.contactsPanelHead}>
            <span>{tw('contacts.agents', 'Digital avatars')}</span>
            <span className={styles.sectionMore}>
              {tw('contacts.agentCount', '{count}', {
                count: filteredAgents.length,
              })}
            </span>
          </div>
          <div className={styles.contactsPanelBody}>
            <Spin spinning={agentsLoading}>
              {filteredAgents.length === 0 ? (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={
                    normalizedKeyword
                      ? tw('contacts.noMatchedAgents', 'No matching avatars')
                      : tw('contacts.noAgents', 'No avatars')
                  }
                />
              ) : (
                filteredAgents.map((agent) => (
                  <div
                    key={agent.id}
                    className={styles.row}
                    style={{
                      opacity: agent.status === STATUS.ACTIVE ? 1 : 0.6,
                    }}
                    onClick={() => openAgentChat(agent)}
                  >
                    <Avatar
                      className={styles.avatar}
                      size={42}
                      style={avatarStyle(agent.name)}
                    >
                      {initials(agent.name)}
                    </Avatar>
                    <div className={styles.rowBody}>
                      <div className={styles.rowTitle}>
                        <span className={styles.conversationNameText}>
                          {agent.name}
                        </span>
                        <Tag
                          color={
                            agent.status === STATUS.ACTIVE ? 'green' : 'default'
                          }
                        >
                          {statusLabel(agent.status)}
                        </Tag>
                      </div>
                      <div className={styles.rowDesc}>
                        {[positionLabel(agent), agent.description]
                          .filter(Boolean)
                          .join(' · ') ||
                          tw('common.noDescription', 'No description')}
                      </div>
                    </div>
                    <MessageOutlined style={{ color: '#a7a2b8' }} />
                  </div>
                ))
              )}
            </Spin>
          </div>
        </div>
        <div className={styles.contactsPanel}>
          <div className={styles.contactsPanelHead}>
            <span>{tw('contacts.groups', 'Groups')}</span>
            <span className={styles.sectionMore}>
              {tw('contacts.groupCount', '{count}', {
                count: filteredGroups.length,
              })}
            </span>
          </div>
          <div className={styles.contactsPanelBody}>
            {filteredGroups.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={
                  normalizedKeyword
                    ? tw('contacts.noMatchedGroups', 'No matching groups')
                    : tw('contacts.noGroups', 'No groups')
                }
              />
            ) : (
              filteredGroups.map((group) => (
                <div
                  key={group.id}
                  className={styles.row}
                  onClick={() => onOpenChat(group.id)}
                >
                  <Avatar
                    className={styles.avatar}
                    size={42}
                    shape="square"
                    style={avatarStyle(conversationDisplayName(group, me))}
                  >
                    {initials(conversationDisplayName(group, me))}
                  </Avatar>
                  <div className={styles.rowBody}>
                    <div className={styles.rowTitle}>
                      <span className={styles.conversationNameText}>
                        {conversationDisplayName(group, me)}
                      </span>
                    </div>
                    <div className={styles.rowDesc}>
                      {conversationPreview(group)}
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>

        <div className={styles.contactsPanel}>
          <div className={styles.contactsPanelHead}>
            <span>{tw('contacts.users', 'Users')}</span>
            <span className={styles.sectionMore}>
              {tw('contacts.userCount', '{count}', {
                count: users.length,
              })}
            </span>
          </div>
          <div className={styles.contactsPanelBody}>
            <Spin spinning={loading}>
              {users.length === 0 ? (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={
                    normalizedKeyword
                      ? tw('contacts.noMatchedUsers', 'No matching users')
                      : tw('contacts.noUsers', 'No users')
                  }
                />
              ) : (
                users.map((user) => (
                  <div
                    key={user.id}
                    className={styles.row}
                    onClick={() => openUser(user)}
                  >
                    <Avatar
                      className={styles.avatar}
                      size={42}
                      src={user.avatar}
                      style={avatarStyle(displayName(user))}
                    >
                      {initials(displayName(user))}
                    </Avatar>
                    <div className={styles.rowBody}>
                      <div className={styles.rowTitle}>
                        <span className={styles.conversationNameText}>
                          {displayName(user)}
                        </span>
                        {isSelfUser(user, me) ? (
                          <span className={styles.selfTag}>
                            {tw('common.meTag', 'Me')}
                          </span>
                        ) : null}
                      </div>
                      <div className={styles.rowDesc}>
                        {(user.roles || []).join(' / ') || user.username}
                        {user.lastLoginAt
                          ? ` · ${listTime(user.lastLoginAt)}`
                          : ''}
                      </div>
                    </div>
                    <MessageOutlined style={{ color: '#a7a2b8' }} />
                  </div>
                ))
              )}
            </Spin>
          </div>
        </div>
      </div>
    </div>
  );
}

function MeTab({ me, onOpenAvatars }: { me: any; onOpenAvatars: () => void }) {
  const { styles } = useStyles();
  const { message } = App.useApp();
  const [providers, setProviders] = useState<any[]>([]);
  const [routes, setRoutes] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyModalOpen, setKeyModalOpen] = useState(false);
  const [selectedProvider, setSelectedProvider] = useState<any>(null);
  const [keyValue, setKeyValue] = useState('');
  const [savingKey, setSavingKey] = useState(false);
  const [modelModalOpen, setModelModalOpen] = useState(false);
  const [serverSettingsOpen, setServerSettingsOpen] = useState(false);
  const [defaultRouteId, setDefaultRouteId] = useState<number | undefined>();
  const [defaultRoute, setDefaultRoute] = useState<any>(null);

  const load = () => {
    setLoading(true);
    Promise.all([
      agentApi.modelProviders.list().catch(() => []),
      agentApi.routes.getDefault().catch(() => null),
    ])
      .then(([providerList, route]) => {
        setProviders(providerList || []);
        setDefaultRoute(route || null);
        setDefaultRouteId(route?.id);
      })
      .catch(() => {
        setProviders([]);
        setDefaultRoute(null);
      })
      .finally(() => setLoading(false));
  };

  const openModelModal = async () => {
    setModelModalOpen(true);
    if (routes.length > 0) return;
    try {
      const routeList = await agentApi.routes.listAll();
      setRoutes(routeList || []);
    } catch {
      setRoutes([]);
    }
  };

  useEffect(load, []);

  const openKeyModal = (provider: any) => {
    setSelectedProvider(provider);
    setKeyValue('');
    setKeyModalOpen(true);
  };

  const saveKey = async () => {
    if (!selectedProvider || !keyValue.trim()) return;
    setSavingKey(true);
    try {
      const created = await agentApi.apiKeys.create({
        providerId: selectedProvider.id,
        alias: 'workspace',
        keyValue: keyValue.trim(),
      });
      await agentApi.modelProviders.setActiveKey(
        selectedProvider.id,
        created.id,
      );
      message.success(tw('me.keySaved', 'API Key saved'));
      setKeyModalOpen(false);
      load();
    } catch {
      message.error(tw('common.saveFailed', 'Save failed'));
    } finally {
      setSavingKey(false);
    }
  };

  const saveDefaultModel = async () => {
    if (!defaultRouteId) return;
    await agentApi.routes.setDefault(defaultRouteId);
    setRoutes((previous) =>
      previous.map((route) => ({
        ...route,
        isDefault: route.id === defaultRouteId,
      })),
    );
    message.success(tw('me.defaultUpdated', 'Default model updated'));
    setModelModalOpen(false);
    load();
  };

  const logout = async () => {
    try {
      await umiRequest(API_PATHS.AUTH_LOGOUT, { method: 'POST' });
    } catch {
      // clear local session even if the server call fails
    }
    clearStoredUser();
    history.replace('/user/login');
  };

  return (
    <div className={styles.page}>
      <div className={styles.meHeader}>
        <Avatar size={62} src={me?.avatar} style={avatarStyle(displayName(me))}>
          {initials(displayName(me))}
        </Avatar>
        <div>
          <div className={styles.meName}>{displayName(me)}</div>
          <div className={styles.meBio}>
            {me?.email ||
              me?.username ||
              tw('me.bio', 'Digital employee workspace')}
          </div>
        </div>
      </div>

      <Spin spinning={loading}>
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>
            {tw('me.aiConfig', 'AI model configuration')}
          </span>
          <span className={styles.sectionMore}>
            {tw('me.fillKey', 'Enter API Key')}
          </span>
        </div>
        <div className={styles.menuCard} style={{ marginBottom: 18 }}>
          {providers.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={tw('me.noProviders', 'No providers')}
            />
          ) : (
            providers.map((provider) => (
              <div key={provider.id} className={styles.providerRow}>
                <div
                  className={styles.menuIcon}
                  style={{ ...avatarStyle(provider.name), color: '#fff' }}
                >
                  <RobotOutlined />
                </div>
                <div className={styles.providerName}>{provider.name}</div>
                <span
                  className={provider.apiKeyId ? styles.tagOk : styles.tagMuted}
                >
                  {provider.apiKeyId
                    ? tw('me.configured', 'Configured')
                    : tw('me.notConfigured', 'Not configured')}
                </span>
                <Button
                  size="small"
                  shape="round"
                  onClick={() => openKeyModal(provider)}
                >
                  {provider.apiKeyId
                    ? tw('me.updateKey', 'Update Key')
                    : tw('me.fillKeyAction', 'Enter Key')}
                </Button>
              </div>
            ))
          )}
        </div>

        <div className={styles.menuCard} style={{ marginBottom: 18 }}>
          <div className={styles.menuRow} onClick={openModelModal}>
            <div className={styles.menuIcon}>
              <SettingOutlined />
            </div>
            <div className={styles.menuBody}>
              <div className={styles.menuTitle}>
                {tw('me.defaultModel', 'Default model')}
              </div>
              <div className={styles.menuDesc}>
                {defaultRoute
                  ? `${defaultRoute.providerName || defaultRoute.provider || ''} ${defaultRoute.modelName || ''}`.trim()
                  : tw('me.notSet', 'Not set')}
              </div>
            </div>
            <span className={styles.sectionMore}>›</span>
          </div>
          <div className={styles.menuRow} onClick={onOpenAvatars}>
            <div className={styles.menuIcon}>
              <TeamOutlined />
            </div>
            <div className={styles.menuBody}>
              <div className={styles.menuTitle}>
                {tw('me.avatarSettings', 'Avatar settings')}
              </div>
              <div className={styles.menuDesc}>
                {tw('me.avatarSettingsDesc', 'Manage on the avatars page')}
              </div>
            </div>
            <span className={styles.sectionMore}>›</span>
          </div>
          {isDesktop() ? (
            <div
              className={styles.menuRow}
              onClick={() => setServerSettingsOpen(true)}
            >
              <div className={styles.menuIcon}>
                <CloudServerOutlined />
              </div>
              <div className={styles.menuBody}>
                <div className={styles.menuTitle}>
                  {tw('me.server', 'Server connection')}
                </div>
                <div className={styles.menuDesc}>
                  {tw(
                    'me.serverDesc',
                    'Switch between online and local backend',
                  )}
                </div>
              </div>
              <span className={styles.sectionMore}>›</span>
            </div>
          ) : null}
          <div
            className={styles.menuRow}
            onClick={() => {
              window.location.href = '/dashboard';
            }}
          >
            <div className={styles.menuIcon}>
              <SettingOutlined />
            </div>
            <div className={styles.menuBody}>
              <div className={styles.menuTitle}>
                {tw('me.admin', 'console')}
              </div>
              <div className={styles.menuDesc}>
                {tw('me.adminDesc', 'Open console')}
              </div>
            </div>
            <span className={styles.sectionMore}>›</span>
          </div>
        </div>
      </Spin>

      <div className={styles.logoutRow}>
        <button type="button" className={styles.logoutButton} onClick={logout}>
          <LogoutOutlined />
          <span>{tw('me.logout', 'Sign out')}</span>
        </button>
      </div>

      {isDesktop() ? (
        <div
          style={{
            marginTop: 10,
            textAlign: 'center',
            fontSize: 11,
            color: '#a7a2b8',
          }}
        >
          {tw('me.clientVersion', 'Desktop version {version}', {
            version: getAppVersion() || '-',
          })}
        </div>
      ) : null}

      <Modal
        className={styles.softModal}
        open={keyModalOpen}
        title={tw('me.apiKeyTitle', '{name} API Key', {
          name: selectedProvider?.name || '',
        })}
        okText={tw('common.save', 'Save')}
        cancelText={tw('common.cancel', 'Cancel')}
        confirmLoading={savingKey}
        onOk={saveKey}
        onCancel={() => setKeyModalOpen(false)}
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">
          {tw(
            'me.keyNote',
            'The key is stored securely and never displayed in plain text.',
          )}
        </Typography.Paragraph>
        <Input.Password
          value={keyValue}
          onChange={(event) => setKeyValue(event.target.value)}
          placeholder={tw('me.keyPlaceholder', 'Enter API Key')}
        />
      </Modal>

      <Modal
        className={styles.softModal}
        open={modelModalOpen}
        title={tw('me.defaultModel', 'Default model')}
        okText={tw('common.save', 'Save')}
        cancelText={tw('common.cancel', 'Cancel')}
        onOk={saveDefaultModel}
        onCancel={() => setModelModalOpen(false)}
        destroyOnHidden
      >
        <Select
          style={{ width: '100%' }}
          value={defaultRouteId}
          onChange={setDefaultRouteId}
          options={routes.map((route) => ({
            value: route.id,
            label: `${route.providerName ? `${route.providerName} · ` : ''}${route.modelName}${
              route.isDefault ? tw('me.currentDefault', ' (current)') : ''
            }`,
          }))}
        />
      </Modal>

      <ServerSettingsModal
        open={serverSettingsOpen}
        onClose={() => setServerSettingsOpen(false)}
      />
    </div>
  );
}
