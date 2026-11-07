/**
 * Jest transformer backed by esbuild (already a transitive dependency).
 *
 * <p>Keeps tests independent of Babel presets: TypeScript syntax is stripped and JSX is
 * compiled to the automatic runtime, then emitted as CommonJS for Jest.
 */
const esbuild = require('esbuild');

module.exports = {
  process(sourceText, sourcePath) {
    const result = esbuild.transformSync(sourceText, {
      loader: sourcePath.endsWith('.tsx')
        ? 'tsx'
        : sourcePath.endsWith('.ts')
          ? 'ts'
          : sourcePath.endsWith('.jsx')
            ? 'jsx'
            : 'js',
      format: 'cjs',
      target: 'node20',
      jsx: 'automatic',
      sourcefile: sourcePath,
      sourcemap: 'inline',
    });
    return { code: result.code };
  },
};
