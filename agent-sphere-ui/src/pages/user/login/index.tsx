import { LockOutlined, UserOutlined } from '@ant-design/icons';
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
import { Alert, App, Button, Divider } from 'antd';
import React, { startTransition, useEffect, useState } from 'react';
import { setStoredUser, type UserVO } from '@/utils/auth';
import { labelWithRule } from '@/utils/labelWithRule';
import Settings from '../../../../config/defaultSettings';
import { useStyles } from './style';

const SSO_PROVIDER = 'business';
const SSO_AUTHORIZE_PATH = '/api/v1/auth/sso/authorize';
const SSO_EXCHANGE_PATH = '/api/v1/auth/sso/exchange';
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
  const { initialState, setInitialState } = useModel('@@initialState');
  const { styles } = useStyles();
  const { message } = App.useApp();
  const intl = useIntl();

  const getSafeRedirectUrl = (redirect: string | null): string => {
    if (!redirect?.startsWith('/')) return '/';
    if (redirect.startsWith('//')) return '/';
    try {
      const parsed = new URL(redirect, window.location.origin);
      if (parsed.origin !== window.location.origin) return '/';
      return `${parsed.pathname}${parsed.search}${parsed.hash}`;
    } catch {
      return '/';
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
      defaultMessage: '登录成功！',
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
      const msg =
        error?.data?.userTip ||
        error?.data?.errorMessage ||
        error?.data?.message ||
        error?.message ||
        '登录失败，请重试！';
      setErrorMessage(msg);
    }
  };

  useEffect(() => {
    void handleSsoCallback();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSsoAuthorize = async () => {
    try {
      const { authorizeUrl } = await umiRequest(SSO_AUTHORIZE_PATH, {
        method: 'GET',
        params: {
          provider: SSO_PROVIDER,
          redirect_uri: `${window.location.origin}${SSO_REDIRECT_PATH}`,
        },
      });
      window.location.href = authorizeUrl;
    } catch (error: any) {
      const msg =
        error?.data?.userTip ||
        error?.data?.errorMessage ||
        error?.data?.message ||
        error?.message ||
        '企业登录发起失败，请重试！';
      setErrorMessage(msg);
    }
  };

  const handleSubmit = async (values: any) => {
    try {
      setErrorMessage('');
      const user = await umiRequest('/api/v1/auth/login', {
        method: 'POST',
        data: { username: values.username, password: values.password },
      });
      await completeLogin(user);
    } catch (error: any) {
      const msg =
        error?.data?.userTip ||
        error?.data?.errorMessage ||
        error?.data?.message ||
        error?.message ||
        '登录失败，请重试！';
      setErrorMessage(msg);
    }
  };

  return (
    <div className={styles.container}>
      <Helmet>
        <title>
          {intl.formatMessage({
            id: 'menu.login',
            defaultMessage: '登录页',
          })}
          {Settings.title && ` - ${Settings.title}`}
        </title>
      </Helmet>
      <Lang />
      <style>{`.ant-pro-form-login-header { flex-direction: column !important; align-items: center !important; gap: 4px; margin-bottom: 38px; }`}</style>
      <div className={styles.wrapper}>
        <LoginForm
          contentStyle={{
            minWidth: 280,
            maxWidth: '75vw',
          }}
          logo={
            <img alt="logo" src="/logo.svg" style={{ width: 64, height: 64 }} />
          }
          title={<span>Agent Sphere</span>}
          subTitle={intl.formatMessage({
            id: 'pages.login.subtitle',
            defaultMessage: 'Intelligent Agent Management & AI Orchestration',
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
                defaultMessage: '用户名',
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
              defaultMessage: '用户名',
            })}
            rules={[
              {
                required: true,
                message: (
                  <FormattedMessage
                    id="pages.login.username.required"
                    defaultMessage="请输入用户名!"
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
                defaultMessage: '密码',
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
              defaultMessage: '密码',
            })}
            rules={[
              {
                required: true,
                message: (
                  <FormattedMessage
                    id="pages.login.password.required"
                    defaultMessage="请输入密码！"
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
                defaultMessage="自动登录"
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
                defaultMessage="去注册"
              />
            </a>
          </div>
          <Divider plain>
            <FormattedMessage
              id="pages.login.sso.divider"
              defaultMessage="其他登录方式"
            />
          </Divider>
          <Button block size="large" onClick={handleSsoAuthorize}>
            <FormattedMessage
              id="pages.login.sso"
              defaultMessage="企业账号登录"
            />
          </Button>
        </LoginForm>
      </div>
    </div>
  );
};

export default Login;
