import { getIntl } from '@umijs/max';

/** Workspace-local translation helper; ids live under the `workspace.` prefix. */
export function tw(
  id: string,
  defaultMessage: string,
  values?: Record<string, string | number>,
) {
  return getIntl().formatMessage(
    { id: `workspace.${id}`, defaultMessage },
    values,
  );
}
