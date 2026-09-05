# 测试报告

## 状态

第一阶段开发中。本文件只记录真实执行结果，未执行项目不标记通过。

| 日期 | 检查点 | 命令 | 结果 | 证据摘要 |
|---|---|---|---|---|
| 2026-09-03 | 基线 | `git status --short --branch` | PASS | 干净工作区，位于所需开发分支 |
| 2026-09-03 | CP1 后端 | `backend\\mvnw.cmd --batch-mode clean verify` | PASS | 1 个单元测试通过，JAR 构建成功 |
| 2026-09-03 | CP1 前端 | `npm run lint; npm run typecheck; npm run test; npm run build` | PASS | 零 lint 警告、严格类型检查通过、1 个 Vitest 通过、生产构建成功 |
| 2026-09-03 | CP2 数据库 | `scripts\\verify-backend.ps1` | PASS | PostgreSQL 16.15 空库应用 2 个迁移；3 个集成测试通过；实测非法课次与重复生效槽位被数据库拒绝 |
| 2026-09-03 | CP3 认证后端 | `scripts\\verify-backend.ps1` | PASS | PostgreSQL 16.15；8 个认证 API + 3 个迁移集成测试、3 个单元测试全部通过；零失败、零跳过 |
| 2026-09-03 | CP3 认证前端 | `npm run lint; npm run typecheck; npm run test; npm run build` | PASS | 零 lint 警告、严格类型检查通过、2 个 Vitest 通过、Vite 生产构建成功 |
| 2026-09-05 | CP4-CP6 资源与预约 | `scripts\\verify-backend.ps1` | PASS | PostgreSQL 16.15；22 个集成测试和 5 个单元测试全部通过，含两线程时段/库存竞争、幂等、版本和状态机；零失败、零跳过 |
| 2026-09-05 | CP7 考勤与通知 | `scripts\\verify-backend.ps1` | PASS | PostgreSQL 16.15；28 个集成测试和 5 个单元测试全部通过，含人工/定时竞争、爽约冻结、自动完成、Outbox 成功/重试/永久失败和通知收件人隔离；零失败、零跳过 |
| 2026-09-05 | CP8 管理与统计 | `scripts\\verify-backend.ps1` | PASS | PostgreSQL 16.15；33 个集成测试和 5 个单元测试全部通过；角色变更即时撤销会话、参数版本、导入任务、审计权限、统计明细核对、数据范围和 CSV 同筛选/公式注入保护通过；零失败、零跳过 |
| 2026-09-05 | CP9 前端质量 | `npm run lint; npm run typecheck; npm test; npm run build` | PASS | 零 lint 警告、严格类型检查、9 个 Vitest 和生产构建通过；Element Plus 按需加载后主入口约 88 KB |
| 2026-09-05 | CP9 浏览器验收 | `npm run e2e` | PASS | Playwright 1280×720 与 390×844 共 14 个场景通过；覆盖日历双视图、学生/教师身份、自动/人工审批、冲突恢复、驳回、代签到签退、爽约、越权、停用课次和 CSV |
| 2026-09-05 | CP9 后端回归 | `scripts\\verify-backend.ps1` | PASS | PostgreSQL 16.15 空库迁移；34 个集成测试与 5 个单元测试通过；新增日历隐私、日期范围及主负责人数据范围验证 |

## 最终报告待填内容

- 后端测试数量、失败数与 JaCoCo 覆盖率。
- 前端 lint、类型检查、Vitest 和生产构建。
- Playwright 桌面/移动 E2E。
- 12 个必测场景映射。
- 并发、性能、安全、Docker 健康与备份恢复实测证据。
