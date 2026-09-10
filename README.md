# 💕 双人情侣互动网站（Couple Site）

记录恋爱日常、共享照片回忆、纪念日倒计时的**私密双人小站**。仅限两个人使用：一方注册生成**情侣码**，另一方凭码绑定，所有数据按情侣关系隔离。

## ✨ 功能

- 🔐 **双人账号系统**：注册生成情侣码 → 伴侣凭码绑定，JWT 无状态认证
- 📜 **回忆时间轴**：图文动态，时间倒序分页，类似"情侣朋友圈"
- 💝 **纪念日管理**：首页展示"在一起 X 天" + 下一个纪念日倒计时
- 📷 **共享云相册**：按主题建相册（如"2024旅行"），上传/浏览/预览照片

## 🛠 技术栈

| 端 | 技术 |
|----|------|
| 后端 | Java 17 · Spring Boot 3.3 · Spring Security + JWT · MyBatis-Plus · MySQL 8 |
| 前端 | Vue 3（组合式 API）· Vite · Element Plus · Pinia · Vue Router · Axios |
| 部署 | Docker Compose（MySQL + Backend + Nginx）· 单机可跑，迁移方便 |

## 📁 项目结构

```
demo/
├── sql/init.sql            # 数据库建表脚本（MySQL 8，首次启动自动执行）
├── backend/                # Spring Boot 后端（Controller-Service-Mapper 分层）
│   ├── Dockerfile
│   └── src/main/java/com/couple/   # config/common/auth/user/couple/moment/anniversary/album/file
├── frontend/               # Vue 3 前端
│   ├── Dockerfile
│   ├── nginx.conf          # SPA 托管 + /api 反代
│   └── src/                # api/stores/router/layout/views/components
├── docs/
│   ├── api-contract.md     # ⭐ 前后端 API 契约（唯一基准）
│   ├── roadmap.md          # ⭐ 从0到1开发路线图（含扩展规划）
│   └── deploy.md           # ⭐ 云服务器部署指导（Docker Compose + HTTPS + 备份）
├── docker-compose.yml      # 一键部署编排
└── .env.example            # 环境变量模板（复制为 .env 并修改密钥）
```

## 🚀 快速开始

### 本地开发

前置：JDK 17+、MySQL 8（先执行 `sql/init.sql`）、Node 18+。

```bash
# 1. 后端（backend/ 下）
mvn spring-boot:run        # http://localhost:8080

# 2. 前端（frontend/ 下）
npm install
npm run dev                # http://localhost:5173 （/api 已代理到 8080）
```

### Docker 一键部署（云服务器）

```bash
cp .env.example .env       # 修改数据库密码与 JWT 密钥
docker compose up -d --build
# 访问 http://<服务器IP>/
```

详细步骤、域名 HTTPS、数据备份与迁移见 [docs/deploy.md](docs/deploy.md)。

## 📖 文档索引

- [API 契约（前后端接口定义）](docs/api-contract.md)
- [从 0 到 1 开发路线图](docs/roadmap.md)
- [部署指导](docs/deploy.md)

## 🔮 后续扩展（架构已预留）

情侣任务打卡 · 积分商城 · 实时聊天（WebSocket）· 相册换 OSS · 纪念日提醒 —— 设计思路见 [docs/roadmap.md](docs/roadmap.md) 阶段 6。
