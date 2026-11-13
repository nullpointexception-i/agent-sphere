import {
  CloseOutlined,
  MinusOutlined,
  MoreOutlined,
  PlusOutlined,
  PushpinOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { App, Avatar, Button, Dropdown, Input, Select, Switch } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import MemberPickerModal from '@/components/MemberPickerModal';
import {
  buildMentionCandidates,
  mentionsFromText,
} from '@/components/mention/mentionModel';
import { useMentionSuggest } from '@/components/mention/useMentionSuggest';
import { STATUS } from '@/constants/status';
import { TIMING } from '@/constants/timing';
import type { ImStreamPayload } from '@/hooks/useReconnectingSse';
import { agentApi } from '@/services/agentSphere/api';
import {
  type ConversationAgent,
  type ImConversation,
  type ImConversationSetting,
  type ImMessage,
  imApi,
  type SessionInputDecision,
} from '@/services/agentSphere/im';
import { superAgentApi } from '@/services/agentSphere/superAgent';
import { getStoredUser } from '@/utils/auth';
import { getDesktopBridge } from '@/utils/desktopBridge';
import { userDisplayLabel } from '@/utils/userLabel';
import { messageText } from '../../im/components/messageUtils';
import type { AgentClarificationAnswer } from '../AgentInputCard';
import {
  AGENT_STREAM_PHASE,
  appendLocalChunk,
  beginAgentStream,
  failAgentStream,
  finalizeAgentStream,
  peekAgentStream,
  resetAgentStream,
  useAgentStream,
} from '../agentStreamStore';
import {
  isSameUser,
  isSelfParticipants,
  mergeMessages,
  type PendingMessage,
} from '../conversationModel';
import { tw } from '../i18n';
import { avatarStyle, displayName, initials } from '../shared';
import { MessageComposer } from './MessageComposer';
import { MessageList } from './MessageList';
import { useChatStyles } from './style';

export function ConversationPane({
  conversationId,
  refreshKey,
  streamEvent,
  initialDraft,
  onChanged,
}: {
  conversationId: number;
  refreshKey: number;
  streamEvent: { type: string; data: ImStreamPayload; nonce: number } | null;
  initialDraft?: string;
  onChanged: () => void;
}) {
  const { styles: chatStyles } = useChatStyles();
  const styles = chatStyles;
  const { message, modal } = App.useApp();
  const me = getStoredUser();
  const [conversation, setConversation] = useState<ImConversation | null>(null);
  const [participants, setParticipants] = useState<any[]>([]);
  const [agents, setAgents] = useState<ConversationAgent[]>([]);
  const [messages, setMessages] = useState<ImMessage[]>([]);
  /** Locally echoed messages awaiting (or failing) the server confirmation. */
  const [pendingMessages, setPendingMessages] = useState<PendingMessage[]>([]);
  const [loading, setLoading] = useState(true);
  const [text, setText] = useState(initialDraft || '');
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
  useEffect(() => {
    if (initialDraft) {
      setText(initialDraft);
    }
  }, [initialDraft]);
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
    if (!streamEvent) return;
    if (
      streamEvent.type !== 'sse_reconnected' &&
      streamEvent.data.conversationId !== conversationId
    ) {
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

      <MessageList
        messages={renderedMessages}
        participants={participants}
        conversation={conversation}
        me={me}
        styles={styles}
        conversationId={conversationId}
        loading={loading}
        hasOlder={hasOlder}
        olderCursor={olderCursor}
        olderLoading={olderLoading}
        listRef={listRef}
        agentStream={agentStream}
        agentParticipant={agentParticipant}
        onBottomChange={(atBottom) => {
          stickToBottomRef.current = atBottom;
        }}
        onLoadOlder={() => void loadOlder()}
        onMessageAction={handleMessageAction}
        onRetryPending={(item) => void retryPending(item)}
        onAnswerClarification={answerClarification}
        onApproveSessionInput={approveSessionInput}
      />

      {agentPaused ? (
        <div className={styles.agentPausedBanner}>
          {tw(
            'avatars.chatPaused',
            'This avatar is paused. Resume it before chatting.',
          )}
        </div>
      ) : null}

      <MessageComposer
        text={text}
        setText={setText}
        composerRef={composerRef}
        mention={mention}
        sending={sending}
        agentPaused={agentPaused}
        onSend={send}
        styles={styles}
      />

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
