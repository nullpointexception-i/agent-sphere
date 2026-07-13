export default [
  {
    path: '/user',
    layout: false,
    routes: [
      {
        path: '/user/login',
        name: 'login',
        component: './user/login',
      },
      {
        path: '/user/register',
        name: 'register',
        component: './user/register',
      },
      {
        path: '/user',
        redirect: '/user/login',
      },
      {
        name: '404',
        component: './exception/404',
        path: '/user/*',
      },
    ],
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    icon: 'dashboard',
    component: './dashboard',
  },
  {
    path: '/capabilities',
    name: 'capabilities',
    icon: 'api',
    routes: [
      {
        path: '/capabilities/mcp',
        name: 'MCP',
        icon: 'api',
        locale: 'menu.capabilities.mcp',
        component: './capabilities/mcp',
      },
      {
        path: '/capabilities/skill',
        name: 'Skill',
        icon: 'code',
        locale: 'menu.capabilities.skill',
        component: './capabilities/skill',
      },
      {
        path: '/capabilities/cli',
        name: 'CLI',
        icon: 'tool',
        locale: 'menu.capabilities.cli',
        component: './capabilities/cli',
      },
      {
        path: '/capabilities/builtin',
        name: 'Builtin',
        icon: 'tool',
        locale: 'menu.capabilities.builtin',
        component: './capabilities/builtin',
      },
    ],
  },
  {
    path: '/models',
    name: 'models',
    icon: 'cloud',
    component: './model-providers',
  },
  {
    path: '/instances',
    name: 'instances',
    icon: 'robot',
    component: './instances',
  },
  {
    path: '/chat',
    name: 'chat',
    icon: 'message',
    component: './chat',
  },
  {
    path: '/chat/:sessionId',
    name: 'chat',
    icon: 'message',
    component: './chat',
    hideInMenu: true,
  },
  {
    path: '/artifacts',
    name: 'artifacts',
    icon: 'folderOpen',
    routes: [
      {
        path: '/artifacts/documents',
        name: 'documents',
        icon: 'fileText',
        component: './artifacts/documents',
      },
      {
        path: '/artifacts/documents/:id',
        component: './artifacts/documents/detail',
        hideInMenu: true,
      },
      {
        path: '/artifacts/documents/:id/edit',
        component: './artifacts/documents/edit',
        hideInMenu: true,
      },
    ],
  },
  {
    path: '/account/profile',
    name: 'profile',
    icon: 'user',
    component: './account/profile',
    hideInMenu: true,
  },
  {
    path: '/account/password',
    name: 'password',
    icon: 'key',
    component: './account/password',
    hideInMenu: true,
  },
  {
    path: '/admin',
    name: 'admin',
    icon: 'setting',
    access: 'canAdmin',
    routes: [
      {
        path: '/admin/settings',
        name: 'settings',
        component: './admin/settings',
      },
    ],
  },
  {
    path: '/s',
    layout: false,
    routes: [
      {
        path: '/s/:shareToken',
        component: './artifacts/documents/shared',
      },
    ],
  },
  {
    path: '/',
    redirect: '/dashboard',
  },
  {
    component: './exception/404',
    path: '/*',
  },
];
