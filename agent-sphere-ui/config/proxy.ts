export default {
  dev: {
    '/api/': {
      target: 'http://localhost:8080',
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
