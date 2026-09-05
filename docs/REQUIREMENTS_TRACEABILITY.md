# 第一阶段需求追踪表

状态只使用 `PLANNED`、`IN_PROGRESS`、`IMPLEMENTED`、`VERIFIED`。`VERIFIED` 必须给出可重复验证证据。

| 需求/章节 | 验收行为 | 后端实现位置 | 前端实现位置 | 数据库迁移 | 测试位置 | 当前状态 | 验证证据 |
|---|---|---|---|---|---|---|---|
| DB-01 / 6, 11 | Flyway 从空 PostgreSQL 创建完整结构、约束、索引和安全演示数据 | `db/migration/V1__initial_schema.sql`, `V2__administration_and_reporting.sql` | 不适用 | V1-V2 + dev repeatable | `database/FlywayMigrationIT` | VERIFIED | `scripts/verify-backend.ps1`：空库应用 3 个迁移、3 个迁移集成测试通过 |
| AUTH-01 / 3.1, 10.1 | 四类角色登录、退出、刷新会话 | `auth/application`, `auth/web` | `stores/auth.ts`, 登录/改密页 | V1 | `auth/AuthApiIT` | VERIFIED | `scripts/verify-backend.ps1`：8 个认证 API 场景通过 |
| AUTH-02 / 业务验收 2-4 | 单飞刷新、Refresh 轮换摘要、改密/退出/禁用撤销 | Refresh Session 摘要与族撤销 | `api/http.ts` 内存令牌与 SingleFlight | V1 | `AuthApiIT`, `http.spec.ts` | VERIFIED | 旧令牌复用撤销、注销撤销、禁用/改密即时失效及前端并发合并均通过 |
| AUTH-03 / 3.1 | 首次登录强制改密、失败锁定、用户禁用 | `AuthService`, `MustChangePasswordFilter` | `ChangePasswordView.vue` | V1 | `AuthApiIT` | VERIFIED | 第五次失败锁定；首次改密前受限；改密后旧会话失效 |
| RBAC-01 / 2 | RBAC 及接口级数据范围 | `SecurityConfig`, `DataScopeService` | 路由会话守卫 | V1 | `AuthApiIT`, `CatalogApiIT`, `ReservationApiIT`, `AdministrationStatisticsApiIT` | VERIFIED | 普通用户本人范围、管理员实验室范围、系统管理员全局范围和管理接口角色限制均通过 |
| USER-01 / 7.6 | 系统管理员管理用户和角色 | `IdentityAdminService`/`IdentityAdminController` | 待完整前端接入 | V1-V2 | `AdministrationStatisticsApiIT` | VERIFIED | 创建/分页/更新、禁用撤销、角色版本与分配、内置管理员保护、CSV 导入任务均通过 |
| PERIOD-01 / 3.5.1 | 四节大课可配置并统一展示 | `CatalogService`/`CatalogController` | 待完整前端接入 | V1 | `CatalogApiIT` | VERIFIED | 查询、系统管理员修改和版本冲突通过 |
| LAB-01 / 3.2 | 实验室信息、状态、负责人、容量、标签和策略管理 | `catalog` 模块 | 待完整前端接入 | V1 | `CatalogApiIT` | VERIFIED | 分页筛选、创建、负责人更新、越权和版本冲突通过 |
| LAB-02 / 3.2 | 周开放规则和特殊停用课次管理 | `catalog` 模块 | 待完整前端接入 | V1 | `CatalogApiIT` | VERIFIED | 批量替换规则、停用新增/删除、数据范围和审计通过 |
| EQUIP-01 / 3.3 | 设备信息、状态、数量和归属管理 | `catalog` 模块 | 待完整前端接入 | V1 | `CatalogApiIT` | VERIFIED | 设备创建、归属范围与查询通过 |
| AVAIL-01 / 3.4 | 日期、课次、容量、位置、标签、设备综合空闲查询 | `ReservationService.availability` | 待完整前端接入 | V1 | `ReservationApiIT` | VERIFIED | 容量、开放规则、停用和有效预约过滤通过 |
| RES-01 / 3.5 | 学生/教师预约、我的预约和详情 | `reservation` 模块 | 待完整前端接入 | V1 | `ReservationApiIT` | VERIFIED | 创建、本人列表、详情和管理列表闭环通过 |
| RES-02 / 2.2 | applicantType 仅由后端业务身份生成 | `ReservationService.applicantType` | 请求类型不含该字段 | V1 | `ReservationApiIT` | VERIFIED | 教师类型由 JWT 角色生成；纯管理员被拒绝 |
| RES-03 / 3.5-3.6 | 自动/人工审批、驳回、管理员取消和历史 | `ReservationService` | 待完整前端接入 | V1 | `ReservationApiIT` | VERIFIED | 教师自动审批、学生人工审批、驳回/历史已通过 |
| RES-04 / 8.2 | 状态机与非法转换保护 | `ReservationStateMachine` | 不适用 | V1 | `ReservationStateMachineTest`, `ReservationApiIT` | VERIFIED | 完整允许路径及终态/跨级非法转换通过 |
| RES-05 / 3.5 | 申请人取消截止规则、管理员强制取消 | `ReservationService` | 待完整前端接入 | V1 | `ReservationApiIT` | IMPLEMENTED | 申请人取消闭环已测；截止边界和管理员取消继续补充 |
| CONS-01 / 6.5 | 乐观锁、幂等键和重复请求保护 | 事务内 advisory lock + `idempotency_record` | 创建请求生成幂等键待接入 | V1 | `CatalogApiIT`, `ReservationApiIT` | VERIFIED | 重放同资源、换请求拒绝、过期版本拒绝通过 |
| CONS-02 / 6.5 | 实验室时段并发只允许一条生效预约 | 实验室行锁 + 部分唯一索引 | 冲突展示待接入 | V1 | `ReservationApiIT`, `FlywayMigrationIT` | VERIFIED | 两线程同时审批仅一条成功，数据库唯一约束复验通过 |
| CONS-03 / 6.5 | 设备并发申请不超卖 | 设备 ID 升序行锁 + 生效用量汇总 | 待完整前端接入 | V1 | `ReservationApiIT` | VERIFIED | 并发申请后生效分配未超过总量；单申请超量审批被拒绝 |
| ATT-01 / 3.7 | 签到、签退、代操作、自动完成和爽约 | `AttendanceService`/`AttendanceController` | 待完整前端接入 | V1 | `AttendanceOutboxIT` | VERIFIED | 申请人/管理员操作、人工与定时竞争、自动完成、违规阈值冻结均通过 |
| NOTIFY-01 / 3.8 | 站内通知、Outbox、重试和失败记录 | `notification` 模块 | 待完整前端接入 | V1 | `AttendanceOutboxIT` | VERIFIED | 收件人隔离、已读、成功投递、退避重试和第 5 次永久失败均通过 |
| STAT-01 / 3.9 | 总览、利用率、学生/教师分类、CSV 同筛选导出 | `StatisticsService`/`StatisticsController` | 待完整前端接入 | V1 | `AdministrationStatisticsApiIT` | VERIFIED | 总量/分类/比率与明细精确核对；利用率分母按开放课次计算；范围、设备排行和 CSV 同筛选通过 |
| AUDIT-01 / 10.3 | 登录、审批、取消、代签到、角色与配置修改审计 | 各业务服务写入 `audit_log`；`SystemAdminService` 查询 | 待完整前端接入 | V1 | `AuthApiIT`, `CatalogApiIT`, `ReservationApiIT`, `AttendanceOutboxIT`, `AdministrationStatisticsApiIT` | VERIFIED | 登录、资源、预约、代操作、角色、参数和导出均留痕；审计筛选和系统管理员权限通过 |
| API-01 / 7 | OpenAPI 3 文档和统一错误格式 | 待实现 | 不适用 | 不适用 | 待实现 | PLANNED | 待验证 |
| UI-01 / 9 | 完整业务端/管理端、四步预约、权限和状态页面 | API 待实现 | 待实现 | 不适用 | 待实现 | PLANNED | 待验证 |
| DEPLOY-01 / 13 | Compose、Nginx、健康检查、HTTPS 模板、非 root | 待实现 | 待实现 | V1 | 待实现 | PLANNED | 待验证 |
| RECOVERY-01 / 13.3 | 备份恢复脚本及预约/审计一致性核对 | 待实现 | 不适用 | V1 | 待实现 | PLANNED | 待验证 |
| TEST-01 / 12 | 12 个必测场景、单元/集成/E2E/并发/覆盖率 | 待实现 | 待实现 | V1 | 待实现 | PLANNED | 待验证 |
| PERF-01 / 4, 12 | 1 万用户、200 实验室、300 并发及 P95 目标 | 待实现 | 不适用 | V1 | 待实现 | PLANNED | 待验证 |
| DOC-01 / 12.3 | README、部署、测试、管理员、API 文档完整一致 | 不适用 | 不适用 | 不适用 | docs/ | IN_PROGRESS | 文档框架已建立 |
