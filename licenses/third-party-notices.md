# 第三方组件与代码来源声明（Third-Party Notices）

> 本项目自身以 Apache License 2.0 发布（许可证全文见根目录 `LICENSE`，第三方署名见根目录 `NOTICE`）；
> 本文件登记本项目引入的第三方组件与复用的第三方代码。
> 维护规则：**每引入一个第三方依赖或复用一段第三方代码，必须在此登记一行**，否则视为未完成。
> 依赖清单的机器可读版本见 `licenses/reports/`：`miniapp-deps.json`、`admin-deps.json`、`api-deps.txt`。
> 本文件最后更新：2026-11-21（许可证切换为 Apache-2.0）

## 1. 运行时依赖（会进入交付产物）

### 1.1 小程序（apps/miniapp）

| 组件 | 版本 | 许可证 | 用途 | 义务 / 备注 |
|------|------|--------|------|-------------|
| unibest | 4.4.1（commit `2c8d2c3`） | MIT，Copyright (c) 2025 菲鸽 | 小程序基础模板 | 保留版权与许可声明；本项目在其之上二次开发 |
| uni-app 全家桶（`@dcloudio/*`） | 3.0.0-5020620260917001 | Apache-2.0 | 跨端框架 | 保留声明；DCloud 明确应用输出物知识产权归开发者 |
| Vue 3 | 3.4+ | MIT | 视图框架 | 保留声明 |
| Pinia / vue-router | 2.0.36 / 4.5.1 | MIT | 状态 / 路由 | 保留声明 |
| UnoCSS | 66.0.0 | MIT | 原子化 CSS | 保留声明 |
| **WotUI（`wot-design-uni`）** | **1.14.0（已安装）** | **MIT**（node_modules `package.json` license 字段实测） | 小程序 UI 组件库 | 保留声明 |
| z-paging | 2.8.7 | MIT（依上游 README 徽章；包内缺 `license` 字段） | 列表分页组件 | **必须留证**：记录上游地址 `github.com/SmileZXLee/uni-z-paging` 与许可证声明截图/链接 |
| alova | 3.3.x | MIT | 请求库（本项目仅保留依赖，不新增用法） | 保留声明 |
| dayjs | 1.11.10 | MIT | 时间处理 | 保留声明 |
| jszip（若使用） | 3.10.1 | `MIT OR GPL-3.0-or-later` 双许可 | 前端导出（如启用） | **本项目明确选择 MIT 分支**，禁用 GPL 分支；保留声明 |
| @iconify-json/carbon | 1.2.x | Apache-2.0（Carbon Design System） | 图标数据 | 保留 NOTICE，注明图标来源 |

### 1.2 管理端（apps/admin）

| 组件 | 版本 | 许可证 | 用途 |
|------|------|--------|------|
| Vue | 3.5.45 | MIT | 视图框架 |
| Vue Router | 4.6.4 | MIT | 路由 |
| Pinia | 2.3.1 | MIT | 状态管理 |
| Element Plus | 2.14.8 | MIT | UI 组件库 |
| @element-plus/icons-vue | 2.3.2 | MIT | 图标 |
| axios | 1.21.0 | MIT | HTTP 客户端 |
| Vite | 5.4.21 | MIT | 构建工具（仅构建期） |
| vue-tsc | 3.3.14 | MIT | 类型检查（仅构建期） |
| @vitejs/plugin-vue | 5.2.4 | MIT | Vite 插件（仅构建期） |
| TypeScript | 5.8.3 | Apache-2.0 | 编译（仅构建期） |
| @types/node | 20.19.43 | MIT | 类型声明（仅构建期） |

> 以上均为宽松许可，可闭源；Apache-2.0 项（TypeScript）保留 NOTICE。

### 1.3 后端（services/api）

| 组件 | 版本 | 许可证 | 义务 / 备注 |
|------|------|--------|-------------|
| Spring Boot / Spring Framework | 3.5.16 | Apache-2.0 | 保留 NOTICE |
| MyBatis-Plus（含 jsqlparser 依赖） | 3.5.18.1 | Apache-2.0 | 保留 NOTICE |
| JSqlParser | 4.6+ | **LGPL-2.1 OR Apache-2.0 双许可** | **本项目选择 Apache-2.0 分支**（本地 jar MANIFEST `Bundle-License` 实测） |
| Flyway（core / mysql） | BOM 管理 | Apache-2.0（Community） | 保留 NOTICE |
| springdoc-openapi | 2.10.0 | Apache-2.0 | 保留 NOTICE |
| HikariCP / Jackson / Tomcat embed / Netty / Lettuce / Micrometer / snakeyaml | BOM 管理 | Apache-2.0（Jackson 为 Apache-2.0） | 保留 NOTICE |
| Logback | BOM 管理 | **EPL-1.0 OR LGPL-2.1 双许可** | 以未修改 jar 依赖方式使用，不做二次分发修改 |
| **MySQL Connector/J** | BOM 管理（8.x） | **GPL-2.0 WITH Universal FOSS Exception** | 依 FOSS 例外可作为未修改依赖与闭源代码组合分发；**不得修改其源码**；保留许可证副本 |
| **jjwt**（api/impl/jackson） | 0.13.0 | Apache-2.0 | Task 02 新增，JWT 签发校验 |
| **spring-security-crypto** | 6.5.11 | Apache-2.0 | Task 02 新增，仅使用 BCrypt 密码哈希 |
| **Apache POI**（poi-ooxml 及其传递依赖） | 6.0.0 | Apache-2.0 | Task 02 新增，员工 Excel 导入 |
| **MinIO Java SDK**（`io.minio:minio`） | 9.0.4 | Apache-2.0 | Task 03 新增，私有桶上传与预签名 URL |
| **OkHttp**（`okhttp-jvm`，MinIO 传递依赖的显式 JVM 变体） | 5.5.1 | Apache-2.0 | Task 03 新增，MinIO HTTP 传输 |
| **BouncyCastle**（`bcprov-jdk18on`，MinIO 传递依赖） | 由 minio 传递 | MIT | 加解密实现 |
| **simple-xml-safe / Guava / commons-compress / snappy-java**（MinIO 传递依赖） | 由 minio 传递 | Apache-2.0 / MIT | 仅作为 SDK 内部依赖 |

> 后端依赖全量快照：`licenses/reports/api-deps.txt`。GPL 例外条款要求：以未修改形式使用、且不构成衍生作品；本项目不修改 MySQL 驱动源码。

## 2. 复用代码（方法级复用，非依赖引入）

> 规则见 `docs/decisions/ADR-001-foundation-selfbuild-with-playedu-method-reuse.md`。
> **尚未实际复用任何代码**。下表为已确定的可复用清单，每完成一处必须补全「文件 -> 改动摘要」两列，并在源文件头加来源注释。

| 来源项目 | 许可证 | 源路径 | 复用落点 | 改动摘要 | 状态 |
|----------|--------|--------|----------|----------|------|
| PlayEdu | Apache-2.0 | `playedu-api/playedu-course/.../UserLearnDurationRecordService.java` | `learning/` | 待落地 | 候选 |
| PlayEdu | Apache-2.0 | `playedu-api/playedu-course/.../UserCourseHourRecordService.java` | `learning/` | 待落地 | 候选 |
| PlayEdu | Apache-2.0 | `playedu-api/playedu-course/.../UserLearnDurationStatsService.java` | `reporting/` | 待落地 | 候选 |
| PlayEdu | Apache-2.0 | `playedu-api/playedu-resource/.../UploadService.java` | `content/` | 待落地 | 候选 |
| PlayEdu | Apache-2.0 | `playedu-api/playedu-resource/.../ResourceService.java` | `content/` | 待落地 | 候选 |
| PlayEdu | Apache-2.0 | `playedu-api/playedu-common/.../AdminRole*Mapper.java` | `identity/` | 待落地 | 候选 |

PlayEdu 许可证原文已归档：`licenses/playedu/LICENSE`（Apache License 2.0，11360 字节，上游 sha `f9dcb41`）。
版权归属：杭州白书科技有限公司（依其 README 使用须知第 1 条）。

## 3. 明确不复用的项目（许可证或品牌原因）

| 项目 | 许可证 | 结论 |
|------|--------|------|
| 学之思 XZS / XZS-WeChat | AGPL-3.0 | **禁止**引入任何代码，闭源商业项目存在传染风险 |
| WTS | GPL-3.0 | **禁止**引入 |
| WLP | 许可证未确认 | 未确认前**禁止**引入 |
| 领课 roncoo-education | 商业授权限制 | **禁止**引入 |
| Moodle | GPL-3.0 | **禁止**引入 |

## 4. 素材（图片 / 字体 / 图标）

| 素材 | 来源 | 许可证 | 状态 |
|------|------|--------|------|
| unibest 模板内置 tabbar 图标、logo | unibest 仓库 | MIT（随仓库） | 上线前替换为自有素材 |
| 字体 | 待定 | - | Task 01 决定，优先系统字体或 Noto Sans SC（OFL） |

> 未经登记的外部图片、字体、图标一律不得进入仓库。
