import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { visualizer } from 'rollup-plugin-visualizer';

export default defineConfig(({ mode }) => ({
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
    'process.env.NODE_ENV': JSON.stringify(
      mode === 'production' ? 'production' : 'development',
    ),
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
        // SSE routes (/runtime/{sid}/stream) break unless gzip/proxy
        // buffering is disabled for the upstream stream.
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq: any, req: any) => {
            if (req.url?.includes('/stream')) {
              proxyReq.removeHeader('accept-encoding');
              proxyReq.removeHeader('Accept-Encoding');
              proxyReq.setHeader('Accept-Encoding', 'identity');
            }
          });
          proxy.on('proxyRes', (proxyRes: any, req: any, res: any) => {
            if (req.url?.includes('/stream')) {
              proxyRes.headers['cache-control'] = 'no-transform';
              res.setHeader('Cache-Control', 'no-transform');
            }
          });
        },
      },
    },
  },
}));
