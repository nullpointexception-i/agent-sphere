import type { InstanceVO } from './types';

/**
 * Whether this instance allows starting/continuing a session from the **widget**.
 *
 * <p>The switch is driven by the instance config `allowWidgetSession` (`agent_instance.allow_widget_session`).
 * The server enforces the same rule in both `createSession` and `chat` (`SessionSurfacePolicy`);
 * this function only keeps the UI consistent with it.
 *
 * <p>Only a strict `true` counts: the backend column is `NOT NULL DEFAULT FALSE`, so null/missing must be treated as **denied**.
 * Treating undefined as allowed would open up instances that should be denied on an older backend or when the field is missing.
 */
export function allowsWidgetSession(instance: InstanceVO | null | undefined): boolean {
  return instance?.allowWidgetSession === true;
}

/**
 * The instance's business domain (trimmed), used only for the placeholder copy `--{businessType} auto session--`.
 *
 * <p>Note that it is **no longer** a gating criterion - gating only looks at `allowWidgetSession`. The business domain is just a label shown to users;
 * the two may disagree (for example an admin turns off the widget switch for an ordinary instance: it has no business domain yet is still denied).
 */
export function businessTypeOf(instance: InstanceVO | null | undefined): string {
  return instance?.businessType?.trim() ?? '';
}
