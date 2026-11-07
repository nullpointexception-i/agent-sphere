import { PERM } from '@/constants/permissions';

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
    canManageModels: perms.includes(PERM.modelProviderRead),
    canManageInstances: perms.includes(PERM.instanceRead),
    canManageSuperAgent: perms.includes(PERM.superAgentRead),
    canManageIm: perms.includes(PERM.imConversationRead),
    canViewDigitalEmployee:
      perms.includes(PERM.superAgentRead) ||
      perms.includes(PERM.imConversationRead),
    canManageCapabilities: perms.some((p) => p.startsWith('capability:')),
    canManageMcp: perms.includes(PERM.capabilityMcpRead),
    canManageSkill: perms.includes(PERM.capabilitySkillRead),
    canManageCli: perms.includes(PERM.capabilityCliRead),
    canManageBuiltin: perms.includes(PERM.capabilityBuiltinRead),
    canViewDocuments: perms.includes(PERM.documentRead),
    canViewArtifacts:
      perms.includes(PERM.documentRead) || perms.includes(PERM.adminTasksRead),
    canManageCompletions: perms.includes(PERM.adminCompletionsRead),
    canManageTasks: perms.includes(PERM.adminTasksRead),
  };
}
