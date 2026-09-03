# 部署手册

## 状态

部署材料将在 CP10 完成。本页先固定生产约束：PostgreSQL 不映射公共端口，后端和 Nginx 使用非 root 用户，密钥通过环境变量或只读挂载提供，生产仅暴露 HTTPS，管理端点不经 Nginx 对外公开。

## 本地开发

复制 `.env.example` 为 `.env` 并替换占位值后运行 `docker compose up --build`。生产不得直接复用示例密钥或密码。

## 备份与恢复

最终脚本将位于 `scripts/backup.ps1`、`scripts/restore.ps1` 和 `scripts/verify-backup-restore.ps1`，恢复验证会在新数据库核对预约与审计行数和摘要。
