import { AppstoreOutlined, ArrowLeftOutlined } from '@ant-design/icons';
import { Button, Space } from 'antd';
import { history } from '@umijs/max';
import { tw } from './i18n';

/**
 * Desktop pages render outside the console layout, so they need their own way
 * back. Keep both destinations explicit: the workspace is the daily surface and
 * the console owns the administration pages.
 */
export function DesktopPageNav() {
  return (
    <Space size={8} wrap>
      <Button
        size="small"
        icon={<ArrowLeftOutlined />}
        onClick={() => history.push('/workspace')}
      >
        {tw('nav.workspace', 'Workspace')}
      </Button>
      <Button
        size="small"
        type="text"
        icon={<AppstoreOutlined />}
        onClick={() => history.push('/dashboard')}
      >
        {tw('nav.console', 'Console')}
      </Button>
    </Space>
  );
}
