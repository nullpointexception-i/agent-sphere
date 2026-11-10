import {
  DownOutlined,
  GlobalOutlined,
  LockOutlined,
  UserOutlined,
} from '@ant-design/icons';
import {
  LoginForm,
  ProFormCheckbox,
  ProFormText,
} from '@ant-design/pro-components';
import {
  FormattedMessage,
  Helmet,
  history,
  SelectLang,
  request as umiRequest,
  useIntl,
  useModel,
} from '@umijs/max';
import { Alert, App, Button, Card, Col, Dropdown, Row } from 'antd';
import React, { startTransition, useEffect, useState } from 'react';
import { API_BASE } from '@/constants/api';
import { API_PATHS } from '@/constants/endpoints';
import { agentApi } from '@/services/agentSphere/api';
import { setStoredUser, type UserVO } from '@/utils/auth';
import { localizeApiError } from '@/utils/errorMessage';
import { labelWithRule } from '@/utils/labelWithRule';
import { isDesktop } from '@/utils/runtimeEnv';
import Settings from '../../../../config/defaultSettings';
import { useStyles } from './style';

const SSO_AUTHORIZE_PATH = API_PATHS.SSO_AUTHORIZE;
const SSO_EXCHANGE_PATH = API_PATHS.SSO_EXCHANGE;
const SSO_QUERY_PARAM_OTC = 'otc';
const SSO_QUERY_PARAM_ERROR = 'error';
const SSO_REDIRECT_PATH = '/user/login';

const Lang = () => {
  const { styles } = useStyles();

  return (
    <div className={styles.lang} data-lang>
      {SelectLang && <SelectLang />}
    </div>
  );
};

const LoginMessage: React.FC<{
  content: string;
}> = ({ content }) => {
  return (
    <Alert
      style={{
        marginBottom: 24,
      }}
      title={content}
      type="error"
      showIcon
    />
  );
};

const Login: React.FC = () => {
  const [errorMessage, setErrorMessage] = useState<string>('');
  const [ssoProviders, setSsoProviders] = useState<
    { code: string; name: string }[]
  >([]);
  const { initialState, setInitialState } = useModel('@@initialState');
  const { styles } = useStyles();
  const { message } = App.useApp();
  const intl = useIntl();
  const [pluginStoreUrl, setPluginStoreUrl] = useState<string>('');
  const [pluginDownloadUrl, setPluginDownloadUrl] = useState<string>('');

  useEffect(() => {
    agentApi.system
      .publicConfig(['plugin.download-url', 'plugin.store-url'])
      .then((res: Record<string, string>) => {
        // The backend stores apiBase as a relative route (e.g. /system/config/plugin/download),
        // so prefix it with BASE=/api/v1; external links (http/https) are used as is
        const toAbs = (raw: string) => {
          if (!raw) return '';
          return /^https?:\/\//i.test(raw)
            ? raw
            : `${window.location.origin}${raw.startsWith('/') ? `${API_BASE}${raw}` : `${API_BASE}/${raw}`}`;
        };
        setPluginStoreUrl(toAbs(res?.['plugin.store-url'] || ''));
        setPluginDownloadUrl(toAbs(res?.['plugin.download-url'] || ''));
      })
      .catch(() => {});
  }, []);

  const getSafeRedirectUrl = (redirect: string | null): string => {
    if (!redirect?.startsWith('/')) return '/workspace';
    if (redirect.startsWith('//')) return '/workspace';
    try {
      const parsed = new URL(redirect, window.location.origin);
      if (parsed.origin !== window.location.origin) return '/workspace';
      return `${parsed.pathname}${parsed.search}${parsed.hash}`;
    } catch {
      return '/workspace';
    }
  };

  const fetchUserInfo = async () => {
    const userInfo = await initialState?.fetchUserInfo?.();
    if (userInfo) {
      startTransition(() => {
        setInitialState((s) => ({
          ...s,
          currentUser: userInfo,
        }));
      });
    }
  };

  const completeLogin = async (user: UserVO) => {
    setStoredUser(user);
    const defaultLoginSuccessMessage = intl.formatMessage({
      id: 'pages.login.success',
      defaultMessage: 'Login successful!',
    });
    message.success(defaultLoginSuccessMessage);
    await fetchUserInfo();
    const urlParams = new URL(window.location.href).searchParams;
    const redirectUrl = getSafeRedirectUrl(urlParams.get('redirect'));
    window.location.href = redirectUrl;
  };

  const handleSsoCallback = async () => {
    const urlParams = new URL(window.location.href).searchParams;
    const ssoError = urlParams.get(SSO_QUERY_PARAM_ERROR);
    if (ssoError) {
      setErrorMessage(ssoError);
      return;
    }
    const otc = urlParams.get(SSO_QUERY_PARAM_OTC);
    if (!otc) return;
    try {
      setErrorMessage('');
      const user = await umiRequest(SSO_EXCHANGE_PATH, {
        method: 'POST',
        data: { otc },
      });
      const cleanUrl = `${window.location.origin}${SSO_REDIRECT_PATH}`;
      window.history.replaceState({}, '', cleanUrl);
      await completeLogin(user);
    } catch (error: any) {
      const body = error?.response?.data ?? error?.data ?? {};
      const msg = localizeApiError(
        body,
        error?.message ||
          intl.formatMessage({
            id: 'pages.login.failure',
            defaultMessage: 'Login failed, please try again!',
          }),
      );
      setErrorMessage(msg);
    }
  };

  useEffect(() => {
    void handleSsoCallback();
    if (!isDesktop()) {
      agentApi.sso
        .providers()
        .then((list: { code: string; name: string }[]) =>
          setSsoProviders(list || []),
        )
        .catch(() => setSsoProviders([]));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSsoAuthorize = async (provider: string) => {
    try {
      const { authorizeUrl } = await umiRequest(SSO_AUTHORIZE_PATH, {
        method: 'GET',
        params: {
          provider,
          redirect_uri: `${window.location.origin}${SSO_REDIRECT_PATH}`,
        },
      });
      window.location.href = authorizeUrl;
    } catch (error: any) {
      const body = error?.response?.data ?? error?.data ?? {};
      const msg = localizeApiError(
        body,
        error?.message ||
          intl.formatMessage({
            id: 'pages.login.ssoFailed',
            defaultMessage: 'Failed to start enterprise sign-in, please retry!',
          }),
      );
      message.error(msg);
    }
  };

  const handleSubmit = async (values: any) => {
    try {
      setErrorMessage('');
      const user = await umiRequest(API_PATHS.AUTH_LOGIN, {
        method: 'POST',
        data: { username: values.username, password: values.password },
      });
      await completeLogin(user);
    } catch (error: any) {
      const body = error?.response?.data ?? error?.data ?? {};
      const msg = localizeApiError(
        body,
        error?.message ||
          intl.formatMessage({
            id: 'pages.login.failure',
            defaultMessage: 'Login failed, please try again!',
          }),
      );
      setErrorMessage(msg);
    }
  };

  return (
    <div className={styles.container}>
      <Helmet>
        <title>
          {intl.formatMessage({
            id: 'menu.login',
            defaultMessage: 'Login',
          })}
          {Settings.title && ` - ${Settings.title}`}
        </title>
      </Helmet>
      <Lang />
      <style>{`.ant-pro-form-login-header { flex-direction: column !important; align-items: center !important; gap: 4px; margin-bottom: 38px; }`}</style>
      <div className={styles.wrapper}>
        <Row
          gutter={24}
          justify="center"
          align="stretch"
          style={{ width: '100%', maxWidth: 960 }}
        >
          <Col xs={24} md={12}>
            <LoginForm
              contentStyle={{
                minWidth: 280,
                maxWidth: '75vw',
              }}
              logo={
                <img
                  alt="logo"
                  src="/logo.svg"
                  style={{ width: 64, height: 64 }}
                />
              }
              title={<span>Agent Sphere</span>}
              subTitle={intl.formatMessage({
                id: 'pages.login.subtitle',
                defaultMessage:
                  'Intelligent Agent Management & AI Orchestration',
              })}
              initialValues={{ autoLogin: true }}
              onFinish={async (values) => {
                await handleSubmit(values);
              }}
            >
              {errorMessage && <LoginMessage content={errorMessage} />}
              <ProFormText
                name="username"
                label={labelWithRule(
                  intl.formatMessage({
                    id: 'pages.login.username.placeholder',
                    defaultMessage: 'Username',
                  }),
                  intl.formatMessage({ id: 'pages.hint.username' }),
                )}
                fieldProps={{
                  size: 'large',
                  prefix: <UserOutlined />,
                  maxLength: 32,
                }}
                placeholder={intl.formatMessage({
                  id: 'pages.login.username.placeholder',
                  defaultMessage: 'Username',
                })}
                rules={[
                  {
                    required: true,
                    message: (
                      <FormattedMessage
                        id="pages.login.username.required"
                        defaultMessage="Please input your username!"
                      />
                    ),
                  },
                  {
                    min: 5,
                    message: intl.formatMessage(
                      {
                        id: 'pages.form.minLength',
                        defaultMessage: 'Min {min} characters',
                      },
                      { min: 5 },
                    ),
                  },
                ]}
              />
              <ProFormText.Password
                name="password"
                label={labelWithRule(
                  intl.formatMessage({
                    id: 'pages.login.password.placeholder',
                    defaultMessage: 'Password',
                  }),
                  intl.formatMessage({ id: 'pages.hint.password' }),
                )}
                fieldProps={{
                  size: 'large',
                  prefix: <LockOutlined />,
                  maxLength: 32,
                }}
                placeholder={intl.formatMessage({
                  id: 'pages.login.password.placeholder',
                  defaultMessage: 'Password',
                })}
                rules={[
                  {
                    required: true,
                    message: (
                      <FormattedMessage
                        id="pages.login.password.required"
                        defaultMessage="Please input your password!"
                      />
                    ),
                  },
                  {
                    min: 5,
                    message: intl.formatMessage(
                      {
                        id: 'pages.form.minLength',
                        defaultMessage: 'Min {min} characters',
                      },
                      { min: 5 },
                    ),
                  },
                ]}
              />
              <div
                style={{
                  marginBottom: 24,
                }}
              >
                <ProFormCheckbox noStyle name="autoLogin">
                  <FormattedMessage
                    id="pages.login.rememberMe"
                    defaultMessage="Remember me"
                  />
                </ProFormCheckbox>
              </div>
              <div
                style={{
                  marginBottom: 24,
                  textAlign: 'center',
                }}
              >
                <a onClick={() => history.push('/user/register')}>
                  <FormattedMessage
                    id="pages.login.register"
                    defaultMessage="Register"
                  />
                </a>
              </div>
              {(pluginStoreUrl || pluginDownloadUrl) && (
                <div
                  style={{
                    marginBottom: 24,
                    textAlign: 'center',
                  }}
                >
                  <Dropdown
                    menu={{
                      items: [
                        ...(pluginStoreUrl
                          ? [
                              {
                                key: 'store',
                                label: (
                                  <a
                                    href={pluginStoreUrl}
                                    target="_blank"
                                    rel="noreferrer"
                                  >
                                    {intl.formatMessage({
                                      id: 'pages.login.pluginStore',
                                      defaultMessage: 'From Chrome Web Store',
                                    })}
                                  </a>
                                ),
                              },
                            ]
                          : []),
                        ...(pluginDownloadUrl
                          ? [
                              {
                                key: 'site',
                                label: (
                                  <a
                                    href={pluginDownloadUrl}
                                    target="_blank"
                                    rel="noreferrer"
                                  >
                                    {intl.formatMessage({
                                      id: 'pages.login.pluginSiteDownload',
                                      defaultMessage: 'Download here',
                                    })}
                                  </a>
                                ),
                              },
                            ]
                          : []),
                      ],
                    }}
                  >
                    <a
                      style={{ display: 'inline-flex', alignItems: 'center' }}
                      onClick={(e) => e.preventDefault()}
                    >
                      <FormattedMessage
                        id="pages.login.pluginDownload"
                        defaultMessage="Plugin Download"
                      />
                      <DownOutlined style={{ marginLeft: 4, fontSize: 10 }} />
                    </a>
                  </Dropdown>
                </div>
              )}
            </LoginForm>
          </Col>
          {!isDesktop() && ssoProviders.length > 0 && (
            <Col xs={24} md={12}>
              <Card
                style={{
                  height: '100%',
                  borderColor: '#e6f0ff',
                  borderRadius: 12,
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    justifyContent: 'center',
                    height: '100%',
                    minHeight: 280,
                    padding: '32px 24px',
                    textAlign: 'center',
                    borderRadius: 8,
                    background:
                      'linear-gradient(180deg, #f5f9ff 0%, #ffffff 100%)',
                  }}
                >
                  <div
                    style={{
                      width: 64,
                      height: 64,
                      borderRadius: '50%',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      background: '#2563eb',
                      color: '#fff',
                      fontSize: 30,
                      marginBottom: 16,
                      boxShadow: '0 8px 20px rgba(37,99,235,0.25)',
                    }}
                  >
                    <GlobalOutlined />
                  </div>
                  <div
                    style={{
                      fontSize: 18,
                      fontWeight: 600,
                      color: '#1f2937',
                    }}
                  >
                    {intl.formatMessage({
                      id: 'pages.login.sso.title',
                      defaultMessage: 'Enterprise SSO Sign-in',
                    })}
                  </div>
                  <div
                    style={{
                      fontSize: 13,
                      color: '#6b7280',
                      margin: '8px 0 20px',
                      maxWidth: 260,
                      lineHeight: 1.6,
                    }}
                  >
                    {intl.formatMessage({
                      id: 'pages.login.sso.desc',
                      defaultMessage:
                        'Sign in with your enterprise identity provider. No registration needed.',
                    })}
                  </div>
                  <Dropdown
                    menu={{
                      items: ssoProviders.map((p) => ({
                        key: p.code,
                        label: p.name || p.code,
                      })),
                      onClick: ({ key }) => handleSsoAuthorize(key),
                      style: { textAlign: 'center', minWidth: 180 },
                    }}
                    placement="bottom"
                  >
                    <Button
                      type="primary"
                      size="large"
                      icon={<GlobalOutlined />}
                      style={{ paddingInline: 36 }}
                    >
                      {intl.formatMessage({
                        id: 'pages.login.sso.choose',
                        defaultMessage: 'Choose a provider',
                      })}
                    </Button>
                  </Dropdown>
                </div>
              </Card>
            </Col>
          )}
        </Row>
      </div>
    </div>
  );
};

export default Login;
