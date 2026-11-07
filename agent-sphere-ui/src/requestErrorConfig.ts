import { localizeApiError } from '@/utils/errorMessage';
import type { RequestConfig } from '@umijs/max';
import { getIntl } from '@umijs/max';
import { clearStoredUser, getToken } from '@/utils/auth';
import { showError } from '@/utils/globalMessage';

export const errorConfig: RequestConfig = {
  errorConfig: {
    errorHandler: (error: any, opts: any) => {
      if (opts?.skipErrorHandler) throw error;
      if (error.response) {
        const status = error.response.status;
        const data = error.response.data;
        const msg = localizeApiError(data, `Request failed (${status})`);
        if (status === 401) {
          clearStoredUser();
          window.location.href = '/user/login';
          return;
        }
        if (status === 403) {
          // Insufficient permission is not a session expiry: redirecting to login would trap the user
          // in a "login succeeds but still 403" loop
          showError(msg);
          return;
        }
        showError(msg);
      } else if (typeof navigator !== 'undefined' && !navigator.onLine) {
        try {
          showError(
            getIntl().formatMessage({
              id: 'app.request.offline',
              defaultMessage:
                'Network unavailable. Please check your connection and try again.',
            }),
          );
        } catch {
          showError(
            'Network unavailable. Please check your connection and try again.',
          );
        }
      } else if (error.request) {
        showError('None response! Please retry.');
      } else {
        showError('Request error, please retry.');
      }
    },
  },

  requestInterceptors: [
    (config: any) => {
      const token = getToken();
      if (token) {
        config.headers = {
          ...config.headers,
          Authorization: `Bearer ${token}`,
        };
      }
      return config;
    },
  ],

  responseInterceptors: [],
};
