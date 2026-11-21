# insurance-training-api

企业内训平台后端服务（模块化单体）。

- 技术栈：Java 21 + Spring Boot 3.5 + MyBatis-Plus + MySQL 8 + Redis 7 + Flyway + springdoc-openapi
- 代码组织：`com.insurance.training.<业务域>.<分层>`
- 接口前缀：`/api/v1`

## 业务域

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
| `archive` | 学员培训档案（只读） |
| `report` | 培训报表（只读，单向依赖业务模块） |
| `audit` | 操作日志 |
| `system` | 系统配置注册表与热加载 |

`report` 与 `archive` 只能被依赖，不得反向依赖任何业务模块。

## 约定

- 统一响应 `{code, message, data, traceId}`，`code` 与 HTTP 状态码语义一致；分页体为 `{items, total, page, pageSize}`。
- 时间：数据库 `DATETIME` 存 Asia/Shanghai；应用与 Jackson 固定 `Asia/Shanghai`；API 输出 `+08:00` 的 ISO-8601。
- 迁移只走 Flyway 版本化脚本（`src/main/resources/db/migration`），`clean` 已禁用，禁止手工改库后不回填脚本。
- 所有跨租户查询必须带企业/公司维度条件，不允许绕过数据范围校验。
- 学员正确答案只保存在服务端；学员 API 不返回正确答案。

## 本地运行

前置：JDK 21、MySQL/Redis 由 `deploy/compose` 提供。

```bash
export JAVA_HOME="<你的 JDK 21 安装路径>"
export MYSQL_PASSWORD=local_app_change_me
mvn -f services/api/pom.xml spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments=--server.port=8081
```

`--server.port=8081` 不可省略：环境变量 `SERVER__PORT`（双下划线）经 Spring 松散绑定也会命中
`server.port`，且优先级高于 yml，某些宿主进程会注入该变量导致端口冲突。端口表与常见问题见
[`docs/DEPLOYMENT.md`](../../docs/DEPLOYMENT.md)。

### 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | 8081（local） | HTTP 端口 |
| `MYSQL_HOST` / `MYSQL_PORT` | 127.0.0.1 / 3306 | 数据库地址 |
| `MYSQL_DATABASE` | insurance_training | 库名 |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | app / **无默认值** | 缺失即启动失败，避免弱口令入库 |
| `REDIS_HOST` / `REDIS_PORT` | 127.0.0.1 / 6379 | 缓存 |
| `APP_JWT_SECRET` | **无默认值**（prod） | JWT 签名密钥，≥ 32 字节 |
| `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | **无默认值** | 对象存储；缺失时拒绝启动 |
| `APP_ENV` | local | `local` / `prod` |

完整清单见 [`docs/DEPLOYMENT.md`](../../docs/DEPLOYMENT.md)。

## 构建与测试

```bash
export JAVA_HOME="<你的 JDK 21 安装路径>"
mvn -f services/api/pom.xml verify        # 编译 + 单元测试
mvn -f services/api/pom.xml clean package -DskipTests
```

## 自检

| 端点 | 期望 |
|---|---|
| `GET /api/v1/ping` | `{"code":200,"data":{"app":"insurance-training-api","serverTime":"...+08:00"}}` |
| `GET /actuator/health` | `status=UP`，`db` / `redis` 均为 `UP` |
| `GET /swagger-ui/index.html` | OpenAPI 文档页（prod profile 下关闭） |
| 任意不存在路径 | `{"code":404,"message":"资源不存在","traceId":"..."}` |

响应头 `X-Trace-Id` 与响应体 `traceId` 一致，日志每行带同一个 `traceId`，便于按请求串联。
