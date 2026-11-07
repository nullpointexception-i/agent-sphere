import { describe, expect, it } from 'vitest';
import { allowsWidgetSession, businessTypeOf } from './instance';
import type { InstanceVO } from './types';

/**
 * Regression: the widget-side session gate only looks at the instance switch `allowWidgetSession`.
 *
 * <p>That backend column is `NOT NULL DEFAULT FALSE`, so null/missing must be treated as **denied** -
 * treating undefined as allowed would open up instances that should be denied on an older backend (one that does not return the field yet) or when the field is missing.
 *
 * <p>`businessType` is **no longer** the criterion, only a label for placeholder copy: an admin can turn the widget switch off for an ordinary
 * instance with no business type, and it must still be gated.
 */
function instance(overrides: Partial<InstanceVO> = {}): InstanceVO {
  return {
    id: 1,
    name: 'Headhunter Assist',
    description: '',
    systemPrompt: '',
    image: '',
    status: 'ENABLED',
    createdAt: '',
    createdBy: 'demo001',
    updatedAt: '',
    ...overrides,
  };
}

describe('allowsWidgetSession', () => {
  it('开关为 true 时放行', () => {
    expect(allowsWidgetSession(instance({ allowWidgetSession: true }))).toBe(true);
  });

  it('开关为 false 时禁止', () => {
    expect(allowsWidgetSession(instance({ allowWidgetSession: false }))).toBe(false);
  });

  it('字段缺失 / null 按禁止处理（对齐列默认 FALSE）', () => {
    expect(allowsWidgetSession(instance())).toBe(false);
    expect(allowsWidgetSession(instance({ allowWidgetSession: null }))).toBe(false);
  });

  it('实例本身为空时按禁止处理', () => {
    expect(allowsWidgetSession(null)).toBe(false);
    expect(allowsWidgetSession(undefined)).toBe(false);
  });

  it('没有业务域但开关关闭的实例同样被禁止', () => {
    // The gate only looks at the switch: this is the key behavioral difference from the old businessType criterion
    expect(allowsWidgetSession(instance({ businessType: null, allowWidgetSession: false }))).toBe(false);
  });
});

describe('businessTypeOf', () => {
  it('返回去空白后的业务域，供占位文案展示', () => {
    expect(businessTypeOf(instance({ businessType: '  sourcing  ' }))).toBe('sourcing');
  });

  it('无业务域时返回空串', () => {
    expect(businessTypeOf(instance({ businessType: null }))).toBe('');
    expect(businessTypeOf(null)).toBe('');
  });
});
