/**
 * Pure helpers for the workspace conversation list and message merging.
 *
 * <p>Extracted from `workspace/index.tsx` so the page component only owns layout/state.
 */
import { getLocale } from '@umijs/max';
import dayjs from 'dayjs';
import type {
  ImConversation,
  ImMessage,
  ImParticipant,
} from '@/services/agentSphere/im';
import { messageText, parsedContent } from '../im/components/messageUtils';
import { tw } from './i18n';

export function statusLabel(status?: string) {
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
export function positionLabel(
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

export function isSameUser(participant: ImParticipant | undefined, me: any) {
  if (!participant || !me) return false;
  if (participant.username && me.username) {
    return participant.username === me.username;
  }
  return Number(participant.participantId) === Number(me.id);
}

export function isSelfParticipants(participants: ImParticipant[], me: any) {
  if (participants.some((p) => p.participantType === 'AGENT')) return false;
  const users = participants.filter(
    (participant) => participant.participantType === 'USER',
  );
  return users.length > 0 && users.every((user) => isSameUser(user, me));
}

export function isSelfConversation(conversation: ImConversation, me: any) {
  if (conversation.type === 'GROUP') return false;
  if (conversation.selfConversation != null) {
    return conversation.selfConversation;
  }
  return isSelfParticipants(conversation.participants || [], me);
}

export function isSelfUser(user: any, me: any) {
  if (!user || !me) return false;
  if (user.username && me.username) {
    return user.username === me.username;
  }
  return Number(user.id) === Number(me.id);
}

export function otherUserParticipant(conversation: ImConversation, me: any) {
  if (conversation.type === 'GROUP') return undefined;
  return conversation.participants?.find(
    (participant) =>
      participant.participantType === 'USER' && !isSameUser(participant, me),
  );
}

export function conversationDisplayName(conversation: ImConversation, me: any) {
  if (conversation.type === 'GROUP') {
    return (
      conversation.remark ||
      conversation.displayName ||
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

export function conversationPreview(conversation: ImConversation) {
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

export function conversationAvatar(conversation: ImConversation, me: any) {
  if (isSelfConversation(conversation, me)) {
    return me?.avatar;
  }
  if (conversation.type === 'GROUP') {
    return conversation.avatar;
  }
  return conversation.avatar || otherUserParticipant(conversation, me)?.avatar;
}

export function sortConversations(conversations: ImConversation[]) {
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

export function mergeConversation(
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

export function listTime(value?: string) {
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

export function messageTime(value?: string) {
  if (!value) return '';
  const time = dayjs(value);
  return time.isValid() ? time.format('HH:mm') : '';
}

export function fullTime(value?: string) {
  if (!value) return '-';
  const time = dayjs(value);
  return time.isValid() ? time.format('YYYY-MM-DD HH:mm') : '-';
}

export type TaskCardView = {
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

export function taskCardOf(message: ImMessage): TaskCardView | null {
  const content = parsedContent(message);
  if (!content?.cardType) return null;
  return content as TaskCardView;
}

export function taskStatusLabel(status?: string) {
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

export function mergeMessages(older: ImMessage[], current: ImMessage[]) {
  const byId = new Map<number, ImMessage>();
  for (const message of [...older, ...current]) {
    byId.set(message.id, message);
  }
  return [...byId.values()].sort((left, right) => left.seq - right.seq);
}
