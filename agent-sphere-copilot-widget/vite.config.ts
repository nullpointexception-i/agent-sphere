import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { visualizer } from 'rollup-plugin-visualizer';

export default defineConfig({
  plugins: [
    react(),
    visualizer({ filename: 'dist/analyze.html', gzipSize: true, brotliSize: true }),
    visualizer({
      filename: 'dist/analyze-stats.json',
      template: 'raw-data',
      gzipSize: true,
      brotliSize: true,
    }),
  ],
  define: {
    'process.env': '{}',
  },
  resolve: {
    alias: {
      '@segment/analytics-node': fileURLToPath(
        new URL('./src/stubs/segment-analytics.ts', import.meta.url),
      ),
      // streamdown 引入 shiki+mermaid 全家桶（16MB 包的大头），stub 为基本 markdown
      'streamdown': fileURLToPath(
        new URL('./src/stubs/streamdown.tsx', import.meta.url),
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
