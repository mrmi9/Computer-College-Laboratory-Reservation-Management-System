# 计算机学院实验室预约管理系统

第一阶段候选版本，面向学生、教师、实验室管理员和系统管理员。系统提供实验室与设备目录、空闲查询、预约审批、签到签退、通知、审计、统计导出及运维恢复闭环。

## 技术栈

- 后端：Java 21、Spring Boot 3.5.13、Spring Security、Spring Data JPA、Flyway、PostgreSQL 16、springdoc-openapi。
- 前端：Vue 3.5、严格 TypeScript、Vite 8、Pinia、Axios、Element Plus、ECharts。
- 质量：JUnit 5、MockMvc、Testcontainers/本机隔离 PostgreSQL、JaCoCo、Vitest、Playwright、k6、Trivy。
- 部署：Docker Compose、非 root Spring Boot/Nginx 容器、PostgreSQL、HTTPS 模板和加密备份。

## 已实现能力

- 四类角色登录、Refresh Token 轮换、双提交 CSRF、首次改密、锁定、禁用与会话撤销。
- 后端 RBAC 与实验室负责人数据范围；纯管理员不能伪造学生/教师身份预约。
- 四节大课、实验室负责人/策略/开放规则/停用课次和设备管理。
- 综合空闲查询、学生/教师预约、自动/人工审批、取消截止与管理员强制取消。
- 乐观版本、幂等键、状态机、实验室槽位唯一约束和设备行锁防超卖。
- 用户/管理员签到签退、自动完成/爽约、违规冻结、站内通知及 Outbox 重试。
- 用户角色管理、CSV 导入、系统参数、审计、统计图表与同筛选 CSV。
- 完整桌面/移动业务端和管理端，含加载、空态、错误、403、404 与 409 恢复。

详细覆盖证据见 [需求追踪表](docs/REQUIREMENTS_TRACEABILITY.md) 和 [测试报告](docs/TEST_REPORT.md)。

## 快速启动（Docker 开发环境）

前置条件：Docker Desktop/Engine 与 Docker Compose 可用。

```powershell
Copy-Item .env.example .env
# 编辑 .env，替换 DB_PASSWORD、JWT_SECRET、BACKUP_ENCRYPTION_KEY
docker compose -f compose.yaml -f compose.dev.yaml up -d --build --wait
```

浏览器入口为 `http://127.0.0.1:8088`。开发 OpenAPI 未经 Nginx 公开，可在后端本机端口或容器内部访问 `/v3/api-docs`。演示账号见 [管理员手册](docs/ADMIN_GUIDE.md)。

停止并删除开发数据卷：

```powershell
docker compose -f compose.yaml -f compose.dev.yaml down --volumes
```

## 本机开发

本机需 Java 21、Node.js 24 和 PostgreSQL 16。先创建 `lab_booking` 数据库，再设置环境变量并启动后端：

```powershell
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:5432/lab_booking'
$env:DB_USERNAME = 'lab_booking'
$env:DB_PASSWORD = '本地数据库密码'
$env:JWT_SECRET = '至少32字符的本地开发密钥'
Set-Location backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

另开终端：

```powershell
Set-Location frontend
npm ci
npm run dev
```

本机前端为 `http://localhost:5173`，后端 Swagger UI 为 `http://localhost:8080/swagger-ui.html`。

## 统一验证

```powershell
.\scripts\verify.ps1
```

默认从干净构建和全新数据库开始，依次执行后端测试/覆盖率、前端 lint/类型/单测/构建、桌面与移动 Playwright、Compose 解析、安全扫描、容器健康、加密备份恢复、真实后端 E2E 和 300 VU 性能验证；任一失败返回非零。

开发调试可显式跳过耗时阶段，但带跳过参数的结果不能作为发布验收：

```powershell
.\scripts\verify.ps1 -SkipDocker -SkipPerformance
.\scripts\verify.ps1 -SkipE2E
```

独立入口：

- `scripts/verify-backend.ps1`：本机一次性 PostgreSQL 16；`-UseTestcontainers` 改用 Testcontainers。
- `scripts/verify-security.ps1`：生产依赖、秘密、配置与 Trivy 扫描。
- `scripts/verify-docker.ps1`：构建、健康、网络、非 root、API 代理、恢复与真实 E2E。
- `scripts/verify-performance.ps1`：1 万用户、200 实验室、300 VU k6。

## 生产部署

生产只映射 Nginx 8443，PostgreSQL 与后端仅在内部网络，证书通过只读目录挂载：

```powershell
docker compose -f compose.yaml -f compose.prod.yaml up -d --build --wait
```

上线、回滚、证书、备份和监控步骤见 [部署手册](docs/DEPLOYMENT.md)。

## 文档

- [权威开发文档](docs/开发文档.md)
- [实施计划](docs/IMPLEMENTATION_PLAN.md)
- [需求追踪](docs/REQUIREMENTS_TRACEABILITY.md)
- [实施状态](docs/IMPLEMENTATION_STATUS.md)
- [架构决策](docs/DECISIONS.md)
- [API 说明](docs/API.md)
- [部署手册](docs/DEPLOYMENT.md)
- [测试报告](docs/TEST_REPORT.md)
- [管理员手册](docs/ADMIN_GUIDE.md)
