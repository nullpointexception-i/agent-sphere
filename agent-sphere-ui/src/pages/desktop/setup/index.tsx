import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useState } from 'react';
import { localRuntimeApi } from '@/services/agentSphere/localRuntime';
import { getToken } from '@/utils/auth';
import {
  type DesktopBridgeStatus,
  getDesktopBridge,
} from '@/utils/desktopBridge';
import { getWebOrigin } from '@/utils/runtimeEnv';
import { tw } from '../i18n';
import { DesktopPageNav } from '../DesktopPageNav';

interface SetupValues {
  serverUrl: string;
  runtimeId: number;
  runtimeName?: string;
  deviceToken: string;
}

const STATE_COLOR: Record<string, string> = {
  ONLINE: 'green',
  CONNECTING: 'blue',
  OFFLINE: 'red',
  PAUSED: 'orange',
  UNCONFIGURED: 'default',
};

export default function DesktopSetupPage() {
  const { message } = App.useApp();
  const bridge = getDesktopBridge();
  const [form] = Form.useForm<SetupValues>();
  const [status, setStatus] = useState<DesktopBridgeStatus>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [serverUrl, setServerUrl] = useState('');
  const [createName, setCreateName] = useState('');
  const [createKind, setCreateKind] = useState<'OPENCODE' | 'CODEX'>(
    'OPENCODE',
  );
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string>();

  useEffect(() => {
    if (!bridge) return;
    void (async () => {
      try {
        const [settings, current] = await Promise.all([
          bridge.settings.get(),
          bridge.runtime.status(),
        ]);
        form.setFieldsValue({
          serverUrl: settings.serverUrl,
          runtimeId: settings.runtimeId,
          runtimeName: settings.runtimeName,
        });
        setServerUrl(settings.serverUrl);
        setStatus(current);
      } catch (reason) {
        setError(reason instanceof Error ? reason.message : String(reason));
      }
    })();
  }, [bridge, form]);

  const submit = async (values: SetupValues) => {
    if (!bridge) return;
    setSaving(true);
    setError(undefined);
    try {
      const next = await bridge.runtime.configure({
        serverUrl: values.serverUrl.trim(),
        runtimeId: values.runtimeId,
        deviceToken: values.deviceToken.trim(),
        runtimeName: values.runtimeName?.trim() || undefined,
      });
      setStatus(next);
      message.success(tw('setup.success', 'Connected'));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setSaving(false);
    }
  };

  const createRuntime = async () => {
    if (!bridge) return;
    const name = createName.trim();
    if (!name) {
      setCreateError(tw('setup.runtimeNameRequired', 'Enter a runtime name'));
      return;
    }
    const token = getToken();
    if (!token) {
      setCreateError(
        tw(
          'setup.loginRequired',
          'Sign in to AgentSphere before creating a runtime',
        ),
      );
      return;
    }
    const targetServer = (
      form.getFieldValue('serverUrl') ||
      serverUrl ||
      ''
    ).trim();
    if (!targetServer) {
      setCreateError(
        tw('setup.serverUrlRequired', 'Enter the server URL first'),
      );
      return;
    }
    setCreating(true);
    setCreateError(undefined);
    try {
      const issued = await localRuntimeApi.create({
        name,
        runtimeKind: createKind,
      });
      await bridge.runtime.configure({
        serverUrl: targetServer,
        runtimeId: issued.id,
        deviceToken: issued.token,
        runtimeName: issued.name,
      });
      message.success(tw('setup.success', 'Connected'));
    } catch (reason) {
      setCreateError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setCreating(false);
    }
  };

  if (!bridge) {
    return (
      <div style={{ maxWidth: 680, margin: '80px auto', padding: 24 }}>
        <Alert
          type="warning"
          showIcon
          message={tw(
            'setup.desktopOnly',
            'Open this page in AgentSphere Desktop',
          )}
          description={tw(
            'setup.desktopOnlyHint',
            'The setup page needs the desktop client bridge to store the device token securely.',
          )}
        />
      </div>
    );
  }

  return (
    <div style={{ maxWidth: 680, margin: '60px auto', padding: 24 }}>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <DesktopPageNav />
        <div>
          <Typography.Title level={3} style={{ marginBottom: 4 }}>
            {tw('setup.title', 'Connect to AgentSphere')}
          </Typography.Title>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            {tw(
              'setup.description',
              'Create a local runtime in the AgentSphere console, then enter its runtime ID and one-time token here.',
            )}
          </Typography.Paragraph>
        </div>

        {status ? (
          <Card size="small">
            <Space>
              <Tag color={STATE_COLOR[status.state] ?? 'default'}>
                {tw(`status.${status.state}`, status.state)}
              </Tag>
              {status.message ? (
                <Typography.Text type="secondary">
                  {status.message}
                </Typography.Text>
              ) : null}
            </Space>
          </Card>
        ) : null}

        {error ? <Alert type="error" showIcon message={error} /> : null}

        {status?.state === 'ONLINE' ? (
          <Alert
            type="success"
            showIcon
            message={tw('setup.alreadyConfigured', 'This device is connected.')}
            action={
              <Space>
                <Button
                  size="small"
                  onClick={() => window.location.assign('/desktop/status')}
                >
                  {tw('status.title', 'Local runtime')}
                </Button>
                <Button
                  size="small"
                  type="primary"
                  onClick={() => window.location.assign('/workspace')}
                >
                  {tw('setup.openWorkspace', 'Open workspace')}
                </Button>
              </Space>
            }
          />
        ) : null}

        <Card
          title={tw('setup.createRuntime', 'Create a runtime')}
          size="small"
        >
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <Typography.Text type="secondary">
              {tw(
                'setup.createHint',
                'Sign in to AgentSphere, then create a runtime here. Its token is stored only on this device.',
              )}
            </Typography.Text>
            <Space.Compact style={{ width: '100%' }}>
              <Input
                value={createName}
                placeholder={tw('setup.runtimeName', 'Runtime name')}
                onChange={(event) => setCreateName(event.target.value)}
              />
              <Select
                value={createKind}
                style={{ width: 170 }}
                onChange={(value: 'OPENCODE' | 'CODEX') => setCreateKind(value)}
                options={[
                  {
                    value: 'OPENCODE',
                    label: tw('runtime.opencode', 'opencode Desktop'),
                  },
                  {
                    value: 'CODEX',
                    label: tw('runtime.codex', 'ChatGPT / Codex Desktop'),
                  },
                ]}
              />
              <Button
                type="primary"
                loading={creating}
                onClick={() => void createRuntime()}
              >
                {tw('setup.create', 'Create')}
              </Button>
            </Space.Compact>
            {createError ? (
              <Alert type="error" showIcon message={createError} />
            ) : null}
            {!getToken() ? (
              <Button
                size="small"
                onClick={() =>
                  window.location.assign('/user/login?redirect=/desktop/setup')
                }
              >
                {tw('setup.signIn', 'Sign in')}
              </Button>
            ) : null}
          </Space>
        </Card>

        <Card
          title={tw('setup.manualBinding', 'Use an existing runtime')}
          size="small"
        >
          <Form
            form={form}
            layout="vertical"
            onFinish={submit}
            requiredMark={false}
          >
            <Form.Item
              name="serverUrl"
              label={tw('setup.serverUrl', 'Server URL')}
              rules={[{ required: true }]}
              extra={tw(
                'setup.serverUrlHint',
                'For example https://as.example.com',
              )}
            >
              <Input placeholder="https://as.example.com" />
            </Form.Item>

            <Form.Item
              name="runtimeId"
              label={tw('setup.runtimeId', 'Runtime ID')}
              rules={[{ required: true }]}
              extra={tw(
                'setup.runtimeIdHint',
                'The numeric ID shown after creating a local runtime in the console.',
              )}
            >
              <InputNumber min={1} style={{ width: '100%' }} />
            </Form.Item>

            <Form.Item
              name="runtimeName"
              label={tw('setup.runtimeName', 'Runtime name')}
            >
              <Input placeholder="Office PC" />
            </Form.Item>

            <Form.Item
              name="deviceToken"
              label={tw('setup.deviceToken', 'Device token')}
              rules={[{ required: true }]}
              extra={tw(
                'setup.deviceTokenHint',
                'The token is stored only in this device keychain',
              )}
            >
              <Input.Password autoComplete="off" />
            </Form.Item>

            <Space>
              <Button type="primary" htmlType="submit" loading={saving}>
                {tw('setup.connect', 'Connect')}
              </Button>
              <Button
                onClick={() =>
                  window.open(`${getWebOrigin()}/workspace`, '_blank')
                }
              >
                {tw('setup.manageInConsole', 'Manage in console')}
              </Button>
            </Space>
          </Form>
        </Card>
      </Space>
    </div>
  );
}
