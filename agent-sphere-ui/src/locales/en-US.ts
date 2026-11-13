import apps from './en-US/apps';
import component from './en-US/component';
import desktop from './en-US/desktop';
import globalHeader from './en-US/globalHeader';
import menu from './en-US/menu';
import network from './en-US/network';
import notifications from './en-US/notifications';
import pages from './en-US/pages';
import projects from './en-US/projects';
import settingDrawer from './en-US/settingDrawer';
import settings from './en-US/settings';
import workspace from './en-US/workspace';

export default {
  'navBar.lang': 'Languages',
  'layout.user.link.help': 'Help',
  'layout.user.link.privacy': 'Privacy',
  'layout.user.link.terms': 'Terms',
  'app.preview.down.block': 'Download this page to your local project',
  ...globalHeader,
  ...menu,
  ...settingDrawer,
  ...settings,
  ...network,
  ...component,
  ...pages,
  ...workspace,
  ...apps,
  ...projects,
  ...desktop,
  ...notifications,
};
