/// <reference types="vitest" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  esbuild: {
    // 生产构建丢弃 debugger；console.debug/log 视为无副作用调用，压缩时移除（保留 warn/error）
    drop: ['debugger'],
    pure: ['console.debug', 'console.log'],
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 3002,
    host: true,
    proxy: {
      // HAgent 后端在 8099、context-path 为 '/'；前端 request 统一带 /api 前缀，这里去掉前缀转发
      '/api': {
        target: 'http://localhost:8099',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
})