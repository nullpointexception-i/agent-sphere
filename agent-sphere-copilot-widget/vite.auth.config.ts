import { defineConfig } from 'vite';

export default defineConfig({
  build: {
    lib: {
      entry: 'src/auth-main.ts',
      name: 'AgentSphereAuth',
      formats: ['iife'],
      fileName: () => 'agent-sphere-auth.js',
    },
    target: 'es2022',
    outDir: 'dist',
    emptyOutDir: false,
    cssCodeSplit: false,
  },
});
