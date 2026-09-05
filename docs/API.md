# API 说明

## 约定

- 基础路径：`/api/v1`。
- 开发 OpenAPI JSON：`/v3/api-docs`；Swagger UI：`/swagger-ui.html`。生产 profile 关闭两者。
- 除登录和刷新外，接口使用 `Authorization: Bearer <access-token>`。
- JSON 响应统一为 `code`、`message`、`data`、`requestId`、`timestamp`。
- 错误响应不返回堆栈、SQL、表名或本地路径；`requestId` 同时写入 `X-Request-Id`。
- 日期使用 `YYYY-MM-DD`，时间戳使用 ISO 8601；业务时区为 `Asia/Shanghai`。
- 分页从 0 开始，普通列表 `size` 上限 100；CSV 默认上限 10,000 行。

成功示例：

```json
{
  "code": "OK",
  "message": "success",
  "data": { "id": 42 },
  "requestId": "c4e76f67-ef53-4e83-a7a4-2c561d370a2b",
  "timestamp": "2026-09-05T18:00:00+08:00"
}
```

错误示例：

```json
{
  "code": "RESERVATION_SLOT_CONFLICT",
  "message": "该实验室在所选课次已被占用",
  "requestId": "c4e76f67-ef53-4e83-a7a4-2c561d370a2b",
  "timestamp": "2026-09-05T18:00:00+08:00"
}
```

## 会话与 CSRF

`POST /auth/login` 接收 `username`、`password`，返回短期 Access Token，并设置：

- `lab_refresh`：HttpOnly、SameSite=Strict，路径 `/api/v1/auth`；生产同时为 Secure。
- `lab_csrf`：前端可读、SameSite=Strict，路径 `/`；生产同时为 Secure。

`POST /auth/refresh` 必须同时带刷新 Cookie 和与 `lab_csrf` 一致的 `X-CSRF-TOKEN`。成功刷新会轮换 Refresh Token；数据库仅存 SHA-256 摘要。旧 Token 复用会撤销整个会话族。前端将 Access Token 只保存在内存，并将并发 401 合并成一个刷新请求。

改密、退出、禁用账号和角色调整会增加会话版本或撤销刷新会话，使旧 Access Token 失效。首次登录只允许访问改密/退出所需接口。

## 幂等与并发

预约创建、批准和驳回支持 `X-Idempotency-Key`（最长 128 字符）。同一用户、操作、键和请求摘要会返回原结果；同键不同请求返回 `IDEMPOTENCY_KEY_REUSED`。审批/更新请求携带实体 `version`；旧版本返回 `RESOURCE_VERSION_CONFLICT`。

关键业务冲突：

- `RESERVATION_STATUS_INVALID`：非法状态转换。
- `RESERVATION_SLOT_CONFLICT`：实验室日期课次已被有效预约占用。
- `EQUIPMENT_CAPACITY_EXCEEDED`：同槽位设备总申请量超出库存。
- `CANCELLATION_DEADLINE_PASSED`：申请人已过取消截止。
- `LAB_UNAVAILABLE`、`LAB_NOT_OPEN`、`LAB_BLACKOUT`：实验室状态/开放规则/停用限制。
- `RESOURCE_VERSION_CONFLICT`：乐观版本冲突。
- `RESERVATION_SCOPE_DENIED`、`LAB_SCOPE_DENIED`：数据范围拒绝。
- `MALFORMED_REQUEST`、`VALIDATION_FAILED`：格式、类型、缺失或字段校验失败。
- `RATE_LIMIT_EXCEEDED`：一分钟窗口限流，响应含 `Retry-After: 60`。

## 端点索引

| 模块 | 方法与路径 | 说明 |
|---|---|---|
| 认证 | `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout` | 登录、轮换刷新、退出 |
| 认证 | `GET /auth/me`, `PUT /auth/password` | 当前用户、首次/日常改密 |
| 课次 | `GET /course-periods`, `PUT /course-periods/{periodNo}` | 四节大课查询/配置 |
| 实验室 | `GET /labs`, `GET /labs/{id}`, `GET /labs/{id}/calendar` | 列表、详情、日历槽位 |
| 实验室 | `POST /labs`, `PUT /labs/{id}` | 系统管理员新增，负责人/系统管理员更新 |
| 开放规则 | `GET/PUT /labs/{id}/open-rules` | 周开放规则整体替换 |
| 停用课次 | `GET/POST /labs/{id}/blackouts`, `DELETE /labs/{labId}/blackouts/{id}` | 特殊停用维护 |
| 设备 | `GET /equipment`, `POST /equipment`, `PUT /equipment/{id}` | 查询和管理 |
| 空闲 | `GET /availability/labs` | 日期、课次、容量、设备综合查询 |
| 预约 | `POST /reservations`, `GET /reservations/my`, `GET /reservations/{id}` | 创建、本人列表、详情 |
| 预约 | `POST /reservations/{id}/cancel` | 申请人按截止规则取消 |
| 审批 | `GET /admin/reservations`, `POST /admin/reservations/{id}/approve` | 管辖列表、批准 |
| 审批 | `POST /admin/reservations/{id}/reject`, `POST /admin/reservations/{id}/cancel` | 驳回、强制取消（原因必填） |
| 历史 | `GET /admin/reservations/{id}/history` | 状态历史 |
| 考勤 | `POST /reservations/{id}/check-in`, `POST /reservations/{id}/check-out` | 申请人签到签退 |
| 考勤 | `POST /admin/reservations/{id}/check-in`, `POST /admin/reservations/{id}/check-out` | 管理员代操作 |
| 通知 | `GET /notifications`, `PUT /notifications/{id}/read`, `PUT /notifications/read-all` | 本人通知与已读 |
| 用户 | `GET/POST /admin/users`, `GET/PUT /admin/users/{id}`, `PUT /admin/users/{id}/roles` | 用户/角色分配 |
| 角色 | `GET/POST /admin/roles`, `PUT /admin/roles/{id}`, `GET /admin/permissions` | 角色权限 |
| 导入 | `POST /admin/users/import`, `GET /admin/import-tasks/{id}` | CSV 导入和行级结果 |
| 系统 | `GET /admin/settings`, `PUT /admin/settings/{key}`, `GET /admin/audit-logs` | 参数与审计 |
| 统计 | `GET /statistics/overview`, `/lab-usage`, `/peak-hours`, `/equipment-ranking` | 统计查询 |
| 导出 | `GET /statistics/export.csv` | 与当前筛选完全一致的 CSV |

具体请求/响应 Schema、必填字段与权限以自动生成 OpenAPI 为准，并由 `OpenApiApiIT` 校验版本、Bearer 安全方案、公开登录元数据及关键路径。
