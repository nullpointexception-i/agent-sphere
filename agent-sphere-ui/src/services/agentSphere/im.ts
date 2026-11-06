import { request } from '@umijs/max';

const BASE = '/api/v1';

export interface ImParticipant {
  id: number;
  conversationId?: number;
  participantType: string;
  participantId: number;
  role?: string;
  displayName?: string;
  username?: string;
  avatar?: string;
  muted?: boolean;
  joinedAt?: string;
}

export interface ImConversation {
  id: number;
  type: string;
  title?: string;
  purpose?: string;
  ownerUserId?: number;
  threadId?: number;
  lastSeq?: number;
  unread?: number;
  lastMessageAt?: string;
  status?: string;
  pinned?: boolean;
  muted?: boolean;
  remark?: string;
  displayName?: string;
  avatar?: string;
  selfConversation?: boolean;
  participantCount?: number;
  lastMessage?: ImMessage;
  pinnedMessage?: ImMessage;
  participants?: ImParticipant[];
}

export interface ImConversationPage {
  pinned: ImConversation[];
  rows: ImConversation[];
  hasMore?: boolean;
  nextCursor?: string;
}

export interface ImConversationSetting {
  conversationId: number;
  pinned: boolean;
  muted: boolean;
  hidden?: boolean;
  remark?: string;
}

/** Per-call decision for a Router tool-approval card. */
export interface SessionInputDecision {
  callId: string;
  /** DENY / ALLOW_ONCE / ALLOW_RUN / ALLOW_SESSION */
  decision: string;
}

export interface ImMessage {
  id: number;
  conversationId: number;
  seq: number;
  senderType: string;
  senderId?: number;
  clientMsgId?: string;
  type: string;
  content?: Record<string, any> | string;
  replyToId?: number;
  status?: string;
  createdAt?: string;
}

export interface ImAttachment {
  fileKey: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  url: string;
}

export interface ImMessagePage {
  rows: ImMessage[];
  hasMore: boolean;
  nextBeforeSeq?: number;
  nextAfterSeq?: number;
}

export interface CreateConversationInput {
  type: string;
  title?: string;
  purpose?: string;
  participantUserIds?: number[];
  gateConfig?: string;
}

export const imApi = {
  conversations: (params?: {
    limit?: number;
    cursor?: string;
    type?: 'DM' | 'GROUP';
  }) => request<ImConversationPage>(`${BASE}/im/conversations`, { params }),
  conversation: (id: number) =>
    request<ImConversation>(`${BASE}/im/conversations/${id}`),
  createConversation: (data: CreateConversationInput) =>
    request<ImConversation>(`${BASE}/im/conversations`, {
      method: 'POST',
      data,
    }),
  createAgentDm: (agentId: number) =>
    request<ImConversation>(`${BASE}/im/conversations/agent-dm`, {
      method: 'POST',
      data: { agentId },
    }),
  participants: (id: number) =>
    request<any[]>(`${BASE}/im/conversations/${id}/participants`),
  addParticipant: (
    id: number,
    data: { participantType: string; participantId: number; role?: string; displayName?: string },
  ) =>
    request<any>(`${BASE}/im/conversations/${id}/participants`, {
      method: 'POST',
      data,
    }),
  muteParticipant: (id: number, participantId: number, muted: boolean) =>
    request<void>(
      `${BASE}/im/conversations/${id}/participants/${participantId}/mute?muted=${muted}`,
      { method: 'POST' },
    ),
  messages: (id: number, beforeSeq?: number, limit = 30) =>
    request<ImMessage[]>(`${BASE}/im/conversations/${id}/messages`, {
      params: { beforeSeq, limit },
    }),
  messagePage: (id: number, beforeSeq?: number, afterSeq?: number, limit = 30) =>
    request<ImMessagePage>(`${BASE}/im/conversations/${id}/messages/page`, {
      params: { beforeSeq, afterSeq, limit },
    }),
  searchMessages: (id: number, keyword: string, beforeSeq?: number, limit = 30) =>
    request<ImMessagePage>(`${BASE}/im/conversations/${id}/messages/search`, {
      params: { keyword, beforeSeq, limit },
    }),
  send: (
    id: number,
    text: string,
    clientMsgId?: string,
    mentions?: { type: string; id: number; name?: string }[],
    replyToId?: number,
  ) =>
    request<ImMessage>(`${BASE}/im/conversations/${id}/messages`, {
      method: 'POST',
      data: { type: 'TEXT', content: { text }, clientMsgId, mentions, replyToId },
    }),
  uploadAttachment: (id: number, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return request<ImAttachment>(`${BASE}/im/conversations/${id}/attachments`, {
      method: 'POST',
      data: form,
    });
  },
  downloadAttachment: (id: number, fileKey: string) =>
    request<Blob>(
      `${BASE}/im/conversations/${id}/attachments/${encodeURIComponent(fileKey)}`,
      { responseType: 'blob' },
    ),
  sendAttachment: (
    id: number,
    type: string,
    attachment: ImAttachment,
    clientMsgId?: string,
    replyToId?: number,
  ) =>
    request<ImMessage>(`${BASE}/im/conversations/${id}/messages`, {
      method: 'POST',
      data: {
        type,
        content: {
          fileKey: attachment.fileKey,
          fileName: attachment.fileName,
          contentType: attachment.contentType,
          sizeBytes: attachment.sizeBytes,
          url: attachment.url,
          text: attachment.fileName,
        },
        clientMsgId,
        replyToId,
      },
    }),
  markRead: (id: number, lastReadSeq: number) =>
    request<void>(`${BASE}/im/conversations/${id}/read`, {
      method: 'POST',
      data: { lastReadSeq },
    }),
  recall: (conversationId: number, messageId: number) =>
    request<void>(`${BASE}/im/conversations/${conversationId}/messages/${messageId}`, {
      method: 'DELETE',
    }),
  edit: (conversationId: number, messageId: number, content: Record<string, any>) =>
    request<ImMessage>(`${BASE}/im/conversations/${conversationId}/messages/${messageId}`, {
      method: 'PUT',
      data: { text: content.text ?? '' },
    }),
  gateConfig: (id: number) =>
    request<{ trigger?: string }>(`${BASE}/im/conversations/${id}/gate-config`),
  updateGateConfig: (id: number, trigger: string) =>
    request<{ trigger?: string }>(`${BASE}/im/conversations/${id}/gate-config`, {
      method: 'PATCH',
      data: { trigger },
    }),
  updateConversation: (id: number, data: { title?: string; purpose?: string }) =>
    request<ImConversation>(`${BASE}/im/conversations/${id}`, {
      method: 'PATCH',
      params: data,
    }),
  deleteConversation: (id: number) =>
    request<void>(`${BASE}/im/conversations/${id}`, { method: 'DELETE' }),
  setting: (id: number) =>
    request<ImConversationSetting>(`${BASE}/im/conversations/${id}/setting`),
  updateSetting: (
    id: number,
    data: { pinned?: boolean; muted?: boolean; remark?: string },
  ) =>
    request<ImConversationSetting>(`${BASE}/im/conversations/${id}/setting`, {
      method: 'PATCH',
      data,
    }),
  pinMessage: (conversationId: number, messageId: number) =>
    request<ImConversation>(
      `${BASE}/im/conversations/${conversationId}/pinned-message/${messageId}`,
      { method: 'POST' },
    ),
  unpinMessage: (conversationId: number) =>
    request<ImConversation>(
      `${BASE}/im/conversations/${conversationId}/pinned-message`,
      { method: 'DELETE' },
    ),
  resolveSessionInputApproval: (
    conversationId: number,
    inputId: string,
    decisions: SessionInputDecision[],
  ) =>
    request<void>(
      `${BASE}/im/conversations/${conversationId}/session-inputs/${inputId}/approve`,
      { method: 'POST', data: { decisions } },
    ),
};
