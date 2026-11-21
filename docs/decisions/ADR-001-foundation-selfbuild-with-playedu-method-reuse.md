# ADR-001：系统底座采用全自研，对 PlayEdu 采取「方法级复用」

## Status

Accepted（2026-11-09，用户确认）

## Background

Task 00 审计发现两条可行底座路线：

- **A 全自研**：Spring Boot 3 自建后端 + Vue 3 管理端 + unibest 小程序。`AGENTS.md` 与 `docs/` 均按此编写。
- **B PlayEdu 二次开发**：继承 PlayEdu 后端 5 个 Maven 模块与 React 18 前端，补保险业务与考试模块。

实测事实（2026-11-09 核对 `PlayEdu/PlayEdu` main 分支，1034 个文件）：

| 事实 | 数据 |
|------|------|
| 许可证 | Apache-2.0（`LICENSE` 已归档至 `licenses/playedu/LICENSE`） |
| 后端 | Spring Boot 3 + MyBatis，多模块：`playedu-api / playedu-common / playedu-course / playedu-resource / playedu-system` |
| 前端 | admin / pc / h5 **全部 React 18** |
| 开源版功能 | 部门、学员、视频学习、进度追踪、私有存储 |
| 企业版独有 | 线上考试、学习任务、文档预览、防盗链、防快进、集成企微/钉钉/飞书 |
| 迁移脚本 | 仓库内**无** Flyway/Liquibase，无 `.sql` 建表脚本 |
| 使用须知 | 强制保留 "Designed By PlayEdu" 页面标识与官网链接；修改代码需逐处注明 |

用户决策：**确认全自研（A），但对 PlayEdu 中可复用的代码方法予以复用。**

## Decision

1. **底座 = A 全自研**：自建 `services/api`（单 Maven module + 按域分包）、`apps/admin`（Vue 3 + Element Plus）、`apps/miniapp`（unibest）。
2. **对 PlayEdu 采取「方法级复用（copy-adapt）」**，明确禁止「模块级引入」：
   - ✅ **允许**：将单个类/方法复制到本项目，改写适配我方包结构、表结构与依赖，并在文件头保留来源、许可证与改动说明。
   - ❌ **禁止**：在 `pom.xml` 中以 `<dependency>` 引入 PlayEdu 任意模块；❌ 禁止整目录 vendor 其模块；❌ 禁止复制其 React 前端代码（技术栈不符，且触发页面品牌标识义务）。
3. **复用以"必要性"为前提**，复制前必须通过三问，任一为「否」则重写：
   - 该算法/逻辑是否**非平凡**（有易错边界，如时长累计、断点续传、签名过期）？
   - 是否**存在难以自证的边界条件**（并发、幂等、超时、分片）？
   - 重写成本是否**超过半天**？
   - 典型否决例：DTO 定义、简单 CRUD、Controller 骨架 → 一律自写。
4. **复用即冻结**：复制进来的代码视为本项目自有代码维护，不跟随 PlayEdu 上游自动同步；上游出现安全修复时由人工评估后手工回合。

## Reuse Boundary（候选清单，已确认文件存在，尚未逐行审阅）

| 候选复用点 | PlayEdu 源路径 | 复用价值 | 我方落点 | 需改造 |
|------------|----------------|----------|----------|--------|
| 学习时长累计 / 课时进度 | `playedu-course/.../service/UserLearnDurationRecordService.java`、`UserCourseHourRecordService.java` | 心跳去重、有效时长边界、进度百分比算法 | `learning/` 域 | 表结构换 `learning_record`（按 `assignment_id + course_version_id`） |
| 时长聚合统计 | `.../service/UserLearnDurationStatsService.java` | 按天/按课程的聚合口径 | `reporting/` 域 | 换成企业维度统计 |
| 章节 / 课时排序与状态 | `.../service/CourseChapterService.java`、`CourseHourService.java` | 章节-课时两级排序、上下线状态机 | `content/` 域 | 对齐 `course_version / course_section` |
| 资源上传与存储 | `playedu-resource/.../service/UploadService.java`、`ResourceService.java`、`ResourceExtraService.java` | 文件类型校验、存储 key 规则、分片/占位处理 | `content/` 域 + MinIO 适配 | 换私有桶策略与短签名 URL |
| RBAC 权限点模型 | `playedu-common/.../mapper/AdminRole*Mapper.java`、`AdminPermissionMapper.java` | 管理员-角色-权限点三元组结构 | `identity/` 域 | 叠加企业/数据范围维度 |
| 播放地址签发 | `playedu-resource` 内签名逻辑 | 短时 URL 的过期与鉴权边界 | `content/` 域 | 换成 MinIO `presignedGetObject` |

> 上述清单是**候选**，不是承诺。每个点在真正复用前必须先读源码、跑通、写测试，并在 `licenses/third-party-notices.md` 登记；未登记的复用视为违规。

## Compliance（Apache-2.0 + 使用须知）

每个复用文件必须满足：

1. **文件头注明来源**（示例，Java）：

```java
/*
 * Adapted from PlayEdu (https://github.com/PlayEdu/PlayEdu)
 * Source: playedu-api/playedu-course/src/main/java/xyz/playedu/course/service/UserLearnDurationRecordService.java
 * Upstream commit: <sha>
 * Copyright (c) 杭州白书科技有限公司
 * Licensed under the Apache License, Version 2.0 (see licenses/playedu/LICENSE)
 * Changes:
 *   - 表结构由 user_course_hour_record 改为 learning_record（assignment_id + course_version_id）
 *   - 移除 department 数据范围依赖，改由本项目 DataScope 拦截器提供
 *   - 增加幂等键校验
 */
```

2. **保留原始版权声明**：不得删除或篡改源文件中的版权信息（README 使用须知第 3 条）。
3. **NOTICE 登记**：在 `licenses/third-party-notices.md` 增行，写明包/来源/许可证/改动摘要/责任人。
4. **界面品牌**：本项目不使用 PlayEdu 的任何页面与前端组件，因此**我方交付界面不出现 "Designed By PlayEdu" 标识**；一旦复用其前端代码，则该页面必须保留原有标识 —— 这也是把前端复用列为禁止项的原因。
5. **审查门禁**：提交信息含 `reuse(playedu):` 前缀；Code Review 必须核对「文件头 + NOTICE 行 + 测试」三项齐全。

## Consequences

**正面**

- 保持技术栈统一（Java 21 / Spring Boot 3 / Vue 3 / unibest），`AGENTS.md` 无需重写。
- 品牌完全自主，交付界面无第三方标识。
- 数据库从零建表，Flyway 基线干净，无 retrofit 风险。
- 仍可吸收 PlayEdu 在「学习时长 / 资源上传 / 权限点」上的成熟边界处理。

**负面 / 风险**

- 课程与视频模块需自研，比直接继承多约 7~10 人日（已在路线对比中量化）。
- 方法级复用需人工改写与测试，存在"复制后没人维护"的风险 → 用「复用即冻结 + NOTICE 登记 + 测试覆盖」约束。
- 复用代码与 Apache-2.0 声明义务绑定，遗漏即合规风险 → 靠提交前缀与 Review 门禁拦截。

## Related

- `licenses/third-party-notices.md`、`licenses/playedu/LICENSE`
