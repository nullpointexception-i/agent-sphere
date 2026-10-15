import type { InstanceVO } from './types';

/**
 * 该实例是否允许从 **widget** 端发起/继续会话。
 *
 * <p>开关由实例配置 `allowWidgetSession`（`agent_instance.allow_widget_session`）决定，
 * 服务端在 `createSession` / `chat` 两处用同一规则强校验（`SessionSurfacePolicy`），
 * 这里只负责让 UI 与之保持一致。
 *
 * <p>只认严格 `true`：后端列 `NOT NULL DEFAULT FALSE`，空值/缺省都必须按**禁止**处理。
 * 反过来把 undefined 当允许会在旧后端/字段缺失时放开本应禁止的实例。
 */
export function allowsWidgetSession(instance: InstanceVO | null | undefined): boolean {
  return instance?.allowWidgetSession === true;
}

/**
 * 实例的业务域（去空白），仅用于占位文案 `--{businessType}自动会话--`。
 *
 * <p>注意它**不再**是门控判据 —— 门控只看 `allowWidgetSession`。业务域只是给用户看的标签，
 * 两者可以不一致（例如管理员把某个普通实例的 widget 开关关掉，它没有业务域但依然禁止）。
 */
export function businessTypeOf(instance: InstanceVO | null | undefined): string {
  return instance?.businessType?.trim() ?? '';
}
