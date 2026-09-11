import { QuestionCircleOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import {
  App,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Tooltip,
} from 'antd';
import { useEffect } from 'react';
import SkillMarkdownEditor from '@/pages/capabilities/skill/components/SkillMarkdownEditor';
import { labelWithRule } from '@/utils/labelWithRule';
import { normalizeItem } from './serialize';
import {
  type FieldDef,
  type LabelSpec,
  type TemplateItem,
  type TemplateItemType,
  TYPE_LABELS,
  TYPE_SCHEMAS,
  type TypeSchema,
} from './types';

const EMPTY_PARAMETERS = { type: 'object', properties: {} };

interface Props {
  open: boolean;
  type: TemplateItemType;
  initial?: TemplateItem | null;
  providers: string[];
  routes: string[];
  onCancel: () => void;
  onSubmit: (item: TemplateItem) => void;
}

function txt(spec: LabelSpec, intl: ReturnType<typeof useIntl>): string {
  return intl.formatMessage({
    id: spec.id,
    defaultMessage: spec.def ?? spec.id,
  });
}

function jsonRule(intl: ReturnType<typeof useIntl>) {
  return {
    validator: (_: unknown, value: string) => {
      if (!value || value.trim() === '') return Promise.resolve();
      try {
        JSON.parse(value);
        return Promise.resolve();
      } catch {
        return Promise.reject(
          new Error(
            intl.formatMessage({
              id: 'pages.admin.completions.invalidJson',
              defaultMessage: '无效 JSON',
            }),
          ),
        );
      }
    },
  };
}

function definitionToForm(def?: string): {
  promptTemplate: string;
  parameters: string;
} {
  if (!def) return { promptTemplate: '', parameters: '' };
  try {
    const raw = def.replace(/^```json\s*/i, '').replace(/```\s*$/, '');
    const obj = JSON.parse(raw);
    return {
      promptTemplate: obj.promptTemplate || obj.prompt || '',
      parameters: obj.parameters ? JSON.stringify(obj.parameters, null, 2) : '',
    };
  } catch {
    return { promptTemplate: '', parameters: '' };
  }
}

function definitionFromForm(values: {
  promptTemplate: string;
  parameters?: string;
}): string {
  let parameters: Record<string, unknown> = EMPTY_PARAMETERS;
  if (values.parameters?.trim()) {
    try {
      const parsed: unknown = JSON.parse(values.parameters);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        parameters = parsed as Record<string, unknown>;
      }
    } catch {
      throw new Error('parameters 不是合法 JSON');
    }
  }
  return JSON.stringify(
    {
      version: 1,
      parameters,
      promptTemplate: values.promptTemplate,
    },
    null,
    2,
  );
}

function FieldRenderer({
  field,
  providers,
  routes,
  intl,
}: {
  field: FieldDef;
  providers: string[];
  routes: string[];
  intl: ReturnType<typeof useIntl>;
}) {
  const label = field.hint
    ? labelWithRule(txt(field.label, intl), txt(field.hint, intl))
    : txt(field.label, intl);
  const rules: any[] = [...(field.required ? [{ required: true }] : [])];
  if (field.json) rules.push(jsonRule(intl));

  const sharedLabel = (
    <Space size={4}>
      {label}
      {field.json && (
        <Tooltip
          title={intl.formatMessage({
            id: 'pages.admin.settings.template.jsonRequired',
            defaultMessage: '必须是合法 JSON 字符串；留空可保存',
          })}
        >
          <QuestionCircleOutlined
            style={{ color: '#999', cursor: 'pointer' }}
          />
        </Tooltip>
      )}
    </Space>
  );

  switch (field.widget) {
    case 'number':
      return (
        <Form.Item
          name={field.name}
          label={label}
          initialValue={field.default}
          rules={rules}
        >
          <InputNumber
            min={0}
            style={{ width: '100%' }}
            placeholder={field.placeholder}
          />
        </Form.Item>
      );
    case 'switch':
      return (
        <Form.Item
          name={field.name}
          label={label}
          valuePropName="checked"
          initialValue={field.default}
          rules={rules}
        >
          <Switch />
        </Form.Item>
      );
    case 'textarea':
      return (
        <Form.Item name={field.name} label={sharedLabel} rules={rules}>
          <Input.TextArea
            rows={field.rows ?? 3}
            maxLength={field.maxLength}
            placeholder={field.placeholder}
          />
        </Form.Item>
      );
    case 'provider-select':
      return (
        <Form.Item name={field.name} label={label} rules={rules}>
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={intl.formatMessage({
              id: 'pages.admin.settings.template.selectProvider',
              defaultMessage: '选择模板内的模型供应商',
            })}
            options={providers.map((name) => ({ value: name, label: name }))}
          />
        </Form.Item>
      );
    case 'route-select':
      return (
        <Form.Item name={field.name} label={label} rules={rules}>
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={intl.formatMessage({
              id: 'pages.admin.settings.template.selectRoute',
              defaultMessage: '选择模板内的模型路由',
            })}
            options={routes.map((name) => ({ value: name, label: name }))}
          />
        </Form.Item>
      );
    case 'skill-definition':
      return (
        <>
          <Form.Item
            name="promptTemplate"
            label={labelWithRule(
              intl.formatMessage({
                id: 'pages.admin.settings.template.skill.promptTemplate',
                defaultMessage: '任务指令 (promptTemplate)',
              }),
              intl.formatMessage({
                id: 'pages.admin.settings.template.skill.promptTemplateHint',
                defaultMessage: '支持 Markdown 与 {{字段}} 占位符',
              }),
            )}
            rules={[{ required: true, message: '任务指令不能为空' }]}
          >
            <SkillMarkdownEditor />
          </Form.Item>
          <Form.Item
            name="parameters"
            label={labelWithRule(
              intl.formatMessage({
                id: 'pages.admin.settings.template.skill.parameters',
                defaultMessage: '入参 JSON Schema (parameters)',
              }),
              intl.formatMessage({
                id: 'pages.admin.settings.template.skill.parametersHint',
                defaultMessage: '可留空=空对象',
              }),
            )}
            rules={[jsonRule(intl)]}
          >
            <Input.TextArea
              rows={5}
              placeholder={'{\n  "type": "object",\n  "properties": {}\n}'}
            />
          </Form.Item>
        </>
      );
    default:
      return (
        <Form.Item
          name={field.name}
          label={label}
          initialValue={field.default}
          rules={rules}
        >
          <Input maxLength={field.maxLength} placeholder={field.placeholder} />
        </Form.Item>
      );
  }
}

export default function TemplateItemForm({
  open,
  type,
  initial,
  providers,
  routes,
  onCancel,
  onSubmit,
}: Props) {
  const intl = useIntl();
  const { message } = App.useApp();
  const [form] = Form.useForm();

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    const schema = TYPE_SCHEMAS[type];
    if (type === 'skill') {
      form.setFieldsValue(
        definitionToForm(initial?.definition as string | undefined),
      );
    } else {
      const values: Record<string, unknown> = {};
      schema.fields.forEach((f) => {
        const v = initial?.[f.name];
        if (v !== undefined) values[f.name] = v;
      });
      form.setFieldsValue(values);
    }
  }, [open, type, initial, form]);

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      let item: TemplateItem;
      if (type === 'skill') {
        let definition: string;
        try {
          definition = definitionFromForm(values);
        } catch (e) {
          message.error((e as Error).message);
          return;
        }
        item = normalizeItem(type, { ...values, definition });
      } else {
        item = normalizeItem(type, values);
      }
      onSubmit(item);
    } catch {
      // 表单校验失败
    }
  };

  const schema: TypeSchema = TYPE_SCHEMAS[type];
  const isEdit = !!initial;

  return (
    <Modal
      title={`${intl.formatMessage({
        id: isEdit ? 'pages.table.edit' : 'pages.table.create',
        defaultMessage: isEdit ? '编辑' : '新增',
      })} ${txt(TYPE_LABELS[type], intl)}`}
      open={open}
      onOk={handleOk}
      onCancel={onCancel}
      width={720}
      styles={{ body: { maxHeight: '70vh', overflowY: 'auto' } }}
    >
      <Form form={form} layout="vertical" preserve={false}>
        {schema.fields.map((field) => (
          <FieldRenderer
            key={field.name}
            field={field}
            providers={providers}
            routes={routes}
            intl={intl}
          />
        ))}
      </Form>
    </Modal>
  );
}
