import { request } from '@umijs/max';

import { API_BASE } from '@/constants/api';

const BASE = API_BASE;

export interface SystemDicEntry {
  id?: number;
  dicType: string;
  code: string;
  nameCn: string;
  nameEn: string;
  sortNo?: number;
  status?: string;
  remark?: string;
}

export const DIC_TYPE_POSITION = 'POSITION';

export const dictApi = {
  /** Active options available to every logged-in user. */
  listActive: (type: string = DIC_TYPE_POSITION) =>
    request<SystemDicEntry[]>(`${BASE}/system/dict`, { params: { type } }),

  list: (type: string = DIC_TYPE_POSITION, keyword?: string) =>
    request<SystemDicEntry[]>(`${BASE}/admin/dictionaries`, {
      params: { type, keyword },
    }),
  create: (data: SystemDicEntry) =>
    request<SystemDicEntry>(`${BASE}/admin/dictionaries`, {
      method: 'POST',
      data,
    }),
  update: (id: number, data: SystemDicEntry) =>
    request<SystemDicEntry>(`${BASE}/admin/dictionaries/${id}`, {
      method: 'PUT',
      data,
    }),
  delete: (id: number) =>
    request<void>(`${BASE}/admin/dictionaries/${id}`, { method: 'DELETE' }),
};
