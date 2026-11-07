// https://umijs.org/config/

import { join } from 'node:path';
import { defineConfig } from '@umijs/max';
import defaultSettings from './defaultSettings';
import proxy from './proxy';

import routes from './routes';

const { UMI_ENV = 'dev' } = process.env;

// Compute commit hash: env vars take precedence, fall back to git at build time
const commitHash =
  process.env.COMMIT_HASH ||
  process.env.CF_PAGES_COMMIT_SHA ||
  (() => {
    try {
      return require('node:child_process')
        .execSync('git rev-parse HEAD', {
          stdio: ['ignore', 'pipe', 'ignore'],
          encoding: 'utf-8',
        })
        .trim();
    } catch {
      return '';
    }
  })();

/**
 * @name public path
 * @description Deployment path. Configure this variable when deploying under a non-root directory.
 * @doc https://umijs.org/docs/api/config#publicpath
 */
const PUBLIC_PATH: string = '/';

export default defineConfig({
  alias: {
    '@root': join(__dirname, '..'),
  },
  /**
   * @name enable hash mode
   * @description Append a hash suffix to build output. Commonly used for incremental releases and to avoid browser cache hits.
   * @doc https://umijs.org/docs/api/config#hash
   */
  hash: true,

  publicPath: PUBLIC_PATH,

  /**
   * @name compatibility settings
   * @description Targeting ie11 is not guaranteed to work perfectly; verify every dependency you use.
   * @doc https://umijs.org/docs/api/config#targets
   */
  // targets: {
  //   ie: 11,
  // },
  /**
   * @name route configuration; files not referenced by routes are not compiled
   * @description Only `path`, `component`, `routes`, `redirect`, `wrappers` and `title` are supported.
   * @doc https://umijs.org/docs/guides/routes
   */
  // umi routes: https://umijs.org/docs/routing
  routes,
  /**
   * @name theme configuration
   * @description Called a theme, but really just Less variable overrides.
   * @doc umi theme configuration https://umijs.org/docs/api/config#theme
   */
  // theme: { '@primary-color': '#1DA57A' }
  /**
   * @name moment locale configuration
   * @description If you do not need locales, enabling this reduces the JS bundle size.
   * @doc https://umijs.org/docs/api/config#ignoremomentlocale
   */
  ignoreMomentLocale: true,
  /**
   * @name proxy configuration
   * @description Lets your local server proxy requests to your backend server so you can access its data.
   * @see Note: proxies only work during local development and are unavailable after build.
   * @doc proxy guide https://umijs.org/docs/guides/proxy
   * @doc proxy configuration https://umijs.org/docs/api/config#proxy
   */
  proxy: proxy[UMI_ENV as keyof typeof proxy],
  /**
   * @name fast refresh
   * @description A solid hot-reload integration that preserves state across updates.
   */
  fastRefresh: true,
  /**
   * @name route prefetch
   * @description Prefetch route resources to speed up page transitions.
   * @doc https://umijs.org/docs/api/config#routePrefetch
   */
  routePrefetch: {},
  /**
   * @name manifest configuration
   * @description Generate an asset manifest, used together with routePrefetch.
   */
  manifest: {},
  //============== max plugin configuration below ===============
  /**
   * @name data flow plugin
   * @@doc https://umijs.org/docs/max/data-flow
   */
  model: {},
  initialState: {},
  /**
   * @name layout plugin
   * @doc https://umijs.org/docs/max/layout-menu
   */
  title: 'Agent Sphere',
  layout: {
    locale: true,
    ...defaultSettings,
  },
  /**
   * @name moment2dayjs plugin
   * @description Replace moment with dayjs across the project.
   * @doc https://umijs.org/docs/max/moment2dayjs
   */
  moment2dayjs: {
    preset: 'antd',
    plugins: ['duration', 'relativeTime'],
  },
  /**
   * @name i18n plugin
   * @doc https://umijs.org/docs/max/i18n
   */
  locale: {
    // default zh-CN
    default: 'zh-CN',
    antd: true,
    // default true, when it is true, will use `navigator.language` overwrite default
    baseNavigator: true,
  },
  /**
   * @name antd plugin
   * @description Bundles the babel import plugin.
   * @doc https://umijs.org/docs/max/antd#antd
   */
  antd: {
    configProvider: {
      variant: 'filled',
      theme: {
        token: {
          fontFamily: 'AlibabaSans, sans-serif',
        },
      },
    },
  },
  /**
   * @name network request configuration
   * @description Unified network request and error handling built on axios and ahooks `useRequest`.
   * @doc https://umijs.org/docs/max/request
   */
  request: {},
  /**
   * @name React Query plugin
   * @description Manage server state with react-query.
   * @doc https://umijs.org/docs/max/react-query
   */
  reactQuery: {},
  /**
   * @name access plugin
   * @description Access plugin based on initialState; initialState must be enabled first.
   * @doc https://umijs.org/docs/max/access
   */
  access: {},
  /**
   * @name Google Analytics
   * @description Site analytics via GA4 (gtag.js).
   * @doc https://umijs.org/docs/max/analytics
   */
  analytics: {
    ga_v2: 'G-59NF1VHHPF',
  },
  /**
   * @name extra script in <head>
   * @description Configure extra scripts injected into <head>.
   */
  headScripts: [
    `var l=document.createElement('link');l.rel='icon';l.href='/favicon.svg';document.head.appendChild(l)`,
    // Avoid a blank screen during the first load
    { src: join(PUBLIC_PATH, 'scripts/loading.js'), async: true },
  ],

  mock: {},
  exportStatic: {},
  esbuildMinifyIIFE: true,
  define: {
    'process.env.CI': process.env.CI,
    'process.env.COMMIT_HASH': commitHash,
    __APP_VERSION__: require('./../package.json').version,
    __UMI_VERSION__: require('@umijs/max/package.json').version,
    __UTOO_VERSION__: require('@utoo/pack/package.json').version,
  },
});
