# API 说明

正式接口以运行时 `/v3/api-docs` 生成的 OpenAPI 3 文档为准，基础路径为 `/api/v1`。

所有响应包含稳定的 `code`、`message`、`requestId`、`timestamp`；成功响应额外包含 `data`。写请求通过 `X-Idempotency-Key` 防止重复副作用，请求链路使用 `X-Request-Id`。

关键冲突码：

- `RESERVATION_STATUS_INVALID`
- `RESOURCE_VERSION_CONFLICT`
- `RESERVATION_SLOT_CONFLICT`
- `EQUIPMENT_CAPACITY_EXCEEDED`

接口实现完成后，本页将给出认证 Cookie/CSRF 约定、分页/导出限制、端点索引和示例。
