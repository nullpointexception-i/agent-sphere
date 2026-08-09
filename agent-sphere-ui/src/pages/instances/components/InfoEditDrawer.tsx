import { UploadOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { App, Button, Drawer, Form, Input, Upload } from 'antd';
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
    form.setFieldsValue(record);
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
              defaultMessage: '业务域',
            }),
            intl.formatMessage({
              id: 'pages.instances.businessType.extra',
              defaultMessage: '任务按业务域匹配该实例，需与调用方一致',
            }),
          )}
        >
          <Input maxLength={64} placeholder="sourcing" />
        </Form.Item>
      </Form>
    </Drawer>
  );
}
