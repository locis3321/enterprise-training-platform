import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

// 管理端默认 5176：本机 5173/5174/5175 已被其他项目占用，避免开发时端口冲突。
// 可用 ADMIN_DEV_PORT 覆盖（例如本机安全软件拦截某端口时换端口排查）。
const DEV_PORT = Number(process.env.ADMIN_DEV_PORT || 5176)
// 后端本地端口 8081：本机 8080 被系统保留（bind 报 WinError 10013），
// 见 services/api/src/main/resources/application-local.yml 的说明。
// 可用 ADMIN_API_TARGET 覆盖，便于同时跑多个后端实例时联调。
const API_TARGET = process.env.ADMIN_API_TARGET || 'http://127.0.0.1:8081'
// 生产部署在 https://<域名>/admin 子路径下；本地开发仍是根路径。
// 子路径构建用 `pnpm build:admin`（mode=admin）读取 .env.admin 的 VITE_APP_BASE。
// 不走环境变量传 base：Git Bash 会把 ADMIN_BASE=/admin/ 做 MSYS 路径转换，
// 改写成 D:/Program Files/Git/admin/，导致产物资源 404、页面白屏。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const BASE = env.VITE_APP_BASE || process.env.VITE_APP_BASE || process.env.ADMIN_BASE || '/'

  return {
  base: BASE,
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: DEV_PORT,
    strictPort: true,
    proxy: {
      // 开发期走代理，避免为本地调试放宽后端 CORS
      '/api': {
        target: API_TARGET,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
  }
})
