import { SettingOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import {
  App,
  Button,
  Empty,
  Form,
  Input,
  Listy,
  Modal,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { ImStreamPayload } from '@/hooks/useReconnectingSse';
import { useReconnectingSse } from '@/hooks/useReconnectingSse';
import {
  type ImAttachment,
  type ImConversation,
  type ImMessage,
  type ImMessagePage,
  imApi,
} from '@/services/agentSphere/im';
import { superAgentApi } from '@/services/agentSphere/superAgent';
import { Composer } from './components/Composer';
import { ConversationList } from './components/ConversationList';
import { MessageList } from './components/MessageList';
import { messageText } from './components/messageUtils';

const COMPENSATION_MS = 45_000;

function mergeMessages(older: ImMessage[], current: ImMessage[]): ImMessage[] {
  const byId = new Map<number, ImMessage>();
  for (const message of [...older, ...current]) {
    byId.set(message.id, message);
  }
  return [...byId.values()].sort((left, right) => left.seq - right.seq);
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

export default function ImPage() {
  const { message: toast, modal: modalApi } = App.useApp();
  const intl = useIntl();
  const t = (id: string, defaultMessage: string) =>
    intl.formatMessage({ id, defaultMessage });

  const [loading, setLoading] = useState(false);
  const [conversations, setConversations] = useState<ImConversation[]>([]);
  const [conversationHasMore, setConversationHasMore] = useState(false);
  const [conversationCursor, setConversationCursor] = useState<
    string | undefined
  >(undefined);
  const [loadingMoreConversations, setLoadingMoreConversations] =
    useState(false);
  const [activeId, setActiveId] = useState<number | undefined>(undefined);
  const [messages, setMessages] = useState<ImMessage[]>([]);
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [nextBeforeSeq, setNextBeforeSeq] = useState<number | undefined>(
    undefined,
  );
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [searchInput, setSearchInput] = useState('');
  const [searchKeyword, setSearchKeyword] = useState('');
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [participants, setParticipants] = useState<any[]>([]);
  const [mentions, setMentions] = useState<number[]>([]);
  const [replyToId, setReplyToId] = useState<number | undefined>(undefined);
  const [timelineOpen, setTimelineOpen] = useState(false);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const [timelineRows, setTimelineRows] = useState<any[]>([]);
  const [editOpen, setEditOpen] = useState(false);
  const [editMessageId, setEditMessageId] = useState<number | undefined>(
    undefined,
  );
  const [editText, setEditText] = useState('');
  const [editSaving, setEditSaving] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [gateOpen, setGateOpen] = useState(false);
  const [gateTrigger, setGateTrigger] = useState('MENTION');
  const [groupTitle, setGroupTitle] = useState('');
  const [groupPurpose, setGroupPurpose] = useState('');
  const [gateSaving, setGateSaving] = useState(false);
  const [createForm] = Form.useForm();
  const bottomRef = useRef<HTMLDivElement>(null);
  const activeIdRef = useRef<number | undefined>(undefined);
  activeIdRef.current = activeId;
  const messagesRef = useRef<ImMessage[]>([]);
  messagesRef.current = messages;

  const active = useMemo(
    () => conversations.find((conversation) => conversation.id === activeId),
    [conversations, activeId],
  );

  const loadConversations = async (silent = false) => {
    if (!silent) setLoading(true);
    try {
      const page = await imApi.conversations({ limit: 50 });
      const list = [...(page?.pinned || []), ...(page?.rows || [])];
      setConversations((previous) => {
        const pageIds = new Set(list.map((item) => item.id));
        const retained = previous.filter((item) => !pageIds.has(item.id));
        return sortConversations([...list, ...retained]);
      });
      setConversationHasMore(Boolean(page?.hasMore));
      setConversationCursor(page?.nextCursor);
      if (list.length) {
        setActiveId((current) => current ?? list[0].id);
      }
    } finally {
      if (!silent) setLoading(false);
    }
  };

  const loadMoreConversations = async () => {
    if (
      !conversationHasMore ||
      !conversationCursor ||
      loadingMoreConversations
    ) {
      return;
    }
    setLoadingMoreConversations(true);
    try {
      const page = await imApi.conversations({
        limit: 50,
        cursor: conversationCursor,
      });
      const existing = new Set(conversations.map((item) => item.id));
      const appended = (page?.rows || []).filter(
        (item) => !existing.has(item.id),
      );
      setConversations((previous) =>
        sortConversations([...previous, ...appended]),
      );
      setConversationHasMore(Boolean(page?.hasMore));
      setConversationCursor(page?.nextCursor);
    } finally {
      setLoadingMoreConversations(false);
    }
  };

  const applyPage = (page: ImMessagePage) => {
    setMessages(page.rows ?? []);
    setHasMore(Boolean(page.hasMore));
    setNextBeforeSeq(page.nextBeforeSeq);
  };

  const loadLatest = async (
    conversationId: number,
    silent = false,
    resetSearch = false,
  ) => {
    if (!silent) setMessagesLoading(true);
    try {
      applyPage(await imApi.messagePage(conversationId));
      if (resetSearch) {
        setSearchInput('');
        setSearchKeyword('');
      }
    } finally {
      if (!silent) setMessagesLoading(false);
    }
  };

  const loadMessages = async (conversationId: number, silent = false) => {
    const keyword = searchKeyword.trim();
    if (!keyword) {
      await loadLatest(conversationId, silent);
      return;
    }
    if (!silent) setMessagesLoading(true);
    try {
      applyPage(await imApi.searchMessages(conversationId, keyword));
    } finally {
      if (!silent) setMessagesLoading(false);
    }
  };

  const handleStreamEvent = async (type: string, event: ImStreamPayload) => {
    const id = event.conversationId;
    if (!id || type === 'heartbeat') return;

    if (type === 'conversation_hidden') {
      setConversations((previous) => previous.filter((item) => item.id !== id));
      setActiveId((current) => (current === id ? undefined : current));
      return;
    }

    if (type === 'message_created') {
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
                    content: { text: event.previewText || '新消息' },
                    createdAt: event.createdAt,
                  },
                }
              : item,
          ),
        ),
      );
      if (id === activeIdRef.current) {
        const current = messagesRef.current;
        const lastSeq = current[current.length - 1]?.seq;
        if (!lastSeq) {
          await loadLatest(id, true);
        } else {
          const page = await imApi.messagePage(id, undefined, lastSeq, 50);
          setMessages((previous) => mergeMessages(previous, page.rows ?? []));
        }
      }
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
                        ? '[消息已撤回]'
                        : event.previewText || '',
                  },
                },
              }
            : item,
        ),
      );
      if (id === activeIdRef.current) {
        await loadLatest(id, true);
      }
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

    if (
      type === 'conversation_created' ||
      type === 'conversation_updated' ||
      type === 'message_pinned' ||
      type === 'message_unpinned' ||
      type === 'participant_changed'
    ) {
      const conversation = await imApi.conversation(id);
      setConversations((previous) => mergeConversation(previous, conversation));
      if (
        id === activeIdRef.current &&
        type !== 'message_pinned' &&
        type !== 'message_unpinned'
      ) {
        imApi
          .participants(id)
          .then(setParticipants)
          .catch(() => {});
      }
    }
  };

  const loadOlder = async () => {
    if (!activeId || !nextBeforeSeq || loadingOlder) return;
    setLoadingOlder(true);
    try {
      const keyword = searchKeyword.trim();
      const page = keyword
        ? await imApi.searchMessages(activeId, keyword, nextBeforeSeq)
        : await imApi.messagePage(activeId, nextBeforeSeq);
      setMessages((current) => mergeMessages(page.rows ?? [], current));
      setHasMore(Boolean(page.hasMore));
      setNextBeforeSeq(page.nextBeforeSeq);
    } finally {
      setLoadingOlder(false);
    }
  };

  const clearSearch = async () => {
    if (!activeId) return;
    setSearchInput('');
    setSearchKeyword('');
    setMessagesLoading(true);
    try {
      applyPage(await imApi.messagePage(activeId));
    } finally {
      setMessagesLoading(false);
    }
  };

  const runSearch = async () => {
    if (!activeId) return;
    const keyword = searchInput.trim();
    if (!keyword) {
      await clearSearch();
      return;
    }
    setMessagesLoading(true);
    try {
      const page = await imApi.searchMessages(activeId, keyword);
      setSearchKeyword(keyword);
      applyPage(page);
    } finally {
      setMessagesLoading(false);
    }
  };

  useEffect(() => {
    loadConversations();
  }, []);

  // IM 用户级 SSE：按事件类型增量更新，不再在事件到达时全量刷新。
  useReconnectingSse('/api/v1/im/stream', (type, event) => {
    void handleStreamEvent(type, event).catch(() => {});
  });

  useEffect(() => {
    if (!activeId) {
      setMessages([]);
      setHasMore(false);
      setNextBeforeSeq(undefined);
      return;
    }
    setSearchInput('');
    setSearchKeyword('');
    loadLatest(activeId, false, true);
    imApi
      .participants(activeId)
      .then(setParticipants)
      .catch(() => setParticipants([]));
    // 45s HTTP compensation only; dense updates arrive through SSE.
    const compensate = () => {
      if (document.visibilityState !== 'visible') return;
      loadConversations(true);
      loadMessages(activeId, true);
    };
    const timer = window.setInterval(compensate, COMPENSATION_MS);
    const onVisible = () => {
      if (document.visibilityState === 'visible') compensate();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [activeId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const send = async () => {
    if (!activeId || !text.trim()) return;
    setSending(true);
    try {
      const mentionPayload = participants
        .filter((participant) => mentions.includes(participant.participantId))
        .map((participant) => ({
          type: participant.participantType,
          id: participant.participantId,
          name: participant.displayName,
        }));
      await imApi.send(
        activeId,
        text.trim(),
        `web-${Date.now()}`,
        mentionPayload,
        replyToId,
      );
      setText('');
      setMentions([]);
      setReplyToId(undefined);
      await loadMessages(activeId, true);
      loadConversations();
    } finally {
      setSending(false);
    }
  };

  const uploadAttachment = async (file: File) => {
    if (!activeId) return;
    setSending(true);
    try {
      const attachment: ImAttachment = await imApi.uploadAttachment(
        activeId,
        file,
      );
      const type = attachment.contentType?.startsWith('image/')
        ? 'IMAGE'
        : 'FILE';
      await imApi.sendAttachment(
        activeId,
        type,
        attachment,
        `web-${Date.now()}`,
        replyToId,
      );
      setReplyToId(undefined);
      await loadMessages(activeId, true);
      loadConversations();
    } catch {
      toast.error('附件发送失败');
    } finally {
      setSending(false);
    }
  };

  const refreshActive = async () => {
    const id = activeIdRef.current;
    if (id) await loadMessages(id, true);
    await loadConversations(true);
  };

  const openCardTimeline = async (content: Record<string, any>) => {
    const agentId = Number(content.superAgentId);
    const workItemId = Number(content.workItemId);
    if (!Number.isFinite(agentId) || !Number.isFinite(workItemId)) return;
    setTimelineOpen(true);
    setTimelineLoading(true);
    try {
      const page = await superAgentApi.workerTimeline(agentId, workItemId);
      setTimelineRows(page?.rows ?? []);
    } finally {
      setTimelineLoading(false);
    }
  };

  const retryCard = async (content: Record<string, any>) => {
    const agentId = Number(content.superAgentId);
    const workItemId = Number(content.workItemId);
    if (!Number.isFinite(agentId) || !Number.isFinite(workItemId)) return;
    await superAgentApi.retryWorkItem(agentId, workItemId);
    toast.success(t('pages.superAgent.retried', '已重新排队'));
    await refreshActive();
  };

  const cancelCard = (content: Record<string, any>) => {
    const agentId = Number(content.superAgentId);
    const workItemId = Number(content.workItemId);
    if (!Number.isFinite(agentId) || !Number.isFinite(workItemId)) return;
    modalApi.confirm({
      title: t(
        'pages.superAgent.cancelConfirm',
        '取消后 Worker Run 会停止，确认取消？',
      ),
      onOk: async () => {
        await superAgentApi.cancelWorkItem(agentId, workItemId);
        toast.success(t('pages.superAgent.cancelled', '任务已取消'));
        await refreshActive();
      },
    });
  };

  const openGate = async () => {
    if (!activeId) return;
    const config = await imApi.gateConfig(activeId);
    const conversation = conversations.find((item) => item.id === activeId);
    setGroupTitle(conversation?.title ?? '');
    setGroupPurpose(conversation?.purpose ?? '');
    setGateTrigger(config?.trigger ?? 'MENTION');
    setGateOpen(true);
  };

  const recallMessage = async (messageId: number) => {
    if (!activeId) return;
    await imApi.recall(activeId, messageId);
    toast.success('已撤回');
    await loadMessages(activeId, true);
  };

  const openEdit = (message: ImMessage) => {
    setEditMessageId(message.id);
    setEditText(messageText(message));
    setEditOpen(true);
  };

  const saveEdit = async () => {
    if (!activeId || !editMessageId) return;
    setEditSaving(true);
    try {
      await imApi.edit(activeId, editMessageId, { text: editText });
      toast.success('已更新');
      setEditOpen(false);
      await loadMessages(activeId, true);
    } finally {
      setEditSaving(false);
    }
  };

  const toggleMute = async (participantId: number, muted: boolean) => {
    if (!activeId) return;
    await imApi.muteParticipant(activeId, participantId, muted);
    setParticipants((prev) =>
      prev.map((participant) =>
        participant.participantId === participantId
          ? { ...participant, muted }
          : participant,
      ),
    );
    toast.success(muted ? '已免打扰' : '已取消免打扰');
  };

  const saveGate = async () => {
    if (!activeId) return;
    setGateSaving(true);
    try {
      await Promise.all([
        imApi.updateGateConfig(activeId, gateTrigger),
        imApi.updateConversation(activeId, {
          title: groupTitle,
          purpose: groupPurpose,
        }),
      ]);
      toast.success(t('pages.im.gateSaved', '群触发规则已保存'));
      setGateOpen(false);
      await loadConversations();
    } finally {
      setGateSaving(false);
    }
  };

  const createConversation = async () => {
    const values = await createForm.validateFields();
    const created = await imApi.createConversation({
      type: values.type,
      title: values.title,
      participantUserIds: (values.participantUserIds ?? [])
        .map((value: string | number) => Number(value))
        .filter((value: number) => Number.isFinite(value)),
    });
    for (const agentId of values.agentIds ?? []) {
      await imApi.addParticipant(created.id, {
        participantType: 'AGENT',
        participantId: Number(agentId),
        displayName: `Agent #${agentId}`,
      });
    }
    toast.success(t('pages.im.created', '会话已创建'));
    setCreateOpen(false);
    createForm.resetFields();
    await loadConversations();
    setActiveId(created.id);
  };

  return (
    <PageContainer>
      <div style={{ display: 'flex', gap: 16, minHeight: 560 }}>
        <ConversationList
          conversations={conversations}
          activeId={activeId}
          loading={loading}
          hasMore={conversationHasMore}
          loadingMore={loadingMoreConversations}
          onLoadMore={loadMoreConversations}
          onSelect={setActiveId}
          onCreate={() => setCreateOpen(true)}
          labels={{
            conversations: t('pages.im.conversations', '会话'),
            newConversation: t('pages.im.newConversation', '新建'),
            noConversation: t('pages.im.noConversation', '暂无会话'),
          }}
        />

        <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
          {active ? (
            <>
              <div style={{ marginBottom: 12 }}>
                <Typography.Title level={5} style={{ margin: 0 }}>
                  {active.title || `${active.type} #${active.id}`}
                </Typography.Title>
                <Typography.Text type="secondary">
                  conversation #{active.id} · thread {active.threadId ?? '-'}
                </Typography.Text>
                {active.type === 'GROUP' ? (
                  <Button
                    type="link"
                    size="small"
                    icon={<SettingOutlined />}
                    onClick={openGate}
                    style={{ marginLeft: 8 }}
                  >
                    {t('pages.im.groupSettings', '群设置')}
                  </Button>
                ) : null}
                <Input.Search
                  value={searchInput}
                  onChange={(event) => setSearchInput(event.target.value)}
                  onSearch={runSearch}
                  allowClear
                  placeholder="搜索消息"
                  style={{ width: 220, marginLeft: 8 }}
                />
                {searchKeyword ? (
                  <Tag closable onClose={clearSearch} style={{ marginLeft: 8 }}>
                    搜索: {searchKeyword}
                  </Tag>
                ) : null}
              </div>
              <MessageList
                messages={messages}
                loading={messagesLoading}
                hasMore={hasMore}
                loadingOlder={loadingOlder}
                bottomRef={bottomRef}
                onLoadOlder={loadOlder}
                onReply={setReplyToId}
                onEdit={openEdit}
                onRecall={recallMessage}
                onRetryCard={retryCard}
                onCancelCard={cancelCard}
                onTimeline={openCardTimeline}
                labels={{
                  noMessage: t('pages.im.noMessage', '还没有消息'),
                  reply: t('pages.im.reply', '回复'),
                }}
              />
              <Composer
                text={text}
                onTextChange={setText}
                onSend={send}
                sending={sending}
                participants={participants}
                mentions={mentions}
                onMentionsChange={setMentions}
                replyToId={replyToId}
                onClearReply={() => setReplyToId(undefined)}
                onUpload={uploadAttachment}
                labels={{
                  placeholder: t(
                    'pages.im.placeholder',
                    '输入消息，Enter 发送，Shift+Enter 换行',
                  ),
                  mention: t('pages.im.mention', '@数字员工'),
                  send: t('pages.im.send', '发送'),
                  replying: t('pages.im.replying', '回复'),
                  attachment: '附件',
                }}
              />
            </>
          ) : (
            <Empty
              description={t('pages.im.selectConversation', '请选择会话')}
            />
          )}
        </div>
      </div>

      <Modal
        open={createOpen}
        title={t('pages.im.newConversation', '新建会话')}
        onCancel={() => setCreateOpen(false)}
        onOk={createConversation}
        destroyOnHidden
      >
        <Form
          form={createForm}
          layout="vertical"
          initialValues={{ type: 'GROUP' }}
        >
          <Form.Item
            name="type"
            label={t('pages.im.type', '类型')}
            rules={[{ required: true }]}
          >
            <Select
              options={[
                { value: 'GROUP', label: 'GROUP' },
                { value: 'DM', label: 'DM' },
              ]}
            />
          </Form.Item>
          <Form.Item name="title" label={t('pages.im.title', '标题')}>
            <Input maxLength={256} />
          </Form.Item>
          <Form.Item
            name="participantUserIds"
            label={t('pages.im.userIds', '成员用户 ID')}
            tooltip={t('pages.im.userIdsHint', '逗号分隔或回车添加')}
          >
            <Select mode="tags" tokenSeparators={[',']} />
          </Form.Item>
          <Form.Item
            name="agentIds"
            label={t('pages.im.agentIds', '数字员工 ID')}
            tooltip={t('pages.im.agentIdsHint', '创建后会把对应数字员工加入群')}
          >
            <Select mode="tags" tokenSeparators={[',']} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={gateOpen}
        title={t('pages.im.groupSettings', '群设置')}
        confirmLoading={gateSaving}
        onCancel={() => setGateOpen(false)}
        onOk={saveGate}
        destroyOnHidden
      >
        <Form layout="vertical">
          <Form.Item label="群名称">
            <Input
              value={groupTitle}
              onChange={(event) => setGroupTitle(event.target.value)}
            />
          </Form.Item>
          <Form.Item label="群目的 / 规则">
            <Input.TextArea
              value={groupPurpose}
              onChange={(event) => setGroupPurpose(event.target.value)}
              autoSize={{ minRows: 3, maxRows: 8 }}
            />
          </Form.Item>
          <Form.Item label={t('pages.im.trigger', '触发规则')}>
            <Select
              value={gateTrigger}
              onChange={setGateTrigger}
              options={[
                { value: 'MENTION', label: 'MENTION - 仅 @数字员工 时触发' },
                {
                  value: 'RELEVANCE',
                  label: 'RELEVANCE - 每条消息都交给 Group Router 判断',
                },
                { value: 'ALL', label: 'ALL - 每条消息都触发' },
              ]}
            />
          </Form.Item>
          <Form.Item label="Agent 免打扰">
            <Listy
              items={participants.filter(
                (participant) => participant.participantType === 'AGENT',
              )}
              rowKey="participantId"
              itemRender={(participant: any) => (
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '8px 0',
                    borderBottom: '1px solid #f0f0f0',
                  }}
                >
                  <span>
                    {participant.displayName ||
                      `Agent #${participant.participantId}`}
                  </span>
                  <Switch
                    checked={Boolean(participant.muted)}
                    onChange={(checked) =>
                      toggleMute(participant.participantId, checked)
                    }
                  />
                </div>
              )}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={editOpen}
        title="编辑消息"
        confirmLoading={editSaving}
        onOk={saveEdit}
        onCancel={() => setEditOpen(false)}
        destroyOnHidden
      >
        <Input.TextArea
          value={editText}
          onChange={(event) => setEditText(event.target.value)}
          autoSize={{ minRows: 3, maxRows: 8 }}
        />
      </Modal>

      <Modal
        open={timelineOpen}
        width={900}
        footer={null}
        title="Worker 时间线"
        onCancel={() => {
          setTimelineOpen(false);
          setTimelineRows([]);
        }}
        destroyOnHidden
      >
        <Spin spinning={timelineLoading}>
          <Listy
            items={timelineRows}
            rowKey={(row: any) => row.id ?? `${row.seq}-${row.kind}`}
            itemRender={(row: any) => {
              const content = row.content ?? {};
              const text =
                content.text ?? content.summary ?? content.response ?? '';
              return (
                <div
                  style={{
                    display: 'block',
                    padding: '10px 0',
                    borderBottom: '1px solid #f0f0f0',
                  }}
                >
                  <Space size={8}>
                    <Tag>{row.kind}</Tag>
                    <Tag>{row.state ?? '-'}</Tag>
                    <Typography.Text type="secondary">
                      seq {row.seq}
                    </Typography.Text>
                  </Space>
                  {text ? (
                    <div style={{ marginTop: 4 }}>{String(text)}</div>
                  ) : null}
                </div>
              );
            }}
          />
        </Spin>
      </Modal>
    </PageContainer>
  );
}
