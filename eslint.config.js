import globals from 'globals';

// The rule that matters here is no-undef, and it is here because of a real bug:
// app.jsx referenced deleteAllRecordings, pruneClips and clearClips without
// importing them. esbuild passes undefined names straight through, so the
// bundle built, the button rendered, the click did nothing, and the console
// stayed clean -- a dead control that looked alive. That is the failure mode a
// bundler cannot catch and a linter can, so the rule is not optional here.
export default [
  {
    files: ['src/**/*.js', 'src/**/*.jsx'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: { ...globals.browser },
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    rules: {
      'no-undef': 'error',
      'no-unused-vars': ['error', { args: 'after-used', argsIgnorePattern: '^_' }],
      'no-dupe-keys': 'error',
      'no-dupe-args': 'error',
      'no-const-assign': 'error',
      'no-redeclare': 'error',
      'no-fallthrough': 'error',
      eqeqeq: ['error', 'smart'],
      'no-console': 'off',
    },
  },
  // Build tooling and the browser-driven checks run in node and reach for
  // fixtures that may not exist until a device is up.
  {
    files: ['build.mjs', 'tools/**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: { ...globals.node, ...globals.browser },
    },
    rules: {
      'no-undef': 'error',
      'no-unused-vars': ['warn', { args: 'none' }],
    },
  },
];