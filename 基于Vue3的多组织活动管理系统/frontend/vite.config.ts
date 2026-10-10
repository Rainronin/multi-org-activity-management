import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 正式前端固定 127.0.0.1:5174；/api 代理到后端 8080。
// 端口占用时直接报错不漂移（开发规范 2.1）；原型演示继续使用 UI演示/ 的 5173。
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
      },
    },
  },
})
