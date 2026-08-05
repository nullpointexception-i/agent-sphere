import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  define: {
    'process.env': '{}',
  },
  resolve: {
    alias: {
      '@segment/analytics-node': fileURLToPath(
        new URL('./src/stubs/segment-analytics.ts', import.meta.url),
      ),
    },
  },
  build: {
    lib: {
      entry: 'src/main.tsx',
      name: 'AgentSphereWidget',
      formats: ['iife'],
      fileName: () => 'agent-sphere-widget.js',
    },
    target: 'es2022',
    outDir: 'dist',
    cssCodeSplit: false,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // SSE routes (AG-UI chat run/connect) break unless gzip/proxy
        // buffering is disabled for the upstream stream.
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq: any, req: any) => {
            if (req.url?.includes('/services/chat/')) {
              proxyReq.removeHeader('accept-encoding');
              proxyReq.removeHeader('Accept-Encoding');
              proxyReq.setHeader('Accept-Encoding', 'identity');
            }
          });
          proxy.on('proxyRes', (proxyRes: any, req: any, res: any) => {
            if (req.url?.includes('/services/chat/')) {
              proxyRes.headers['cache-control'] = 'no-transform';
              res.setHeader('Cache-Control', 'no-transform');
            }
          });
        },
      },
    },
  },
});
