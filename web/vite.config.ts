/// <reference types="vitest/config" />

import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 3000,
    strictPort: true,
    // Vite resolves the serving root to web/, so anything under design/ — the
    // token file, and the webfonts when #42 lands — is 403 in dev while
    // building fine. design/ is the source of truth the app compiles against
    // (notes/2026-08-07-design-system.md), so it has to be servable.
    fs: { allow: ['..'] },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
})
