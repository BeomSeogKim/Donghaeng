/// <reference types="vite/client" />

/*
 * Vite's own `ImportMetaEnv` carries an index signature, so every VITE_* read is
 * `any` and a typo resolves to `undefined` at runtime with nothing complaining.
 * Declaring the one variable we have makes it `string | undefined`, which is
 * what forces lib/api.ts to say what happens when it is missing.
 */
interface ImportMetaEnv {
  /** Origin of the API. No trailing slash. See lib/api.ts. */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
