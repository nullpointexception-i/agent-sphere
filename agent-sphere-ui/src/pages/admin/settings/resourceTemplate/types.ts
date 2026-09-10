export type TemplateItemType =
  | 'model_provider'
  | 'api_key'
  | 'model_route'
  | 'completions'
  | 'instance'
  | 'mcp'
  | 'skill'
  | 'document';

export const TEMPLATE_TYPES: TemplateItemType[] = [
  'model_provider',
  'api_key',
  'model_route',
  'completions',
  'instance',
  'mcp',
  'skill',
  'document',
];

export interface TemplateItem {
  type: string;
  [key: string]: unknown;
}

/** 类型在模板项中的“主名称”字段（列表展示用） */
export const TYPE_NAME_FIELD: Record<string, string> = {
  model_provider: 'name',
  api_key: 'alias',
  model_route: 'modelName',
  completions: 'name',
  instance: 'name',
  mcp: 'name',
  skill: 'name',
  document: 'title',
};

export interface LabelSpec {
  id: string;
  def?: string;
}

export type WidgetType =
  | 'input'
  | 'textarea'
  | 'number'
  | 'switch'
  | 'provider-select'
  | 'route-select'
  | 'skill-definition';

export interface FieldDef {
  name: string;
  label: LabelSpec;
  widget: WidgetType;
  required?: boolean;
  rows?: number;
  maxLength?: number;
  default?: unknown;
  placeholder?: string;
  hint?: LabelSpec;
  json?: boolean;
}

export interface TypeSchema {
  nameField: string;
  fields: FieldDef[];
}

/** 各类型 descriptor 表单结构（字段布局复刻现有实体新增表单，值写回模板 descriptor） */
export const TYPE_SCHEMAS: Record<TemplateItemType, TypeSchema> = {
  model_provider: {
    nameField: 'name',
    fields: [
      {
        name: 'name',
        label: { id: 'pages.form.name' },
        widget: 'input',
        required: true,
        maxLength: 64,
        hint: { id: 'pages.hint.name' },
      },
      {
        name: 'baseUrl',
        label: { id: 'pages.models.baseUrl' },
        widget: 'input',
        required: true,
        maxLength: 500,
        hint: { id: 'pages.hint.url' },
      },
    ],
  },
  api_key: {
    nameField: 'alias',
    fields: [
      {
        name: 'provider',
        label: {
          id: 'pages.admin.settings.template.label.provider',
          def: 'Provider',
        },
        widget: 'provider-select',
        required: true,
      },
      {
        name: 'alias',
        label: { id: 'pages.table.alias' },
        widget: 'input',
        maxLength: 255,
        default: 'default-key',
      },
    ],
  },
  model_route: {
    nameField: 'modelName',
    fields: [
      {
        name: 'provider',
        label: {
          id: 'pages.admin.settings.template.label.provider',
          def: 'Provider',
        },
        widget: 'provider-select',
        required: true,
      },
      {
        name: 'modelName',
        label: { id: 'pages.table.modelName' },
        widget: 'input',
        required: true,
        maxLength: 64,
        hint: { id: 'pages.hint.name' },
      },
      {
        name: 'company',
        label: { id: 'pages.models.company', def: 'Company' },
        widget: 'input',
        maxLength: 64,
      },
      {
        name: 'weight',
        label: { id: 'pages.table.weight' },
        widget: 'number',
        default: 100,
      },
      {
        name: 'supportsAttachment',
        label: { id: 'pages.models.supportsAttachment', def: 'Attachment' },
        widget: 'switch',
        default: false,
      },
    ],
  },
  completions: {
    nameField: 'name',
    fields: [
      {
        name: 'name',
        label: { id: 'pages.form.name' },
        widget: 'input',
        required: true,
        maxLength: 200,
        hint: { id: 'pages.hint.name' },
      },
      {
        name: 'description',
        label: { id: 'pages.form.description' },
        widget: 'textarea',
        rows: 2,
        maxLength: 2000,
        hint: { id: 'pages.hint.description' },
      },
      {
        name: 'businessType',
        label: { id: 'pages.instances.businessType', def: '业务域' },
        widget: 'input',
        maxLength: 64,
        placeholder: 'sourcing',
      },
      {
        name: 'route',
        label: {
          id: 'pages.admin.settings.template.label.route',
          def: '模型路由',
        },
        widget: 'route-select',
      },
      {
        name: 'config',
        label: { id: 'pages.admin.completions.config', def: 'Config (JSON)' },
        widget: 'textarea',
        rows: 2,
        json: true,
        placeholder: '{"temperature":0.1,"thinking":false}',
      },
      {
        name: 'inputSchema',
        label: {
          id: 'pages.admin.completions.inputSchema',
          def: 'Input Schema (JSON)',
        },
        widget: 'textarea',
        rows: 4,
        json: true,
        placeholder: '{"type":"object","properties":{}}',
      },
      {
        name: 'outputSchema',
        label: {
          id: 'pages.admin.completions.outputSchema',
          def: 'Output Schema (JSON)',
        },
        widget: 'textarea',
        rows: 4,
        json: true,
        placeholder: '{"type":"object","properties":{}}',
      },
      {
        name: 'promptSystem',
        label: {
          id: 'pages.admin.settings.template.label.promptSystem',
          def: 'System Prompt',
        },
        widget: 'textarea',
        rows: 4,
      },
      {
        name: 'promptUser',
        label: {
          id: 'pages.admin.settings.template.label.promptUser',
          def: 'User Prompt',
        },
        widget: 'textarea',
        rows: 4,
      },
    ],
  },
  instance: {
    nameField: 'name',
    fields: [
      {
        name: 'name',
        label: { id: 'pages.form.name' },
        widget: 'input',
        required: true,
        maxLength: 64,
        hint: { id: 'pages.hint.name' },
      },
      {
        name: 'description',
        label: { id: 'pages.form.description' },
        widget: 'textarea',
        rows: 2,
        maxLength: 255,
        hint: { id: 'pages.hint.description' },
      },
      {
        name: 'systemPrompt',
        label: { id: 'pages.instances.systemPrompt' },
        widget: 'textarea',
        rows: 4,
        maxLength: 5000,
        hint: { id: 'pages.hint.text' },
      },
      {
        name: 'businessType',
        label: { id: 'pages.instances.businessType', def: '业务域' },
        widget: 'input',
        maxLength: 64,
        placeholder: 'sourcing',
      },
      {
        name: 'route',
        label: {
          id: 'pages.admin.settings.template.label.route',
          def: '模型路由',
        },
        widget: 'route-select',
      },
      {
        name: 'config',
        label: { id: 'pages.instances.config', def: '高级配置 (JSON)' },
        widget: 'textarea',
        rows: 4,
        json: true,
        placeholder: '{"temperature":0.2,"thinking":true,"max_tokens":4096}',
      },
    ],
  },
  mcp: {
    nameField: 'name',
    fields: [
      {
        name: 'name',
        label: { id: 'pages.form.name' },
        widget: 'input',
        required: true,
        maxLength: 64,
        hint: { id: 'pages.hint.name' },
      },
      {
        name: 'serverUrl',
        label: { id: 'pages.capabilities.serverUrl' },
        widget: 'input',
        required: true,
        maxLength: 500,
        hint: { id: 'pages.hint.url' },
      },
      {
        name: 'serverType',
        label: {
          id: 'pages.admin.settings.template.label.serverType',
          def: 'Server Type',
        },
        widget: 'input',
        required: true,
        maxLength: 64,
        default: 'stdio',
      },
    ],
  },
  skill: {
    nameField: 'name',
    fields: [
      {
        name: 'name',
        label: { id: 'pages.form.name' },
        widget: 'input',
        required: true,
        maxLength: 64,
        hint: { id: 'pages.hint.name' },
      },
      {
        name: 'description',
        label: { id: 'pages.form.description' },
        widget: 'textarea',
        rows: 2,
        maxLength: 255,
        hint: { id: 'pages.hint.description' },
      },
      {
        name: 'definition',
        label: {
          id: 'pages.admin.settings.template.label.definition',
          def: 'Definition',
        },
        widget: 'skill-definition',
      },
    ],
  },
  document: {
    nameField: 'title',
    fields: [
      {
        name: 'title',
        label: {
          id: 'pages.admin.settings.template.label.title',
          def: 'Title',
        },
        widget: 'input',
        required: true,
        maxLength: 255,
      },
      {
        name: 'contentType',
        label: {
          id: 'pages.admin.settings.template.label.contentType',
          def: 'Content Type',
        },
        widget: 'input',
        maxLength: 64,
        default: 'text',
      },
      {
        name: 'content',
        label: {
          id: 'pages.admin.settings.template.label.content',
          def: 'Content',
        },
        widget: 'textarea',
        rows: 6,
      },
    ],
  },
};

/** 类型中文展示标签（i18n key, 带默认文案） */
export const TYPE_LABELS: Record<string, LabelSpec> = {
  model_provider: {
    id: 'pages.admin.settings.template.type.modelProvider',
    def: '模型供应商',
  },
  api_key: { id: 'pages.admin.settings.template.type.apiKey', def: 'API Key' },
  model_route: {
    id: 'pages.admin.settings.template.type.modelRoute',
    def: '模型路由',
  },
  completions: {
    id: 'pages.admin.settings.template.type.completions',
    def: 'Completions 能力',
  },
  instance: {
    id: 'pages.admin.settings.template.type.instance',
    def: 'Agent 实例',
  },
  mcp: { id: 'pages.admin.settings.template.type.mcp', def: 'MCP 服务' },
  skill: { id: 'pages.admin.settings.template.type.skill', def: '技能' },
  document: { id: 'pages.admin.settings.template.type.document', def: '文档' },
};
