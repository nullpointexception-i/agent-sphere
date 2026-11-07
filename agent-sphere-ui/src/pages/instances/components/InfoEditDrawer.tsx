import { UploadOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import {
  App,
  Button,
  Drawer,
  Form,
  Input,
  InputNumber,
  Select,
  Switch,
  Upload,
} from 'antd';
import { useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { labelWithRule } from '@/utils/labelWithRule';

interface Props {
  record: any;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}

export default function InfoEditDrawer({
  record,
  open,
  onClose,
  onSaved,
}: Props) {
  const { message } = App.useApp();
  const intl = useIntl();
  const [imagePreview, setImagePreview] = useState('');
  const [form] = Form.useForm();

  useEffect(() => {
    if (!open) return;
    setImagePreview(record.image || '');
    form.setFieldsValue({
      ...record,
      maxLoopCount: record.maxLoopCount ?? 0,
      completionProtocol: record.completionProtocol || 'MODEL_DEFAULT',
    });
  }, [open, record]);

  const submit = async () => {
    const values = await form.validateFields();
    const payload = { ...values, image: imagePreview || undefined };
    await agentApi.instances.update(record.id, payload);
    message.success('Saved');
    onClose();
    onSaved();
  };

  return (
    <Drawer
      title={record?.name}
      open={open}
      onClose={onClose}
      size="large"
      extra={
        <Button type="primary" onClick={submit}>
          {intl.formatMessage({ id: 'pages.save', defaultMessage: 'Save' })}
        </Button>
      }
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="name"
          label={labelWithRule(
            intl.formatMessage({ id: 'pages.form.name' }),
            intl.formatMessage({ id: 'pages.hint.name' }),
          )}
          rules={[{ required: true }]}
        >
          <Input maxLength={64} />
        </Form.Item>
        <Form.Item
          label={intl.formatMessage({
            id: 'pages.instances.image',
            defaultMessage: 'Image',
          })}
        >
          <Upload
            listType="picture-card"
            showUploadList={false}
            accept="image/*"
            beforeUpload={(file) => {
              if (file.size > 1 * 1024 * 1024) {
                message.error(
                  intl.formatMessage({
                    id: 'pages.upload.fileTooLarge',
                    defaultMessage: 'File size must not exceed 1MB',
                  }),
                );
                return false;
              }
              const reader = new FileReader();
              reader.onload = (e) =>
                setImagePreview(e.target?.result as string);
              reader.readAsDataURL(file);
              return false;
            }}
          >
            {imagePreview ? (
              <img
                src={imagePreview}
                alt="preview"
                style={{ width: '100%', height: '100%', objectFit: 'cover' }}
              />
            ) : (
              <div>
                <UploadOutlined />
                <div style={{ marginTop: 4 }}>Upload</div>
              </div>
            )}
          </Upload>
        </Form.Item>
        <Form.Item
          name="description"
          label={labelWithRule(
            intl.formatMessage({ id: 'pages.form.description' }),
            intl.formatMessage({ id: 'pages.hint.description' }),
          )}
        >
          <Input.TextArea rows={2} maxLength={255} />
        </Form.Item>
        <Form.Item
          name="systemPrompt"
          label={labelWithRule(
            intl.formatMessage({ id: 'pages.instances.systemPrompt' }),
            intl.formatMessage({ id: 'pages.hint.text' }),
          )}
        >
          <Input.TextArea rows={4} maxLength={5000} />
        </Form.Item>
        <Form.Item
          name="businessType"
          label={labelWithRule(
            intl.formatMessage({
              id: 'pages.instances.businessType',
              defaultMessage: 'Business Type',
            }),
            intl.formatMessage({
              id: 'pages.instances.businessType.extra',
              defaultMessage:
                'Tasks match this instance by businessType; must match the caller',
            }),
          )}
        >
          <Input maxLength={64} placeholder="sourcing" />
        </Form.Item>
        <Form.Item
          name="allowConsoleSession"
          label={labelWithRule(
            intl.formatMessage({
              id: 'pages.instances.allowConsoleSession',
              defaultMessage: 'Allow Console Session',
            }),
            intl.formatMessage({
              id: 'pages.instances.allowConsoleSession.extra',
              defaultMessage:
                'When off, the console (main site) cannot start or continue sessions on this instance',
            }),
          )}
          valuePropName="checked"
        >
          <Switch />
        </Form.Item>
        <Form.Item
          name="allowWidgetSession"
          label={labelWithRule(
            intl.formatMessage({
              id: 'pages.instances.allowWidgetSession',
              defaultMessage: 'Allow Widget Session',
            }),
            intl.formatMessage({
              id: 'pages.instances.allowWidgetSession.extra',
              defaultMessage:
                'When off, the embedded widget cannot start or continue sessions on this instance (keep off for task-only instances)',
            }),
          )}
          valuePropName="checked"
        >
          <Switch />
        </Form.Item>
        <Form.Item
          name="maxLoopCount"
          label={labelWithRule(
            intl.formatMessage({
              id: 'pages.instances.maxLoopCount',
              defaultMessage: 'Max Loop Count',
            }),
            intl.formatMessage({
              id: 'pages.instances.maxLoopCount.extra',
              defaultMessage:
                'Max loops per run; 0=system default, >0=override, required',
            }),
          )}
          rules={[
            {
              required: true,
              message: intl.formatMessage({
                id: 'pages.instances.maxLoopCountRequired',
                defaultMessage: 'Please enter the max loop count',
              }),
            },
          ]}
        >
          <InputNumber
            min={0}
            max={2000}
            placeholder={intl.formatMessage({
              id: 'pages.instances.systemDefault',
              defaultMessage: 'System default',
            })}
            style={{ width: '100%' }}
          />
        </Form.Item>
        <Form.Item
          name="completionProtocol"
          label={labelWithRule(
            intl.formatMessage({
              id: 'pages.instances.completionProtocol',
              defaultMessage: 'Completion Protocol',
            }),
            intl.formatMessage({
              id: 'pages.instances.completionProtocol.extra',
              defaultMessage:
                'Follows the model route by default; task instances can force strict finish',
            }),
          )}
        >
          <Select
            options={[
              {
                value: 'MODEL_DEFAULT',
                label: intl.formatMessage({
                  id: 'pages.instances.completionProtocol.inherit',
                  defaultMessage: 'Follow model route (recommended)',
                }),
              },
              {
                value: 'STRICT_FINISH',
                label: intl.formatMessage({
                  id: 'pages.instances.completionProtocol.strict',
                  defaultMessage: 'Force strict finish',
                }),
              },
              {
                value: 'IMPLICIT_TEXT',
                label: intl.formatMessage({
                  id: 'pages.instances.completionProtocol.implicit',
                  defaultMessage: 'Force implicit text completion',
                }),
              },
            ]}
          />
        </Form.Item>
        <Form.Item
          name="config"
          label={labelWithRule(
            intl.formatMessage({
              id: 'pages.instances.config',
              defaultMessage: 'Advanced Config (JSON)',
            }),
            intl.formatMessage({
              id: 'pages.instances.config.extra',
              defaultMessage:
                'Sampling params JSON (same shape as Completions config); empty = use global system default',
            }),
          )}
        >
          <Input.TextArea
            rows={4}
            maxLength={8000}
            placeholder={
              '{"temperature":0.2,"thinking":true,"max_tokens":4096}'
            }
          />
        </Form.Item>
      </Form>
    </Drawer>
  );
}
