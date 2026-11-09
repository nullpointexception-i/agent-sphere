export default {
  dev: {
    '/api/': {
      // Env override lets the UI run against a remote backend without editing
      // this file; the default stays the local Spring Boot port.
      target: process.env.API_PROXY_TARGET || 'http://localhost:8080',
      changeOrigin: true,
      onProxyReq: (proxyReq: any, req: any) => {
        if (req.url?.includes('/stream')) {
          proxyReq.removeHeader('accept-encoding');
          proxyReq.removeHeader('Accept-Encoding');
          proxyReq.setHeader('Accept-Encoding', 'identity');
        }
      },
      onProxyRes: (proxyRes: any, _req: any, res: any) => {
        if (_req.url?.includes('/stream')) {
          res.setHeader('Cache-Control', 'no-transform');
        }
      },
    },
  },
  test: {
    '/api/': {
      target: 'https://pro-api.ant-design-demo.workers.dev',
      changeOrigin: true,
    },
  },
  pre: {
    '/api/': {
      target: 'your pre url',
      changeOrigin: true,
    },
  },
};
