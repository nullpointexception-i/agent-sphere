import { tw } from '../i18n';

/**
 * Resolves a stable API enum value through a locale key map. Unknown values
 * stay visible instead of disappearing, so new backend states are easy to spot
 * during UAT and can be added to the map afterwards.
 */
export function enumLabel(
  labelKeys: Record<string, string>,
  value?: string | null,
) {
  if (!value) return '';
  return tw(labelKeys[value] ?? value, value);
}
