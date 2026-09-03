# 实施状态

## 当前检查点

`CP3 认证、会话、RBAC 与数据范围` — 进行中。

## 基线审计（2026-09-03）

- 基线提交：`8bca1f44094263e0e4e98af97ac3b7fc2b694051`。
- 分支：`codex/complete-first-release`，由 `origin/main` 安全创建。
- 初始工作区：仅含 `docs/开发文档.md`，无用户未提交修改。
- 远程：`https://github.com/mrmi9/Computer-College-Laboratory-Reservation-Management-System.git`。
- 环境：Java 21.0.12.1、Maven 3.9.16、Node 24.20.0、npm 11.19.0、Docker 29.7.2、Compose 5.5.0。
- `gh` 和宿主机 `k6` 不可用；PR 使用 compare URL/API 可用性继续验证，k6 通过固定版本容器执行。

## 最近进展

- 已完整阅读权威开发文档，未修改原文。
- 已建立实施计划、需求追踪、决策、测试、部署、API 和管理员文档入口。
- 正在创建后端/前端工程骨架、锁文件、Wrapper、CI 和统一验证脚本。

## 已完成检查点

- CP1：Spring Boot 3.5.13/Maven Wrapper、Vue 3.5.42/Vite 8.2.2 严格 TypeScript 工程、固定依赖 lockfile、配置分层、CI 和 PowerShell 验证入口已建立；后端 `clean verify` 与前端 lint/typecheck/Vitest/build 均通过。
- CP2：Flyway 从 PostgreSQL 16.15 空库创建 25 张业务表、约束与索引，开发数据独立于生产迁移；非法课次、同槽位生效预约唯一性和密码摘要均通过集成测试。

## 环境注意事项

- Docker Desktop 4.89 当前因宿主机未启用 WSL 而无法启动。Windows 验证脚本会自动使用本机 PostgreSQL 16 工具创建一次性真实数据库；CI 和 Docker 可用环境仍通过同一个测试类执行 Testcontainers 路径。
