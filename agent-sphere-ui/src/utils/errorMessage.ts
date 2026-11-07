import { getIntl } from '@umijs/max';

/**
 * Localizes a backend error envelope using `errorNamespace + errorCode`.
 * Falls back to the server-provided user tip/message, then to the caller fallback.
 */
export function localizeApiError(body: any, fallback: string): string {
  const namespace = body?.errorNamespace;
  const code = body?.errorCode;
  const serverText = body?.userTip || body?.errorMessage || body?.message;
  if (namespace && code) {
    try {
      return getIntl().formatMessage({
        id: `errors.${namespace}.${code}`,
        defaultMessage: serverText || fallback,
      });
    } catch {
      return serverText || fallback;
    }
  }
  return serverText || fallback;
}
