# 上游基线记录（UPSTREAM）

本目录代码来自 `unibest` 模板，已作为源码内嵌进本仓库（非 submodule），可自由修改。

| 项 | 值 |
|----|----|
| 上游仓库 | https://github.com/feige996/unibest |
| 上游分支 | `base`（默认分支，README 说明 `main == base`） |
| 上游提交 | `2c8d2c3aba4bf18890d6078db04cc2044b9cdd73`（2026-11-05，`修复 eslint 和 type-check 报错 (#466)`） |
| 模板版本 | unibest 4.4.1 |
| 许可证 | MIT，Copyright (c) 2025 菲鸽（见 `LICENSE`，已在 `licenses/third-party-notices.md` 登记） |
| 获取方式 | `git clone --depth 1 --no-single-branch` 后删除嵌套 `.git` |

## 我们对上游做的改动（持续维护）

| 日期 | 文件 | 改动 | 原因 |
|------|------|------|------|
| 2026-11-09 | `package.json` | `prepare` 脚本由 `pnpm init-husky & pnpm init-baseFiles` 改为 `pnpm init-baseFiles` | 去掉 `git init`：源码已内嵌主仓库，每次 `pnpm install` 重建嵌套 `.git` 会破坏主仓库跟踪 |
| 2026-11-09 | `env/.env` | 品牌、后端地址、微信开发者工具 CLI 路径 | 接入本项目环境（详见下方） |
| 2026-11-09 | `pages.config.ts` | easycom 增加 `^wd-` 规则 | 手动接入 WotUI（`base` 分支 4.4.1 已剥离 UI 库依赖） |
| 2026-11-09 | `src/tabbar/config.ts` | 四个 Tab：首页 / 课程 / 考试 / 我的 | 对齐小程序信息架构 |
| 2026-11-09 | `src/pages/**` | 新增 login / course / exam / profile 骨架页 | Task 01 基础页面 |
| 2026-11-09 | `src/style/index.scss` | 覆盖 `--wot-color-theme` 等主题变量 | 品牌色（Design Token 待 Phase 2 细化） |

## 如何跟随上游更新

```bash
# 1. 新增上游远端（可选，仅用于比对）
git remote add unibest https://github.com/feige996/unibest.git

# 2. 拉取上游分支（不合并）
git fetch unibest base:refs/remotes/unibest/base

# 3. 对比我方与上游差异，人工挑选需要的修复
git diff unibest/base -- apps/miniapp
```

**约定**：不再自动跟上游升级（上游可能重构依赖版本），所有上游修复均由人工评估后手工回合，并在上表登记。

## 禁止事项

- 不在本目录内执行 `git init`（会重新产生嵌套仓库）。
- 不把 `apps/miniapp` 变成 submodule。
- 不引入 `uView Plus` 或第二套 UI 库（`AGENTS.md` 约束），UI 统一用 WotUI。
