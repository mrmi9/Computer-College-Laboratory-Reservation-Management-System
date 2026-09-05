# 测试报告

## 结论

第一阶段功能与本地非容器验证已完成；容器启动、真实后端浏览器闭环、备份恢复和 300 VU 性能结果须以远程干净环境中的 `scripts/verify.ps1` 最终执行为准。本文件只记录真实执行结果，未执行项目不标记通过。

## 执行记录

| 日期 | 检查点 | 命令 | 结果 | 证据摘要 |
|---|---|---|---|---|
| 2026-09-03 | 基线 | `git status --short --branch` | PASS | 干净工作区，位于所需开发分支 |
| 2026-09-03 | CP1 后端 | `backend\mvnw.cmd --batch-mode clean verify` | PASS | 1 个单元测试通过，JAR 构建成功 |
| 2026-09-03 | CP1 前端 | `npm run lint; npm run typecheck; npm run test; npm run build` | PASS | 零 lint 警告、严格类型检查通过、1 个 Vitest 通过、生产构建成功 |
| 2026-09-03 | CP2 数据库 | `scripts\verify-backend.ps1` | PASS | PostgreSQL 16.15 空库应用 2 个迁移；3 个集成测试通过；非法课次与重复生效槽位被数据库拒绝 |
| 2026-09-03 | CP3 认证后端 | `scripts\verify-backend.ps1` | PASS | PostgreSQL 16.15；8 个认证 API + 3 个迁移集成测试、3 个单元测试全部通过 |
| 2026-09-03 | CP3 认证前端 | `npm run lint; npm run typecheck; npm run test; npm run build` | PASS | 零 lint 警告、严格类型检查通过、2 个 Vitest 通过、生产构建成功 |
| 2026-09-05 | CP4-CP6 资源与预约 | `scripts\verify-backend.ps1` | PASS | PostgreSQL 16.15；22 个集成测试和 5 个单元测试通过，含时段/库存竞争、幂等、版本和状态机 |
| 2026-09-05 | CP7 考勤与通知 | `scripts\verify-backend.ps1` | PASS | PostgreSQL 16.15；28 个集成测试和 5 个单元测试通过，含人工/定时竞争、爽约冻结、自动完成和 Outbox 重试 |
| 2026-09-05 | CP8 管理与统计 | `scripts\verify-backend.ps1` | PASS | PostgreSQL 16.15；33 个集成测试和 5 个单元测试通过；角色撤销、参数版本、导入、审计、统计和 CSV 通过 |
| 2026-09-05 | CP9 前端质量 | `npm run lint; npm run typecheck; npm test; npm run build` | PASS | 零 lint 警告、严格类型检查、9 个 Vitest 和生产构建通过；主入口约 88 KB |
| 2026-09-05 | CP9 浏览器验收 | `npm run e2e` | PASS | Playwright 1280×720 与 390×844 共 14 个场景通过，覆盖全部核心界面流程 |
| 2026-09-05 | CP9 后端回归 | `scripts\verify-backend.ps1` | PASS | PostgreSQL 16.15 空库迁移；34 个集成测试与 5 个单元测试通过 |
| 2026-09-05 | CP10 后端/OpenAPI | `scripts\verify-backend.ps1` | PASS | PostgreSQL 16.15 全新临时集群；5 个单元测试、39 个集成测试，零失败、零错误、零跳过；OpenAPI、限流和预约边界回归通过 |
| 2026-09-05 | CP10 核心覆盖率 | `scripts\verify-backend.ps1` | PASS | JaCoCo 对 `reservation.domain` 强制 LINE ≥ 80%；实际 20/20 行、54/54 指令及 2/2 分支覆盖，行覆盖率 100% |
| 2026-09-05 | CP10 Compose 静态校验 | `docker compose ... config --quiet` | PASS | 开发与生产叠加配置均可解析；固定镜像标签、网络、只读文件系统、非 root 用户、健康检查和生产 TLS 挂载均写入最终模型 |
| 2026-09-05 | CP10 本地安全检查 | `scripts\verify-security.ps1 -SkipContainerScan` | PASS | npm 生产依赖审计 0 个漏洞；密钥/私钥跟踪、生产配置和 `latest` 标签检查通过；Trivy 待 Docker 环境执行 |
| 2026-09-05 | CP10 本地统一回归 | `scripts\verify.ps1 -SkipDocker -SkipPerformance` | PASS | 从全新 PostgreSQL 临时集群和前后端干净构建执行；后端、前端、14 个生产静态产物 E2E、两套 Compose 配置与非容器安全检查全部通过 |

## 开发文档 12.2 必测场景映射

| # | 必测行为 | 自动化证据 | 结果 |
|---:|---|---|---|
| 1 | 同实验室、日期、课次并发审批仅一个成功 | `ReservationApiIT.concurrentApprovalAllowsOnlyOneEffectiveReservationForTheSameSlot` + Flyway 部分唯一索引复验 | PASS |
| 2 | 同实验室同日第 1、2 节可分别预约 | `ReservationApiIT.separatePeriodsInTheSameLabCanBothBeApproved` | PASS |
| 3 | `periodNo` 0、5、空值及日期格式/边界被拒绝 | `ReservationApiIT.requestBoundariesAndApprovalRevalidationRejectInvalidInputAndMaintenance`、`FlywayMigrationIT.databaseRejectsInvalidPeriodsAndDuplicateEffectiveSlots` | PASS |
| 4 | 提交后实验室维护时审批失败 | `ReservationApiIT.requestBoundariesAndApprovalRevalidationRejectInvalidInputAndMaintenance` | PASS |
| 5 | 多预约竞争有限设备不超卖 | `ReservationApiIT.equipmentRequestsCannotExceedInventoryDuringApproval` | PASS |
| 6 | 重复审批或网络重试只有一次副作用 | `ReservationApiIT.decisionsEnforceVersionStateIdempotencyAndDataScope` | PASS |
| 7 | 普通用户不能读取、取消他人预约 | `ReservationApiIT.applicantCanListInspectAndCancelWhileManagerCanSeeTheResult`、Playwright 越权场景 | PASS |
| 8 | 实验室管理员不能操作未授权实验室 | `CatalogApiIT.labAdministratorCanMaintainOnlyAssignedLabResources`、`AuthApiIT.dataScopeAllowsOnlyAssignedLabOrSystemAdministrator` | PASS |
| 9 | 取消截止时间前后规则正确 | `ReservationApiIT.cancellationDeadlineBlocksApplicantButAdministratorCanForceCancelWithAudit` | PASS |
| 10 | 自动爽约与人工签到并发结果合法 | `AttendanceOutboxIT.concurrentNoShowSweepAndManualCheckInProduceOneValidFinalState` | PASS |
| 11 | 用户禁用后原令牌失效 | `AuthApiIT.disablingUserImmediatelyInvalidatesExistingAccessToken` | PASS |
| 12 | 报表与预约明细抽样汇总一致 | `AdministrationStatisticsApiIT.statisticsReconcileWithDetailsRespectScopeAndExportIdenticalFilters` | PASS |

## 分层质量结果

- 后端：44 个测试（5 单元、39 集成）全部通过；集成测试使用 PostgreSQL 16.15，Flyway 从空库执行 3 个迁移。
- 前端：ESLint 零警告、TypeScript 严格检查通过、9 个 Vitest 通过、Vite 生产构建通过。
- 浏览器：Mock API 的 14 个 Playwright 场景在 1280×720 与 390×844 视口通过；生产代码调用真实 `/api`，Mock 仅存在于测试夹具。
- 并发：两线程实验室时段审批、设备库存竞争、人工签到与定时爽约竞争均由真实 PostgreSQL 集成测试验证。
- 安全：BCrypt、JWT/Refresh 摘要、CSRF、会话撤销、生产 Cookie/CORS、请求限流、统一错误封装均有实现或测试；容器 Trivy 结论待最终远程验证。

## 最终环境待回填

| 项目 | 目标 | 当前状态 |
|---|---|---|
| 统一入口 | 干净环境 `scripts/verify.ps1` 返回 0 | 待远程 CI 执行 |
| Docker | PostgreSQL、后端、Nginx 健康，真实 `/api` 可达 | 待远程 CI 执行 |
| 恢复 | 加密备份恢复至新库，预约/审计指纹一致 | 待远程 CI 执行 |
| 性能 | 10,000 用户、200 实验室、300 VU；查询 P95 < 500 ms，提交 P95 < 1 s | 待远程 CI 执行 |
| 容器安全 | Trivy 未处理 HIGH/CRITICAL 为 0 | 待远程 CI 执行 |
