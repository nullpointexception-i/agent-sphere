import { CloudServerOutlined, LinkOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import {
  Alert,
  Button,
  Input,
  Modal,
  message,
  Space,
  Tag,
  Typography,
} from 'antd';
import type { CSSProperties } from 'react';
import { useEffect, useState } from 'react';
import {
  type DesktopBridgeSettings,
  getDesktopBridge,
} from '@/utils/desktopBridge';
import {
  mergeRecentServers,
  normalizeServerUrl,
  presetIdOf,
  SERVER_PRESETS,
  safeOrigin,
} from './model';

export interface ServerSettingsModalProps {
  open: boolean;
  onClose: () => void;
}

interface TestResult {
  ok: boolean;
  text: string;
}

function t(
  intl: ReturnType<typeof useIntl>,
  id: string,
  defaultMessage: string,
  values?: Record<string, string | number>,
): string {
  return intl.formatMessage(
    { id: `desktop.server.${id}`, defaultMessage },
    values,
  );
}

/**
 * In-app backend switcher. Each server keeps its own login session and desktop
 * runtime binding, so switching does not force a re-login on the other side.
 */
export function ServerSettingsModal({
  open,
  onClose,
}: ServerSettingsModalProps) {
  const intl = useIntl();
  const bridge = getDesktopBridge();
  const [settings, setSettings] = useState<DesktopBridgeSettings | null>(null);
  const [serverUrl, setServerUrl] = useState('');
  const [loading, setLoading] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<TestResult | null>(null);

  useEffect(() => {
    if (!open || !bridge) return;
    let active = true;
    setLoading(true);
    setTestResult(null);
    bridge.settings
      .get()
      .then((next) => {
        if (!active) return;
        setSettings(next);
        setServerUrl(next.serverUrl);
      })
      .catch(() => {
        if (active) setSettings(null);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [open, bridge]);

  if (!bridge) return null;

  const currentOrigin = safeOrigin(settings?.serverUrl);
  const recent = mergeRecentServers(
    currentOrigin,
    settings?.recentServers ?? [],
  );
  const preset = presetIdOf(safeOrigin(serverUrl));
  const boundRuntimeId =
    settings?.environments?.[currentOrigin]?.runtimeId ?? settings?.runtimeId;

  const test = async () => {
    let origin: string;
    try {
      origin = normalizeServerUrl(serverUrl);
    } catch {
      message.error(t(intl, 'invalid', 'Enter a valid http(s) address'));
      return;
    }
    setTesting(true);
    setTestResult(null);
    try {
      const result = await bridge.settings.test(origin);
      setTestResult({
        ok: result.ok,
        text: result.ok
          ? t(intl, 'testOk', 'Connection succeeded')
          : t(intl, 'testFailed', 'Connection failed: {error}', {
              error: result.message || `HTTP ${result.status ?? '?'}`,
            }),
      });
    } catch (error) {
      setTestResult({
        ok: false,
        text: t(intl, 'testFailed', 'Connection failed: {error}', {
          error: error instanceof Error ? error.message : 'unknown error',
        }),
      });
    } finally {
      setTesting(false);
    }
  };

  const save = () => {
    let origin: string;
    try {
      origin = normalizeServerUrl(serverUrl);
    } catch {
      message.error(t(intl, 'invalid', 'Enter a valid http(s) address'));
      return;
    }
    if (origin === currentOrigin) {
      message.info(t(intl, 'same', 'This environment is already active'));
      return;
    }
    Modal.confirm({
      title: t(intl, 'confirmTitle', 'Switch to {origin}?', { origin }),
      content: t(
        intl,
        'confirmContent',
        'The saved login session and local runtime binding for that environment will be restored. You only need to sign in if it has never been used.',
      ),
      okText: t(intl, 'save', 'Save & switch'),
      cancelText: t(intl, 'cancel', 'Cancel'),
      onOk: async () => {
        try {
          await bridge.settings.update({
            serverUrl: origin,
            webOrigin: origin,
          });
          message.success(t(intl, 'switched', 'Switched, reloading…'));
        } catch (error) {
          message.error(
            error instanceof Error
              ? error.message
              : t(intl, 'saveFailed', 'Failed to switch environment'),
          );
        }
      },
    });
  };

  return (
    <Modal
      open={open}
      title={
        <Space>
          <CloudServerOutlined />
          <span>{t(intl, 'title', 'Server connection')}</span>
        </Space>
      }
      okText={t(intl, 'save', 'Save & switch')}
      cancelText={t(intl, 'cancel', 'Cancel')}
      okButtonProps={{ loading }}
      onOk={save}
      onCancel={onClose}
      destroyOnHidden
      width={520}
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        {t(
          intl,
          'description',
          'Choose which backend this desktop client connects to. Online and local environments keep separate login sessions and runtime bindings.',
        )}
      </Typography.Paragraph>

      <div style={{ marginBottom: 8 }}>
        <Typography.Text type="secondary">
          {t(intl, 'current', 'Current environment')}
        </Typography.Text>
        <div
          style={{
            display: 'flex',
            gap: 8,
            alignItems: 'center',
            marginTop: 6,
          }}
        >
          <Tag color={presetColor(presetIdOf(currentOrigin))}>
            {presetLabel(intl, presetIdOf(currentOrigin))}
          </Tag>
          <Typography.Text code>{currentOrigin || '-'}</Typography.Text>
          <Typography.Text type="secondary">
            {typeof boundRuntimeId === 'number'
              ? t(intl, 'runtimeBound', 'Runtime #{id}', { id: boundRuntimeId })
              : t(intl, 'runtimeUnbound', 'No local runtime')}
          </Typography.Text>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
        {SERVER_PRESETS.map((item) => (
          <Button
            key={item.id}
            size="small"
            type={preset === item.id ? 'primary' : 'default'}
            shape="round"
            onClick={() => {
              setServerUrl(item.url);
              setTestResult(null);
            }}
          >
            {presetLabel(intl, item.id)}
          </Button>
        ))}
      </div>

      <Input
        value={serverUrl}
        onChange={(event) => {
          setServerUrl(event.target.value);
          setTestResult(null);
        }}
        placeholder="https://as.example.com"
        prefix={<LinkOutlined />}
        onPressEnter={test}
      />

      <Space style={{ marginTop: 12 }}>
        <Button loading={testing} onClick={test}>
          {t(intl, 'test', 'Test connection')}
        </Button>
        {testResult ? (
          <Typography.Text type={testResult.ok ? 'success' : 'danger'}>
            {testResult.text}
          </Typography.Text>
        ) : null}
      </Space>

      {recent.length > 1 ? (
        <div style={{ marginTop: 14 }}>
          <Typography.Text type="secondary">
            {t(intl, 'recent', 'Recently used')}
          </Typography.Text>
          <div
            style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 6 }}
          >
            {recent.map((item) => (
              <Tag
                key={item}
                style={{ cursor: 'pointer' }}
                color={item === currentOrigin ? 'green' : undefined}
                onClick={() => {
                  setServerUrl(item);
                  setTestResult(null);
                }}
              >
                {presetLabel(intl, presetIdOf(item))}
                {presetIdOf(item) === 'custom' ? ` · ${item}` : ''}
              </Tag>
            ))}
          </div>
        </div>
      ) : null}

      {!loading && !currentOrigin ? (
        <Alert
          style={{ marginTop: 12 }}
          type="warning"
          showIcon
          title={t(intl, 'invalid', 'Enter a valid http(s) address')}
        />
      ) : null}
    </Modal>
  );
}

function presetLabel(
  intl: ReturnType<typeof useIntl>,
  id: 'online' | 'local' | 'custom',
): string {
  if (id === 'online') return t(intl, 'online', 'Online');
  if (id === 'local') return t(intl, 'local', 'Local');
  return t(intl, 'custom', 'Custom');
}

function presetColor(id: 'online' | 'local' | 'custom'): string | undefined {
  if (id === 'online') return 'blue';
  if (id === 'local') return 'orange';
  return undefined;
}

export interface ServerSettingsEntryProps {
  style?: CSSProperties;
}

/** Small entry point used by the login/register pages. */
export function ServerSettingsEntry({ style }: ServerSettingsEntryProps) {
  const intl = useIntl();
  const [open, setOpen] = useState(false);
  if (!getDesktopBridge()) return null;
  return (
    <span style={style}>
      <Button type="link" size="small" onClick={() => setOpen(true)}>
        <CloudServerOutlined />
        {t(intl, 'open', 'Connection settings')}
      </Button>
      <ServerSettingsModal open={open} onClose={() => setOpen(false)} />
    </span>
  );
}
