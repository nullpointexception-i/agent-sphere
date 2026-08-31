import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { Helmet, history, SelectLang, useIntl } from '@umijs/max';
import { Alert, App } from 'antd';
import React, { useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { setStoredUser } from '@/utils/auth';
import { labelWithRule } from '@/utils/labelWithRule';
import Settings from '../../../../config/defaultSettings';
import { useStyles } from './style';

const Lang = () => {
  const { styles } = useStyles();
  return (
    <div className={styles.lang} data-lang>
      {SelectLang && <SelectLang />}
    </div>
  );
};

const Register: React.FC = () => {
  const { styles } = useStyles();
  const { message } = App.useApp();
  const intl = useIntl();
  const [errorMessage, setErrorMessage] = useState('');
  const [usernameStatus, setUsernameStatus] = useState<
    'idle' | 'valid' | 'invalid'
  >('idle');

  const checkUsername = async (username: string) => {
    if (!username.trim()) {
      setUsernameStatus('idle');
      return;
    }
    const available = await agentApi.auth.checkUsername(username);
    setUsernameStatus(available ? 'valid' : 'invalid');
  };

  const handleSubmit = async (values: any) => {
    if (values.password !== values.repeatPassword) {
      setErrorMessage(
        intl.formatMessage({
          id: 'pages.settings.passwordMismatch',
          defaultMessage: 'Passwords do not match',
        }),
      );
      return;
    }
    try {
      setErrorMessage('');
      const user = await agentApi.auth.register(values);
      setStoredUser(user);
      message.success(
        intl.formatMessage({
          id: 'pages.register.success',
          defaultMessage: '注册成功！',
        }),
      );
      // 整页刷新让 getInitialState 重新执行并拉取 /auth/me：
      // 注册响应已含 permissions，但 SPA push 不会触发重新初始化，
      // 需重载才能让菜单按新用户的权限正常展示（与登录流程一致）。
      window.location.href = '/chat';
    } catch (error: any) {
      const body = error?.response?.data ?? error?.data ?? {};
      setErrorMessage(
        body?.userTip ||
          body?.errorMessage ||
          body?.message ||
          error?.message ||
          'Registration failed',
      );
    }
  };

  return (
    <div className={styles.container}>
      <Helmet>
        <title>
          {intl.formatMessage({
            id: 'menu.register',
            defaultMessage: 'Register',
          })}{' '}
          - {Settings.title}
        </title>
      </Helmet>
      <Lang />
      <style>{`.ant-pro-form-login-header { flex-direction: column !important; align-items: center !important; gap: 4px; margin-bottom: 38px; }`}</style>
      <div className={styles.wrapper}>
        <LoginForm
          contentStyle={{ minWidth: 280, maxWidth: '75vw' }}
          logo={
            <img alt="logo" src="/logo.svg" style={{ width: 64, height: 64 }} />
          }
          title={<span>Agent Sphere</span>}
          subTitle={intl.formatMessage({
            id: 'pages.register.subtitle',
            defaultMessage: 'Create your account',
          })}
          onFinish={handleSubmit}
          submitter={{
            searchConfig: {
              submitText: intl.formatMessage({
                id: 'pages.register.submit',
                defaultMessage: 'Register',
              }),
            },
            submitButtonProps: { style: { width: '100%' } },
          }}
        >
          {errorMessage && (
            <Alert
              style={{ marginBottom: 24 }}
              title={errorMessage}
              type="error"
              showIcon
            />
          )}
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
              onBlur: (e) => checkUsername(e.target.value),
            }}
            placeholder={intl.formatMessage({
              id: 'pages.login.username.placeholder',
              defaultMessage: 'Username',
            })}
            rules={[
              {
                required: true,
                message: intl.formatMessage({
                  id: 'pages.login.username.required',
                  defaultMessage: 'Please enter username',
                }),
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
              {
                pattern: /^[a-zA-Z0-9]+$/,
                message: intl.formatMessage({
                  id: 'pages.register.usernamePattern',
                  defaultMessage: 'Letters and digits only',
                }),
              },
            ]}
            suffix={
              usernameStatus === 'valid' ? (
                <span style={{ color: '#52c41a' }}>✓</span>
              ) : usernameStatus === 'invalid' ? (
                <span style={{ color: '#ff4d4f' }}>✗</span>
              ) : null
            }
            help={
              usernameStatus === 'invalid'
                ? intl.formatMessage({
                    id: 'pages.register.usernameTaken',
                    defaultMessage: 'Username already exists',
                  })
                : undefined
            }
            validateStatus={
              usernameStatus === 'invalid'
                ? 'error'
                : usernameStatus === 'valid'
                  ? 'success'
                  : undefined
            }
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
              defaultMessage: 'Password',
            })}
            rules={[
              {
                required: true,
                message: intl.formatMessage({
                  id: 'pages.login.password.required',
                  defaultMessage: 'Please enter password',
                }),
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
            name="repeatPassword"
            label={labelWithRule(
              intl.formatMessage({
                id: 'pages.register.repeatPassword',
                defaultMessage: '确认密码',
              }),
              intl.formatMessage({ id: 'pages.hint.password' }),
            )}
            fieldProps={{
              size: 'large',
              prefix: <LockOutlined />,
              maxLength: 32,
            }}
            placeholder={intl.formatMessage({
              id: 'pages.register.repeatPassword',
              defaultMessage: 'Repeat password',
            })}
            rules={[
              {
                required: true,
                message: intl.formatMessage({
                  id: 'pages.register.repeatPasswordRequired',
                  defaultMessage: 'Please repeat password',
                }),
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
              ({ getFieldValue }: any) => ({
                validator: (_, value: string) =>
                  value && getFieldValue('password') === value
                    ? Promise.resolve()
                    : Promise.reject(
                        new Error(
                          intl.formatMessage({
                            id: 'pages.settings.passwordMismatch',
                            defaultMessage: 'Passwords do not match',
                          }),
                        ),
                      ),
              }),
            ]}
          />
          <div style={{ textAlign: 'center', marginTop: 24, marginBottom: 24 }}>
            <a onClick={() => history.push('/user/login')}>
              {intl.formatMessage({
                id: 'pages.register.backToLogin',
                defaultMessage: 'Already have an account? Login',
              })}
            </a>
          </div>
        </LoginForm>
      </div>
    </div>
  );
};

export default Register;
