import { API_PATHS } from '@/constants/endpoints';
import {
  AudioOutlined,
  CheckOutlined,
  CloseOutlined,
  ContactsOutlined,
  DeleteOutlined,
  EditOutlined,
  GlobalOutlined,
  HomeOutlined,
  LogoutOutlined,
  MessageOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  PushpinOutlined,
  RobotOutlined,
  SendOutlined,
  SettingOutlined,
  StarFilled,
  TeamOutlined,
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
  Button,
  Drawer,
  Dropdown,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Spin,
  Switch,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { type ReactNode, useEffect, useRef, useState } from 'react';
import type { ImStreamPayload } from '@/hooks/useReconnectingSse';
import { useReconnectingSse } from '@/hooks/useReconnectingSse';
import { agentApi } from '@/services/agentSphere/api';
import { dictApi, type SystemDicEntry } from '@/services/agentSphere/dict';
import {
  type ImConversation,
  type ImConversationSetting,
  type ImMessage,
  type ImParticipant,
  imApi,
  type SessionInputDecision,
} from '@/services/agentSphere/im';
import {
  type SuperAgent,
  superAgentApi,
} from '@/services/agentSphere/superAgent';
import { clearStoredUser, getStoredUser } from '@/utils/auth';
import { messageText, parsedContent } from '../im/components/messageUtils';
import {
  AgentInputCard,
  ROUTER_APPROVAL,
  ROUTER_CLARIFICATION,
} from './AgentInputCard';
import { AgentStreamBubble } from './AgentStreamBubble';
import {
  applyAgentStreamEvent,
  beginAgentStream,
  finalizeAgentStream,
  resetAgentStream,
  useAgentStream,
} from './agentStreamStore';
import { tw } from './i18n';
import { MessageMarkdown } from './MessageMarkdown';
import { avatarStyle, displayName, initials } from './shared';
import { useStyles } from './style';

type Tab = 'home' | 'avatars' | 'chat' | 'contacts' | 'me';

function statusLabel(status?: string) {
  switch (status) {
    case 'ACTIVE':
      return tw('status.active', 'Active');
    case 'PAUSED':
      return tw('status.paused', 'Paused');
    case 'ARCHIVED':
      return tw('status.archived', 'Archived');
    default:
      return status || tw('status.unknown', 'Unknown');
  }
}

/** Position names are stored on the agent; pick by current UI language. */
function positionLabel(
  agent?: {
    positionNameCn?: string;
    positionNameEn?: string;
  } | null,
) {
  if (!agent) return '';
  const isEnglish = getLocale() === 'en-US';
  return (
    (isEnglish ? agent.positionNameEn : agent.positionNameCn) ||
    agent.positionNameCn ||
    agent.positionNameEn ||
    ''
  );
}

function isSameUser(participant: ImParticipant | undefined, me: any) {
  if (!participant || !me) return false;
  if (participant.username && me.username) {
    return participant.username === me.username;
  }
  return Number(participant.participantId) === Number(me.id);
}

function isSelfParticipants(participants: ImParticipant[], me: any) {
  if (participants.some((p) => p.participantType === 'AGENT')) return false;
  const users = participants.filter(
    (participant) => participant.participantType === 'USER',
  );
  return users.length > 0 && users.every((user) => isSameUser(user, me));
}

function isSelfConversation(conversation: ImConversation, me: any) {
  if (conversation.type === 'GROUP') return false;
  if (conversation.selfConversation != null) {
    return conversation.selfConversation;
  }
  return isSelfParticipants(conversation.participants || [], me);
}

function isSelfUser(user: any, me: any) {
  if (!user || !me) return false;
  if (user.username && me.username) {
    return user.username === me.username;
  }
  return Number(user.id) === Number(me.id);
}

function otherUserParticipant(conversation: ImConversation, me: any) {
  if (conversation.type === 'GROUP') return undefined;
  return conversation.participants?.find(
    (participant) =>
      participant.participantType === 'USER' && !isSameUser(participant, me),
  );
}

function conversationDisplayName(conversation: ImConversation, me: any) {
  if (conversation.type === 'GROUP') {
    return (
      conversation.remark ||
      conversation.title ||
      tw('conversation.unnamedGroup', 'Untitled group')
    );
  }
  if (isSelfConversation(conversation, me)) {
    return tw('conversation.self', 'Myself');
  }
  const other = otherUserParticipant(conversation, me);
  return (
    conversation.displayName ||
    other?.displayName ||
    conversation.title ||
    tw('conversation.dm', 'Direct chat')
  );
}

function conversationPreview(conversation: ImConversation) {
  const lastMessage = (conversation as any).lastMessage;
  if (!lastMessage) {
    return conversation.purpose || tw('conversation.noMessages', 'No messages');
  }
  const card = taskCardOf(lastMessage);
  if (card) {
    return (
      card.title ||
      card.summary ||
      tw('chat.taskFallback', 'Task') ||
      tw('conversation.noMessages', 'No messages')
    );
  }
  return (
    messageText(lastMessage) || tw('conversation.noMessages', 'No messages')
  );
}

function conversationAvatar(conversation: ImConversation, me: any) {
  if (isSelfConversation(conversation, me)) {
    return me?.avatar;
  }
  return conversation.avatar || otherUserParticipant(conversation, me)?.avatar;
}

function sortConversations(conversations: ImConversation[]) {
  return [...conversations].sort((left, right) => {
    const pinned = Number(Boolean(right.pinned)) - Number(Boolean(left.pinned));
    if (pinned !== 0) return pinned;
    const leftAt = left.lastMessageAt ? dayjs(left.lastMessageAt).valueOf() : 0;
    const rightAt = right.lastMessageAt
      ? dayjs(right.lastMessageAt).valueOf()
      : 0;
    return rightAt - leftAt || right.id - left.id;
  });
}

function mergeConversation(
  conversations: ImConversation[],
  conversation: ImConversation,
) {
  const exists = conversations.some((item) => item.id === conversation.id);
  const next = exists
    ? conversations.map((item) =>
        item.id === conversation.id ? conversation : item,
      )
    : [conversation, ...conversations];
  return sortConversations(next);
}

function listTime(value?: string) {
  if (!value) return '';
  const time = dayjs(value);
  if (!time.isValid()) return '';
  const now = dayjs();
  if (time.isSame(now, 'day')) return time.format('HH:mm');
  if (time.isSame(now.subtract(1, 'day'), 'day')) {
    return tw('time.yesterday', 'Yesterday');
  }
  if (time.isSame(now, 'year')) {
    return tw('time.monthDay', '{month}/{day}', {
      month: time.month() + 1,
      day: time.date(),
    });
  }
  return time.format('YYYY/M/D');
}

function messageTime(value?: string) {
  if (!value) return '';
  const time = dayjs(value);
  return time.isValid() ? time.format('HH:mm') : '';
}

function fullTime(value?: string) {
  if (!value) return '-';
  const time = dayjs(value);
  return time.isValid() ? time.format('YYYY-MM-DD HH:mm') : '-';
}

type TaskCardView = {
  title?: string;
  status?: string;
  summary?: string;
  workItemId?: number;
  /** Router input cards (ROUTER_CLARIFICATION / ROUTER_APPROVAL) only. */
  cardType?: string;
  inputId?: string;
  description?: string;
  multiple?: boolean;
  options?: string;
  toolCallsJson?: string;
};

function taskCardOf(message: ImMessage): TaskCardView | null {
  const content = parsedContent(message);
  if (!content?.cardType) return null;
  return content as TaskCardView;
}

function taskStatusLabel(status?: string) {
  switch (status) {
    case 'QUEUED':
      return tw('chat.taskStatus.queued', 'Queued');
    case 'DISPATCHING':
      return tw('chat.taskStatus.dispatching', 'Preparing');
    case 'RUNNING':
      return tw('chat.taskStatus.running', 'Running');
    case 'WAITING':
      return tw('chat.taskStatus.waiting', 'Waiting');
    case 'COMPLETED':
    case 'DONE':
      return tw('chat.taskStatus.completed', 'Completed');
    case 'FAILED':
      return tw('chat.taskStatus.failed', 'Failed');
    case 'CANCELLED':
      return tw('chat.taskStatus.cancelled', 'Cancelled');
    case 'PENDING':
      return tw('chat.taskStatus.pending', 'Awaiting reply');
    case 'ANSWERED':
      return tw('chat.taskStatus.answered', 'Answered');
    default:
      return status || tw('chat.taskStatus.unknown', 'Unknown');
  }
}

function mergeMessages(older: ImMessage[], current: ImMessage[]) {
  const byId = new Map<number, ImMessage>();
  for (const message of [...older, ...current]) {
    byId.set(message.id, message);
  }
  return [...byId.values()].sort((left, right) => left.seq - right.seq);
}

export default function WorkspacePage() {
  const { styles } = useStyles();
  useIntl();
  const { initialState } = useModel('@@initialState');
  const me = getStoredUser() || initialState?.currentUser;
  const [tab, setTab] = useState<Tab>('home');
  const [conversationId, setConversationId] = useState<number | null>(null);

  const openChat = (id: number) => {
    setConversationId(id);
    setTab('chat');
  };

  return (
    <div className={styles.backdrop}>
      <div className={styles.app}>
        <SideRail tab={tab} onChange={setTab} me={me} styles={styles} />
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
                  <AvatarsTab onOpenChat={openChat} />
                ) : null}
                {tab === 'contacts' ? (
                  <ContactsTab onOpenChat={openChat} />
                ) : null}
                {tab === 'me' ? (
                  <MeTab me={me} onOpenAvatars={() => setTab('avatars')} />
                ) : null}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function TopBar({ me }: { me: any }) {
  const { styles } = useStyles();
  const locale = getLocale();
  return (
    <div className={styles.topBar}>
      <div className={styles.topRight}>
        <Dropdown
          trigger={['click']}
          menu={{
            selectedKeys: [locale],
            items: [
              { key: 'zh-CN', label: '简体中文' },
              { key: 'en-US', label: 'English' },
            ],
            onClick: ({ key }) => setLocale(key, false),
          }}
        >
          <button type="button" className={styles.langButton}>
            <GlobalOutlined />
            {locale === 'en-US' ? 'English' : '中文'}
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
}: {
  tab: Tab;
  onChange: (tab: Tab) => void;
  me: any;
  styles: any;
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
                          agent.status === 'ACTIVE'
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

function AvatarsTab({ onOpenChat }: { onOpenChat: (id: number) => void }) {
  const { styles } = useStyles();
  const { message, modal } = App.useApp();
  const [loading, setLoading] = useState(true);
  const [agents, setAgents] = useState<SuperAgent[]>([]);
  const [positions, setPositions] = useState<SystemDicEntry[]>([]);
  const [startingChat, setStartingChat] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();
  const nameValue = Form.useWatch('name', form);
  const [editingAgent, setEditingAgent] = useState<SuperAgent | null>(null);
  const [agentModalMode, setAgentModalMode] = useState<'view' | 'edit'>('view');
  const [editSubmitting, setEditSubmitting] = useState(false);
  const [editForm] = Form.useForm();
  const editName = Form.useWatch('name', editForm);

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
      await superAgentApi.create({
        name: values.name,
        description: values.description,
        positionCode: values.positionCode,
      });
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
    setAgentModalMode('view');
  };

  const startEdit = () => {
    if (!editingAgent) return;
    editForm.setFieldsValue({
      name: editingAgent.name,
      description: editingAgent.description,
      positionCode: editingAgent.positionCode,
    });
    setAgentModalMode('edit');
  };

  const startChat = async () => {
    if (!editingAgent || startingChat) return;
    if (editingAgent.status !== 'ACTIVE') {
      message.warning(
        tw(
          'avatars.chatPaused',
          'This avatar is paused. Resume it before chatting.',
        ),
      );
      return;
    }
    setStartingChat(true);
    try {
      const conversation = await imApi.createAgentDm(editingAgent.id);
      setEditingAgent(null);
      onOpenChat(conversation.id);
    } catch {
      message.error(tw('avatars.chatFailed', 'Failed to start chat'));
    } finally {
      setStartingChat(false);
    }
  };

  const saveEdit = async () => {
    if (!editingAgent) return;
    const values = await editForm.validateFields();
    setEditSubmitting(true);
    try {
      const updated = await superAgentApi.update(editingAgent.id, {
        name: values.name,
        description: values.description,
        positionCode: values.positionCode,
      });
      message.success(tw('avatars.saved', 'Saved'));
      setEditingAgent(updated);
      setAgentModalMode('view');
      load();
    } finally {
      setEditSubmitting(false);
    }
  };

  const toggle = async (agent: SuperAgent) => {
    const updated =
      agent.status === 'ACTIVE'
        ? await superAgentApi.pause(agent.id)
        : await superAgentApi.resume(agent.id);
    setEditingAgent(updated);
    load();
  };

  const remove = (agent: SuperAgent) => {
    modal.confirm({
      title: tw('avatars.deleteTitle', 'Delete {name}', { name: agent.name }),
      content: tw(
        'avatars.deleteContent',
        'The avatar will be archived after deletion. Continue?',
      ),
      okText: tw('common.delete', 'Delete'),
      cancelText: tw('avatars.keep', 'Keep it'),
      okType: 'danger',
      onOk: async () => {
        await superAgentApi.delete(agent.id);
        message.success(tw('avatars.deleted', 'Deleted'));
        setEditingAgent(null);
        load();
      },
    });
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
          onClick={() => setCreateOpen(true)}
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
                  <div className={styles.rowTitle}>
                    {agent.name}
                    <Tag
                      color={agent.status === 'ACTIVE' ? 'green' : 'default'}
                    >
                      {statusLabel(agent.status)}
                    </Tag>
                    {positionLabel(agent) ? (
                      <Tag color="purple">{positionLabel(agent)}</Tag>
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

        <Form form={form} layout="vertical" className={styles.createBody}>
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

      <Drawer
        open={editingAgent != null}
        placement="right"
        width={440}
        closable={false}
        onClose={() => setEditingAgent(null)}
        destroyOnHidden
        rootClassName={styles.avatarDrawerRoot}
        getContainer={() =>
          document.getElementById('workspace-main') ?? document.body
        }
        styles={{ body: { padding: 0 } }}
      >
        <div className={styles.avatarDrawerBody}>
          <div className={styles.avatarDetailHeader}>
            <Avatar
              className={styles.avatarDetailAvatar}
              size={52}
              shape="square"
              style={avatarStyle(
                agentModalMode === 'edit'
                  ? editName || editingAgent?.name
                  : editingAgent?.name,
              )}
            >
              {initials(
                agentModalMode === 'edit'
                  ? editName || editingAgent?.name
                  : editingAgent?.name,
              )}
            </Avatar>
            <div className={styles.avatarDetailHeadText}>
              <div className={styles.avatarDetailName}>
                {agentModalMode === 'view'
                  ? editingAgent?.name || tw('nav.avatars', 'Avatars')
                  : tw('avatars.editTitle', 'Edit avatar')}
              </div>
              <div className={styles.avatarDetailSub}>
                {agentModalMode === 'view'
                  ? tw('avatars.detailTitle', 'Avatar details')
                  : tw(
                      'avatars.editSubtitle',
                      'Change the name and responsibility',
                    )}
              </div>
            </div>
            <button
              type="button"
              className={styles.avatarDetailClose}
              onClick={() => setEditingAgent(null)}
            >
              <CloseOutlined />
            </button>
          </div>

          {agentModalMode === 'view' ? (
            <>
              <div className={styles.avatarDetailSection}>
                <div className={styles.avatarDetailLabel}>
                  {tw('avatars.position', 'Role')}
                </div>
                <span className={styles.avatarRoleTag}>
                  {positionLabel(editingAgent) ||
                    tw('avatars.positionUnset', 'Not set')}
                </span>
              </div>

              <div className={styles.avatarDetailSection}>
                <div className={styles.avatarDetailLabel}>
                  {tw('avatars.desc', 'Responsibility')}
                </div>
                <div className={styles.avatarDetailValue}>
                  {editingAgent?.description ||
                    tw('common.noDescription', 'No description')}
                </div>
              </div>

              <div className={styles.avatarDetailSection}>
                <div className={styles.avatarDetailLabel}>
                  {tw('avatars.status', 'Status')}
                </div>
                <div className={styles.avatarStatusRow}>
                  <span
                    className={`${styles.avatarStatusDot} ${
                      editingAgent?.status === 'ACTIVE'
                        ? ''
                        : styles.avatarStatusDotPaused
                    }`}
                  />
                  {statusLabel(editingAgent?.status)}
                </div>
              </div>

              <div className={styles.avatarDetailSection}>
                <div className={styles.avatarDetailLabel}>
                  {tw('avatars.createdAtLabel', 'Created')}
                </div>
                <div className={styles.avatarDetailValue}>
                  {fullTime(editingAgent?.createdAt)}
                </div>
              </div>

              {editingAgent?.status !== 'ACTIVE' ? (
                <div className={styles.avatarPausedHint}>
                  {tw(
                    'avatars.chatPaused',
                    'This avatar is paused. Resume it before chatting.',
                  )}
                </div>
              ) : null}

              <div className={styles.avatarDetailFooter}>
                <Button
                  type="primary"
                  block
                  icon={<MessageOutlined />}
                  loading={startingChat}
                  disabled={editingAgent?.status !== 'ACTIVE'}
                  className={styles.avatarDetailPrimary}
                  onClick={startChat}
                >
                  {tw('avatars.startChat', 'Start chat')}
                </Button>
                <div className={styles.avatarSecondaryActions}>
                  <button
                    type="button"
                    className={`${styles.avatarSecondaryButton} ${styles.avatarEditButton}`}
                    onClick={startEdit}
                  >
                    <EditOutlined />
                    {tw('avatars.editProfile', 'Edit profile')}
                  </button>
                  <button
                    type="button"
                    className={`${styles.avatarSecondaryButton} ${styles.avatarPauseButton}`}
                    onClick={() => editingAgent && toggle(editingAgent)}
                  >
                    {editingAgent?.status === 'ACTIVE' ? (
                      <PauseCircleOutlined />
                    ) : (
                      <PlayCircleOutlined />
                    )}
                    {editingAgent?.status === 'ACTIVE'
                      ? tw('avatars.pause', 'Pause avatar')
                      : tw('avatars.resume', 'Resume avatar')}
                  </button>
                  <button
                    type="button"
                    className={`${styles.avatarSecondaryButton} ${styles.avatarDeleteButton}`}
                    onClick={() => editingAgent && remove(editingAgent)}
                  >
                    <DeleteOutlined />
                    {tw('avatars.delete', 'Delete avatar')}
                  </button>
                </div>
              </div>
            </>
          ) : (
            <>
              <Form
                form={editForm}
                layout="vertical"
                className={styles.avatarEditForm}
              >
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
                        message: tw(
                          'avatars.positionRequired',
                          'Select a role',
                        ),
                      },
                    ]}
                    style={{ marginBottom: 0 }}
                  >
                    <Select
                      className={styles.softField}
                      placeholder={tw(
                        'avatars.positionPlaceholder',
                        'Select a role',
                      )}
                      options={positionOptions}
                    />
                  </Form.Item>
                </div>

                <div className={styles.fieldBlock} style={{ marginBottom: 10 }}>
                  <div className={styles.fieldLabel}>
                    {tw('avatars.desc', 'Responsibility')}{' '}
                    <span className={styles.optional}>
                      {tw('common.optional', 'Optional')}
                    </span>
                  </div>
                  <Form.Item name="description" style={{ marginBottom: 14 }}>
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
                  <div className={styles.fieldHint}>
                    {tw(
                      'avatars.editHint',
                      'Changes take effect on the next run',
                    )}
                  </div>
                </div>
              </Form>

              <div className={styles.avatarEditFooter}>
                <Button
                  className={styles.avatarCancelButton}
                  onClick={() => setAgentModalMode('view')}
                >
                  {tw('common.cancel', 'Cancel')}
                </Button>
                <Button
                  type="primary"
                  className={styles.avatarSaveButton}
                  loading={editSubmitting}
                  onClick={saveEdit}
                >
                  {tw('avatars.saveEdit', 'Save changes')}
                </Button>
              </div>
            </>
          )}
        </div>
      </Drawer>
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
        participantUserIds: groupMembers,
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
                  <div key={user.id} className={styles.groupSelectedRow}>
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
  const [messages, setMessages] = useState<ImMessage[]>([]);
  const [loading, setLoading] = useState(true);
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [agentPaused, setAgentPaused] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [setting, setSetting] = useState<ImConversationSetting | null>(null);
  const [memberKeyword, setMemberKeyword] = useState('');
  const [groupName, setGroupName] = useState('');
  const [announcement, setAnnouncement] = useState('');
  const [remarkValue, setRemarkValue] = useState('');
  const [savingMeta, setSavingMeta] = useState(false);
  const [savingSetting, setSavingSetting] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);
  const composerRef = useRef<HTMLTextAreaElement>(null);
  const loadedIdRef = useRef<number | null>(null);
  const messagesRef = useRef<ImMessage[]>([]);
  messagesRef.current = messages;
  const agentStream = useAgentStream(conversationId);

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
      imApi.messagePage(conversationId, undefined, undefined, 50),
    ])
      .then(([current, members, page]) => {
        setConversation(current || null);
        setParticipants(members || []);
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
      .then((agent) => setAgentPaused(agent.status !== 'ACTIVE'))
      .catch(() => setAgentPaused(false));
  }, [conversationId, chatAgentId]);

  useEffect(() => {
    const first = loadedIdRef.current !== conversationId;
    if (first) {
      setMessages([]);
      setParticipants([]);
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
  }, [messages, agentStream?.reply]);

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
    if (agentStream?.phase !== 'done' || !agentStream.finalized) {
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
    if (agentStream?.phase !== 'waiting' || agentStream.runId) {
      return;
    }
    const timer = window.setTimeout(() => {
      resetAgentStream(conversationId);
    }, 30000);
    return () => window.clearTimeout(timer);
  }, [agentStream?.phase, agentStream?.runId, conversationId]);

  const send = async () => {
    const value = text.trim();
    if (!value || sending) return;
    await sendText(value, `workspace-${Date.now()}`);
    setText('');
    if (composerRef.current) composerRef.current.style.height = 'auto';
  };

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
    // Only DMs with a digital avatar show the "processing" bubble; regular user/group sessions do not.
    const shouldStream = Boolean(chatAgentId);
    setSending(true);
    // Sending is an explicit jump-to-bottom intent even if the user had
    // scrolled up to read history.
    stickToBottomRef.current = true;
    if (shouldStream) {
      // Show the "processing" bubble immediately: Router dispatch is already millisecond-level, but the model's first token still takes time.
      beginAgentStream(conversationId);
    }
    try {
      await imApi.send(
        conversationId,
        value,
        clientMsgId || `option-${Date.now()}`,
      );
      load(true);
    } catch {
      if (shouldStream) {
        resetAgentStream(conversationId);
      }
      message.error(tw('chat.sendFailed', 'Failed to send'));
    } finally {
      setSending(false);
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
    const text = `${member.displayName || ''} ${member.username || ''}`;
    return text.toLowerCase().includes(memberKeyword.trim().toLowerCase());
  });

  const memberCount = participants.length;
  const isGroup = conversation?.type === 'GROUP';
  const selfConversation = !isGroup && isSelfParticipants(participants, me);
  const otherParticipant = participants.find(
    (member) => member.participantType === 'USER' && !isSameUser(member, me),
  );
  const agentParticipant = participants.find(
    (member) =>
      member.participantType === 'AGENT' &&
      Number(member.participantId) !== Number(me?.id),
  );
  const conversationTitle = (() => {
    if (!conversation) return tw('conversation.chat', 'Chat');
    if (conversation.type === 'GROUP') {
      return (
        conversation.remark ||
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
                ? undefined
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
          {messages.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={tw('conversation.noMessages', 'No messages')}
            />
          ) : (
            messages.map((item) => {
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
              const senderName =
                sender?.displayName ||
                (item.senderType === 'AGENT'
                  ? tw('chat.agent', 'Digital avatar')
                  : item.senderType === 'SYSTEM'
                    ? tw('chat.system', 'System')
                    : tw('chat.unknownUser', 'Unknown user'));
              const avatarName = self ? displayName(me) : senderName;
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
                  trigger={['contextMenu']}
                  menu={{
                    items: menuItems,
                    onClick: ({ key }) => handleMessageAction(item, key),
                  }}
                >
                  <div
                    className={`${styles.messageRow} ${self ? styles.messageRowSelf : ''}`}
                  >
                    <Avatar
                      size={32}
                      src={self ? me?.avatar : sender?.avatar}
                      style={avatarStyle(avatarName)}
                    >
                      {initials(avatarName)}
                    </Avatar>
                    <div
                      className={`${styles.bubbleWrap} ${self ? styles.bubbleWrapSelf : ''}`}
                    >
                      <div
                        className={`${styles.messageMeta} ${self ? styles.messageMetaSelf : ''}`}
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
                            onReplyText={sendText}
                            submitting={sending}
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
                                  card.status === 'COMPLETED' ||
                                  card.status === 'DONE'
                                    ? styles.taskCardStatusDone
                                    : card.status === 'FAILED' ||
                                        card.status === 'CANCELLED'
                                      ? styles.taskCardStatusFailed
                                      : styles.taskCardStatusRunning
                                }`}
                              >
                                {taskStatusLabel(card.status)}
                              </span>
                            </div>
                            {card.summary ? (
                              <div className={styles.taskCardSummary}>
                                {card.summary}
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
              agentStream.phase === 'waiting' &&
              messages[messages.length - 1]?.senderType !== 'USER'
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
            setText(event.target.value);
            const element = event.target;
            element.style.height = 'auto';
            element.style.height = `${Math.min(element.scrollHeight, 120)}px`;
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              send();
            }
          }}
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
                  {memberRows.length === 0 ? (
                    <div className={styles.settingHint}>
                      {tw('chat.noMatchedMembers', 'No matching members')}
                    </div>
                  ) : (
                    memberRows.map((member) => {
                      const name =
                        member.displayName ||
                        member.username ||
                        tw('common.unnamedMember', 'Unnamed member');
                      return (
                        <div
                          key={`${member.participantType}-${member.participantId}`}
                          className={styles.memberRow}
                        >
                          <Avatar
                            size={34}
                            src={member.avatar}
                            style={avatarStyle(name)}
                          >
                            {initials(name)}
                          </Avatar>
                          <div className={styles.memberBody}>
                            <div className={styles.memberName}>{name}</div>
                            <div className={styles.memberRole}>
                              {member.role === 'OWNER'
                                ? tw('chat.owner', 'Owner')
                                : member.participantType === 'AGENT'
                                  ? tw('chat.agent', 'Digital avatar')
                                  : tw('chat.member', 'Member')}
                            </div>
                          </div>
                        </div>
                      );
                    })
                  )}
                </div>
              </div>

              <div className={styles.settingSection}>
                <div className={styles.settingLabel}>
                  {tw('chat.remark', 'Group alias')}
                </div>
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
    if (agent.status !== 'ACTIVE') {
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
                    style={{ opacity: agent.status === 'ACTIVE' ? 1 : 0.6 }}
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
                            agent.status === 'ACTIVE' ? 'green' : 'default'
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

  const isAdmin =
    me?.superAdmin === '1' ||
    me?.superAdmin === true ||
    (me?.roles || []).some(
      (role: string) => role === 'ADMIN' || role === 'SUPER_ADMIN',
    );

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
          {isAdmin ? (
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
                  {tw('me.admin', 'Admin console')}
                </div>
                <div className={styles.menuDesc}>
                  {tw('me.adminDesc', 'Open the admin console')}
                </div>
              </div>
              <span className={styles.sectionMore}>›</span>
            </div>
          ) : null}
        </div>
      </Spin>

      <div className={styles.logoutRow}>
        <button type="button" className={styles.logoutButton} onClick={logout}>
          <LogoutOutlined />
          <span>{tw('me.logout', 'Sign out')}</span>
        </button>
      </div>

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
    </div>
  );
}
