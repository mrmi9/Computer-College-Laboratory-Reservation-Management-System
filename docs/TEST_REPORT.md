# 测试报告

## 状态

第一阶段开发中。本文件只记录真实执行结果，未执行项目不标记通过。

| 日期 | 检查点 | 命令 | 结果 | 证据摘要 |
|---|---|---|---|---|
| 2026-09-03 | 基线 | `git status --short --branch` | PASS | 干净工作区，位于所需开发分支 |
| 2026-09-03 | CP1 后端 | `backend\\mvnw.cmd --batch-mode clean verify` | PASS | 1 个单元测试通过，JAR 构建成功 |
| 2026-09-03 | CP1 前端 | `npm run lint; npm run typecheck; npm run test; npm run build` | PASS | 零 lint 警告、严格类型检查通过、1 个 Vitest 通过、生产构建成功 |
| 2026-09-03 | CP2 数据库 | `scripts\\verify-backend.ps1` | PASS | PostgreSQL 16.15 空库应用 2 个迁移；3 个集成测试通过；实测非法课次与重复生效槽位被数据库拒绝 |

## 最终报告待填内容

- 后端测试数量、失败数与 JaCoCo 覆盖率。
- 前端 lint、类型检查、Vitest 和生产构建。
- Playwright 桌面/移动 E2E。
- 12 个必测场景映射。
- 并发、性能、安全、Docker 健康与备份恢复实测证据。
