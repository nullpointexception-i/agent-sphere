/**
 * One human label shared by the console and the workspace.
 * External identity-provider users show provider@external-username (for example bole@elvin);
 * local users keep the existing nickname / English name / username fallback.
 */
export function userDisplayLabel(user: any, fallback = 'User'): string {
  if (user?.ssoProviderCode && user?.ssoSubject) {
    return `${user.ssoProviderCode}@${user.ssoSubject}`;
  }
  return (
    user?.displayName ||
    user?.englishName ||
    user?.username ||
    user?.name ||
    fallback
  );
}
