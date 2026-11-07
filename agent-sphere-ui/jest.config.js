/** Jest config for the UmiJS admin UI. */
module.exports = {
  testEnvironment: 'jsdom',
  roots: ['<rootDir>/src'],
  testMatch: ['**/__tests__/**/*.[jt]s?(x)', '**/?(*.)+(spec|test).[jt]s?(x)'],
  moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json', 'node'],
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1',
    '^@@/(.*)$': '<rootDir>/src/.umi/$1',
    '^@root/(.*)$': '<rootDir>/$1',
    '^@umijs/max$': '<rootDir>/test/mocks/umijs-max.js',
    '\\.(css|less|sass|scss)$': '<rootDir>/test/styleStub.js',
  },
  transform: {
    '^.+\\.[tj]sx?$': '<rootDir>/jest.transform.js',
  },
  setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],
  collectCoverageFrom: [
    'src/constants/**/*.ts',
    'src/pages/chat/components/chat/timelineModel.ts',
    'src/pages/workspace/conversationModel.ts',
    'src/pages/workspace/agentStreamStore.ts',
  ],
  clearMocks: true,
};
