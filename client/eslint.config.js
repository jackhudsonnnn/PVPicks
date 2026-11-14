import js from "@eslint/js";
import eslintPluginPrettier from "eslint-plugin-prettier/recommended";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import tseslint from "typescript-eslint";
import { readdirSync } from "node:fs";

const serverOnly = {
  name: "server-only",
  message:
    "TanStack Start does not use the Next.js `server-only` package. Rename the module to `*.server.ts` or mark it with `@tanstack/react-start/server-only`.",
};
const features = readdirSync(new URL("./src/features", import.meta.url), { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name);
const deepImport = (allowed) => ({
  regex: allowed ? `^@/features/(?!${allowed}/)[^/]+/` : "^@/features/[^/]+/",
  message: "Import other features through their index: `@/features/<name>`.",
});
const restrict = (...patterns) => ["error", { paths: [serverOnly], patterns }];
const tests = ["src/**/*.test.{ts,tsx}", "src/test/**"];

/**
 * Module boundaries: `app` (the frame) may use features; features use each other only through their `index.ts`;
 * `lib` and `components` are shared and never import features or the app. Tests may import any module, so they can
 * mock exactly what they need.
 */
const boundaries = [
  {
    files: ["src/**/*.{ts,tsx}"],
    rules: { "no-restricted-imports": restrict(deepImport(null)) },
  },
  ...features.map((feature) => ({
    files: [`src/features/${feature}/**/*.{ts,tsx}`],
    rules: {
      "no-restricted-imports": restrict(deepImport(feature), {
        group: ["../*", "@/app", "@/app/*"],
        message:
          "Features don't import the app frame, and use `./` or `@/features/<own>/…` instead of `../`.",
      }),
    },
  })),
  {
    files: ["src/lib/**/*.{ts,tsx}", "src/components/**/*.{ts,tsx}"],
    rules: {
      "no-restricted-imports": restrict({
        group: ["@/features", "@/features/*", "@/app", "@/app/*"],
        message: "Shared code doesn't depend on features or the app frame.",
      }),
    },
  },
  {
    files: tests,
    rules: { "no-restricted-imports": restrict() },
  },
];

export default tseslint.config(
  { ignores: ["dist", ".output", ".vinxi"] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ["**/*.{ts,tsx}"],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    plugins: {
      "react-hooks": reactHooks,
      "react-refresh": reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      "no-restricted-imports": restrict(),
      "react-refresh/only-export-components": ["warn", { allowConstantExport: true }],
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_", caughtErrors: "none" },
      ],
    },
  },
  ...boundaries,
  eslintPluginPrettier,
);
