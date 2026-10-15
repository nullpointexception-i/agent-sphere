import { describe, expect, it } from 'vitest';
import { allowsWidgetSession, businessTypeOf } from './instance';
import type { InstanceVO } from './types';

/**
 * 回归：widget 端会话门控只看实例开关 `allowWidgetSession`。
 *
 * <p>后端该列是 `NOT NULL DEFAULT FALSE`，所以空值/缺省必须按**禁止**处理 ——
 * 反过来把 undefined 当允许，会在旧后端（还没返回该字段）或字段缺失时放开本应禁止的实例。
 *
 * <p>业务域 `businessType` **不再**是判据，只作为占位文案的标签：管理员可以把一个没有业务域的
 * 普通实例的 widget 开关关掉，那时它同样必须被门控。
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
    // 门控只看开关：这是从 businessType 判据切换过来的关键行为差异
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
