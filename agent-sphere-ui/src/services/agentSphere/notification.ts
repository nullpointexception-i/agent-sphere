import { request } from '@umijs/max';

import { API_BASE } from '@/constants/api';

const BASE = API_BASE;

export interface NotificationVO {
  id: number;
  type: string;
  titleKey?: string;
  bodyKey?: string;
  payload?: string;
  sourceType?: string;
  sourceId?: number;
  read: boolean;
  createdAt?: string;
}

export interface NotificationPage {
  records: NotificationVO[];
  total: number;
  page: number;
  pageSize: number;
}

export const notificationApi = {
  page(params: { unreadOnly?: boolean; page?: number; pageSize?: number }) {
    return request<NotificationPage>(`${BASE}/im/notifications`, {
      method: 'GET',
      params,
    });
  },
  unreadCount() {
    return request<number>(`${BASE}/im/notifications/unread-count`, {
      method: 'GET',
    });
  },
  markRead(id: number) {
    return request<void>(`${BASE}/im/notifications/${id}/read`, {
      method: 'POST',
    });
  },
  markAllRead() {
    return request<void>(`${BASE}/im/notifications/read-all`, {
      method: 'POST',
    });
  },
};
