import { PageContainer } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { App, Button, Card, Form, Input } from 'antd';
import { useState } from 'react';
import { Can } from '@/components/Can';
import { agentApi } from '@/services/agentSphere/api';
import { labelWithRule } from '@/utils/labelWithRule';

export default function Password() {
  const { message } = App.useApp();
  const intl = useIntl();
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);

  const handleSubmit = async () => {
    setSaving(true);
    try {
      const values = await form.validateFields();
      if (values.newPassword !== values.confirmPassword) {
        message.error(
          intl.formatMessage({ id: 'pages.settings.passwordMismatch' }),
        );
        return;
      }
      await agentApi.auth.updatePassword({
        oldPassword: values.oldPassword,
        newPassword: values.newPassword,
      });
      message.success(
        intl.formatMessage({ id: 'pages.settings.passwordChanged' }),
      );
      form.resetFields();
    } finally {
      setSaving(false);
    }
  };

  return (
    <PageContainer
      title={intl.formatMessage({ id: 'pages.settings.password' })}
      childrenContentStyle={{
        height: 'calc(100vh - 120px)',
        overflow: 'hidden',
        padding: 24,
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <div style={{ overflow: 'auto', flex: 1, minHeight: 0 }}>
        <Card style={{ maxWidth: 500 }}>
          <Form form={form} layout="vertical" style={{ maxWidth: 400 }}>
            <Form.Item
              name="oldPassword"
              label={labelWithRule(
                intl.formatMessage({ id: 'pages.settings.oldPassword' }),
                intl.formatMessage({ id: 'pages.hint.password' }),
              )}
              rules={[{ required: true }, { min: 6 }]}
            >
              <Input.Password maxLength={32} />
            </Form.Item>
            <Form.Item
              name="newPassword"
              label={labelWithRule(
                intl.formatMessage({ id: 'pages.settings.newPassword' }),
                intl.formatMessage({ id: 'pages.hint.password' }),
              )}
              rules={[{ required: true }, { min: 6 }]}
            >
              <Input.Password maxLength={32} />
            </Form.Item>
            <Form.Item
              name="confirmPassword"
              label={labelWithRule(
                intl.formatMessage({ id: 'pages.settings.confirmPassword' }),
                intl.formatMessage({ id: 'pages.hint.password' }),
              )}
              rules={[{ required: true }, { min: 6 }]}
            >
              <Input.Password maxLength={32} />
            </Form.Item>
            <Form.Item>
              <Can code="user:password:update">
                <Button type="primary" onClick={handleSubmit} loading={saving}>
                  {intl.formatMessage({ id: 'pages.settings.save' })}
                </Button>
              </Can>
            </Form.Item>
          </Form>
        </Card>
      </div>
    </PageContainer>
  );
}
