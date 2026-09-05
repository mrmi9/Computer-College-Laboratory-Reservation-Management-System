# 部署与恢复手册

## 拓扑与边界

生产拓扑为 `客户端 -> Nginx:8443 -> /api -> Spring Boot:8080 -> PostgreSQL:5432`。只有 Nginx 映射宿主机端口；后端、Prometheus 指标和 PostgreSQL 均不经 Nginx 公开。`data` 网络为内部数据库网络，`web` 网络仅连接前端与后端。

运行时镜像均锁定明确版本；后端 UID 10001、Nginx 使用非 root 用户，二者启用只读根文件系统、临时目录、`no-new-privileges` 和 capability drop。Nginx 生产配置只接受 TLS 1.2/1.3，并设置 HSTS、CSP、禁止 frame、MIME 嗅探和权限策略响应头。

## 前置条件

- Docker Engine/Desktop 和 Compose v2（仓库验证版本：Docker 29.7.2、Compose 5.5.0）。
- 生产证书目录包含 `fullchain.pem` 和 `privkey.pem`，私钥权限仅允许运维账户读取。
- 独立生成数据库密码、JWT 密钥和备份密钥；JWT/备份密钥至少 32 个高熵字符且不能相同。
- DNS、证书、备份异机存储与告警接收方由部署单位提供。

## 配置

```powershell
Copy-Item .env.example .env
```

必须修改：`DB_PASSWORD`、`JWT_SECRET`、`BACKUP_ENCRYPTION_KEY`、`CORS_ALLOWED_ORIGINS`、`TLS_CERT_DIRECTORY`。`.env` 被 Git 忽略，不应通过聊天、工单正文或日志传输。

校验两套 Compose：

```powershell
docker compose --env-file .env -f compose.yaml -f compose.dev.yaml config --quiet
docker compose --env-file .env -f compose.yaml -f compose.prod.yaml config --quiet
```

## 开发/验收环境

```powershell
docker compose -f compose.yaml -f compose.dev.yaml up -d --build --wait
docker compose -f compose.yaml -f compose.dev.yaml ps
```

默认前端仅绑定 `127.0.0.1:8088`，PostgreSQL 仅绑定 `127.0.0.1:5432`，便于本机调试而不对局域网公开。可通过 `PUBLIC_HTTP_PORT` 和 `POSTGRES_DEV_PORT` 改端口。`dev` profile 会加载四个演示账号和两间实验室，不能用于生产数据。

## 生产启动

1. 把证书放入 `TLS_CERT_DIRECTORY`，不要提交仓库。
2. 确认 `SPRING_PROFILES_ACTIVE=prod` 和唯一允许来源的 `CORS_ALLOWED_ORIGINS`。
3. 在维护窗口先备份现有数据库。
4. 拉取目标提交后执行：

```powershell
docker compose -f compose.yaml -f compose.prod.yaml build --pull
docker compose -f compose.yaml -f compose.prod.yaml up -d --wait
docker compose -f compose.yaml -f compose.prod.yaml ps
```

后端启动时 Flyway 先校验并迁移；迁移失败会导致后端健康检查失败，Nginx 不会进入就绪状态。JPA 始终使用 `ddl-auto: validate`，不会自动改表。

验证：

```powershell
Invoke-WebRequest https://服务器地址:8443/healthz
docker compose -f compose.yaml -f compose.prod.yaml exec -T backend curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness
```

生产 `/v3/api-docs` 与 Swagger UI 已关闭；Prometheus 只可由内部采集器访问后端 `/actuator/prometheus`。建议告警 CPU/内存/磁盘、容器重启、JVM/GC、连接池、HTTP P95/P99 与 5xx、数据库锁等待、预约失败率、Outbox/待审批积压。

## 备份

`backup.ps1` 在 PostgreSQL 容器内生成 custom-format 一致性 dump，复制到宿主机后使用 PBKDF2-HMAC-SHA256（200,000 次）派生 AES-256-GCM 密钥加密；临时明文 dump 在 `finally` 中清除。

```powershell
$env:BACKUP_ENCRYPTION_KEY = '从秘密管理器注入的独立密钥'
.\scripts\backup.ps1 -OutputPath D:\secure-backups\lab-booking-20260905.dump.enc
```

建议每日全量、重要时段提高频率；日备份保留 30 天、月备份保留 12 个月，并复制到与应用主机隔离的介质。密钥与备份必须分开保存。目标 RPO ≤ 24 小时、RTO ≤ 4 小时。

## 恢复与演练

恢复默认拒绝覆盖已有数据库；覆盖必须显式传 `-ReplaceExisting`。生产恢复前停止写流量并再次确认数据库名：

```powershell
$env:BACKUP_ENCRYPTION_KEY = '与备份匹配的密钥'
.\scripts\restore.ps1 -InputPath D:\secure-backups\lab-booking-20260905.dump.enc -TargetDatabase lab_booking_restore
```

隔离演练会写入唯一审计标记、备份、恢复到新数据库，并比较预约/审计行数与有序 MD5 指纹：

```powershell
.\scripts\verify-backup-restore.ps1
```

结果写入 `artifacts/backup/latest-result.json`，包含恢复耗时、指纹、RPO 和加密算法。每季度至少演练一次并把该文件转存至运维记录。

## 升级与回滚

- 发布前执行完整 `scripts/verify.ps1`，保留对应 Git 提交与镜像摘要。
- 应用回滚：切回上一已验证提交并重新构建启动；不得手工删除 Flyway 历史。
- 数据库回滚：先停止写流量，恢复发布前加密备份到新数据库，核对预约和审计指纹后切换 `DB_URL`。
- V2 为追加式迁移；仍应把数据库恢复视为独立变更流程，不使用 `flyway clean`。
- 若健康检查失败，先看 `docker compose logs backend` 中首个 Flyway/配置错误，再决定应用或数据回滚。

## 安全检查

```powershell
.\scripts\verify-security.ps1
.\scripts\verify-docker.ps1
```

前者检查生产 npm 依赖、已跟踪私钥/环境文件、生产安全配置和 Trivy；后者验证空卷迁移、健康、非 root、端口绑定、Nginx API 路由、管理端点隔离、运行时镜像、备份恢复和真实后端 E2E。
