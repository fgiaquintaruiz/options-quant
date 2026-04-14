import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:9090',
      '/live-ui': { target: 'http://localhost:9090', bypass: () => null },
      '/backtest-ui': { target: 'http://localhost:9090', bypass: () => null },
      '/actuator': 'http://localhost:9090'
    }
  },
  build: {
    outDir: '../build/resources/main/static',
    emptyOutDir: true
  }
})
