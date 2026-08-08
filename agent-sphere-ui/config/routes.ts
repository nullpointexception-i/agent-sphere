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
    path: '',
    key: 'workbench',
    name: 'workbench',
    icon: 'home',
    routes: [
      {
        path: 'dashboard',
        name: 'dashboard',
        icon: 'dashboard',
        component: './dashboard',
      },
    ],
  },
  {
    path: '',
    key: 'capabilities',
    name: 'capabilities',
    icon: 'api',
    access: 'canManageCapabilities',
    routes: [
      {
        path: 'capabilities/mcp',
        name: 'MCP',
        icon: 'api',
        locale: 'menu.capabilities.mcp',
        access: 'canManageMcp',
        component: './capabilities/mcp',
      },
      {
        path: 'capabilities/skill',
        name: 'Skill',
        icon: 'code',
        locale: 'menu.capabilities.skill',
        access: 'canManageSkill',
        component: './capabilities/skill',
      },
      {
        path: 'capabilities/cli',
        name: 'CLI',
        icon: 'tool',
        locale: 'menu.capabilities.cli',
        access: 'canManageCli',
        component: './capabilities/cli',
      },
      {
        path: 'capabilities/builtin',
        name: 'Builtin',
        icon: 'tool',
        locale: 'menu.capabilities.builtin',
        access: 'canManageBuiltin',
        component: './capabilities/builtin',
      },
    ],
  },
  {
    path: '',
    key: 'agents',
    name: 'agents',
    icon: 'robot',
    routes: [
      {
        path: 'models',
        name: 'models',
        icon: 'cloud',
        access: 'canManageModels',
        component: './model-providers',
      },
      {
        path: 'completions',
        name: 'completions',
        icon: 'thunderbolt',
        access: 'canManageCompletions',
        component: './completions',
      },
      {
        path: 'instances',
        name: 'instances',
        icon: 'robot',
        access: 'canManageInstances',
        component: './instances',
      },
      {
        path: 'tasks',
        name: 'tasks',
        icon: 'carryOut',
        access: 'canManageTasks',
        component: './tasks',
      },
      {
        path: 'chat',
        name: 'chat',
        icon: 'message',
        component: './chat',
      },
      {
        path: 'chat/:sessionId',
        name: 'chat',
        icon: 'message',
        component: './chat',
        hideInMenu: true,
      },
    ],
  },
  {
    path: '',
    key: 'artifacts',
    name: 'artifacts',
    icon: 'folderOpen',
    access: 'canViewDocuments',
    routes: [
      {
        path: 'artifacts/documents',
        name: 'documents',
        icon: 'fileText',
        access: 'canViewDocuments',
        component: './artifacts/documents',
      },
      {
        path: 'artifacts/documents/:id',
        component: './artifacts/documents/detail',
        hideInMenu: true,
      },
      {
        path: 'artifacts/documents/:id/edit',
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
    layout: false,
    access: 'canAdmin',
    routes: [
      {
        path: '/admin',
        component: './admin/layout',
        routes: [
          { path: 'users', component: './admin/users' },
          { path: 'roles', component: './admin/roles' },
          { path: 'permissions', component: './admin/permissions' },
          { path: 'settings', component: './admin/settings' },
          { path: 'audit-logs', component: './admin/audit-logs' },
          {
            path: 'identity-providers',
            component: './admin/identity-providers',
          },
          { path: '', redirect: '/admin/users' },
        ],
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
