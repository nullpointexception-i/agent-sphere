import { defineConfig } from 'vitest/config';

// Pure-function unit tests (toolRenderers, etc.) run in node; jsdom is not needed.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
