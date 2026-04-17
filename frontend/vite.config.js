import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': { target: 'http://localhost:9090', changeOrigin: true },
      '/live-ui': { target: 'http://localhost:9090', changeOrigin: true },
      '/backtest-ui': { 
        target: 'http://localhost:9090', 
        changeOrigin: true,
        secure: false,
        // Critical for SSE: ensure no buffering
        headers: {
          'Connection': 'keep-alive',
          'Cache-Control': 'no-cache'
        }
      },
      '/actuator': { target: 'http://localhost:9090', changeOrigin: true },
      '/charts': { target: 'http://localhost:9090', changeOrigin: true }
    }
  },
  build: {
    outDir: '../build/resources/main/static',
    emptyOutDir: true
  }
})
