export const ErrorCategory = {
  NOT_FOUND: 'not_found',
  CSP_BLOCKED: 'csp_blocked',
  DETACHED: 'detached',
  INJECT_FAILED: 'inject_failed',
  TIMEOUT: 'timeout',
  NO_TAB: 'no_tab',
  UNKNOWN: 'unknown',
};

export function okResult(data, method) {
  const r = { success: true, data };
  if (method) r.method = method;
  return r;
}

export function noReturn(method) {
  return {
    success: true,
    data: '__NO_RETURN__',
    _resultType: 'void',
    ...(method ? { method } : {}),
  };
}

// Success with a warning (e.g. execution may have been blocked / result not serializable).
export function okWarning(method, warning, data, resultType) {
  return {
    success: true,
    data: data === undefined ? null : data,
    _resultType: resultType || (data === undefined ? 'void' : typeof data),
    ...(method ? { method } : {}),
    warning,
  };
}

export function failResult(message, category, extra) {
  const r = { success: false, error: message, errorCategory: category || ErrorCategory.UNKNOWN };
  if (extra) Object.assign(r, extra);
  return r;
}
