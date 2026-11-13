/** Requirement and project state values shared by the workspace. */
export const REQUIREMENT_STATUS = {
  DRAFT: 'DRAFT',
  REVIEWING: 'REVIEWING',
  APPROVED: 'APPROVED',
  IN_PROGRESS: 'IN_PROGRESS',
  DONE: 'DONE',
  REJECTED: 'REJECTED',
} as const;

export const REQUIREMENT_STATUS_LIST = [
  REQUIREMENT_STATUS.DRAFT,
  REQUIREMENT_STATUS.REVIEWING,
  REQUIREMENT_STATUS.APPROVED,
  REQUIREMENT_STATUS.IN_PROGRESS,
  REQUIREMENT_STATUS.DONE,
  REQUIREMENT_STATUS.REJECTED,
] as const;

export const PROJECT_STATUS = {
  ACTIVE: 'ACTIVE',
  ARCHIVED: 'ARCHIVED',
} as const;

export const PROJECT_MEMBER_ROLE = {
  OWNER: 'OWNER',
  MANAGER: 'MANAGER',
  MEMBER: 'MEMBER',
  VIEWER: 'VIEWER',
} as const;

/** Locale keys for project and requirement enum values shown in the UI. */
export const PROJECT_STATUS_META: Record<
  string,
  { color: string; labelKey: string }
> = {
  [PROJECT_STATUS.ACTIVE]: {
    color: 'green',
    labelKey: 'projects.status.active',
  },
  [PROJECT_STATUS.ARCHIVED]: {
    color: 'default',
    labelKey: 'projects.status.archived',
  },
};

export const REQUIREMENT_STATUS_META: Record<
  string,
  { color: string; labelKey: string }
> = {
  [REQUIREMENT_STATUS.DRAFT]: {
    color: '#8b86a0',
    labelKey: 'projects.requirementStatus.draft',
  },
  [REQUIREMENT_STATUS.REVIEWING]: {
    color: '#fa8c16',
    labelKey: 'projects.requirementStatus.reviewing',
  },
  [REQUIREMENT_STATUS.APPROVED]: {
    color: '#1677ff',
    labelKey: 'projects.requirementStatus.approved',
  },
  [REQUIREMENT_STATUS.IN_PROGRESS]: {
    color: '#722ed1',
    labelKey: 'projects.requirementStatus.inProgress',
  },
  [REQUIREMENT_STATUS.DONE]: {
    color: '#52c41a',
    labelKey: 'projects.requirementStatus.done',
  },
  [REQUIREMENT_STATUS.REJECTED]: {
    color: '#ff4d4f',
    labelKey: 'projects.requirementStatus.rejected',
  },
};

export const PROJECT_STATUS_LABEL_KEYS: Record<string, string> = {
  [PROJECT_STATUS.ACTIVE]: 'projects.status.active',
  [PROJECT_STATUS.ARCHIVED]: 'projects.status.archived',
};

export const REQUIREMENT_STATUS_LABEL_KEYS: Record<string, string> = {
  [REQUIREMENT_STATUS.DRAFT]: 'projects.requirementStatus.draft',
  [REQUIREMENT_STATUS.REVIEWING]: 'projects.requirementStatus.reviewing',
  [REQUIREMENT_STATUS.APPROVED]: 'projects.requirementStatus.approved',
  [REQUIREMENT_STATUS.IN_PROGRESS]: 'projects.requirementStatus.inProgress',
  [REQUIREMENT_STATUS.DONE]: 'projects.requirementStatus.done',
  [REQUIREMENT_STATUS.REJECTED]: 'projects.requirementStatus.rejected',
};

export const PROJECT_MEMBER_ROLE_LABEL_KEYS: Record<string, string> = {
  [PROJECT_MEMBER_ROLE.OWNER]: 'projects.memberRole.owner',
  [PROJECT_MEMBER_ROLE.MANAGER]: 'projects.memberRole.manager',
  [PROJECT_MEMBER_ROLE.MEMBER]: 'projects.memberRole.member',
  [PROJECT_MEMBER_ROLE.VIEWER]: 'projects.memberRole.viewer',
};
