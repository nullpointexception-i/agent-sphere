import type { WidgetConfig } from '../config';

interface LoginViewProps {
  config: WidgetConfig;
  error: string | null;
  onLogin: (provider: string) => void;
}

export function LoginView({ config, error, onLogin }: LoginViewProps) {
  const provider = config.provider ?? 'business';
  return (
    <div className="aw-login">
      <div className="aw-login-brand">{config.title ?? 'AgentSphere 助手'}</div>
      <p className="aw-login-hint">登录以开始对话</p>
      {error ? <div className="aw-login-error">{error}</div> : null}
      <button
        type="button"
        className="aw-login-button"
        onClick={() => onLogin(provider)}
      >
        统一身份认证登录
      </button>
    </div>
  );
}