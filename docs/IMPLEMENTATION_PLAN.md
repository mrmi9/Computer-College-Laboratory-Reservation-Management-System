# 第一阶段实施计划

## 交付原则

- `docs/开发文档.md` 不做改写，以第 19 节完成定义为最高验收依据。
- 每个检查点形成最小完整闭环，测试通过后使用 Conventional Commit 提交。
- 未经真实实现和可重复验证的需求不得标记为 `VERIFIED`。
- 数据库结构仅通过 Flyway 迁移；生产配置使用 `ddl-auto: validate`。

## 检查点

| 检查点 | 范围 | 退出条件 | 状态 |
|---|---|---|---|
| CP1 | 工程骨架、版本锁定、Wrapper、lockfile、配置、忽略规则、CI | 后端与前端最小构建及测试通过 | COMPLETE |
| CP2 | PostgreSQL、Flyway 全量表结构、约束、索引、演示种子 | 空库迁移和结构测试通过 | COMPLETE |
| CP3 | 登录、刷新轮换、首次改密、锁定、撤销、RBAC、数据范围 | 认证和越权测试通过 | IN_PROGRESS |
| CP4 | 课次、实验室、开放规则、停用课次、设备 | 管理接口和权限测试通过 | PLANNED |
| CP5 | 空闲查询、创建、详情、列表、状态机、取消 | 学生/教师预约闭环测试通过 | PLANNED |
| CP6 | 审批、乐观锁、幂等、时段与设备并发 | 强制并发场景通过 | PLANNED |
| CP7 | 签到签退、任务、通知、Outbox、审计 | 人工/定时竞争和重试测试通过 | PLANNED |
| CP8 | 统计、CSV、用户与系统管理 | 统计与明细核对通过 | PLANNED |
| CP9 | 完整前端、响应式、权限、异常/空状态 | 1280px/390px E2E 与视觉检查通过 | PLANNED |
| CP10 | OpenAPI、Docker、Nginx、备份恢复、性能、安全和文档 | 部署与恢复演练通过 | PLANNED |
| CP11 | 全新环境总验证、修复、整理、远程交付 | `verify.ps1` 返回 0，分支推送并创建 PR/compare URL | PLANNED |

## 验证节奏

每个检查点依次执行格式检查、编译、相关单元/集成测试，并同步更新 `REQUIREMENTS_TRACEABILITY.md`、`IMPLEMENTATION_STATUS.md` 和 `TEST_REPORT.md`。最终验证从无构建产物、全新测试数据库开始。
