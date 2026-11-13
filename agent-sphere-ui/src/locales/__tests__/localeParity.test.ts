import {
  APPLICATION_OUTPUT_LOCALE_LABEL_KEYS,
  APPLICATION_RUN_MODE,
  APPLICATION_RUN_MODE_LABEL_KEYS,
  APPLICATION_RUN_STATUS,
  APPLICATION_RUN_STATUS_LABEL_KEYS,
  APPLICATION_SCENE_TYPE,
  APPLICATION_SCENE_TYPE_LABEL_KEYS,
  APPLICATION_TRIGGER_TYPE,
  APPLICATION_TRIGGER_TYPE_LABEL_KEYS,
  APPLICATION_TYPE,
  APPLICATION_TYPE_LABEL_KEYS,
  ENTITY_BINDING_ROLE,
  ENTITY_BINDING_ROLE_LABEL_KEYS,
} from '@/constants/application';
import {
  PROJECT_MEMBER_ROLE,
  PROJECT_MEMBER_ROLE_LABEL_KEYS,
  PROJECT_STATUS,
  PROJECT_STATUS_LABEL_KEYS,
  REQUIREMENT_STATUS_LABEL_KEYS,
  REQUIREMENT_STATUS_LIST,
} from '@/constants/project';
import en from '../en-US';
import zh from '../zh-CN';

function flattenKeys(value: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(value).flatMap(([key, entry]) => {
    const path = prefix ? `${prefix}.${key}` : key;
    if (entry && typeof entry === 'object' && !Array.isArray(entry)) {
      return flattenKeys(entry as Record<string, unknown>, path);
    }
    return [path];
  });
}

describe('locale parity', () => {
  it('keeps zh-CN and en-US keys aligned', () => {
    const zhKeys = flattenKeys(zh).sort();
    const enKeys = flattenKeys(en).sort();

    expect(enKeys).toEqual(zhKeys);
  });

  it('covers the workspace apps namespace in both locales', () => {
    const expected = [
      'workspace.nav.projects',
      'workspace.nav.apps',
      'workspace.apps.title',
      'workspace.apps.subtitle',
      'workspace.apps.empty',
      'workspace.apps.emptyHint',
    ];

    for (const key of expected) {
      expect(zh).toHaveProperty([key]);
      expect(en).toHaveProperty([key]);
    }
  });

  it('localizes every application enum label in both locales', () => {
    const labelMaps = {
      applicationType: APPLICATION_TYPE_LABEL_KEYS,
      sceneType: APPLICATION_SCENE_TYPE_LABEL_KEYS,
      runStatus: APPLICATION_RUN_STATUS_LABEL_KEYS,
      runMode: APPLICATION_RUN_MODE_LABEL_KEYS,
      triggerType: APPLICATION_TRIGGER_TYPE_LABEL_KEYS,
      outputLocale: APPLICATION_OUTPUT_LOCALE_LABEL_KEYS,
    };

    for (const labelMap of Object.values(labelMaps)) {
      for (const key of Object.values(labelMap)) {
        expect(zh).toHaveProperty([`workspace.${key}`]);
        expect(en).toHaveProperty([`workspace.${key}`]);
      }
    }
  });

  it('localizes every project enum label in both locales', () => {
    const labelMaps = {
      projectStatus: PROJECT_STATUS_LABEL_KEYS,
      requirementStatus: REQUIREMENT_STATUS_LABEL_KEYS,
      memberRole: PROJECT_MEMBER_ROLE_LABEL_KEYS,
      bindingRole: ENTITY_BINDING_ROLE_LABEL_KEYS,
    };

    for (const labelMap of Object.values(labelMaps)) {
      for (const key of Object.values(labelMap)) {
        expect(zh).toHaveProperty([`workspace.${key}`]);
        expect(en).toHaveProperty([`workspace.${key}`]);
      }
    }
  });

  it('covers every application enum value with a label key', () => {
    for (const value of Object.values(APPLICATION_TYPE)) {
      expect(APPLICATION_TYPE_LABEL_KEYS[value]).toBeDefined();
    }
    for (const value of Object.values(APPLICATION_SCENE_TYPE)) {
      expect(APPLICATION_SCENE_TYPE_LABEL_KEYS[value]).toBeDefined();
    }
    for (const value of Object.values(APPLICATION_RUN_STATUS)) {
      expect(APPLICATION_RUN_STATUS_LABEL_KEYS[value]).toBeDefined();
    }
    for (const value of Object.values(APPLICATION_RUN_MODE)) {
      expect(APPLICATION_RUN_MODE_LABEL_KEYS[value]).toBeDefined();
    }
    for (const value of Object.values(APPLICATION_TRIGGER_TYPE)) {
      expect(APPLICATION_TRIGGER_TYPE_LABEL_KEYS[value]).toBeDefined();
    }
  });

  it('covers every project enum value with a label key', () => {
    for (const value of Object.values(PROJECT_STATUS)) {
      expect(PROJECT_STATUS_LABEL_KEYS[value]).toBeDefined();
    }
    for (const value of REQUIREMENT_STATUS_LIST) {
      expect(REQUIREMENT_STATUS_LABEL_KEYS[value]).toBeDefined();
    }
    for (const value of Object.values(PROJECT_MEMBER_ROLE)) {
      expect(PROJECT_MEMBER_ROLE_LABEL_KEYS[value]).toBeDefined();
    }
    for (const value of Object.values(ENTITY_BINDING_ROLE)) {
      expect(ENTITY_BINDING_ROLE_LABEL_KEYS[value]).toBeDefined();
    }
  });
});
