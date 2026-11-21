# admin（管理后台）

企业内训平台管理端。Vue 3 + TypeScript + Vite 5 + Element Plus + Pinia + Vue Router。

当前为 Task 01 骨架：登录页为**占位**（不伪造登录、不写死账号），只有仪表盘接入了真实后端连通性检测。

## 目录

```text
src/
├── main.ts                 # 应用入口：Pinia、Router、Element Plus、全局样式
├── App.vue
├── router/index.ts         # 路由表 + 登录态守卫
├── stores/auth.ts          # 登录态（token 仅存本地，权限以后端为准）
├── layouts/DefaultLayout.vue  # 侧边分组菜单，未开放模块标「待开发」
├── views/
│   ├── login/LoginView.vue     # 占位登录页，点击只提示「登录接口待 Task 02 接入」
│   └── dashboard/DashboardView.vue  # 调用 GET /api/v1/ping，展示连通性与服务器时间
├── api/system.ts           # 接口封装
├── utils/request.ts        # axios 实例：401 清 token 跳登录，code !== 200 视为业务失败
├── styles/tokens.css       # 设计变量（--color-primary: #0e5a8a 等），禁止硬编码颜色
└── types/                  # env.d.ts、router.d.ts（路由元信息类型增强）
```

## 本地运行

```bash
pnpm install
pnpm dev        # http://127.0.0.1:5176
```

- 端口固定 5176（`strictPort: true`）：本机 5173 已被其他项目占用。
- `/api` 由 Vite 代理到 `http://127.0.0.1:8081`，前端代码里只写相对路径 `/api/v1/...`，
  不要在页面里硬编码后端地址，也不要为本地调试放宽后端 CORS。
- 后端启动方式见 `services/api/README.md`（必须显式 `--server.port=8081`）。

## 构建

```bash
pnpm build        # vue-tsc --noEmit && vite build
pnpm preview      # 预览 dist
```

## 约定

- 页面只做展示与交互，权限、数据范围、截止时间、判分一律由后端再次校验。
- 颜色、间距、圆角只用 `styles/tokens.css` 的变量，不在组件里写十六进制色值。
- 接口统一走 `utils/request.ts`，不要各自 `new axios`。
- 表格分页、筛选、导入结果、错误提示要统一（Task 02 起逐步补齐）。
