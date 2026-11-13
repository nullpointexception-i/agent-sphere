/** Application and entity values shared by the workspace pages. */
export const APPLICATION_TYPE = {
  SYSTEM: 'SYSTEM',
  CUSTOM: 'CUSTOM',
} as const;

export const APPLICATION_SCENE_TYPE = {
  QA: 'QA',
  PERIODIC: 'PERIODIC',
  PAGE: 'PAGE',
  ACTION: 'ACTION',
} as const;

export const APPLICATION_TRIGGER_TYPE = {
  MANUAL: 'MANUAL',
  CRON: 'CRON',
  EVENT: 'EVENT',
  PAGE: 'PAGE',
} as const;

export const APPLICATION_RUN_MODE = {
  READ_ONLY: 'READ_ONLY',
  WRITE: 'WRITE',
} as const;

export const APPLICATION_RUN_STATUS = {
  QUEUED: 'QUEUED',
  RUNNING: 'RUNNING',
  WAITING_APPROVAL: 'WAITING_APPROVAL',
  SUCCEEDED: 'SUCCEEDED',
  FAILED: 'FAILED',
  CANCELLED: 'CANCELLED',
} as const;

/** Locale keys for enum values rendered in the workspace app pages. */
export const APPLICATION_TYPE_LABEL_KEYS: Record<string, string> = {
  [APPLICATION_TYPE.SYSTEM]: 'apps.type.system',
  [APPLICATION_TYPE.CUSTOM]: 'apps.type.custom',
};

export const APPLICATION_SCENE_TYPE_LABEL_KEYS: Record<string, string> = {
  [APPLICATION_SCENE_TYPE.QA]: 'apps.sceneType.qa',
  [APPLICATION_SCENE_TYPE.PERIODIC]: 'apps.sceneType.periodic',
  [APPLICATION_SCENE_TYPE.PAGE]: 'apps.sceneType.page',
  [APPLICATION_SCENE_TYPE.ACTION]: 'apps.sceneType.action',
};

export const APPLICATION_RUN_STATUS_LABEL_KEYS: Record<string, string> = {
  [APPLICATION_RUN_STATUS.QUEUED]: 'apps.runStatus.queued',
  [APPLICATION_RUN_STATUS.RUNNING]: 'apps.runStatus.running',
  [APPLICATION_RUN_STATUS.WAITING_APPROVAL]: 'apps.runStatus.waitingApproval',
  [APPLICATION_RUN_STATUS.SUCCEEDED]: 'apps.runStatus.succeeded',
  [APPLICATION_RUN_STATUS.FAILED]: 'apps.runStatus.failed',
  [APPLICATION_RUN_STATUS.CANCELLED]: 'apps.runStatus.cancelled',
};

export const APPLICATION_RUN_MODE_LABEL_KEYS: Record<string, string> = {
  [APPLICATION_RUN_MODE.READ_ONLY]: 'apps.runMode.readOnly',
  [APPLICATION_RUN_MODE.WRITE]: 'apps.runMode.write',
};

export const APPLICATION_TRIGGER_TYPE_LABEL_KEYS: Record<string, string> = {
  [APPLICATION_TRIGGER_TYPE.MANUAL]: 'apps.triggerType.manual',
  [APPLICATION_TRIGGER_TYPE.CRON]: 'apps.triggerType.cron',
  [APPLICATION_TRIGGER_TYPE.EVENT]: 'apps.triggerType.event',
  [APPLICATION_TRIGGER_TYPE.PAGE]: 'apps.triggerType.page',
};

export const APPLICATION_OUTPUT_LOCALE_LABEL_KEYS: Record<string, string> = {
  'zh-CN': 'apps.outputLocale.zhCN',
  'en-US': 'apps.outputLocale.enUS',
};

export const ENTITY_TYPE = {
  APPLICATION: 'APPLICATION',
  APPLICATION_SCENE: 'APPLICATION_SCENE',
  PROJECT: 'PROJECT',
  REQUIREMENT: 'REQUIREMENT',
} as const;

export const ENTITY_BINDING_ROLE = {
  PRIMARY: 'PRIMARY',
  ASSISTANT: 'ASSISTANT',
  OBSERVER: 'OBSERVER',
} as const;

export const ENTITY_BINDING_ROLE_LABEL_KEYS: Record<string, string> = {
  [ENTITY_BINDING_ROLE.PRIMARY]: 'projects.bindingRole.primary',
  [ENTITY_BINDING_ROLE.ASSISTANT]: 'projects.bindingRole.assistant',
  [ENTITY_BINDING_ROLE.OBSERVER]: 'projects.bindingRole.observer',
};

export const ENTITY_CONVERSATION_PURPOSE = {
  CHAT: 'CHAT',
  DISCUSSION: 'DISCUSSION',
  RUN: 'RUN',
} as const;

/** Deep-link identity of the built-in project-management application. */
export const SYSTEM_PROJECTS_APP_KEY = 'system-projects';

/** Page schema data sources supported by the controlled renderer. */
export const SCHEMA_DATA_SOURCE = {
  APPLICATION_RUNS: 'APPLICATION_RUNS',
} as const;
