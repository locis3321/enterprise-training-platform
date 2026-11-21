# 部署与配置说明

本文覆盖本地开发环境、生产部署形态、全部环境变量与构建产物校验。

## 1. 组件与拓扑

```text
                    ┌──────────────┐
   微信小程序 ──────▶│              │
                    │    Nginx     │  TLS 终止 + 静态资源 + API 反向代理
   浏览器    ──────▶│              │
                    └──────┬───────┘
                           │ /api/v1  (X-Forwarded-For)
                           ▼
                    ┌──────────────┐
                    │ Spring Boot  │  :8080
                    └──────┬───────┘
              ┌────────────┼────────────┐
              ▼            ▼            ▼
          MySQL 8       Redis 7     MinIO（私有桶）
```

应用服务（后端、管理端、小程序）可在宿主机运行或容器化；`deploy/compose/docker-compose.dev.yml`
只编排 MySQL / Redis / MinIO 三个依赖，便于本地热重载与断点调试。

## 2. 环境变量

### 2.1 后端（`services/api`）

生产 profile 为 `prod`（`APP_ENV=prod`）。下表中标注「必填」的项目缺失时服务**启动失败**，
这是刻意的设计——连接信息与密钥不允许有隐式兜底。

| 变量 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `APP_ENV` | | `local` | 激活的 Spring profile，`prod` 关闭 Swagger 与健康详情 |
| `SERVER_PORT` | | `8080` | 监听端口 |
| `MYSQL_HOST` / `MYSQL_PORT` | 是（prod） | `3306` | 数据库地址与端口 |
| `MYSQL_DATABASE` | 是（prod） | | 数据库名 |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 是（prod） | | 数据库凭据 |
| `MYSQL_USE_SSL` | | `false` | 数据库要求 TLS 时置 `true` 并配置 JVM truststore |
| `DB_POOL_SIZE` | | `20` | HikariCP 最大连接数 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | | `127.0.0.1` / `6379` / 空 | Redis 连接信息 |
| `APP_JWT_SECRET` | 是（prod） | | JWT 签名密钥，长度需 ≥ 32 字节；`local` profile 有开发用默认值 |
| `MINIO_ENDPOINT` | 是 | | 服务端可访问的 MinIO 地址 |
| `MINIO_PUBLIC_ENDPOINT` | | | 客户端可访问的地址；与服务端地址不同时必须显式配置 |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | 是 | | 对象存储凭据 |
| `MINIO_BUCKET` | | `training-private` | 私有桶名称 |
| `WECHAT_APPID` | | `touristappid` | 小程序 AppID；生产请在管理端「系统设置」中配置 |
| `TRUSTED_PROXIES` | | `127.0.0.1,::1` | 受信任反向代理地址；仅这些来源的 `X-Forwarded-For` 可信 |

> **反向代理注意事项**：若后端未配置 `TRUSTED_PROXIES`，所有请求的客户端 IP 都会退化为
> `127.0.0.1`，导致登录限流与审计日志失真。生产部署必须按实际 Nginx 所在网段配置。

### 2.2 管理端（`apps/admin`）

| 文件 | 用途 |
|---|---|
| `.env.example` | 变量样例，复制为 `.env.local` 使用（`.env.local` 已被忽略） |
| `.env.admin` | `pnpm build:admin` 使用的生产子路径构建配置 |

| 变量 | 说明 |
|---|---|
| `VITE_API_BASE_URL` | 后端 API 前缀，默认 `/api/v1`；生产由 Nginx 反代到后端 |
| `VITE_APP_TITLE` | 浏览器标题 |
| `VITE_APP_BASE` | 静态资源 base path，仅在 `.env.admin` 中定义为 `/admin/` |

### 2.3 小程序（`apps/miniapp`）

Vite 的 `envDir` 指向 `apps/miniapp/env`，因此 `env/` 下的 `.env` 与 `.env.*` 才是生效文件，
放在项目根目录的 `.env.local` 不会被任何模式加载。`env/` 下所有文件已被 `.gitignore` 忽略，
请从 [`apps/miniapp/env/.env.example`](../apps/miniapp/env/.env.example) 复制后按需修改。

| 变量 | 说明 |
|---|---|
| `VITE_APP_TITLE` | 小程序标题 |
| `VITE_WX_APPID` | 微信小程序 AppID |
| `VITE_SERVER_BASEURL` | 后端地址（不含 `/api/v1` 之外的额外前缀） |
| `VITE_SERVER_BASEURL__WEIXIN_DEVELOP` | 微信开发者工具「开发版」环境专用地址 |
| `VITE_APP_MOCK_WECHAT` | `true` 时登录页显示手机号模拟入口，仅用于无资质时的本地联调 |
| `WECHAT_DEVTOOLS_CLI_PATH` | 微信开发者工具 CLI 路径，供 `pnpm dev:mp` 自动拉起 IDE |

## 3. 本地开发部署

```bash
# 1. 依赖服务
cd deploy/compose
cp .env.example .env
docker compose -f docker-compose.dev.yml up -d

# 2. 后端
export JAVA_HOME="<你的 JDK 21 安装路径>"
export MYSQL_PASSWORD=local_app_change_me
mvn -f services/api/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments=--server.port=8081

# 3. 管理端
cd apps/admin && pnpm install && pnpm dev        # http://127.0.0.1:5176

# 4. 小程序
cd apps/miniapp && pnpm install
cp .env.example env/.env
pnpm dev:mp                                        # 产物 dist/dev/mp-weixin
```

| 组件 | 端口 |
|---|---|
| Spring Boot API | 8081（本地固定，避开宿主机 8080 占用） |
| 管理端 Vite | 5176（`strictPort: true`，端口冲突会直接失败而非静默改号） |
| MySQL / Redis | 3306 / 6379 |
| MinIO API / 控制台 | 9000 / 9001 |

## 4. 生产部署

### 4.1 后端

```bash
export JAVA_HOME="<你的 JDK 21 安装路径>"
mvn -f services/api/pom.xml clean package -DskipTests
# 产物：services/api/target/insurance-training-api-0.1.0-SNAPSHOT.jar
```

以环境变量方式注入配置后启动：

```bash
export APP_ENV=prod
export MYSQL_HOST=... MYSQL_DATABASE=... MYSQL_USERNAME=... MYSQL_PASSWORD=...
export APP_JWT_SECRET='<≥32 字节随机串>'
export MINIO_ENDPOINT=... MINIO_ACCESS_KEY=... MINIO_SECRET_KEY=... MINIO_PUBLIC_ENDPOINT=...
export REDIS_HOST=... TRUSTED_PROXIES=...
java -jar services/api/target/insurance-training-api-0.1.0-SNAPSHOT.jar
```

首次启动自动执行 Flyway 迁移。**迁移属 DDL 变更，生产执行前请先备份并确认迁移脚本内容。**

### 4.2 管理端

| 部署形态 | 命令 | 产物资源路径 |
|---|---|---|
| 根路径 | `pnpm build` | `/assets/...` |
| 子路径 `/admin/` | `pnpm build:admin` | `/admin/assets/...` |

`build:admin` 通过 `--mode admin` 读取 `.env.admin` 的 `VITE_APP_BASE`。

> **不要**使用 `ADMIN_BASE=/admin/ pnpm build`：Git Bash 的 MSYS 会把 `/admin/` 转换为
> `D:/Program Files/Git/admin/`，产物资源路径全部错误、页面白屏。

上传前必须校验产物：

```bash
cd apps/admin
grep -oE '(src|href)="[^"]*"' dist/index.html   # 应为 /admin/assets/...
grep -rl 'Program Files' dist/                  # 应为空
grep -rlE 'file://|[A-Z]:/' dist/               # 应为空
```

### 4.3 Nginx

```nginx
server {
    listen 443 ssl;
    server_name example.com;

    ssl_certificate     /path/to/fullchain.pem;
    ssl_certificate_key /path/to/privkey.pem;

    location /admin/ {
        alias /var/www/training-admin/;
        try_files $uri $uri/ /admin/index.html;
    }

    location /api/v1/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 1030m;   # 与后端视频上传上限一致
    }
}
```

`client_max_body_size` 必须大于等于后端 `spring.servlet.multipart.max-request-size`（1030MB），
否则大文件上传会在 Nginx 层被截断。

### 4.4 小程序

```bash
cd apps/miniapp
pnpm install
# 先在 env/.env 或 env/.env.production 中把 VITE_SERVER_BASEURL 指向生产 HTTPS 地址
pnpm build:mp:prod          # 产物 dist/build/mp-weixin
```

用微信开发者工具导入 `dist/build/mp-weixin` 目录并上传体验版 / 提交审核。

命令行上传（需在公众平台开通「小程序代码上传」权限、配置上传 IP 白名单，并提供
`apps/miniapp/private.<appid>.key` 私钥文件）：

```bash
pnpm upload:mp --version=1.0.0 --desc="版本说明"
```

私钥文件与 `.env` 均已被 `.gitignore` 忽略，**不得提交到仓库**。

## 5. 构建产物校验清单

| 产物 | 校验项 |
|---|---|
| 后端 jar | 文件大小合理；`java -jar` 能启动并通过 `/actuator/health` |
| 管理端 dist | `dist/index.html` 中的资源路径为站内相对路径；无 `Program Files`、`file://`、本机盘符 |
| 小程序 dist | 开发者工具导入后无报错；`app.json` 与页面清单一致 |

## 6. 常见问题

| 现象 | 原因与处理 |
|---|---|
| 后端启动报端口占用 | 宿主注入了 `SERVER__PORT`，其优先级高于配置文件；用命令行 `--server.port` 显式指定 |
| 管理端部署后白屏、静态资源 404 | 构建时 base 被 MSYS 路径转换；改用 `pnpm build:admin` 并按 4.2 校验产物 |
| 登录限流不生效 / 审计里 IP 都是 127.0.0.1 | 未配置 `TRUSTED_PROXIES`，反向代理来源 IP 不可信 |
| 生产启动即失败并提示某项缺失 | prod profile 对连接信息与密钥刻意不设兜底，按 2.1 补齐环境变量 |
| 小程序改了 `env/.env` 但无效果 | Vite 的 `envDir` 指向 `apps/miniapp/env`，根目录 `.env.local` 不生效 |
