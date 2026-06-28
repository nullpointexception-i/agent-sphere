import { UploadOutlined, UserOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { useIntl, useModel } from '@umijs/max';
import { App, Button, Card, Form, Input, Upload } from 'antd';
import { useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { labelWithRule } from '@/utils/labelWithRule';

export default function Profile() {
  const { message } = App.useApp();
  const intl = useIntl();
  const { initialState, setInitialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;
  const [avatar, setAvatar] = useState(currentUser?.avatar || '');
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const handleSave = async () => {
    setSaving(true);
    try {
      const values = await form.validateFields();
      await agentApi.auth.updateProfile({
        displayName: values.displayName,
        englishName: values.englishName,
        avatar: avatar || undefined,
      });
      setInitialState((s: any) => ({
        ...s,
        currentUser: {
          ...s.currentUser,
          name: values.displayName,
          englishName: values.englishName,
          avatar,
        },
      }));
      message.success(intl.formatMessage({ id: 'pages.settings.saved' }));
    } finally {
      setSaving(false);
    }
  };

  return (
    <PageContainer
      title={intl.formatMessage({ id: 'pages.settings.profile' })}
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
          <Form
            form={form}
            layout="vertical"
            initialValues={{
              displayName: currentUser?.name || '',
              englishName: currentUser?.englishName || '',
            }}
            style={{ maxWidth: 400 }}
          >
            <Form.Item
              label={intl.formatMessage({ id: 'pages.settings.avatar' })}
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
                  reader.onload = (e) => setAvatar(e.target?.result as string);
                  reader.readAsDataURL(file);
                  return false;
                }}
              >
                {avatar ? (
                  <img
                    src={avatar}
                    alt="avatar"
                    style={{
                      width: '100%',
                      height: '100%',
                      objectFit: 'cover',
                    }}
                  />
                ) : currentUser?.avatar ? (
                  <img
                    src={currentUser.avatar}
                    alt="avatar"
                    style={{
                      width: '100%',
                      height: '100%',
                      objectFit: 'cover',
                    }}
                  />
                ) : (
                  <div>
                    <UserOutlined style={{ fontSize: 28 }} />
                    <div style={{ marginTop: 4 }}>
                      {intl.formatMessage({ id: 'pages.settings.avatar' })}
                    </div>
                  </div>
                )}
              </Upload>
            </Form.Item>
            <Form.Item
              name="displayName"
              label={labelWithRule(
                intl.formatMessage({ id: 'pages.settings.nickname' }),
                intl.formatMessage({ id: 'pages.hint.name' }),
              )}
              rules={[
                { required: true },
                {
                  max: 64,
                  message: intl.formatMessage(
                    {
                      id: 'pages.form.maxLength',
                      defaultMessage: 'Max {max} characters',
                    },
                    { max: 64 },
                  ),
                },
              ]}
            >
              <Input maxLength={64} />
            </Form.Item>
            <Form.Item
              name="englishName"
              label={labelWithRule(
                intl.formatMessage({ id: 'pages.settings.englishName' }),
                intl.formatMessage({ id: 'pages.hint.englishName' }),
              )}
              rules={[
                { required: true },
                {
                  max: 64,
                  message: intl.formatMessage(
                    {
                      id: 'pages.form.maxLength',
                      defaultMessage: 'Max {max} characters',
                    },
                    { max: 64 },
                  ),
                },
                {
                  pattern: /^[a-zA-Z ]*$/,
                  message: intl.formatMessage({
                    id: 'pages.register.englishNamePattern',
                    defaultMessage:
                      'English name must be letters or spaces only',
                  }),
                },
              ]}
            >
              <Input maxLength={64} />
            </Form.Item>
            <Form.Item>
              <Button type="primary" onClick={handleSave} loading={saving}>
                {intl.formatMessage({ id: 'pages.settings.save' })}
              </Button>
            </Form.Item>
          </Form>
        </Card>
      </div>
    </PageContainer>
  );
}
