# 双人情侣互动网站（Couple Site）项目梳理

> 本文档基于对仓库内源码、SQL、文档与构建产物的实际检查整理，作为项目现状的快速入口。
> 更新时间：2026-09-29（初版 2026-09-07）· 梳理范围：`D:\project\dsh\couple-site` 全仓（backend / frontend / sql / docs / 部署编排）
> 远端仓库：`https://github.com/kaer45/couple-site-2026.git`（分支 `main`）

---

## 1. 项目概览

**定位**：仅限两个人使用的私密互动小站 —— 记录恋爱日常、共享照片回忆、纪念日倒计时、实时聊天。一方注册生成**情侣码**，另一方凭码绑定，共享数据按情侣关系（`couple_id`）隔离，个人数据按用户（`user_id`）隔离。

**核心功能**：

| 功能 | 说明 |
|------|------|
| 双人账号系统 | 注册即生成 6 位情侣码（剔除易混淆字符），伴侣凭码绑定；JWT 无状态认证 |
| 回忆时间轴 | 图文动态，时间倒序分页（类"情侣朋友圈"），支持按日期/月份快速定位 |
| 纪念日管理 | "在一起 X 天" + 下一个纪念日倒计时；纪念日按"月-日"每年重复；绑定关系时自动创建"在一起的那天" |
| 共享云相册 | 按主题建相册，上传**图片与视频**，支持手动设置封面、修改照片描述 |
| 悄悄话（聊天） | WebSocket 实时双人聊天：图片消息、已读位点、未读红点、断线重连补增量；`@bot` 开头召唤 AI |
| AI 助手 | 独立页面 SSE 流式对话，基于情侣共享数据（动态/相册）语义检索 |
| 每日待办 | **纯个人数据**，按"逾期/今天/将来/今日完成"四分组聚合 |
| 学习笔记 | **纯个人数据**，上传 `.md` / `.txt` / `.docx` 笔记，自动抽取正文用于搜索与 AI 检索，支持下载原文件 |
| 站内通知 | 顶部铃铛红点 + 通知页，单条已读与全部已读；当前来源为纪念日提醒定时任务 |

**技术栈**：

| 端 | 技术 |
|----|------|
| 后端 | Java 17 · Spring Boot 3.3.5 · Spring Security + JWT（jjwt 0.12.6，HS256）· Spring WebSocket · WebClient/WebFlux（SSE）· MyBatis-Plus 3.5.7 · MySQL 8.0 · Lombok |
| 前端 | Vue 3（组合式 API）· Vite 5 · Element Plus 2.7 · Pinia · Vue Router 4 · Axios · dayjs |
| AI（可选） | Python Agent 服务（`agent-service/`）：`/v1/ingest`（向量同步）、`/v1/chat`（SSE 对话）、`/v1/summarize`（内容摘要）、`/v1/health`（探活）。技术栈：FastAPI + **hello-agents 0.2.9** + Qdrant + SQLite。⏳ Neo4j 知识图谱未实现 |
| 部署 | Docker Compose（MySQL 8.0 + Backend + Nginx），单机可跑，数据卷持久化 |

**开发进度**（对照 `docs/roadmap.md`）：阶段 1~5 全部完成；阶段 6 扩展功能已落地 **4 项**（实时聊天、纪念日提醒、每日待办、AI 助手），其余 4 项（情侣任务打卡、积分商城、OSS、农历）仍在规划。

---

## 2. 目录结构总览

```
couple-site/
├── backend/                 # Spring Boot 后端
│   ├── pom.xml              # Boot 3.3.5 / Java 17 / MP 3.5.7 / jjwt 0.12.6 / websocket / webflux
│   ├── Dockerfile           # 多阶段构建：maven 打包 → JRE17 运行
│   ├── uploads/             # 本地上传目录（已 gitignore，按 yyyy/MM/dd 归档）
│   ├── target/              # Maven 编译产物
│   └── src/main/
│       ├── java/com/couple/  # 见 §5 后端模块
│       └── resources/application.yml  # 端口/数据源/JWT/上传/Agent/CORS 配置
├── frontend/                # Vue 3 前端
│   ├── package.json         # Vue3/Element Plus/Pinia/Router/Axios/dayjs
│   ├── vite.config.js       # @别名 + /api、/uploads、/ws(ws:true) 开发代理 → 8080
│   ├── Dockerfile           # node 构建 → nginx 托管
│   ├── nginx.conf           # SPA 回退 + /api 反代 + /ws 升级 + SSE 免缓冲
│   ├── dist/                # 生产构建产物（已 gitignore，存在说明构建验证过）
│   └── src/                 # api/stores/router/layout/views/components/utils
├── sql/
│   ├── init.sql             # 建库建表脚本（12 张表 + 末尾注释预留扩展表）
│   ├── fix_comment.sql      # 历史修复：补齐表/字段注释
│   └── migration-*.sql      # 增量迁移脚本（已有库升级用；init.sql 只在空数据卷执行）
├── agent-service/           # Python Agent 服务（FastAPI + hello-agents 0.2.9 + Qdrant + SQLite）
│   ├── requirements.txt     # hello-agents==0.2.9 / fastapi / uvicorn / pydantic-settings / qdrant-client / neo4j
│   ├── .env.example         # 配置模板（LLM_* / DASHSCOPE_* / QDRANT_* / AGENT_MOCK）
│   ├── README.md            # 启动、mock 模式、降级矩阵、排错
│   ├── run.py               # 启动入口
│   ├── tests/               # test_core（纯逻辑 17 项）/ smoke_api（契约端到端 36 项）/ check_framework_api（框架假设 29 项）
│   └── app/                 # main / config / logging_conf / core / schemas / api / services / db / prompts
├── docs/
│   ├── api-contract.md      # ⭐ 前后端 API 契约（唯一基准，含 WS 与 SSE）
│   ├── agent-api.md         # ⭐ Java ↔ Python Agent 服务契约
│   ├── roadmap.md           # ⭐ 从 0 到 1 路线图（含阶段 6 落地情况）
│   ├── project-overview.md  # ⭐ 本文档（项目现状梳理）
│   └── deploy.md            # ⭐ 云服务器部署指导
├── couple-site-self-learning-docs/  # 按日整理的学习笔记（架构与实现原理）
├── docker-compose.yml       # 一键编排：mysql + backend + frontend
├── .env.example             # 环境变量模板（含 AGENT_*）
├── .gitignore               # 排除 target/dist/node_modules/uploads/.env/IDE/个人文件
└── README.md                # 项目入口 README
```

> 根目录另有 `resume-modular.md`、`resume-project-templates.md`、`秋招投递记录表.xlsx`、`csv2xlsx.py` 等**与本项目无关的个人文件**，已在 `.gitignore` 中排除；`test-body.json` 为调试残留（未跟踪）。

---

## 3. 系统架构

### 3.1 总体架构

```
浏览器（手机/PC）
   │
   ▼
┌────────────── Nginx (frontend 容器, 80 端口) ──────────────┐
│ 静态资源: /            → /usr/share/nginx/html (SPA)        │
│ API 反代: /api/**      → http://backend:8080               │
│ SSE 反代: /api/agent/chat → 关闭缓冲，流式下发               │
│ WS 升级:  /ws/**       → 转发 Upgrade 头，长连接              │
│ 图片视频: /uploads/**  → http://backend:8080               │
└──────────────────────────┬──────────────────────────────────┘
                           ▼
┌────────────── Backend (Spring Boot 容器, :8080) ────────────┐
│ Security 过滤链 → JwtAuthenticationFilter(解析 Bearer Token) │
│ WebSocket: /ws/chat ← ChatHandshakeInterceptor(?token= 鉴权) │
│ Controller: auth/moment/anniversary/notification/album/file  │
│             /chat /agent /todo /note /test(dev)              │
│ Service 层: 业务逻辑 + 事务 + 情侣码生成 + 提醒判定            │
│ Mapper 层: MyBatis-Plus 单表 CRUD / QueryWrapper / @Select    │
│ 定时任务:  AnniversaryReminderTask（每天 08:00 发站内通知）   │
│ 线程池:    agentExecutor(core2/max8/queue200) 异步 ingest+SSE │
│ FileStorageService: 本地磁盘实现（接口抽象，可换 OSS）        │
└──────────┬───────────────────────────────┬──────────────────┘
           ▼                               ▼
  MySQL 8.0 (couple 库, utf8mb4)   Python Agent (可选, 内网 :8001)
  12 张业务表 + 预留扩展表注释       /v1/ingest  /v1/chat(SSE)  /v1/health
```

### 3.2 认证与数据隔离设计

- **无状态 JWT**：登录/注册签发 HS256 Token（载荷含 `userId`、`coupleId`，默认 7 天有效）。请求经 `JwtAuthenticationFilter` 解析放入 SecurityContext，接口直接从 Principal 取用户，无需查库。
- **放行规则**：`/api/auth/**`（注册/登录）、`/uploads/**`（媒体文件）、`/ws/**`（握手自行鉴权）公开，其余接口一律要求认证；未带/失效 Token 返回统一 401 JSON，越权返回 403 JSON。
- **两级数据隔离**：
  - **情侣共享域**（`couple_id`）：`moment` / `album` / `photo` / `anniversary` / `reminder_log` / `chat_message` / `chat_read` —— 查询前先从 Principal 取 `couple_id` 过滤；
  - **个人私有域**（`user_id`）：`user_todo`（对方完全不可见）、`notification`（各看各的）。
- **情侣码安全**：6 位大写字母+数字（剔除 O/0/I/1），绑定成功后双方 `couple_code` 置空（用后即废，防重放）。
- **WebSocket 鉴权**：浏览器原生 WS 无法自定义 header，故 token 走 query（`?token=<JWT>`，不带 `Bearer ` 前缀）；拦截器校验后把 `userId`/`coupleId` 写入 session attributes（`coupleId` 取库中最新值，不信 JWT 旧值），连接建立后不再依赖 token。
- **聊天幂等**：`UNIQUE(couple_id, client_msg_id)` + 客户端 UUID，重连/抖动导致的重复发送只会入库一次。

---

## 4. 数据模型（sql/init.sql，12 张表）

| # | 表 | 作用 | 关键字段 / 约束 |
|---|----|------|------------------|
| 1 | `user` | 双人账号 | `username`(唯一)、`password`(BCrypt)、`couple_code`(可空)、`couple_id`(可空) |
| 2 | `couple` | 情侣关系 | `user_a_id`(发起方)、`user_b_id`(被绑定方)、`start_date`；双方均唯一约束 |
| 3 | `moment` | 动态时间轴 | `couple_id`、`user_id`、`content`、`images`(JSON 数组)、`location`；索引 `(couple_id, created_at DESC)` |
| 4 | `album` | 共享相册 | `couple_id`、`name`、`description`、`cover_url`、**`cover_manual`**(1=手动封面，不被新上传覆盖) |
| 5 | `photo` | 照片/视频 | `album_id`、`user_id`、`url`、**`type`**(IMAGE/VIDEO)、`thumbnail_url`、`description`、`taken_at` |
| 6 | `anniversary` | 纪念日 | `couple_id`、`name`、`anniversary_date`、`is_start`(1=在一起那天，自动创建不可删)、`remind_days` |
| 7 | `reminder_log` | 提醒防重 | `anniversary_id`、`remind_date`；`UNIQUE(anniversary_id, remind_date)` 为防重最后防线 |
| 8 | `notification` | 站内通知 | `user_id`、`couple_id`、`type`、`title`、`content`、`related_id`、`is_read`；索引 `(user_id, is_read, created_at DESC)` |
| 9 | `user_todo` | 个人待办 | `user_id`、`title`、`done`、`priority`、`due_date`(空=未排期)、`completed_at`；索引 `(user_id, due_date)` |
| 10 | `chat_message` | 聊天消息 | `couple_id`、`sender_id`、`type`(TEXT/IMAGE/AGENT)、`content`、`bot_flag`、`client_msg_id`；`UNIQUE(couple_id, client_msg_id)` |
| 11 | `chat_read` | 聊天已读位点 | 主键 `(couple_id, user_id)`、`last_read_message_id`（一人一行，未读数=对方的、id 大于位点的条数） |
| 12 | `note` | 学习笔记（个人） | `user_id`、`title`、`format`(MD/TXT/DOCX)、`original_url`、`file_size`、`content_text`(抽取正文)、`parse_status`/`parse_error`、**`summary_ai`/`summary_ai_at`**(AI 摘要)；`FULLTEXT … WITH PARSER ngram`（预留） |

**预留扩展表**（`init.sql` 末尾以注释存在，启用时取消注释即可）：`task` / `task_record`（情侣任务打卡）、`point_record`（积分商城）。

**设计要点**：
- `moment.images` 用 JSON 列存图片 URL 数组，前端九宫格直接消费；
- "在一起 X 天"口径 = `couple.start_date` 到今天的 `ChronoUnit.DAYS` 差值；
- 纪念日"下一次到达" = 把月-日套到今年，已过则套到明年（`2/29` 非闰年退化为 `2/28`）；
- 相册封面 = 手动封面优先，否则取最新一张**图片**（跳过视频）；删除封面照片后回退为最新图片并恢复自动模式；
- 聊天用"已读位点"而非逐条已读表，`chat_read` 恒定一人一行，未读数计算 O(1) 定位 + 一次 count；
- 学习笔记**双存**：`original_url` 存原文件（供查看/下载），`content_text` 存抽取出的纯文本（供关键词搜索与 AI 检索）；
- 笔记解析失败**不阻断上传**：`parse_status=2` + `parse_error` 记录原因，原文件照常可下载，只是不参与搜索与向量同步。

---

## 5. 后端模块（backend/src/main/java/com/couple/）

| 包 | 内容 | 说明 |
|----|------|------|
| `CoupleApplication` | 启动类 | — |
| `config/` | `SecurityConfig`、`JwtAuthenticationFilter`、`CoupleUserPrincipal`、`WebConfig`(静态资源映射 /uploads)、`MybatisPlusConfig` | 安全过滤链、CORS、JWT 过滤器、MP 分页 |
| `common/` | `Result<T>`、`BusinessException`、`GlobalExceptionHandler`、`PageResult` | 统一响应体 + 全局异常兜底（400/401/403/404/500） |
| `auth/` | `AuthController/Service(Impl)`、`JwtUtil` + DTO | 注册/登录/绑定/me/头像；情侣码生成（SecureRandom，去重尝试） |
| `user/` `couple/` | `User`/`Couple` entity + mapper | 用户表与关系表 |
| `moment/` | `MomentController/Service(Impl)` + DTO + entity + mapper | 发布/分页时间轴(anchorDate 快速定位)/删除/日期&月份列表 |
| `anniversary/` | `AnniversaryController/Service(Impl)`、**`NotificationController/Service`**、**`AnniversaryReminderService` + `task/AnniversaryReminderTask`**、`util/AnniversaryDateUtil`、`controller/TestController`(dev) | summary(在一起天数+下一个纪念日+未来 180 天列表)/CRUD（is_start=1 不可删）/每天 08:00 提醒/站内通知/手动触发测试 |
| `album/` | `Album/Photo` Controller/Service(Impl) + DTO + entity + mapper | 相册 CRUD、图片视频上传绑定(urls+types)、手动封面、照片描述修改、删除(仅本人)、级联删相册 |
| `file/` | `FileController`、`FileStorageService`(接口) + `LocalFileStorageServiceImpl` | 单文件上传（image/* 或 video/*，≤200MB），按 yyyy/MM/dd 存盘；尽力删除磁盘文件 |
| `chat/` | `ChatController/Service(Impl)`、`ChatWsHandler`、`ChatHandshakeInterceptor`、`WebSocketConfig` + DTO + entity + mapper | REST(历史/增量/已读/未读) + WebSocket 广播 + 90s 心跳踢连接 + `agent/AgentChatService`(@bot 异步调 Agent 并落库) |
| `agent/` | `AgentClient`、`AgentChatClient`、`AgentConfig`、`AgentProperties`、`IngestEvent`、`IngestEventListener` | 向量同步 ingest（AFTER_COMMIT + @Async + 重试）+ 对话 SSE 客户端（超时/熔断/降级）+ 专用线程池 |
| `todo/` | `TodoController/Service(Impl)` + DTO + entity + mapper | 个人每日待办（today-view 四分组聚合、CRUD、全量覆盖更新） |
| `note/` | `NoteController/Service(Impl)`、**`NoteTextExtractor`**、`NoteFormat` + DTO + entity + mapper | 学习笔记（md/txt/docx 上传、纯文本抽取、分页搜索、重命名、删除；发布 `note` 类 IngestEvent） |

**分层规范**：一律 `Controller → Service(接口+Impl) → Mapper`；单表 CRUD 用 MyBatis-Plus 零 XML，复杂查询用 `LambdaQueryWrapper` / `@Select`；业务错误抛 `BusinessException(code, msg)`，由全局异常处理器统一转为 JSON（`code` 直接作为 HTTP 状态码，因此业务"不存在/无权"表现为 404）。

**可扩展点**：
- `FileStorageService` 接口抽象，换阿里云 OSS 只需新增实现类并改注入；
- 写业务数据后通过发布 `IngestEvent`（而非直接调 Agent 客户端）触发向量同步，新增模块复用同一通道即可；
- `AnniversaryReminderService` 已具备"判定 + 防重"骨架，新增提醒渠道（微信/邮件）只需在其后追加动作。

---

## 6. 前端模块（frontend/src/）

| 目录/文件 | 内容 | 说明 |
|-----------|------|------|
| `api/` | `auth` `moment` `anniversary` `album` `file` `chat` `notification` `todo` `agent` | 9 个模块的接口封装 |
| `utils/request.js` | axios 实例 | 自动注入 `Authorization: Bearer <token>`；401 时清 token 跳登录；统一错误提示 |
| `stores/` | `user.js`、`notification.js` | 用户/伴侣/关系状态；通知未读红点 |
| `router/index.js` | 路由 + 全局前置守卫 | 需登录页无 token → 跳 /login 带 redirect；已绑定用户访问 login/register/bind → 回首页 |
| `layout/Layout.vue` | 侧边栏布局 | 主导航（时间轴/纪念日/悄悄话/AI 助手/相册/每日待办）+ 顶部通知铃铛 + 头像上传弹窗 + 聊天未读 15s 轮询 |
| `views/` | `Home`(时间轴) `Login` `Register` `Bind` `Anniversary` `Album` `AlbumDetail` `Chat` `AiChat` `Todo` `Note` `Notification` | 12 个页面 |
| `components/` | `MomentCard.vue` `EmptyState.vue` | 动态卡片、空态占位 |
| `styles/index.css` | 全局样式 | — |

**首页（时间轴）数据流**：进入 Home → 并行拉取 `auth/me`（用户/伴侣/在一起天数）+ `anniversaries/summary`（倒计时）+ `moments`（分页时间轴）+ `moments/months`、`moments/dates`（快速定位侧栏）；发布动态先经 `/api/files/upload` 逐张传图拿 URL，再 POST 到 `/api/moments`。

**聊天页（Chat.vue）关键实现**：

```
连接   new WebSocket(`/ws/chat?token=${jwt}`)，onclose 指数退避 min(1000*2^n, 30000) 重连
首屏   GET /api/chat/messages?size=30 → reverse 成正序
发送   本地先插入占位气泡(带 clientMsgId) → ws.send → 收到广播帧(同 id) 后合并去重
重连   先重发失败队列(同 clientMsgId，服务端幂等) → after(本地最大 id) 补增量
已读   取本地最大真实 id，600ms 节流 PATCH /api/chat/read
@bot   内容以 @bot 开头 → 服务端异步调 Python → AGENT_STREAM 帧流式追加到气泡
```

**AI 助手页（AiChat.vue）**：用 `fetch` + `ReadableStream` 手工解析 SSE（而非 `EventSource`，因为需要 POST 请求体与 Authorization 头），会话历史存 localStorage（key `ai-chat-{userId}`），不落库。

**路由清单**：`/login` `/register` `/bind`(需登录) `/`(首页时间轴) `/anniversaries` `/chat`(悄悄话) `/ai`(AI 助手) `/albums` `/albums/:id` `/todos` `/notes`(学习笔记) `/notifications`，未知路径兜底回首页。

---

## 7. API 一览（详见 docs/api-contract.md）

统一响应体：`{ code, message, data }`；鉴权头：`Authorization: Bearer <token>`；页码分页参数：`current` / `size`。

| 模块 | 方法 | 路径 | 鉴权 |
|------|------|------|------|
| auth | POST | `/api/auth/register` | 公开（返回 token + 情侣码） |
| auth | POST | `/api/auth/login` | 公开 |
| auth | POST | `/api/auth/bind` | 需登录（coupleCode + startDate） |
| auth | GET | `/api/auth/me` | 需登录 |
| auth | PUT | `/api/auth/avatar` | 需登录 |
| moments | POST | `/api/moments` | 需登录 + 已绑定 |
| moments | GET | `/api/moments?current&size&anchorDate` | 需登录 + 已绑定 |
| moments | DELETE | `/api/moments/{id}` | 仅本人（403） |
| moments | GET | `/api/moments/dates` · `/api/moments/months` | 需登录 + 已绑定 |
| anniversaries | GET | `/api/anniversaries/summary` | 需登录 + 已绑定 |
| anniversaries | GET/POST | `/api/anniversaries` | 需登录 + 已绑定 |
| anniversaries | PUT/DELETE | `/api/anniversaries/{id}` | 需登录 + 已绑定（is_start=1 不可删） |
| notifications | GET | `/api/notifications?current&size` | 仅需登录 |
| notifications | GET | `/api/notifications/unread-count` | 仅需登录 |
| notifications | PUT | `/api/notifications/{id}/read` · `/api/notifications/read-all` | 仅需登录 |
| todos | GET | `/api/todos/today-view` | 仅需登录（个人数据） |
| todos | POST | `/api/todos` | 仅需登录 |
| todos | PUT/DELETE | `/api/todos/{id}` | 仅需登录（归属校验，越权 404） |
| notes | GET | `/api/notes?current&size&keyword` | 仅需登录（个人数据，列表不含正文） |
| notes | GET | `/api/notes/{id}` | 仅需登录（含抽取出的正文） |
| notes | POST | `/api/notes` | 仅需登录（multipart：file + 可选 title，md/txt/docx ≤10MB） |
| notes | PUT/DELETE | `/api/notes/{id}` | 仅需登录（改标题 / 删除原文件与向量） |
| albums | GET/POST | `/api/albums` | 需登录 + 已绑定 |
| albums | PUT/DELETE | `/api/albums/{id}` | 需登录 + 已绑定 |
| albums | GET | `/api/albums/{id}` | 需登录 + 已绑定（仅本情侣） |
| albums | POST | `/api/albums/{id}/photos` | 需登录 + 已绑定（JSON urls + types） |
| albums | PUT | `/api/albums/{id}/photos/{photoId}` | 需登录 + 已绑定（改描述，不校验上传者） |
| albums | PUT | `/api/albums/{id}/cover` | 需登录 + 已绑定（设置手动封面） |
| albums | DELETE | `/api/albums/{id}/photos/{photoId}` | 仅上传者（403） |
| files | POST | `/api/files/upload` | 需登录（multipart，image/* 或 video/*，≤200MB） |
| files | POST | `/api/notes`（笔记专用上传，见上） | 需登录（扩展名白名单，≤10MB，校验 docx ZIP 魔数） |
| chat | GET | `/api/chat/messages?beforeId&size` | 需登录 + 已绑定（游标分页） |
| chat | GET | `/api/chat/messages/after?afterId&limit` | 需登录 + 已绑定（增量补齐） |
| chat | PATCH | `/api/chat/read` | 需登录 + 已绑定（上报已读位点） |
| chat | GET | `/api/chat/unread-count` | 需登录 + 已绑定 |
| chat | WS | `/ws/chat?token=<JWT>` | 握手鉴权（需已绑定） |
| agent | POST | `/api/agent/chat` | 仅需登录（SSE 流式，不落库） |
| test | POST | `/api/test/remind/trigger` | 仅 `dev` profile（手动触发提醒任务） |

---

## 8. 核心业务流程

### 8.1 注册 → 绑定

```
用户A 注册 ──► 生成情侣码(6位) + BCrypt 密码入库 ──► 前端展示码，转发给伴侣
用户B 注册 → 输入情侣码 + 开始日期 ──► 校验(码存在/未被用/不是自己)
  ──► 创建 couple(user_a=码主人, user_b=B) ──► 双方 couple_id 更新、couple_code 置空
  ──► 自动创建 anniversary("在一起的那天", is_start=1) ──► 首页开始倒计时
```

### 8.2 请求认证链路

```
请求 ──► JwtAuthenticationFilter 解析 Bearer Token
  ├─ 有效 ──► 构造 CoupleUserPrincipal 放入 SecurityContext ──► Controller 拿 userId/coupleId
  └─ 缺失/失效 ──► AuthenticationEntryPoint 返回 401 JSON
WebSocket 握手 ──► ChatHandshakeInterceptor 取 ?token= ──► 校验用户存在且已绑定
  ├─ 通过 ──► session attributes 写入 userId/coupleId ──► 建立连接
  └─ 失败 ──► beforeHandshake 返回 false（不升级连接）
```

### 8.3 媒体上传链路

```
前端 el-upload ──► POST /api/files/upload(multipart) ──► 校验类型(image/*|video/*)/大小(≤200MB)
  ──► 按 yyyy/MM/dd 存盘（本地 uploads/ 或 Docker /data/uploads 卷）──► 返回 { url }
  ──► 前端把 url 写入动态 images / 相册 photos(+types) / 头像
浏览器访问 /uploads/** ──► WebConfig 静态映射（本地）/ Nginx 反代（Docker）
```

### 8.4 实时聊天与 @bot

```
前端 ws.send({type, content, clientMsgId})
  ──► ChatWsHandler 反序列化 ──► ChatService.save()（撞唯一键则丢弃）
  ──► 广播 MessageVO 给本情侣全部在线连接（含发送者）
  ──► 若 content.trim() 以 @bot 开头：
        @Async 调 Python /v1/chat（scope=couple, session=couple{coupleId}）
        增量经 AGENT_STREAM 帧**边收边推** ──► 完整回复落库(type=AGENT, botFlag=1) ──► 历史/重连可见
服务端心跳：每 30s 扫描，90s 无上行文本帧即关闭连接（4001 heartbeat timeout）
```

### 8.5 纪念日提醒

```
每天 08:00 AnniversaryReminderTask
  ──► 遍历所有纪念日 ──► remindDays 为空/0 视为当天 ──► 算下一次到达日
  ──► 仅当 daysLeft == remindDays 时：先插 reminder_log（唯一键防重）
  ──► 插入成功才发通知 ──► 该情侣下每人一条 notification ──► 前端铃铛红点
```

### 8.6 学习笔记上传与解析

```
前端 el-upload（自定义 http-request）──► POST /api/notes（multipart: file + 可选 title）
  ──► 先把请求体整体读成字节（同一份字节用于落盘 + 解析）
       ⚠️ 不能先落盘再读：transferTo() 写完会删掉容器临时文件
  ──► 存储层校验：扩展名白名单(.md/.markdown/.txt/.docx) + ≤10MB + docx ZIP 魔数
  ──► 落盘原始文件 ──► 返回 /uploads/yyyy/MM/dd/uuid.ext
  ──► NoteTextExtractor 抽取纯文本（复用同一份字节）
        ├─ MD/TXT：UTF-8 严格解码，失败退回 GBK（去 UTF-8 BOM）
        └─ DOCX：ZIP 解包 word/document.xml，StAX 抽 w:t 文本节点
  ──► 抽取成功：parse_status=1，写 content_text
      抽取失败：parse_status=2 + parse_error，**上传仍算成功**（原文件可下载）
  ──► 入库 ──► 解析成功才发 IngestEvent("note", id, 标题+正文, "personal", userId, "upsert")
删除：删行 + 删磁盘文件 + IngestEvent(action="delete")
```

### 8.7 向量同步（AI 检索的数据来源）

```
业务写库（moment 发布/删除、photo 上传/改描述/删照片/删相册、note 上传/改标题/删除）
  ──► 发布 IngestEvent ──► IngestEventListener(@Async + AFTER_COMMIT) ──► AgentClient
  ──► POST {agent.url}/v1/ingest（X-Agent-Key），失败按 5s/30s 重试
```

---

## 9. 运行与部署

### 9.1 本地开发

```bash
# 前置：JDK 17+、MySQL 8（先执行 sql/init.sql）、Node 18+
cd backend  && mvn spring-boot:run        # 后端 http://localhost:8080
cd frontend && npm install && npm run dev # 前端 http://localhost:5173，/api /uploads /ws 已代理
```

### 9.2 Docker 一键部署

```bash
cp .env.example .env        # 必改：MYSQL_ROOT_PASSWORD / MYSQL_PASSWORD / JWT_SECRET
docker compose up -d --build
# 三服务：couple-mysql / couple-backend / couple-frontend(80 端口)
# 首次启动自动执行 sql/init.sql（仅空数据卷）；数据在 ./data/ 卷
```

### 9.3 环境变量（.env.example）

| 变量 | 默认 | 说明 |
|------|------|------|
| `MYSQL_ROOT_PASSWORD` | —（必改） | MySQL root 密码 |
| `MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` | couple / couple / —（必改） | 业务库与账号 |
| `JWT_SECRET` | —（必改） | 至少 32 位随机串，可用 `openssl rand -base64 32` 生成 |
| `JWT_EXPIRATION` | 604800000 | Token 有效期（毫秒，7 天） |
| `CORS_ALLOWED_ORIGINS` | http://localhost:5173 | 允许跨域来源（生产同源可不动） |
| `AGENT_ENABLED` | `.env` 中为 `false`（代码默认 `true`） | 是否启用 Python Agent |
| `AGENT_URL` | `http://host.docker.internal:8001` | Agent 地址（容器内访问宿主机） |
| `AGENT_KEY` | `change-me`（生产必改） | 与 Python 端 `X-Agent-Key` 一致的共享密钥 |

其他后端配置：`UPLOAD_DIR`（默认 `./uploads`，Docker 为 `/data/uploads`）、`SPRING_DATASOURCE_*`。
HTTPS / 备份 / 迁移 / 常见故障见 `docs/deploy.md`。

---

## 10. 项目现状与注意事项

### 10.1 完成状态（基于产物核查）

| 项 | 状态 |
|----|------|
| 后端代码 | ✅ 已编写，`backend/target` 存在（编译过） |
| 前端代码 | ✅ 已编写，`frontend/dist` 存在（生产构建跑通过） |
| 数据库 | ✅ `sql/init.sql` 建表脚本就绪（12 张表，已去重整理） |
| 联调痕迹 | ✅ `backend/uploads/2026/09/**` 存在真实上传图片（含 09-10、09-11 等日期目录） |
| 版本管理 | ✅ 已是 git 仓库，远端 `github.com/kaer45/couple-site-2026`，分支 `main` |
| 文档 | ✅ README / API 契约 / Agent 契约 / 路线图 / 部署 / 本文档齐全 |
| 上传与构建产物 | ✅ `backend/uploads/`、`frontend/dist/`、`backend/target/` 均已 gitignore |

### 10.2 ✅ 本次已修复的问题

| 问题 | 原状况 | 处理 |
|------|--------|------|
| 后端端口不一致 | `application.yml` 为 8081，而 Vite/nginx/compose/Dockerfile 与文档均为 8080，本地与生产都会连不上 | 现 `application.yml` 已是 `8080`，全链路一致 |
| 生产 Nginx 缺 WebSocket 代理 | `nginx.conf` 只有 `/`、`/api/`、`/uploads/`，`/ws/chat` 会命中 SPA 回退返回 index.html，**生产聊天必然连不上** | 新增 `location /ws/`（`proxy_http_version 1.1` + 转发 `Upgrade`/`Connection`，读写超时 3600s） |
| 生产 Nginx 上传上限过小 | `client_max_body_size 20m`，而后端已支持 200MB 单文件 / 250MB 单请求，大视频会被 413 拒绝 | 提升为 `250m` |
| SSE 被 Nginx 缓冲 | `/api/agent/chat` 经普通 `/api/` 反代会被缓冲，AI 增量会攒着一次性下发 | 新增专用 location 并 `proxy_buffering off` |
| SQL 表重复定义 | `chat_message` / `chat_read` 在 `init.sql` 中被定义两遍（前一处 `IF NOT EXISTS` + 后一处 `DROP` + `CREATE`），且前一处未读数注释写错 | 删除重复块，保留 `DROP` + `CREATE` 版本并按 1~11 重新编号，预留扩展表统一移到文件末尾（编号 12/13） |
| Docker 未传 Agent 配置 | `docker-compose.yml` 与 `.env.example` 均无 `AGENT_*`，容器内只能吃代码默认值（`enabled=true` 却连不上 Python，持续打错误日志） | compose 增加 `AGENT_ENABLED`（默认 `false`）/`AGENT_URL`/`AGENT_KEY` 与 `extra_hosts: host.docker.internal`；`.env.example` 补齐说明 |
| AI 回复为"假流式" | `AgentChatClient` 用 `collectList().block()` 把 Python 的 SSE 流**攒完才返回**，再一次性下发；每层都用了流式技术，用户看到的却是"等几秒 → 整段出现"，前端打字机逻辑形同装饰 | 改为 `doOnNext` + `.then()` + `onDelta` 回调边收边推；补 `publishOn(boundedElastic)` 把阻塞下发移出 Netty 事件循环；回调异常不再中断整条流。`AgentController` / `AgentChatService` 同步改为到达即下发，聊天失败时按"已推内容"落库（详见 `couple-site-self-learning-docs/2026-09-29/agent-streaming-refactor.md`） |
| 缺少学习笔记模块 | 契约里早已预留 `biz_type=note` / `scope=personal` / 命名空间 `note_u{userId}`，但模块本身不存在；且 `FileStorageService.store()` 写死了 image/video，笔记文件会被拒 | 新增 `note` 模块（表 + 后端 + `Note.vue` 页面）与 `storeDocument()`；docx 解析**未引 POI** 而用 JDK 的 ZIP + StAX（本环境拉不到该依赖），取舍见 `docs/api-contract.md` §11.6 |
| 笔记上传后一律"解析失败" | `NoteServiceImpl.upload()` 先 `storeDocument()`（内部 `transferTo`）再 `file.getBytes()`；而 `transferTo` 写完会**删除容器临时文件**，于是读内容必然 `FileNotFoundException`，被"解析失败不阻断上传"的 catch 吞掉，表现为"上传成功但解析失败" | 改为**先把内容读成字节**，同一份字节既落盘也解析；`storeDocument` 签名改为 `(byte[], String)` 彻底消除顺序依赖（已端到端复测 md/txt，含 GBK 编码回退） |
| 笔记列表卡片一律显示"（无正文内容）" | 列表查询为省内存排除了 `content_text` 列，但 `summary` 正是从该列算出来的，于是永远为 null | 改为 `LEFT(content_text, 300) AS summary` 让数据库侧截取，映射到实体的非表字段 `summary`（`@TableField(exist = false)`）；已实测列表与关键词搜索均正常 |
| docx 笔记切到"纯文本"再切回"渲染"变空白 | 抽屉用 `v-if/v-else` 切换视图，切走时销毁了 `<div ref="docxContainer">`，而 `renderDocx` 只在 `openDetail` 里调用 → 切回来只剩一个空容器 | 两个视图都改用 `v-show` 保持挂载（docx 渲染结果不再被销毁），并加一个 `watch(viewMode)` 兜底：容器为空时才补渲染 |
| 笔记卡片显示原始 Markdown 语法 | 后端旧实现只折叠空白、未清洗语法，卡片里直接出现 `# trick`、`**标题**` | 改为后端只给**原始节选**（前 300 字，保留换行），前端新增 `renderExcerpt()` 用 markdown-it 解析后渲染：只取 inline 块（跳过代码块）、不硬截断（避免残缺标签）、丢图片、**软换行换成空格**（`breaks:true` 产生的 `<br>` 会与卡片的 `line-clamp` 打架）、**公式正常渲染 KaTeX**（初版曾为省体积保留 `$...$` 原文，实测效果就是"没渲染"，已改） |
| 摘要只有"正文节选"一层 | 需求是「文档自身内容 + 大模型生成」两层，且大模型那层要可手动触发 | 新增 `note.summary_ai` / `summary_ai_at` + `POST /api/notes/{id}/summary`（同步调 Python `/v1/summarize`）；展示优先级 **AI 摘要 > 正文节选**；卡片上加「AI 摘要」标记；**失败绝不写库**，只返回 503 与具体原因。契约见 `docs/agent-api.md` §3 |

### 10.3 ⚠️ 仍存在、建议后续处理

| 位置 | 问题 | 影响 / 建议 |
|------|------|-------------|
| `frontend/src/views/Chat.vue` | **前端不发心跳帧**，而服务端 90s 无上行文本帧即踢连接 | 页面静默约 1.5 分钟后会被动断开再重连。建议前端每 30~60s 发送一个轻量保活帧（服务端对任意文本帧都会刷新活跃时间） |
| `backend/.../agent/AgentClient.java` | 去重 Map `recentCalls` **只写不清理** | 随 (bizType,bizId,action) 组合单调增长，长期运行有内存隐患。建议改为带过期时间的缓存 |
| `backend/.../agent/AgentClient.java` | ingest 重试**不区分错误码**（401/400 也重试），且 `RETRY_DELAYS_MS` 的第 3 个 2min 因循环条件永不 sleep；`RestTemplate` 未配超时 | 与 `docs/agent-api.md` 原描述不符（文档已标注现状）。建议区分可重试状态码并配置连接/读超时 |
| `backend/.../agent/AgentChatClient.java` | 用 `instanceof TimeoutException` 判定超时，但 Reactor `.timeout()`/`block()` 会把超时异常包装成 `ReactiveException`/`IllegalStateException` | 超时可能被归类为 `UNREACHABLE`（文案相同，用户无感，但错误归因不准）。建议改为判定 cause 链 |
| `backend/.../album/AlbumServiceImpl.java` | 更新照片描述**不校验上传者**，而删除照片要求本人 | 伴侣可互改描述，删除却不可。若为有意设计需在契约中注明（已注明）；否则应统一 |
| `frontend/src/views/Album.vue` | 创建相册弹窗 `maxlength` 为 30/100，编辑弹窗为 100/255，与后端 `name ≤100`/`description ≤255` 不完全一致 | 建议统一为 100/255；另 `AlbumRequest.description` 后端无 `@Size` 校验，超长会撞 DB 报错 |
| `sql/init.sql` | `user_todo` 表段落的注释编号与"扩展预留"段落曾被混排 | 已在本次整理中重排；后续新增表请延续编号并保持 `DROP` + `CREATE` 风格 |
| `backend/.../chat/WebSocketConfig.java` | `setAllowedOriginPatterns("*")` 允许任意 Origin 握手；token 走 query 会进入 Nginx/网关访问日志 | 生产建议收敛 Origin，并在反代层对 `token` 参数脱敏 |
| `docker-compose.yml` | 未编排 Python Agent 服务（当前通过 `host.docker.internal` 访问宿主机） | 若长期使用，建议把 Python 服务也纳入 compose 并改用服务名互访 |
| `backend/.../note/` | 学习笔记的正文抽取是**同步**执行的（在请求线程内），且列表搜索用 `LIKE '%kw%'`（不走 `note` 表的 ngram 全文索引） | 单篇 ≤10MB 时同步解析耗时可接受；若允许更大文件或并发上传，建议改为异步解析（复用 `agentExecutor`）+ 轮询 `parse_status`；数据量大后把搜索切到 `MATCH ... AGAINST` |
| `frontend/src/views/Note.vue` | 正文以**纯文本**预览（`<pre>`），不做 Markdown 渲染 | 项目未引入 markdown 渲染库（`markdown-it` / `marked` 均不在 `node_modules`）。刻意不自己实现渲染器——手写 Markdown→HTML 极易引入 XSS。联网后 `npm i markdown-it` 并配合 sanitize 再启用渲染即可 |
| 仓库根目录 | 存在 `test-body.json` 等调试残留（未跟踪） | 建议删除或加入 gitignore |

### 10.4 其他备注

- `out/` 为 IntelliJ IDEA 编译输出，可忽略/加入 gitignore（当前 `.gitignore` 已覆盖 `.idea/`，未覆盖 `out/`）；
- 上传目录 `backend/uploads/`（本地）与 Docker 数据卷 `./data/uploads` 是两份独立数据，迁移/备份时注意区分；
- `couple-site-self-learning-docs/` 是按日期沉淀的学习笔记（认证链路、上传链路、异常处理、模块分层、提醒防重等），可作为理解实现的补充材料；
- 扩展功能（任务打卡/积分/OSS/农历/消息推送）的设计思路与预留点见 `docs/roadmap.md` 阶段 6。

---

## 11. 快速上手路径（建议阅读顺序）

1. `README.md` — 项目定位与快速开始
2. `docs/api-contract.md` — 前后端接口唯一基准（联调必看，含 WebSocket 与 SSE 协议）
3. `docs/agent-api.md` — Java ↔ Python Agent 契约（涉及 AI 能力时看）
4. `docs/roadmap.md` — 设计决策与扩展规划
5. `docs/project-overview.md` — 本文档，现状全貌
6. `docs/deploy.md` — 部署上线（需要时）
