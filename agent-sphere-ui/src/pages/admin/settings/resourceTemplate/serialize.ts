import {
  TEMPLATE_TYPES,
  type TemplateItem,
  type TemplateItemType,
  TYPE_NAME_FIELD,
  TYPE_SCHEMAS,
} from './types';

/** Parse configuration text into a template item array (empty string means an empty template). Throws Error on parse failure. */
export function parseTemplate(text: string): TemplateItem[] {
  const trimmed = (text ?? '').trim();
  if (!trimmed) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    throw new Error('Failed to parse JSON');
  }
  if (!Array.isArray(parsed)) {
    throw new Error('Configuration must be a JSON array');
  }
  return parsed
    .filter((item) => item && typeof item === 'object')
    .map((item) => ({ ...(item as object) })) as TemplateItem[];
}

/** Serialize a template item array into formatted JSON text. */
export function stringifyTemplate(items: TemplateItem[]): string {
  return JSON.stringify(items, null, 2);
}

/** Value of the item's primary name field (api_key uses alias, document uses title, etc.). */
export function itemName(item: TemplateItem): string {
  const field = TYPE_NAME_FIELD[item.type];
  const value = field ? item[field] : undefined;
  return typeof value === 'string' ? value : '';
}

/** Aggregated counts per type, used for list display. */
export function summarizeItems(items: TemplateItem[]): {
  type: string;
  count: number;
}[] {
  const map = new Map<string, number>();
  items.forEach((item) => {
    map.set(item.type, (map.get(item.type) ?? 0) + 1);
  });
  return Array.from(map.entries()).map(([type, count]) => ({ type, count }));
}

/** Existing provider names / route names in the template (for dropdown references). */
export function providerNames(items: TemplateItem[]): string[] {
  return namesOf(items, 'model_provider', 'name');
}

export function routeNames(items: TemplateItem[]): string[] {
  return namesOf(items, 'model_route', 'modelName');
}

function namesOf(items: TemplateItem[], type: string, field: string): string[] {
  return Array.from(
    new Set(
      items
        .filter((item) => item.type === type)
        .map((item) => item[field])
        .filter((v): v is string => typeof v === 'string' && v.length > 0),
    ),
  );
}

export interface ItemIssue {
  item: TemplateItem;
  message: string;
}

/** Collect template-level issues (unknown type / missing primary name / dangling provider or route reference). */
export function collectIssues(items: TemplateItem[]): ItemIssue[] {
  const issues: ItemIssue[] = [];
  const providers = providerNames(items);
  const routes = routeNames(items);
  items.forEach((item) => {
    if (!TEMPLATE_TYPES.includes(item.type as TemplateItemType)) {
      issues.push({ item, message: `Unknown type: ${item.type}` });
      return;
    }
    if (!itemName(item)) {
      issues.push({
        item,
        message: `Missing name field (${TYPE_NAME_FIELD[item.type]})`,
      });
    }
    const provider = item.provider;
    if (
      typeof provider === 'string' &&
      provider &&
      !providers.includes(provider)
    ) {
      issues.push({
        item,
        message: `Referenced model provider does not exist: ${provider}`,
      });
    }
    const route = item.route;
    if (typeof route === 'string' && route && !routes.includes(route)) {
      issues.push({
        item,
        message: `Referenced model route does not exist: ${route}`,
      });
    }
  });
  return issues;
}

/** Normalize form values into a template item (drop empty strings; keep numbers/booleans as is). */
export function normalizeItem(
  type: string,
  values: Record<string, unknown>,
): TemplateItem {
  const item: TemplateItem = { type };
  Object.entries(values ?? {}).forEach(([key, value]) => {
    if (value === undefined || value === null) return;
    if (typeof value === 'string') {
      if (value.trim()) item[key] = value;
      return;
    }
    item[key] = value;
  });
  if (typeof item.weight === 'string' && item.weight.trim() !== '') {
    item.weight = Number(item.weight);
  }
  return item;
}

export function schemaOf(type: string) {
  return TYPE_SCHEMAS[type as TemplateItemType];
}
