# 💕 双人情侣互动网站（Couple Site）

记录恋爱日常、共享照片回忆、纪念日倒计时的**私密双人小站**。仅限两个人使用：一方注册生成**情侣码**，另一方凭码绑定，所有数据按情侣关系隔离；此外还有只属于自己的每日待办与学习笔记。

## ✨ 功能

| 模块 | 说明 |
|------|------|
| 🔐 双人账号系统 | 注册生成情侣码 → 伴侣凭码绑定；JWT 无状态认证，业务数据按 `couple_id` 隔离 |
| 📜 回忆时间轴 | 图文动态，时间倒序分页（类"情侣朋友圈"），支持按日期 / 月份快速定位 |
| 💝 纪念日管理 | "在一起 X 天" + 下一个纪念日倒计时；纪念日按"月-日"每年重复，每天 08:00 定时任务按 `remind_days` 发站内通知（`reminder_log` 防重） |
| 📷 共享云相册 | 按主题建相册，上传图片与视频，支持手动设置封面、修改照片描述 |
| 💬 悄悄话 | WebSocket 实时双人聊天：图片消息、已读位点、未读红点、断线重连补增量；以 `@bot` 开头可召唤 AI 回复 |
| 🤖 AI 助手 | 独立页面 SSE 流式对话，基于情侣共享数据（动态 / 相册）检索作答 |
| ✅ 每日待办 | **纯个人数据，伴侣不可见**；按"逾期 / 今天 / 将来 / 今日完成"四组聚合展示 |
| 📒 学习笔记 | **纯个人数据**；上传 `.md` / `.txt` / `.docx`，自动抽取正文用于搜索与 AI 检索，可下载原文件 |
| 🔔 站内通知 | 顶部铃铛红点 + 通知页，支持单条已读与全部已读 |

## 🛠 技术栈

| 端 | 技术 |
|----|------|
| 后端 | Java 17 · Spring Boot 3.3.5 · Spring Security + JWT（jjwt 0.12.6，HS256）· Spring WebSocket · WebClient（SSE）· MyBatis-Plus 3.5.7 · MySQL 8.0 · Lombok |
| 前端 | Vue 3（组合式 API）· Vite 5 · Element Plus · Pinia · Vue Router 4 · Axios · dayjs |
| AI（可选） | Python Agent 服务（`agent-service/`）：FastAPI + **Qdrant**（向量）+ **Neo4j**（知识图谱）+ **DeepSeek**（对话）+ **DashScope text-embedding-v3**（嵌入），契约见 `docs/agent-api.md` |
| 部署 | Docker Compose（MySQL + Backend + Nginx）· 单机可跑，迁移方便 |

## 📁 项目结构

```
couple-site/
├── sql/
│   ├── init.sql            # 建库建表脚本（12 张表，MySQL 8，首次启动自动执行）
│   └── fix_comment.sql     # 历史修复：补齐表/字段注释
├── backend/                # Spring Boot 后端（Controller-Service-Mapper 分层）
│   ├── Dockerfile
│   └── src/main/java/com/couple/
│       ├── config/         # Security、JWT 过滤器、静态资源映射、MP 分页
│       ├── common/         # Result / BusinessException / 全局异常 / PageResult
│       ├── auth/ user/ couple/   # 注册登录绑定、用户与关系
│       ├── moment/               # 回忆时间轴
│       ├── anniversary/          # 纪念日 + 提醒定时任务 + 站内通知
│       ├── album/ file/          # 相册（图片/视频/封面）、文件上传
│       ├── chat/                 # 实时聊天（WebSocket + 历史/已读 + @bot）
│       ├── agent/                # Java ↔ Python Agent 客户端（向量同步、SSE 对话）
│       ├── todo/                 # 每日待办（个人数据）
│       └── note/                 # 学习笔记（md/txt/docx 上传与正文抽取）
├── agent-service/          # Python Agent 服务（FastAPI + Qdrant + Neo4j）
│   ├── requirements.txt    # 依赖清单（pip install -r）
│   ├── .env.example        # 配置模板（DeepSeek / DashScope / Qdrant / Neo4j）
│   ├── run.py              # 启动入口（默认 8001）
│   └── app/                # api / services / db / prompts / core / schemas
├── frontend/               # Vue 3 前端
│   ├── Dockerfile
│   ├── nginx.conf          # SPA 托管 + /api 反代 + /ws 升级 + SSE 免缓冲
│   └── src/                # api/stores/router/layout/views/components/utils
├── docs/
│   ├── api-contract.md     # ⭐ 前后端 API 契约（唯一基准）
│   ├── agent-api.md        # ⭐ Java ↔ Python Agent 服务契约
│   ├── roadmap.md          # ⭐ 从0到1开发路线图（含扩展规划）
│   ├── project-overview.md # ⭐ 项目现状梳理（架构/模块/进度）
│   └── deploy.md           # ⭐ 云服务器部署指导（Docker Compose + HTTPS + 备份）
├── couple-site-self-learning-docs/   # 按日整理的学习笔记（架构与实现原理）
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
npm run dev                # http://localhost:5173 （/api、/uploads、/ws 已代理到 8080）
```

> AI 助手与聊天内 `@bot` 需要额外启动 Python Agent 服务；未启动时这些入口只返回降级文案，不影响其他功能。设 `AGENT_ENABLED=false` 可完全关闭。

### Docker 一键部署（云服务器）

```bash
cp .env.example .env       # 修改数据库密码与 JWT 密钥
docker compose up -d --build
# 访问 http://<服务器IP>/
```

详细步骤、域名 HTTPS、数据备份与迁移、常见故障排查见 [docs/deploy.md](docs/deploy.md)。

## 📖 文档索引

- [API 契约（前后端接口定义）](docs/api-contract.md)
- [Agent 服务契约（Java ↔ Python）](docs/agent-api.md)
- [从 0 到 1 开发路线图](docs/roadmap.md)
- [项目现状梳理](docs/project-overview.md)
- [部署指导](docs/deploy.md)

## 🔮 后续扩展（架构已预留）

情侣任务打卡 · 积分商城 · 相册换 OSS · 纪念日公历转农历 · 消息推送（微信 / 邮件） —— 设计思路见 [docs/roadmap.md](docs/roadmap.md) 阶段 6。
