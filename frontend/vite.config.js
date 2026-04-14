import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:9090',
      '/live-ui': 'http://localhost:9090',
      '/backtest-ui': 'http://localhost:9090',
      '/actuator': 'http://localhost:9090'
    }
  },
  build: {
    outDir: '../build/resources/main/static',
    emptyOutDir: true
  }
})
