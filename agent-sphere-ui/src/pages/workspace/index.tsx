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
  MinusOutlined,
  MoreOutlined,
  PlusOutlined,
  PushpinOutlined,
  RobotOutlined,
  SendOutlined,
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
  Drawer,
  Dropdown,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import MemberPickerModal from '@/components/MemberPickerModal';
import { MentionSuggest } from '@/components/mention/MentionSuggest';
import {
  buildMentionCandidates,
  mentionsFromText,
} from '@/components/mention/mentionModel';
import { useMentionSuggest } from '@/components/mention/useMentionSuggest';
import { ServerSettingsModal } from '@/components/ServerSettings';
import { API_PATHS } from '@/constants/endpoints';
import { STATUS, SUPER_AGENT_STATUS_CODE } from '@/constants/status';
import { TIMING } from '@/constants/timing';
import type { ImStreamPayload } from '@/hooks/useReconnectingSse';
import { useReconnectingSse } from '@/hooks/useReconnectingSse';
import { agentApi } from '@/services/agentSphere/api';
import { dictApi, type SystemDicEntry } from '@/services/agentSphere/dict';
import {
  type ConversationAgent,
  type ImConversation,
  type ImConversationSetting,
  type ImMessage,
  imApi,
  type SessionInputDecision,
} from '@/services/agentSphere/im';
import {
  type SuperAgent,
  superAgentApi,
} from '@/services/agentSphere/superAgent';
import { clearStoredUser, getStoredUser } from '@/utils/auth';
import { getDesktopBridge } from '@/utils/desktopBridge';
import { getAppVersion, isDesktop } from '@/utils/runtimeEnv';
import { userDisplayLabel } from '@/utils/userLabel';
import { messageText } from '../im/components/messageUtils';
import {
  type AgentClarificationAnswer,
  AgentInputCard,
  ROUTER_APPROVAL,
  ROUTER_CLARIFICATION,
} from './AgentInputCard';
import { AgentStreamBubble } from './AgentStreamBubble';
import {
  AGENT_STREAM_PHASE,
  appendLocalChunk,
  applyAgentStreamEvent,
  beginAgentStream,
  failAgentStream,
  finalizeAgentStream,
  peekAgentStream,
  resetAgentStream,
  useAgentStream,
} from './agentStreamStore';
import { ApprovalInboxDrawer } from './approval/ApprovalInboxDrawer';
import { usePendingApprovals } from './approval/usePendingApprovals';
import { AvatarDetailDrawer } from './avatars/AvatarDetailDrawer';
import { LocalTaskModal } from './avatars/LocalTaskModal';
import { CollapsibleMarkdown } from './CollapsibleMarkdown';
import {
  conversationAvatar,
  conversationDisplayName,
  conversationPreview,
  fullTime,
  isSameUser,
  isSelfConversation,
  isSelfParticipants,
  isSelfUser,
  listTime,
  mergeConversation,
  mergeMessages,
  messageTime,
  type PendingMessage,
  positionLabel,
  sortConversations,
  statusLabel,
  taskCardOf,
  taskStatusLabel,
} from './conversationModel';
import { DocumentsTab } from './documents/DocumentsTab';
import { tw } from './i18n';
import { MessageMarkdown } from './MessageMarkdown';
import { avatarStyle, displayName, initials } from './shared';
import { useStyles } from './style';
import { TrainingPanel } from './training/TrainingPanel';

type Tab = 'home' | 'avatars' | 'chat' | 'contacts' | 'documents' | 'me';

export default function WorkspacePage() {
  const { styles } = useStyles();
  useIntl();
  const { initialState } = useModel('@@initialState');
  const me = getStoredUser() || initialState?.currentUser;
  const [tab, setTab] = useState<Tab>('home');
  const [conversationId, setConversationId] = useState<number | null>(null);
  const [approvalOpen, setApprovalOpen] = useState(false);
  const [trainingAgent, setTrainingAgent] = useState<SuperAgent | null>(null);
  const {
    items: pendingApprovals,
    loading: approvalsLoading,
    reload: reloadApprovals,
    count: pendingApprovalCount,
  } = usePendingApprovals();

  const openChat = (id: number) => {
    setConversationId(id);
    setTab('chat');
  };

  return (
    <>
      <div className={styles.backdrop}>
        <div className={styles.app}>
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
    </>
  );
}

function TopBar({ me }: { me: any }) {
  const { styles } = useStyles();
  const locale = getLocale();
  return (
    <div className={styles.topBar}>
      <div className={styles.topRight}>
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
  const { message, modal } = App.useApp();
  const [loading, setLoading] = useState(true);
  const [agents, setAgents] = useState<SuperAgent[]>([]);
  const [positions, setPositions] = useState<SystemDicEntry[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();
  const nameValue = Form.useWatch('name', form);
  const createType = Form.useWatch('agentType', form);
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
  const { styles } = useStyles();
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
  const [streamEvent, setStreamEvent] = useState<{
    type: string;
    data: ImStreamPayload;
    nonce: number;
  } | null>(null);
  const loadedRef = useRef(false);
  const creatingRef = useRef(false);
  const activeIdRef = useRef<number | null>(conversationId);

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
    setStreamEvent({ type, data: event, nonce: Date.now() });

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

  useReconnectingSse(
    API_PATHS.IM_STREAM,
    applyStreamEvent,
    // Every (re)connect: notify the chat panel so it refetches whatever the
    // dropped connection swallowed (server never replays missed events).
    () =>
      setStreamEvent({
        type: 'sse_reconnected',
        data: {} as ImStreamPayload,
        nonce: Date.now(),
      }),
  );

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
          <ChatDetail
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

function ChatDetail({
  conversationId,
  refreshKey,
  streamEvent,
  onChanged,
}: {
  conversationId: number;
  refreshKey: number;
  streamEvent: { type: string; data: ImStreamPayload; nonce: number } | null;
  onChanged: () => void;
}) {
  const { styles } = useStyles();
  const { message, modal } = App.useApp();
  const me = getStoredUser();
  const [conversation, setConversation] = useState<ImConversation | null>(null);
  const [participants, setParticipants] = useState<any[]>([]);
  const [agents, setAgents] = useState<ConversationAgent[]>([]);
  const [messages, setMessages] = useState<ImMessage[]>([]);
  /** Locally echoed messages awaiting (or failing) the server confirmation. */
  const [pendingMessages, setPendingMessages] = useState<PendingMessage[]>([]);
  const [loading, setLoading] = useState(true);
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [agentPaused, setAgentPaused] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [setting, setSetting] = useState<ImConversationSetting | null>(null);
  const [memberKeyword, setMemberKeyword] = useState('');
  const [inviteOpen, setInviteOpen] = useState(false);
  const [inviteOptions, setInviteOptions] = useState<any[]>([]);
  const [inviteSelected, setInviteSelected] = useState<number[]>([]);
  const [inviting, setInviting] = useState(false);
  const [removeOpen, setRemoveOpen] = useState(false);
  const [removeSelected, setRemoveSelected] = useState<number[]>([]);
  const [removing, setRemoving] = useState(false);
  const [groupName, setGroupName] = useState('');
  const [announcement, setAnnouncement] = useState('');
  const [remarkValue, setRemarkValue] = useState('');
  const [savingMeta, setSavingMeta] = useState(false);
  const [savingSetting, setSavingSetting] = useState(false);
  const [gateTrigger, setGateTrigger] = useState('MENTION');
  const [gateStrict, setGateStrict] = useState(false);
  const [savingGate, setSavingGate] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);
  const composerRef = useRef<HTMLTextAreaElement>(null);
  const loadedIdRef = useRef<number | null>(null);
  const messagesRef = useRef<ImMessage[]>([]);
  messagesRef.current = messages;
  /** Stable negative ids for optimistic rows; never collide with server ids. */
  const pendingIdRef = useRef(-1);
  const agentStream = useAgentStream(conversationId);

  const avatarCandidates = useMemo(
    () =>
      agents.map((agent) => ({
        participantType: 'AGENT',
        participantId: agent.participantId,
        displayName: agent.displayName,
        ownerUserId: agent.ownerUserId,
      })),
    [agents],
  );
  const mentionCandidates = useMemo(
    () =>
      buildMentionCandidates([...participants, ...avatarCandidates], me?.id),
    [participants, avatarCandidates, me?.id],
  );
  const mention = useMentionSuggest({
    textareaRef: composerRef,
    value: text,
    candidates: mentionCandidates,
    onChange: (nextText, caret) => {
      setText(nextText);
      requestAnimationFrame(() => {
        const element = composerRef.current;
        if (!element) return;
        element.focus();
        element.setSelectionRange(caret, caret);
        element.style.height = 'auto';
        element.style.height = `${Math.min(element.scrollHeight, 120)}px`;
      });
    },
  });

  const load = (silent = false) => {
    if (!silent) {
      setLoading(true);
      // The history cursor must be reset on first load when switching sessions, to avoid a beforeSeq from another session.
      setHasOlder(true);
      setOlderCursor(undefined);
    }
    Promise.all([
      imApi.conversation(conversationId),
      imApi.participants(conversationId),
      // Older backends have no /agents endpoint; avatars then still come from participants.
      imApi.agents(conversationId).catch(() => []),
      imApi.messagePage(conversationId, undefined, undefined, 50),
    ])
      .then(([current, members, agentRows, page]) => {
        setConversation(current || null);
        setParticipants(members || []);
        setAgents(agentRows || []);
        setMessages((page?.rows || []).slice().sort((a, b) => a.seq - b.seq));
        setHasOlder(Boolean(page?.hasMore));
        setOlderCursor(page?.nextBeforeSeq);
      })
      .catch(() => {
        setMessages([]);
      })
      .finally(() => {
        loadedIdRef.current = conversationId;
        setLoading(false);
      });
  };

  useEffect(() => {
    setSettingsOpen(false);
  }, [conversationId]);

  /**
   * Desktop runtime fragments arrive here directly, before the backend relays
   * them on the user SSE stream. Render them immediately; the server stream and
   * the authoritative `message_created` still replace this optimistic text.
   */
  useEffect(() => {
    const bridge = getDesktopBridge();
    const threadId =
      conversation?.id === conversationId ? conversation.threadId : undefined;
    if (!bridge || !threadId) return undefined;
    // Tasks already finished on this machine must not recreate a bubble when a
    // late fragment arrives after the authoritative message was merged.
    const finishedTasks = new Set<number>();
    const timers = new Set<number>();
    const rememberFinished = (taskId: number) => {
      finishedTasks.add(taskId);
      if (finishedTasks.size > 20) {
        const oldest = finishedTasks.values().next().value;
        if (oldest !== undefined) finishedTasks.delete(oldest);
      }
    };
    const unsubscribeChunk = bridge.events.onChunk((chunk) => {
      if (chunk.threadId !== threadId || finishedTasks.has(chunk.taskId)) {
        return;
      }
      appendLocalChunk(conversationId, {
        taskId: chunk.taskId,
        agentId: chunk.agentId,
        phase: chunk.phase,
        delta: chunk.delta,
      });
    });
    const unsubscribeTask = bridge.events.onTask((task) => {
      if (task.threadId !== threadId) return;
      if (
        task.status !== 'COMPLETED' &&
        task.status !== 'FAILED' &&
        task.status !== 'CANCELLED'
      ) {
        return;
      }
      rememberFinished(task.taskId);
      if (task.status === 'FAILED' || task.status === 'CANCELLED') {
        if (!peekAgentStream(conversationId)) beginAgentStream(conversationId);
        const state = peekAgentStream(conversationId);
        if (state?.taskId && state.taskId !== task.taskId) return;
        if (state && !state.finalized) {
          failAgentStream(
            conversationId,
            task.error || tw('chat.localTaskFailed', 'Local task failed'),
          );
        }
        return;
      }
      // The server SSE normally finalizes first. If it is down or late, route
      // the bubble into the authoritative refetch path instead of hanging.
      const timer = window.setTimeout(() => {
        timers.delete(timer);
        const state = peekAgentStream(conversationId);
        if (state?.taskId && state.taskId !== task.taskId) return;
        if (state && !state.finalized) finalizeAgentStream(conversationId);
      }, TIMING.LOCAL_STREAM_COMPLETE_GRACE_MS);
      timers.add(timer);
    });
    return () => {
      unsubscribeChunk();
      unsubscribeTask();
      for (const timer of timers) window.clearTimeout(timer);
      timers.clear();
    };
  }, [conversationId, conversation?.threadId]);

  /**
   * Scroll-up pagination for the message list. The viewport is pinned to the
   * first pre-existing row so prepending 50 messages does not visually jump.
   */
  const loadOlder = async () => {
    if (!olderCursor || !hasOlder || olderLoading) return;
    setOlderLoading(true);
    try {
      const page = await imApi.messagePage(
        conversationId,
        olderCursor,
        undefined,
        50,
      );
      const container = listRef.current;
      const beforeHeight = container?.scrollHeight;
      setMessages((previous) => mergeMessages(page.rows || [], previous));
      setHasOlder(Boolean(page.hasMore));
      setOlderCursor(page.nextBeforeSeq);
      // React renders the prepended rows after this tick: re-anchor the
      // viewport on the next frame so the user keeps looking at the same row.
      window.requestAnimationFrame(() => {
        const element = listRef.current;
        if (element && beforeHeight) {
          element.scrollTop += element.scrollHeight - beforeHeight;
        }
      });
    } catch {
      setHasOlder(false);
    } finally {
      setOlderLoading(false);
    }
  };

  const chatAgentId =
    conversation?.type === 'DM'
      ? participants.find((member) => member.participantType === 'AGENT')
          ?.participantId
      : undefined;

  const [olderCursor, setOlderCursor] = useState<number | undefined>(undefined);
  const [hasOlder, setHasOlder] = useState(true);
  const [olderLoading, setOlderLoading] = useState(false);

  useEffect(() => {
    if (!chatAgentId) {
      setAgentPaused(false);
      return;
    }
    superAgentApi
      .get(chatAgentId)
      .then((agent) => setAgentPaused(agent.status !== STATUS.ACTIVE))
      .catch(() => setAgentPaused(false));
  }, [conversationId, chatAgentId]);

  useEffect(() => {
    const first = loadedIdRef.current !== conversationId;
    if (first) {
      setMessages([]);
      setPendingMessages([]);
      setParticipants([]);
      setAgents([]);
      setConversation(null);
      // A freshly opened conversation starts pinned to the bottom.
      stickToBottomRef.current = true;
    }
    load(!first);
  }, [conversationId, refreshKey]);

  // Regular user sessions have no digital-avatar participant: clean up any residual streaming bubble state.
  useEffect(() => {
    if (participants.length === 0) return;
    const hasAgent = participants.some(
      (member) => member.participantType === 'AGENT',
    );
    if (!hasAgent) {
      resetAgentStream(conversationId);
    }
  }, [conversationId, participants]);

  /**
   * Follow new content only while the user is already near the bottom. Without
   * this gate, prepending a history page (scroll-up pagination) would yank the
   * viewport straight back down on the very next effect run.
   */
  const stickToBottomRef = useRef(true);

  useEffect(() => {
    if (listRef.current && stickToBottomRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
    // Reasoning fragments now live in the floating title chip, not the flow.
  }, [messages, pendingMessages, agentStream?.reply]);

  useEffect(() => {
    const lastSeq = messages[messages.length - 1]?.seq;
    if (lastSeq) {
      imApi.markRead(conversationId, lastSeq).catch(() => {});
    }
  }, [conversationId, messages]);

  useEffect(() => {
    if (!streamEvent || streamEvent.data.conversationId !== conversationId) {
      return;
    }
    const { type, data } = streamEvent;
    if (type === 'sse_reconnected') {
      // The connection was dropped (or the tab surfaced again): fetch whatever
      // messages arrived during the gap, since the server never replays them.
      const lastSeq = messagesRef.current[messagesRef.current.length - 1]?.seq;
      if (!lastSeq) return;
      imApi
        .messagePage(conversationId, undefined, lastSeq, 50)
        .then((page) => {
          const rows = page.rows || [];
          if (rows.length === 0) return;
          setMessages((previous) => mergeMessages(previous, rows));
          const agentRow = rows.find((row) => row.senderType === 'AGENT');
          if (agentRow) {
            finalizeAgentStream(conversationId, agentRow.seq);
          }
        })
        .catch(() => {});
      return;
    }
    if (type === 'message_created') {
      if (data.clientMsgId) {
        // The authoritative row landed: retire the matching local echo.
        setPendingMessages((previous) =>
          previous.filter((item) => item.clientMsgId !== data.clientMsgId),
        );
      }
      // Agent authoritative reply: retire the ephemeral stream bubble.
      if (data.senderType === 'AGENT') {
        finalizeAgentStream(conversationId, data.seq);
      }
      const current = messagesRef.current;
      const lastSeq = current[current.length - 1]?.seq;
      if (!lastSeq) {
        load(true);
        return;
      }
      imApi
        .messagePage(conversationId, undefined, lastSeq, 50)
        .then((page) => {
          const rows = page.rows || [];
          if (rows.length === 0) return;
          setMessages((previous) => mergeMessages(previous, rows));
          const agentRow = rows.find((row) => row.senderType === 'AGENT');
          if (agentRow) {
            finalizeAgentStream(conversationId, agentRow.seq);
          }
          const last = rows[rows.length - 1]?.seq;
          if (last) {
            imApi.markRead(conversationId, last).catch(() => {});
          }
        })
        .catch(() => {});
      return;
    }
    if (type === 'message_updated' || type === 'message_recalled') {
      load(true);
      return;
    }
    if (type === 'conversation_updated' || type === 'participant_changed') {
      imApi
        .participants(conversationId)
        .then(setParticipants)
        .catch(() => {});
      imApi
        .conversation(conversationId)
        .then((conversation) => {
          if (conversation) setConversation(conversation);
        })
        .catch(() => {});
      return;
    }
    if (type === 'message_pinned' || type === 'message_unpinned') {
      imApi
        .conversation(conversationId)
        .then((conversation) => {
          if (conversation) setConversation(conversation);
        })
        .catch(() => {});
      return;
    }
    if (type === 'conversation_setting_changed') {
      setConversation((previous) =>
        previous
          ? {
              ...previous,
              pinned: data.pinned ?? previous.pinned,
              muted: data.muted ?? previous.muted,
              remark: data.remark ?? previous.remark,
            }
          : previous,
      );
    }
  }, [streamEvent, conversationId]);

  // Authoritative message fallback: fetch one incremental batch when the stream is completed but message_created arrives late / is lost.
  useEffect(() => {
    if (
      agentStream?.phase !== AGENT_STREAM_PHASE.DONE ||
      !agentStream.finalized
    ) {
      return;
    }
    const timer = window.setTimeout(() => {
      const lastSeq = messagesRef.current[messagesRef.current.length - 1]?.seq;
      imApi
        .messagePage(conversationId, undefined, lastSeq, 50)
        .then((page) => {
          const rows = page.rows || [];
          if (rows.length > 0) {
            setMessages((previous) => mergeMessages(previous, rows));
            const agentRow = rows.find((row) => row.senderType === 'AGENT');
            if (agentRow) {
              finalizeAgentStream(conversationId, agentRow.seq);
            }
          }
        })
        .catch(() => {});
    }, 800);
    return () => window.clearTimeout(timer);
  }, [agentStream?.phase, agentStream?.finalized, conversationId]);

  // Waiting-state fallback: avoid the "processing" bubble staying forever when an event is lost.
  useEffect(() => {
    if (
      agentStream?.phase !== AGENT_STREAM_PHASE.WAITING ||
      agentStream.runId
    ) {
      return;
    }
    const timer = window.setTimeout(() => {
      resetAgentStream(conversationId);
    }, 30000);
    return () => window.clearTimeout(timer);
  }, [agentStream?.phase, agentStream?.runId, conversationId]);

  const send = () => {
    const value = text.trim();
    if (!value || sending) return;
    if (agentPaused) {
      message.warning(
        tw(
          'avatars.chatPaused',
          'This avatar is paused. Resume it before chatting.',
        ),
      );
      return;
    }
    // Clear the composer immediately: the message is already on the wall as a
    // pending bubble, so waiting for the POST would make sending feel slow.
    setText('');
    mention.close();
    if (composerRef.current) composerRef.current.style.height = 'auto';
    void sendText(value, `workspace-${Date.now()}`);
  };

  const sendErrorText = (error: any): string =>
    error?.response?.data?.message ||
    error?.data?.message ||
    error?.message ||
    tw('chat.sendFailed', 'Failed to send');

  /** Sends an arbitrary DM text; clarification option buttons reuse this path. */
  const sendText = async (value: string, clientMsgId?: string) => {
    if (!value || sending) return;
    if (agentPaused) {
      message.warning(
        tw(
          'avatars.chatPaused',
          'This avatar is paused. Resume it before chatting.',
        ),
      );
      return;
    }
    const shouldStream = Boolean(chatAgentId);
    const id = clientMsgId || `workspace-${Date.now()}`;
    // Optimistic echo: the bubble appears before the server confirms, then is
    // replaced by the authoritative row (same clientMsgId) on success.
    setPendingMessages((previous) => [
      ...previous,
      {
        id: pendingIdRef.current--,
        conversationId,
        seq: 0,
        senderType: 'USER',
        senderId: me?.id,
        clientMsgId: id,
        type: 'TEXT',
        content: { text: value },
        status: 'SENDING',
        createdAt: new Date().toISOString(),
        senderName: displayName(me),
      },
    ]);
    setSending(true);
    // Sending is an explicit jump-to-bottom intent even if the user had
    // scrolled up to read history.
    stickToBottomRef.current = true;
    if (shouldStream) {
      // Show the "processing" bubble immediately: Router dispatch is already millisecond-level, but the model's first token still takes time.
      beginAgentStream(conversationId);
    }
    try {
      // The body is the source of truth: deleting an @name also drops its routing.
      const mentionList = mentionsFromText(value, mentionCandidates);
      const sent = await imApi.send(conversationId, value, id, mentionList);
      setPendingMessages((previous) =>
        previous.filter((item) => item.clientMsgId !== id),
      );
      if (sent) {
        setMessages((previous) => mergeMessages(previous, [sent]));
      }
    } catch (error: any) {
      const reason = sendErrorText(error);
      setPendingMessages((previous) =>
        previous.map((item) =>
          item.clientMsgId === id
            ? { ...item, status: 'FAILED', error: reason }
            : item,
        ),
      );
      if (shouldStream) {
        resetAgentStream(conversationId);
      }
      message.error(reason);
    } finally {
      setSending(false);
    }
  };

  /** Retries a failed echo; the server deduplicates by clientMsgId. */
  const retryPending = async (item: PendingMessage) => {
    if (item.status === 'SENDING') return;
    const value = messageText(item) || '';
    if (!value) return;
    const id = item.clientMsgId || `retry-${Date.now()}`;
    const shouldStream = Boolean(chatAgentId);
    setPendingMessages((previous) =>
      previous.map((row) =>
        row.id === item.id
          ? { ...row, clientMsgId: id, status: 'SENDING', error: undefined }
          : row,
      ),
    );
    if (shouldStream) beginAgentStream(conversationId);
    try {
      const sent = await imApi.send(
        conversationId,
        value,
        id,
        mentionsFromText(value, mentionCandidates),
      );
      setPendingMessages((previous) =>
        previous.filter((row) => row.id !== item.id),
      );
      if (sent) {
        setMessages((previous) => mergeMessages(previous, [sent]));
      }
    } catch (error: any) {
      const reason = sendErrorText(error);
      setPendingMessages((previous) =>
        previous.map((row) =>
          row.id === item.id
            ? { ...row, status: 'FAILED', error: reason }
            : row,
        ),
      );
      if (shouldStream) resetAgentStream(conversationId);
      message.error(reason);
    }
  };

  /** Card-only clarification answer; ordinary composer text never reaches this path. */
  const answerClarification = async (
    inputId: string,
    payload: AgentClarificationAnswer,
  ) => {
    try {
      await imApi.answerClarification(conversationId, inputId, payload);
      await load(true);
      message.success(tw('common.saved', 'Saved'));
      return true;
    } catch (error: any) {
      message.error(
        error?.response?.data?.message ||
          error?.data?.message ||
          error?.message ||
          tw('chat.sendFailed', 'Failed to send'),
      );
      return false;
    }
  };

  /** Submits per-call approval decisions for a Router tool-approval card. */
  const approveSessionInput = async (
    inputConversationId: number,
    inputId: string,
    decisions: SessionInputDecision[],
  ) => {
    try {
      await imApi.resolveSessionInputApproval(
        inputConversationId,
        inputId,
        decisions,
      );
      message.success(tw('chat.inputApproved', 'Approval submitted'));
      load(true);
      return true;
    } catch {
      message.error(
        tw(
          'chat.inputApproveFailed',
          'Failed to submit the approval, please retry',
        ),
      );
      return false;
    }
  };

  const openSettings = () => {
    setSettingsOpen(true);
    setMemberKeyword('');
    setSetting(null);
    setGroupName(conversation?.title || '');
    setAnnouncement(conversation?.purpose || '');
    setRemarkValue(conversation?.remark || '');
    imApi
      .setting(conversationId)
      .then((data) => {
        setSetting(data);
        setRemarkValue(data.remark || '');
      })
      .catch(() => setSetting(null));
    if (conversation?.type === 'GROUP') {
      imApi
        .gateConfig(conversationId)
        .then((config) => {
          setGateTrigger(config?.trigger || 'MENTION');
          setGateStrict(Boolean(config?.clarificationStrict));
        })
        .catch(() => {
          setGateTrigger('MENTION');
          setGateStrict(false);
        });
    } else {
      setGateTrigger('MENTION');
      setGateStrict(false);
    }
  };

  const saveMeta = async (patch: { title?: string; purpose?: string }) => {
    setSavingMeta(true);
    try {
      const updated = await imApi.updateConversation(conversationId, patch);
      setConversation((previous) =>
        previous ? { ...previous, ...updated } : updated,
      );
      message.success(tw('common.saved', 'Saved'));
      onChanged();
    } catch {
      message.error(tw('common.saveFailed', 'Save failed'));
    } finally {
      setSavingMeta(false);
    }
  };

  const refreshConversation = async () => {
    const [updated, agentRows] = await Promise.all([
      imApi.conversation(conversationId),
      imApi.agents(conversationId).catch(() => []),
    ]);
    setConversation(updated);
    setParticipants(updated?.participants || []);
    setAgents(agentRows || []);
    onChanged();
  };

  const changeMemberRole = async (member: any, role: string) => {
    try {
      await imApi.updateParticipantRole(
        conversationId,
        member.participantId,
        role,
      );
      await refreshConversation();
      message.success(tw('common.saved', 'Saved'));
    } catch {
      message.error(tw('common.saveFailed', 'Save failed'));
    }
  };

  const transferGroupOwner = (member: any) => {
    modal.confirm({
      title: tw('chat.transferOwnerConfirm', 'Transfer group ownership?'),
      content: tw(
        'chat.transferOwnerHint',
        'The selected member becomes owner; you become an administrator.',
      ),
      okText: tw('common.confirm', 'Confirm'),
      onOk: async () => {
        await imApi.transferOwner(conversationId, member.participantId);
        await refreshConversation();
      },
    });
  };

  const toggleMemberInvite = async (checked: boolean) => {
    try {
      await imApi.updateInvitePolicy(conversationId, checked);
      setConversation((previous) =>
        previous ? { ...previous, allowMemberInvite: checked } : previous,
      );
      message.success(tw('common.saved', 'Saved'));
    } catch {
      message.error(tw('common.saveFailed', 'Save failed'));
    }
  };

  const changeGateTrigger = async (trigger: string) => {
    const previous = gateTrigger;
    setGateTrigger(trigger);
    setSavingGate(true);
    try {
      await imApi.updateGateConfig(conversationId, { trigger });
      message.success(tw('common.saved', 'Saved'));
    } catch {
      setGateTrigger(previous);
      message.error(tw('common.saveFailed', 'Save failed'));
    } finally {
      setSavingGate(false);
    }
  };

  const changeGateStrict = async (checked: boolean) => {
    const previous = gateStrict;
    setGateStrict(checked);
    try {
      await imApi.updateGateConfig(conversationId, {
        clarificationStrict: checked,
      });
      message.success(tw('common.saved', 'Saved'));
    } catch {
      setGateStrict(previous);
      message.error(tw('common.saveFailed', 'Save failed'));
    }
  };

  const openInvite = () => {
    setInviteOpen(true);
    setInviteSelected([]);
    agentApi.workspaceContacts
      .users({ page: 1, size: 200 })
      .then((page: any) =>
        setInviteOptions(
          (page?.records || []).filter(
            (user: any) =>
              !participants.some(
                (member: any) =>
                  member.participantType === 'USER' &&
                  member.participantId === user.id,
              ),
          ),
        ),
      )
      .catch(() => setInviteOptions([]));
  };

  const inviteMembers = async () => {
    if (inviteSelected.length === 0) return;
    setInviting(true);
    try {
      for (const userId of inviteSelected) {
        await imApi.addParticipant(conversationId, {
          participantType: 'USER',
          participantId: userId,
          role: 'MEMBER',
        });
      }
      setInviteOpen(false);
      setInviteSelected([]);
      await refreshConversation();
      message.success(tw('common.saved', 'Saved'));
    } catch {
      await refreshConversation().catch(() => {});
      message.error(tw('common.saveFailed', 'Save failed'));
    } finally {
      setInviting(false);
    }
  };

  const openRemove = () => {
    setRemoveOpen(true);
    setRemoveSelected([]);
  };

  const removeMembers = async () => {
    if (removeSelected.length === 0) return;
    setRemoving(true);
    try {
      for (const participantId of removeSelected) {
        await imApi.removeParticipant(conversationId, participantId);
      }
      setRemoveOpen(false);
      setRemoveSelected([]);
      await refreshConversation();
      message.success(tw('common.saved', 'Saved'));
    } catch {
      await refreshConversation().catch(() => {});
      message.error(tw('common.saveFailed', 'Save failed'));
    } finally {
      setRemoving(false);
    }
  };

  const saveSetting = async (patch: {
    pinned?: boolean;
    muted?: boolean;
    remark?: string;
  }) => {
    setSavingSetting(true);
    try {
      const updated = await imApi.updateSetting(conversationId, patch);
      setSetting(updated);
      if (patch.remark !== undefined) {
        setRemarkValue(updated.remark || '');
      }
      setConversation((previous) =>
        previous
          ? {
              ...previous,
              pinned: updated.pinned,
              muted: updated.muted,
              remark: updated.remark,
            }
          : previous,
      );
      onChanged();
    } catch {
      message.error(tw('common.saveFailed', 'Save failed'));
    } finally {
      setSavingSetting(false);
    }
  };

  const pinMessage = async (messageId: number) => {
    try {
      const updated = await imApi.pinMessage(conversationId, messageId);
      setConversation(updated);
      message.success(tw('chat.pinned', 'Pinned'));
      onChanged();
    } catch {
      message.error(tw('chat.pinFailed', 'Failed to pin'));
    }
  };

  const unpinMessage = async () => {
    try {
      const updated = await imApi.unpinMessage(conversationId);
      setConversation(updated);
      message.success(tw('chat.unpinned', 'Unpinned'));
      onChanged();
    } catch {
      message.error(tw('chat.unpinFailed', 'Failed to unpin'));
    }
  };

  const deleteMessage = (item: ImMessage) => {
    modal.confirm({
      title: tw('chat.deleteMessageTitle', 'Delete this message?'),
      content: tw(
        'chat.deleteMessageContent',
        'Other members will no longer see this message.',
      ),
      okText: tw('common.delete', 'Delete'),
      okType: 'danger',
      cancelText: tw('common.cancel', 'Cancel'),
      onOk: async () => {
        await imApi.recall(conversationId, item.id);
        message.success(tw('chat.deleteMessageSuccess', 'Deleted'));
        load(true);
        onChanged();
      },
    });
  };

  const handleMessageAction = (item: ImMessage, key: string) => {
    if (key === 'pin') {
      pinMessage(item.id);
    } else if (key === 'unpin') {
      unpinMessage();
    } else if (key === 'delete') {
      deleteMessage(item);
    }
  };

  const memberRows = participants.filter((member) => {
    if (!memberKeyword.trim()) return true;
    const text = `${userDisplayLabel(member, '')} ${member.username || ''}`;
    return text.toLowerCase().includes(memberKeyword.trim().toLowerCase());
  });

  const memberCount = participants.length;
  const isGroup = conversation?.type === 'GROUP';
  const isOwner = conversation?.myRole === 'OWNER';
  const isAdmin = isOwner || conversation?.myRole === 'ADMIN';
  const allowMemberInvite = conversation?.allowMemberInvite !== false;
  const canInvite =
    isAdmin ||
    (isGroup && conversation?.myRole === 'MEMBER' && allowMemberInvite);
  const removableMembers = participants.filter(
    (member) =>
      member.participantType === 'USER' &&
      member.role !== 'OWNER' &&
      !isSameUser(member, me) &&
      (isOwner || member.role !== 'ADMIN'),
  );
  const selfConversation = !isGroup && isSelfParticipants(participants, me);
  const otherParticipant = participants.find(
    (member) => member.participantType === 'USER' && !isSameUser(member, me),
  );
  const agentParticipant = participants.find(
    (member) =>
      member.participantType === 'AGENT' &&
      Number(member.participantId) !== Number(me?.id),
  );
  // Local echoes render after the authoritative wall; they never participate in
  // seq/cursor math until the server confirms them.
  const renderedMessages = pendingMessages.length
    ? [...messages, ...pendingMessages]
    : messages;
  const conversationTitle = (() => {
    if (!conversation) return tw('conversation.chat', 'Chat');
    if (conversation.type === 'GROUP') {
      return (
        conversation.remark ||
        conversation.displayName ||
        conversation.title ||
        tw('conversation.unnamedGroup', 'Untitled group')
      );
    }
    if (selfConversation) return tw('conversation.self', 'Myself');
    return (
      otherParticipant?.displayName ||
      agentParticipant?.displayName ||
      conversation.title ||
      tw('conversation.dm', 'Direct chat')
    );
  })();

  return (
    <div className={styles.chatPanel}>
      <div className={styles.chatHeader}>
        <div className={styles.chatHeaderMain}>
          <Avatar
            size={38}
            shape={isGroup ? 'square' : 'circle'}
            src={
              isGroup
                ? conversation.avatar
                : selfConversation
                  ? me?.avatar
                  : otherParticipant?.avatar
            }
            style={avatarStyle(conversationTitle)}
          >
            {initials(conversationTitle)}
          </Avatar>
          <div className={styles.chatTitleWrap}>
            <div className={styles.chatTitleRow}>
              <div className={styles.chatTitle}>{conversationTitle}</div>
              {selfConversation ? (
                <span className={styles.selfTag}>
                  {tw('common.meTag', 'Me')}
                </span>
              ) : null}
              {!isGroup && agentParticipant ? (
                <span className={styles.agentTag}>
                  {tw('chat.agentTag', 'Digital avatar')}
                </span>
              ) : null}
            </div>
            <div className={styles.chatMembers}>
              {isGroup
                ? tw('chat.groupMeta', 'Group · {count} members', {
                    count: memberCount,
                  })
                : selfConversation
                  ? tw('chat.onlySelf', 'Only visible to you')
                  : tw('chat.dmMeta', 'Direct chat')}
            </div>
          </div>
        </div>
        {isGroup ? (
          <button
            type="button"
            className={styles.headerIconButton}
            title={tw('chat.settings', 'Group settings')}
            onClick={openSettings}
          >
            <SettingOutlined />
          </button>
        ) : null}
      </div>

      {conversation?.pinnedMessage ? (
        <div className={styles.pinnedBar}>
          <PushpinOutlined />
          <span className={styles.pinnedText}>
            {messageText(conversation.pinnedMessage) ||
              tw('chat.pinnedFallback', '[Message]')}
          </span>
          <button
            type="button"
            className={styles.pinnedClose}
            title={tw('conversation.unpin', 'Unpin')}
            onClick={unpinMessage}
          >
            <CloseOutlined />
          </button>
        </div>
      ) : null}

      <div
        className={styles.messages}
        ref={listRef}
        onScroll={(event) => {
          const element = event.currentTarget;
          const distanceToBottom =
            element.scrollHeight - element.scrollTop - element.clientHeight;
          // Only follow streamed content while the user is reading the bottom.
          stickToBottomRef.current = distanceToBottom < 48;
          // Scroll-up pagination: near the top pull the next history page.
          if (
            hasOlder &&
            olderCursor &&
            !olderLoading &&
            element.scrollTop < 48
          ) {
            void loadOlder();
          }
        }}
      >
        <Spin spinning={loading}>
          {renderedMessages.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={tw('conversation.noMessages', 'No messages')}
            />
          ) : (
            renderedMessages.map((item) => {
              const pending = item.id < 0;
              const sender = participants.find(
                (member) =>
                  member.participantType === item.senderType &&
                  Number(member.participantId) === Number(item.senderId),
              );
              const self =
                item.senderType === 'USER' &&
                (sender
                  ? isSameUser(sender, me)
                  : Number(item.senderId) === Number(me?.id));
              // Backend marks agent messages whose avatar belongs to the viewer. In a group the
              // own avatar answers on the user's side; in a DM it is the counterpart, so it stays
              // on the left while keeping the agent name + owner label.
              const ownAgent =
                item.senderType === 'AGENT' &&
                item.ownAgent === true &&
                conversation?.type === 'GROUP';
              const alignRight = self || ownAgent;
              // Avatar messages carry a server-resolved name + owner: the wall
              // reads "Agent (owner)" even though avatars are no longer participants.
              const serverLabel = item.senderName
                ? `${item.senderName}${
                    item.senderOwnerName
                      ? tw('chat.ownerSuffix', ' ({owner})', {
                          owner: item.senderOwnerName,
                        })
                      : ''
                  }`
                : null;
              const senderName =
                serverLabel ||
                (sender
                  ? userDisplayLabel(
                      sender,
                      item.senderType === 'AGENT'
                        ? tw('chat.agent', 'Digital avatar')
                        : tw('chat.unknownUser', 'Unknown user'),
                    )
                  : item.senderType === 'AGENT'
                    ? tw('chat.agent', 'Digital avatar')
                    : item.senderType === 'SYSTEM'
                      ? tw('chat.system', 'System')
                      : tw('chat.unknownUser', 'Unknown user'));
              const avatarName = self
                ? displayName(me)
                : item.senderName || senderName;
              const card = taskCardOf(item);
              const pinned = conversation?.pinnedMessage?.id === item.id;
              const menuItems: any[] = [
                pinned
                  ? {
                      key: 'unpin',
                      label: tw('conversation.unpin', 'Unpin'),
                    }
                  : { key: 'pin', label: tw('conversation.pin', 'Pin') },
              ];
              if (self) {
                menuItems.push({ type: 'divider' });
                menuItems.push({
                  key: 'delete',
                  label: tw('common.delete', 'Delete'),
                  danger: true,
                });
              }
              return (
                <Dropdown
                  key={item.id}
                  disabled={pending}
                  trigger={['contextMenu']}
                  menu={{
                    items: menuItems,
                    onClick: ({ key }) => handleMessageAction(item, key),
                  }}
                >
                  <div
                    className={`${styles.messageRow} ${alignRight ? styles.messageRowSelf : ''}`}
                  >
                    <Avatar
                      size={32}
                      src={self ? me?.avatar : sender?.avatar}
                      style={avatarStyle(avatarName)}
                    >
                      {initials(avatarName)}
                    </Avatar>
                    <div
                      className={`${styles.bubbleWrap} ${alignRight ? styles.bubbleWrapSelf : ''}`}
                    >
                      <div
                        className={`${styles.messageMeta} ${alignRight ? styles.messageMetaSelf : ''}`}
                      >
                        {self ? tw('chat.me', 'Me') : senderName}
                        {' · '}
                        {messageTime(item.createdAt)}
                      </div>
                      {card ? (
                        card.cardType === ROUTER_CLARIFICATION ||
                        card.cardType === ROUTER_APPROVAL ? (
                          <AgentInputCard
                            card={card}
                            conversationId={conversationId}
                            canView={item.canView !== false}
                            canAnswer={item.canAnswer !== false}
                            deniedReason={item.deniedReason}
                            onAnswer={answerClarification}
                            onApprove={approveSessionInput}
                          />
                        ) : (
                          <div className={styles.taskCard}>
                            <div className={styles.taskCardTop}>
                              <span className={styles.taskCardTitle}>
                                {card.title || tw('chat.taskFallback', 'Task')}
                              </span>
                              <span
                                className={`${styles.taskCardStatus} ${
                                  card.status === STATUS.WORK_ITEM.COMPLETED ||
                                  card.status === STATUS.WORK_ITEM.DONE
                                    ? styles.taskCardStatusDone
                                    : card.status === STATUS.WORK_ITEM.FAILED ||
                                        card.status ===
                                          STATUS.WORK_ITEM.CANCELLED
                                      ? styles.taskCardStatusFailed
                                      : styles.taskCardStatusRunning
                                }`}
                              >
                                {taskStatusLabel(card.status)}
                              </span>
                            </div>
                            {card.summary ? (
                              <div className={styles.taskCardSummary}>
                                <CollapsibleMarkdown text={card.summary} />
                              </div>
                            ) : (
                              <div className={styles.taskCardSummary}>
                                {tw(
                                  'chat.taskCreated',
                                  'Task created and in progress',
                                )}
                              </div>
                            )}
                          </div>
                        )
                      ) : item.type === 'TEXT' &&
                        item.senderType === 'AGENT' &&
                        messageText(item) ? (
                        <div className={styles.bubble}>
                          <MessageMarkdown text={messageText(item) || ''} />
                        </div>
                      ) : (
                        <div
                          className={`${styles.bubble} ${self ? styles.bubbleSelf : ''}`}
                        >
                          {messageText(item) ||
                            tw('chat.messageFallback', '[Message]')}
                        </div>
                      )}
                      {pending ? (
                        item.status === 'FAILED' ? (
                          <button
                            type="button"
                            className={styles.messageRetry}
                            title={(item as PendingMessage).error}
                            onClick={() => void retryPending(item)}
                          >
                            {tw(
                              'chat.sendFailedRetry',
                              'Failed to send · Retry',
                            )}
                          </button>
                        ) : (
                          <span className={styles.messageSending}>
                            {tw('chat.sending', 'Sending…')}
                          </span>
                        )
                      ) : null}
                    </div>
                  </div>
                </Dropdown>
              );
            })
          )}
        </Spin>
        {agentStream && agentParticipant ? (
          <AgentStreamBubble
            state={agentStream}
            agent={agentParticipant}
            // Placeholder waits until the user's own message is on the wall:
            // beginAgentStream fires pre-send, so "waiting" would otherwise
            // render above a message the list does not show yet.
            hidden={
              agentStream.phase === AGENT_STREAM_PHASE.WAITING &&
              renderedMessages[renderedMessages.length - 1]?.senderType !==
                'USER'
            }
          />
        ) : null}
      </div>

      {agentPaused ? (
        <div className={styles.agentPausedBanner}>
          {tw(
            'avatars.chatPaused',
            'This avatar is paused. Resume it before chatting.',
          )}
        </div>
      ) : null}

      <div className={styles.composer}>
        <textarea
          ref={composerRef}
          className={styles.composerInput}
          rows={1}
          value={text}
          disabled={agentPaused}
          placeholder={tw('chat.inputPlaceholder', 'Type a message...')}
          onChange={(event) => {
            const next = event.target.value;
            setText(next);
            mention.refresh(next, event.target.selectionStart);
            const element = event.target;
            element.style.height = 'auto';
            element.style.height = `${Math.min(element.scrollHeight, 120)}px`;
          }}
          onKeyDown={(event) => {
            if (mention.handleKeyDown(event)) {
              return;
            }
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              send();
            }
          }}
          onSelect={(event) =>
            mention.refresh(
              event.currentTarget.value,
              event.currentTarget.selectionStart,
            )
          }
        />
        <MentionSuggest
          open={mention.open}
          items={mention.items}
          activeIndex={mention.activeIndex}
          position={mention.position}
          labels={{
            agent: tw('chat.mentionCandidateAgent', 'Avatar'),
            user: tw('chat.mentionCandidateUser', 'Member'),
            self: tw('chat.mentionCandidateSelf', 'Me'),
          }}
          onHover={mention.setActiveIndex}
          onSelect={mention.applyCandidate}
        />
        <button
          type="button"
          className={`${styles.composerSend} ${
            !text.trim() || sending || agentPaused
              ? styles.composerSendDisabled
              : ''
          }`}
          onClick={send}
        >
          <SendOutlined />
          <span style={{ marginLeft: 6 }}>{tw('chat.send', 'Send')}</span>
        </button>
      </div>

      {settingsOpen ? (
        <>
          <div
            className={styles.settingsScrim}
            onClick={() => setSettingsOpen(false)}
          />
          <div className={styles.settingsPanel}>
            <div className={styles.settingsPanelHeader}>
              <span>{tw('chat.settings', 'Group settings')}</span>
              <button
                type="button"
                className={styles.settingsClose}
                onClick={() => setSettingsOpen(false)}
              >
                <CloseOutlined />
              </button>
            </div>
            <div className={styles.settingsPanelBody}>
              <div className={styles.settingSection}>
                <div className={styles.settingLabel}>
                  {tw('chat.groupName', 'Group name')}
                </div>
                <div className={styles.inlineField}>
                  <Input
                    value={groupName}
                    maxLength={64}
                    placeholder={tw('chat.groupNamePlaceholder', 'Group name')}
                    onChange={(event) => setGroupName(event.target.value)}
                  />
                  <Button
                    type="primary"
                    className={styles.primaryPill}
                    loading={savingMeta}
                    onClick={() => saveMeta({ title: groupName.trim() })}
                  >
                    {tw('common.save', 'Save')}
                  </Button>
                </div>
              </div>

              <div className={styles.settingSection}>
                <div className={styles.settingLabel}>
                  {tw('chat.announcement', 'Announcement')}
                </div>
                <Input.TextArea
                  value={announcement}
                  rows={3}
                  maxLength={500}
                  placeholder={tw(
                    'chat.announcementPlaceholder',
                    'Set an announcement so members know the group rules',
                  )}
                  onChange={(event) => setAnnouncement(event.target.value)}
                />
                <div className={styles.settingActions}>
                  <Button
                    className={styles.ghostPill}
                    loading={savingMeta}
                    onClick={() => saveMeta({ purpose: announcement })}
                  >
                    {tw('chat.saveAnnouncement', 'Save announcement')}
                  </Button>
                </div>
              </div>

              <div className={styles.settingSection}>
                <div className={styles.settingLabel}>
                  {tw('chat.membersLabel', 'Members · {count}', {
                    count: memberCount,
                  })}
                </div>
                <Input.Search
                  allowClear
                  placeholder={tw('chat.searchMembers', 'Search members')}
                  value={memberKeyword}
                  onChange={(event) => setMemberKeyword(event.target.value)}
                />
                <div className={styles.memberList}>
                  <div className={styles.memberGrid}>
                    {memberRows.map((member) => {
                      const name = userDisplayLabel(
                        member,
                        tw('common.unnamedMember', 'Unnamed member'),
                      );
                      const manageable =
                        isAdmin &&
                        member.participantType === 'USER' &&
                        member.role !== 'OWNER';
                      return (
                        <Dropdown
                          key={`${member.participantType}-${member.participantId}`}
                          trigger={['click']}
                          disabled={!manageable}
                          menu={{
                            items: [
                              {
                                key: 'role',
                                label:
                                  member.role === 'ADMIN'
                                    ? tw('chat.removeAdmin', 'Remove admin')
                                    : tw('chat.makeAdmin', 'Make admin'),
                              },
                              ...(isOwner
                                ? [
                                    {
                                      key: 'owner',
                                      label: tw(
                                        'chat.transferOwner',
                                        'Transfer ownership',
                                      ),
                                      danger: true,
                                    },
                                  ]
                                : []),
                            ],
                            onClick: ({ key, domEvent }) => {
                              domEvent.stopPropagation();
                              if (key === 'role') {
                                changeMemberRole(
                                  member,
                                  member.role === 'ADMIN' ? 'MEMBER' : 'ADMIN',
                                );
                              }
                              if (key === 'owner') {
                                transferGroupOwner(member);
                              }
                            },
                          }}
                        >
                          <div
                            className={styles.memberCard}
                            title={name}
                            style={{
                              cursor: manageable ? 'pointer' : 'default',
                            }}
                          >
                            <Avatar
                              shape="square"
                              size={56}
                              src={member.avatar}
                              style={{
                                ...avatarStyle(name),
                                borderRadius: 14,
                              }}
                            >
                              {initials(name)}
                            </Avatar>
                            {manageable ? (
                              <span
                                className={`member-card-more ${styles.memberCardMore}`}
                              >
                                <MoreOutlined />
                              </span>
                            ) : null}
                            <div className={styles.memberCardName}>{name}</div>
                            <div className={styles.memberCardRole}>
                              {member.role === 'OWNER'
                                ? tw('chat.owner', 'Owner')
                                : member.role === 'ADMIN'
                                  ? tw('chat.admin', 'Admin')
                                  : member.participantType === 'AGENT'
                                    ? tw('chat.agent', 'Digital avatar')
                                    : tw('chat.member', 'Member')}
                            </div>
                          </div>
                        </Dropdown>
                      );
                    })}
                    {canInvite ? (
                      <button
                        type="button"
                        className={styles.memberActionCard}
                        onClick={openInvite}
                      >
                        <span
                          className={`member-action-box ${styles.memberActionBox}`}
                        >
                          <PlusOutlined />
                        </span>
                        <span className={styles.memberCardName}>
                          {tw('chat.addMember', 'Add')}
                        </span>
                      </button>
                    ) : null}
                    {isAdmin ? (
                      <button
                        type="button"
                        className={styles.memberActionCard}
                        onClick={openRemove}
                      >
                        <span
                          className={`member-action-box ${styles.memberActionBox}`}
                        >
                          <MinusOutlined />
                        </span>
                        <span className={styles.memberCardName}>
                          {tw('chat.removeMemberAction', 'Remove')}
                        </span>
                      </button>
                    ) : null}
                  </div>
                  {memberRows.length === 0 ? (
                    <div className={styles.settingHint}>
                      {tw('chat.noMatchedMembers', 'No matching members')}
                    </div>
                  ) : null}
                </div>
              </div>

              {isGroup && isAdmin ? (
                <div className={styles.settingSection}>
                  <div className={styles.settingSwitchRow}>
                    <div>
                      <div className={styles.settingLabel}>
                        {tw(
                          'chat.allowMemberInvite',
                          'Allow members to invite',
                        )}
                      </div>
                      <div className={styles.settingHint}>
                        {tw(
                          'chat.allowMemberInviteHint',
                          'When on, ordinary members can invite users.',
                        )}
                      </div>
                    </div>
                    <Switch
                      checked={allowMemberInvite}
                      onChange={toggleMemberInvite}
                    />
                  </div>
                </div>
              ) : null}

              {isGroup && isAdmin ? (
                <div className={styles.settingSection}>
                  <div className={styles.settingLabel}>
                    {tw('chat.trigger', 'Trigger rule')}
                  </div>
                  <Select
                    value={gateTrigger}
                    loading={savingGate}
                    disabled={savingGate}
                    onChange={changeGateTrigger}
                    style={{ width: '100%' }}
                    options={[
                      {
                        value: 'MENTION',
                        label: tw(
                          'chat.triggerMention',
                          'MENTION - only when a digital avatar is @mentioned',
                        ),
                      },
                      {
                        value: 'RELEVANCE',
                        label: tw(
                          'chat.triggerRelevance',
                          'RELEVANCE - Group Router judges every message',
                        ),
                      },
                      {
                        value: 'ALL',
                        label: tw(
                          'chat.triggerAll',
                          'ALL - triggered by every message',
                        ),
                      },
                    ]}
                  />
                  <div className={styles.settingHint}>
                    {tw(
                      'chat.triggerHint',
                      'Group Router handles new messages under RELEVANCE or ALL; @mentions always reply directly.',
                    )}
                  </div>
                  <div
                    className={styles.settingSwitchRow}
                    style={{ marginTop: 8 }}
                  >
                    <div>
                      <div className={styles.settingLabel}>
                        {tw(
                          'chat.clarifyStrict',
                          'Restrict clarification answers',
                        )}
                      </div>
                      <div className={styles.settingHint}>
                        {tw(
                          'chat.clarifyStrictHint',
                          'When on, only the asker, the avatar owner and group admins can answer a clarification.',
                        )}
                      </div>
                    </div>
                    <Switch checked={gateStrict} onChange={changeGateStrict} />
                  </div>
                </div>
              ) : null}

              <div className={styles.settingSection}>
                <div className={styles.settingLabel}>
                  {tw('chat.remark', 'Group alias')}
                </div>{' '}
                <div className={styles.inlineField}>
                  <Input
                    value={remarkValue}
                    maxLength={64}
                    placeholder={tw(
                      'chat.remarkPlaceholder',
                      'A name only you can see',
                    )}
                    onChange={(event) => setRemarkValue(event.target.value)}
                  />
                  <Button
                    className={styles.ghostPill}
                    loading={savingSetting}
                    onClick={() => saveSetting({ remark: remarkValue })}
                  >
                    {tw('common.save', 'Save')}
                  </Button>
                </div>
                <div className={styles.settingHint}>
                  {tw(
                    'chat.remarkHint',
                    'Only visible to you, and preferred in the chat list.',
                  )}
                </div>
              </div>

              <div className={styles.settingSwitchRow}>
                <div>
                  <div className={styles.settingLabel}>
                    {tw('chat.mute', 'Mute notifications')}
                  </div>
                  <div className={styles.settingHint}>
                    {tw('chat.muteHint', 'Stop notifying about new messages')}
                  </div>
                </div>
                <Switch
                  checked={setting?.muted ?? conversation?.muted ?? false}
                  loading={savingSetting}
                  onChange={(checked) => saveSetting({ muted: checked })}
                />
              </div>

              <div className={styles.settingSwitchRow}>
                <div>
                  <div className={styles.settingLabel}>
                    {tw('chat.pinChat', 'Pin chat')}
                  </div>
                  <div className={styles.settingHint}>
                    {tw('chat.pinHint', 'Show at the top of the chat list')}
                  </div>
                </div>
                <Switch
                  checked={setting?.pinned ?? conversation?.pinned ?? false}
                  loading={savingSetting}
                  onChange={(checked) => saveSetting({ pinned: checked })}
                />
              </div>
            </div>
          </div>
        </>
      ) : null}

      <MemberPickerModal
        open={inviteOpen}
        title={tw('chat.addMemberTitle', 'Add members')}
        selectedTitle={tw('chat.memberPickerSelected', 'Selected members')}
        confirmText={tw('chat.addMember', 'Add')}
        cancelText={tw('common.cancel', 'Cancel')}
        searchPlaceholder={tw('chat.searchContacts', 'Search contacts')}
        emptyText={tw('chat.noMatchedContacts', 'No matching contacts')}
        noSelectedText={tw('chat.noMemberSelected', 'No member selected')}
        items={inviteOptions.map((user) => ({
          id: user.id,
          name: userDisplayLabel(user),
          avatar: user.avatar,
        }))}
        selected={inviteSelected}
        confirming={inviting}
        onToggle={(id) =>
          setInviteSelected((previous) =>
            previous.includes(id)
              ? previous.filter((value) => value !== id)
              : [...previous, id],
          )
        }
        onCancel={() => setInviteOpen(false)}
        onConfirm={inviteMembers}
      />

      <MemberPickerModal
        open={removeOpen}
        title={tw('chat.removeMemberTitle', 'Remove members')}
        selectedTitle={tw('chat.memberPickerSelected', 'Selected members')}
        confirmText={tw('chat.removeMemberAction', 'Remove')}
        cancelText={tw('common.cancel', 'Cancel')}
        searchPlaceholder={tw('chat.searchMembers', 'Search members')}
        emptyText={tw('chat.noMatchedMembers', 'No matching members')}
        noSelectedText={tw('chat.noMemberSelected', 'No member selected')}
        items={removableMembers.map((member) => ({
          id: member.participantId,
          name: userDisplayLabel(member),
          avatar: member.avatar,
        }))}
        selected={removeSelected}
        confirming={removing}
        onToggle={(id) =>
          setRemoveSelected((previous) =>
            previous.includes(id)
              ? previous.filter((value) => value !== id)
              : [...previous, id],
          )
        }
        onCancel={() => setRemoveOpen(false)}
        onConfirm={removeMembers}
      />
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
