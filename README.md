# 计算机学院实验室预约管理系统

面向学生、教师、实验室管理员和系统管理员的实验室预约平台。项目采用 Java 21 + Spring Boot 3.5、Vue 3 + TypeScript、PostgreSQL 16 和 Docker Compose。

## 当前状态

第一阶段正在按 [实施计划](docs/IMPLEMENTATION_PLAN.md) 开发。只有 [需求追踪表](docs/REQUIREMENTS_TRACEABILITY.md) 中的范围全部标记为 `VERIFIED`，且 `scripts/verify.ps1` 在干净环境返回 0，才视为可验收。

## 本地开发

前置条件：Java 21、Node.js 24、Docker Desktop。

```powershell
Copy-Item .env.example .env
docker compose up -d postgres
Set-Location backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

另开终端：

```powershell
Set-Location frontend
npm ci
npm run dev
```

默认入口为 `http://localhost:5173`，后端 OpenAPI 文档为 `http://localhost:8080/swagger-ui.html`。演示账号将在种子数据检查点完成后列入 [管理员手册](docs/ADMIN_GUIDE.md)。

## 验证

```powershell
.\scripts\verify.ps1
```

验证脚本采用非交互方式执行后端、前端、E2E、容器、安全检查和备份恢复验证，任何阶段失败都会返回非零退出码。

## 文档索引

- [权威开发文档](docs/开发文档.md)
- [实施计划](docs/IMPLEMENTATION_PLAN.md)
- [需求追踪](docs/REQUIREMENTS_TRACEABILITY.md)
- [实施状态](docs/IMPLEMENTATION_STATUS.md)
- [架构决策](docs/DECISIONS.md)
- [API 说明](docs/API.md)
- [部署手册](docs/DEPLOYMENT.md)
- [测试报告](docs/TEST_REPORT.md)
- [管理员手册](docs/ADMIN_GUIDE.md)
