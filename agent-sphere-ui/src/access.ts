export default function access(
  initialState:
    | { currentUser?: API.CurrentUser; permissions?: string[] }
    | undefined,
) {
  const { currentUser, permissions } = initialState ?? {};
  const perms = permissions ?? [];
  return {
    canAdmin:
      (currentUser && currentUser.access === 'admin') ||
      perms.some((p) => p.startsWith('admin:')),
    canManageModels: perms.includes('model:provider:read'),
    canManageInstances: perms.includes('instance:read'),
    canManageCapabilities: perms.some((p) => p.startsWith('capability:')),
    canManageMcp: perms.includes('capability:mcp:read'),
    canManageSkill: perms.includes('capability:skill:read'),
    canManageCli: perms.includes('capability:cli:read'),
    canManageBuiltin: perms.includes('capability:builtin:read'),
    canViewDocuments: perms.includes('document:read'),
  };
}
