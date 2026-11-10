import { getIntl } from '@umijs/max';

/** Desktop-page translation helper; ids live under the `desktop.` prefix. */
export function tw(
  id: string,
  defaultMessage: string,
  values?: Record<string, string | number>,
) {
  return getIntl().formatMessage(
    { id: `desktop.${id}`, defaultMessage },
    values,
  );
}
