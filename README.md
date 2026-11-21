# 企业内训平台

面向集团与其子公司/部门的通用企业内训平台。管理员在 Web 后台组织单位、员工、课程、培训包与培训任务；
员工通过微信小程序完成学习与考试，全程的学习进度、考试准入、评分与合格判定均由服务端裁决。

## 特性

- **三级逐级自治**：平台管理员 / 集团管理员 / 子公司管理员，数据范围由后端强制校验，越权一律 403
- **课程版本化**：培训包发布后生成不可变版本快照，培训任务绑定具体版本，变更发布新版本而不影响存量任务
- **服务端裁决**：课程完成、考试准入、考试时间、交卷、评分、合格状态全部由服务端判定，客户端不可伪造
- **学习进度可追溯**：心跳上报 + 有效观看记录，按章节与课程逐级判定完成度
- **考试留痕**：每次考试创建独立 attempt，保留全部历史成绩，支持重考并展示最佳成绩
- **报表与导出**：子公司汇总、任务汇总、个人学习与考试明细，服务端聚合，手机号脱敏，支持 Excel 导出
- **配置热加载**：6 项业务参数入库可热改，无需重启；密钥类配置仍由环境变量管理
- **私有文件存储**：MinIO 私有桶 + 短期签名 URL，学员资料不落公开桶

## 功能模块

| 端 | 能力 |
|---|---|
| 管理端 | 组织与员工 CRUD、Excel 批量导入、课程与章节管理、资源上传、题库与试卷、培训包编排与发布、培训任务分配与进度、三级账号管理、培训报表与导出、系统设置 |
| 小程序 | 微信登录与手机号绑定、首页任务概览、课程与章节学习、视频播放与资料查看、在线考试与成绩、培训档案、改密、隐私协议 |

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 21、Spring Boot 3.5、MyBatis-Plus、Flyway、Spring Security Crypto、jjwt、Apache POI |
| 管理端 | Vue 3.5、TypeScript 5.8、Vite 5、Element Plus、Pinia、Vue Router |
| 小程序 | uni-app 3.0（微信小程序）、Vue 3.4、TypeScript、WotUI、UnoCSS、alova、z-paging |
| 数据 | MySQL 8、Redis 7 |
| 存储 | MinIO 私有桶（预签名 URL） |
| 部署 | Docker Compose、Nginx |

## 目录结构

```text
.
├── apps/
│   ├── admin/          # Vue 3 管理后台
│   └── miniapp/        # uni-app 微信小程序
├── services/
│   └── api/            # Spring Boot 模块化单体
├── deploy/
│   └── compose/        # 本地依赖编排（MySQL / Redis / MinIO）
├── docs/
│   └── DEPLOYMENT.md   # 部署与配置说明
├── licenses/           # 第三方依赖与代码来源声明
├── AGENTS.md           # 面向 AI 编码助手的工程规则
└── LICENSE
```

## 快速开始

### 环境要求

- JDK 21、Maven 3.9+
- Node.js 20+、pnpm 10
- Docker 与 Docker Compose
- 微信开发者工具（仅小程序开发需要）

### 1. 启动本地依赖（MySQL / Redis / MinIO）

```bash
cd deploy/compose
cp .env.example .env          # 按需修改其中的口令
docker compose -f docker-compose.dev.yml up -d
docker compose -f docker-compose.dev.yml ps -a   # 三个服务应为 healthy
```

### 2. 启动后端

```bash
export JAVA_HOME="<你的 JDK 21 安装路径>"
export MYSQL_PASSWORD=local_app_change_me        # 与 deploy/compose/.env 保持一致
mvn -f services/api/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments=--server.port=8081
```

首次启动会自动执行 Flyway 迁移（`services/api/src/main/resources/db/migration`），本地空库从 `V1__baseline.sql` 起。

> `--server.port` 需显式传入：部分宿主进程会注入 `SERVER__PORT` 环境变量，其优先级高于
> `application-local.yml`，会导致服务抢占非预期端口。

### 3. 启动管理端

```bash
cd apps/admin
pnpm install
pnpm dev        # http://127.0.0.1:5176
```

`/api` 由 Vite 代理转发到后端，本地调试无需放宽后端 CORS。

### 4. 启动小程序

```bash
cd apps/miniapp
pnpm install
cp .env.example env/.env     # 按需填写微信 AppID 与后端地址
pnpm dev:mp                  # 产物在 dist/dev/mp-weixin
```

用微信开发者工具导入 `apps/miniapp/dist/dev/mp-weixin` 目录。

### 5. 自检

```bash
curl http://127.0.0.1:8081/api/v1/ping          # {"code":200,...}
curl http://127.0.0.1:8081/actuator/health      # status=UP
```

完整的部署、环境变量与构建产物校验说明见 **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)**。

## 架构要点

### 后端模块

| 模块 | 职责 |
|---|---|
| `common` | 统一响应、错误码、鉴权过滤器、数据范围守卫、业务时钟、配置基座 |
| `identity` | 管理端账号与角色、学员账号、微信身份绑定、登录限流 |
| `tenancy` | 集团与子公司 |
| `employee` | 员工档案与 Excel 导入 |
| `content` | 课程、版本、章节、资源、存储签名 |
| `training` | 培训包、包版本、培训任务与分配 |
| `learning` | 学习心跳、章节/课程/任务完成判定 |
| `exam` | 题库、试卷、考试 attempt 与判分 |
| `report` | 培训报表（只读，单向依赖上述业务模块） |
| `system` | 系统配置注册表与热加载 |

报表模块只能被依赖，不得反向依赖业务模块；其数据范围由当前登录人显式拼入查询条件（不使用拦截器），以保证可测试、可审计。

### 关键数据不变量

- 发布后的培训包版本不可原地修改，变更产生新版本；培训任务始终绑定具体版本
- 每次考试生成独立 attempt，保存开始/提交时间、分数、结果与试卷快照引用；答案正确性只存在服务端
- 课程完成状态由服务端依据课程要求与有效学习记录计算，不采信客户端上报
- 所有业务状态变更考虑事务、并发与幂等

### API 约定

- 前缀 `/api/v1`，管理端 `/api/v1/admin/**`，小程序 `/api/v1/miniapp/**`
- 统一响应 `{ code, message, data, traceId }`，统一分页 `page` / `pageSize`
- 使用 DTO/VO，不直接暴露数据库实体；参数校验、统一异常处理、OpenAPI 文档

## 术语对照

界面文案已统一为通用内训语义，下表列出与数据模型历史命名的对应关系。标识符保持不变是为兼容既有数据库与已发布客户端。

| 界面术语 | 数据模型 |
|---|---|
| 集团/公司 | `insurance_company` 表、`insuranceCompanyId` 字段、`INSURANCE_COMPANY` 数据范围 |
| 子公司/部门 | `enterprise` 表、`enterpriseId` 字段、`ENTERPRISE` 数据范围 |
| 平台管理员 / 集团管理员 / 子公司管理员 | `SUPER_ADMIN` / `INSURANCE_ADMIN` / `ENTERPRISE_ADMIN` |

## 安全约定

- 不把微信 AppSecret、数据库口令、JWT 密钥等写入 Git；`.env` 与密钥文件已在 `.gitignore` 中
- 不信任客户端提交的手机号、openid、员工 ID、学习完成状态与考试分数
- 管理端接口一律经过身份认证、角色授权与企业/数据范围校验
- SQL 参数化；文件上传校验 MIME、扩展名、大小与访问权限
- 登录、手机号绑定、考试提交等敏感操作具备频率限制、审计日志与幂等保护

## 许可证

本项目采用 [Apache License 2.0](LICENSE)，包含显式的专利授权与变更说明义务。

- 许可证全文：[LICENSE](LICENSE)
- 第三方署名（NOTICE）：[NOTICE](NOTICE)
- 第三方依赖与代码来源的完整声明：[`licenses/third-party-notices.md`](licenses/third-party-notices.md)，
  其中列出每个依赖的版本、许可证、用途与义务，以及本项目对双许可包所选择的分支

许可分层：本仓库自身代码以 Apache-2.0 发布；`apps/miniapp` 目录内嵌的 unibest 上游代码
保留其原始 MIT 许可证（见 [`apps/miniapp/LICENSE`](apps/miniapp/LICENSE)）与署名，
上游基线与改动记录见 [`apps/miniapp/UPSTREAM.md`](apps/miniapp/UPSTREAM.md)。

## 致谢

小程序端基于 [unibest](https://github.com/feige996/unibest) 开发（模板版本 4.4.1，MIT 许可证，
Copyright (c) 2025 菲鸽）。本项目在其之上完成了路由、状态、请求层与全部业务页面的重写，
上游许可证原文保留在 [`apps/miniapp/LICENSE`](apps/miniapp/LICENSE)，基线与改动记录见
[`apps/miniapp/UPSTREAM.md`](apps/miniapp/UPSTREAM.md)。
