import {
  TEMPLATE_TYPES,
  type TemplateItem,
  type TemplateItemType,
  TYPE_NAME_FIELD,
  TYPE_SCHEMAS,
} from './types';

/** 将配置文本解析为模板项数组（空串视为空模板）。解析失败抛 Error。 */
export function parseTemplate(text: string): TemplateItem[] {
  const trimmed = (text ?? '').trim();
  if (!trimmed) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    throw new Error('JSON 解析失败');
  }
  if (!Array.isArray(parsed)) {
    throw new Error('配置值必须是 JSON 数组');
  }
  return parsed
    .filter((item) => item && typeof item === 'object')
    .map((item) => ({ ...(item as object) })) as TemplateItem[];
}

/** 将模板项数组序列化为格式化 JSON 文本。 */
export function stringifyTemplate(items: TemplateItem[]): string {
  return JSON.stringify(items, null, 2);
}

/** 项的主名称字段值（api_key 用 alias、document 用 title 等）。 */
export function itemName(item: TemplateItem): string {
  const field = TYPE_NAME_FIELD[item.type];
  const value = field ? item[field] : undefined;
  return typeof value === 'string' ? value : '';
}

/** 类型聚合统计，用于列表展示。 */
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

/** 模板内已有的 provider 名 / route 名（供下拉引用）。 */
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

/** 收集模板级问题（未知类型 / 主名称缺失 / provider·route 悬空引用）。 */
export function collectIssues(items: TemplateItem[]): ItemIssue[] {
  const issues: ItemIssue[] = [];
  const providers = providerNames(items);
  const routes = routeNames(items);
  items.forEach((item) => {
    if (!TEMPLATE_TYPES.includes(item.type as TemplateItemType)) {
      issues.push({ item, message: `未知类型: ${item.type}` });
      return;
    }
    if (!itemName(item)) {
      issues.push({
        item,
        message: `缺少名称字段(${TYPE_NAME_FIELD[item.type]})`,
      });
    }
    const provider = item.provider;
    if (
      typeof provider === 'string' &&
      provider &&
      !providers.includes(provider)
    ) {
      issues.push({ item, message: `引用的模型供应商不存在: ${provider}` });
    }
    const route = item.route;
    if (typeof route === 'string' && route && !routes.includes(route)) {
      issues.push({ item, message: `引用的模型路由不存在: ${route}` });
    }
  });
  return issues;
}

/** 将表单 values 规整为模板项（剔除空字符串；数字/布尔原样保留）。 */
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
