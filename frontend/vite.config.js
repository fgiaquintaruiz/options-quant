/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.js'],
    include: ['src/**/*.{test,spec}.{js,jsx}'],
    coverage: {
      provider: 'v8',
      reportsDirectory: '../build/reports/coverage-frontend',
      reporter: ['text', 'html', 'lcov'],
      exclude: [
        '**/*.test.js',
        '**/*.test.jsx',
        '**/*.spec.js',
        'src/test-setup.js',
      ],
    },
  },
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
