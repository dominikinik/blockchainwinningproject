import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'SLANA_')
  const uptimeServiceTarget = env.SLANA_UPTIME_SERVICE_TARGET || 'http://localhost:8080'

  return {
    plugins: [react(), tailwindcss()],
    resolve: { alias: { buffer: 'buffer/' } },
    server: {
      proxy: {
        '/api': { target: uptimeServiceTarget, changeOrigin: true },
      },
    },
  }
})
