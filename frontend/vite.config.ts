import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'SLANA_')
  const uptimeServiceTarget = env.SLANA_UPTIME_SERVICE_TARGET || 'http://localhost:8080'

  return {
    plugins: [react(), tailwindcss()],
    resolve: { alias: { buffer: 'buffer/' } },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      css: false,
      restoreMocks: true,
    },
    server: {
      proxy: {
        '/api': { target: uptimeServiceTarget, changeOrigin: true },
      },
    },
  }
})
