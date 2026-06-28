import type { RequestConfig } from '@umijs/max';
import { getIntl } from '@umijs/max';
import { getToken } from '@/utils/auth';
import { showError } from '@/utils/globalMessage';

export const errorConfig: RequestConfig = {
  errorConfig: {
    errorHandler: (error: any, opts: any) => {
      if (opts?.skipErrorHandler) throw error;
      if (error.response) {
        const status = error.response.status;
        const data = error.response.data;
        const msg =
          data?.userTip ||
          data?.errorMessage ||
          data?.message ||
          `Request failed (${status})`;
        if (status === 401 || status === 403) {
          window.location.href = '/user/login';
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
