export const ErrorCategory = {
  NOT_FOUND: 'not_found',
  // The target exists but is currently not actionable (disabled / hidden / detached from the document).
  // Kept separate from not_found: the former means "wait for it to become usable or switch targets", the latter means "not found, look elsewhere".
  // (The tool description's Rules already declared this category, but nothing actually produced it before.)
  NOT_INTERACTABLE: 'not_interactable',
  CSP_BLOCKED: 'csp_blocked',
  DETACHED: 'detached',
  INJECT_FAILED: 'inject_failed',
  TIMEOUT: 'timeout',
  NO_TAB: 'no_tab',
  WRONG_SITE: 'wrong_site',
  AMBIGUOUS: 'ambiguous',
  // The click/hover coordinate hit a different element (sticky header, overlay, transparent mask).
  // Kept distinct from not_found: change the selector/scope or close the overlay first, rather than waiting for the element to appear.
  OCCLUDED: 'occluded',
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
