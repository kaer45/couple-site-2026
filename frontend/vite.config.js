import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// Vite 配置：Vue 插件 + 开发代理（/api、/uploads 转发到后端）+ 构建产物目录
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // @ 指向 src，方便模块引用
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      // 后端 API（契约约定 Base URL 为 /api）
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // 后端静态资源（上传的图片，如 /uploads/xxx.jpg）
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist'
  }
})
