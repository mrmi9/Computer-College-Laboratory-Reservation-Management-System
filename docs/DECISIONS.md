# 架构与需求决策

## ADR-001：完成定义优先级

- 状态：已接受
- 决定：第 19 节以及目标文件显式补充的设备、签到签退、并发、审计、测试、备份恢复和部署均为必做。
- 原因：目标文件明确解决了第 1.2 节“加分扩展”与第 19 节之间的范围冲突。

## ADR-002：管理员预约身份

- 状态：已接受
- 决定：纯管理员不能创建预约；只有同时具有 `STUDENT` 或 `TEACHER` 业务角色的账号才能预约，类别由后端身份生成。
- 原因：遵循第 1.3、2.2 节和目标文件的显式解释，修正第 9.1 节路由表的宽泛描述。

## ADR-003：会话与 CSRF

- 状态：已接受
- 决定：Access Token 由前端仅保存在内存；Refresh Token 使用 HttpOnly Cookie，数据库只存 SHA-256 摘要；刷新接口采用双提交 CSRF Token，生产 Cookie 启用 Secure 和 SameSite=Strict。
- 原因：兼顾 SPA 使用体验、令牌泄露面和刷新请求 CSRF 防护。

## ADR-004：性能工具

- 状态：已接受
- 决定：性能脚本使用 k6，并通过固定版本 Docker 镜像执行，不依赖宿主机安装。
- 原因：当前环境未安装 k6，容器方式可重复且满足“不使用 latest”。

## ADR-005：PostgreSQL 集成测试运行方式

- 状态：已接受
- 决定：同一个无跳过的 Flyway 集成测试优先读取一次性外部测试数据库；未提供时使用 `postgres:16.10-alpine` Testcontainers。Windows 统一验证脚本在 Docker 不可用时，以本机 PostgreSQL 16 二进制创建并销毁一次性集群。
- 原因：当前 Docker Desktop 因 WSL 未启用而无法运行，但本机具备 PostgreSQL 16；该方式仍真实验证 PostgreSQL 语义，并保留 CI/Testcontainers 路径。
