# 从 0 到 1 开发路线图：双人情侣互动网站

> 参考开源项目：EchoMap（架构分层）、情侣小窝（功能设计）。本文给出完整落地路径，本项目代码已按此路线实现到 **阶段 5**（部署方案就绪），**阶段 6 的扩展功能已落地五项**（实时聊天、纪念日提醒、每日待办、AI 助手、学习笔记），其余仍在规划。

## 总览

| 阶段 | 内容 | 状态 |
|------|------|------|
| 1 | 需求确认与技术选型 | ✅ 完成 |
| 2 | 数据库设计 | ✅ 完成（`sql/init.sql`，12 张表） |
| 3 | 后端开发（认证/动态/纪念日/相册/上传） | ✅ 完成（`backend/`） |
| 4 | 前端开发（页面/组件/状态管理） | ✅ 完成（`frontend/`） |
| 5 | 联调与部署（Docker Compose） | ✅ 方案就绪（`docker-compose.yml` + `docs/deploy.md`） |
| 6 | 扩展功能（聊天/提醒/待办/Agent/打卡/积分） | 🚧 部分完成，见文末 |

---

## 阶段 1：需求与选型（已定）

- **用户**：仅两人，注册一方生成情侣码，另一方凭码绑定 → 天然隔离数据（所有业务表带 `couple_id`）。
- **后端**：Spring Boot 3.3 + Spring Security + JWT（无状态）+ MyBatis-Plus（Controller-Service-Mapper 分层）。
- **前端**：Vue 3 组合式 API + Vite + Element Plus + Pinia + Vue Router + Axios。
- **数据库**：MySQL 8.0，utf8mb4。
- **部署**：Docker Compose（MySQL + Backend + Nginx 前端），单机即可，方便迁移。

## 阶段 2：数据库设计

核心表（`sql/init.sql`，MySQL 8.0+）：

| 表 | 作用 | 关键字段 |
|----|------|----------|
| `user` | 双人账号 | username(唯一)、password(BCrypt)、couple_code(情侣码)、couple_id |
| `couple` | 情侣关系 | user_a_id、user_b_id、start_date(在一起那天) |
| `moment` | 动态时间轴 | couple_id、user_id、content、images(JSON)、location |
| `album` | 共享相册 | couple_id、name、cover_url、cover_manual(手动封面标记) |
| `photo` | 照片/视频 | album_id、url、type(IMAGE/VIDEO)、user_id、description |
| `anniversary` | 纪念日 | couple_id、name、anniversary_date、is_start、remind_days |
| `reminder_log` | 提醒防重 | anniversary_id、remind_date（唯一键防重复提醒） |
| `notification` | 站内通知 | user_id、type、title、content、related_id、is_read |
| `user_todo` | 个人待办 | user_id、title、done、priority、due_date、completed_at |
| `chat_message` | 聊天消息 | couple_id、sender_id、type、content、bot_flag、client_msg_id(幂等键) |
| `chat_read` | 聊天已读位点 | (couple_id, user_id)、last_read_message_id |

**设计要点**
- 数据隔离靠 `couple_id`：所有业务查询先取当前用户的 couple_id，天然保证只看到自己两人的数据，无需复杂权限。
- **个人数据用 `user_id` 隔离**：`user_todo` 与 `notification` 属于个人域，伴侣不可见（待办）/各自独立（通知）。
- `couple_code` 用后即废（绑定成功后置空），避免重放攻击。
- `moment.images` 用 JSON 列，前端九宫格直接消费；后期要拆大表也容易。
- `anniversary.is_start=1` 的"在一起的那天"在绑定时自动创建，不可删除，作为首页"在一起 X 天"的口径。
- 纪念日按"月-日"**每年重复**；`remind_days` 控制提前几天提醒。
- `chat_message.client_msg_id` + `UNIQUE(couple_id, client_msg_id)` 实现发送幂等，防重连重复入库。
- 聊天已读用"位点"而非逐条已读表：`chat_read.last_read_message_id` 一人一行，未读数按"对方的、id 大于位点的消息数"计算。
- 扩展表（task / task_record / point_record）以注释形式预留在 `sql/init.sql` 末尾，启用时取消注释即可。

## 阶段 3：后端开发

目录结构（`backend/src/main/java/com/couple/`）：

```
config/        SecurityConfig, JwtAuthenticationFilter, WebConfig(静态资源), MybatisPlusConfig
common/        Result 统一响应, BusinessException, GlobalExceptionHandler, PageResult
auth/          AuthController/Service, JwtUtil, dto(register/login/bind/avatar)
user/ couple/  User/Couple entity + mapper
moment/        Moment entity/mapper/service/controller (时间轴)
anniversary/   Anniversary 模块 (summary 倒计时) + Notification 站内信 + 提醒定时任务
album/         Album/Photo 模块 (共享相册、图片/视频、手动封面)
file/          FileStorageService 接口 + 本地实现 (上传，图片/视频 ≤200MB)
chat/          WebSocket 聊天 (ChatWsHandler/握手拦截器) + REST(历史/增量/已读/未读)
agent/         AgentClient/AgentChatClient (Java ↔ Python 向量同步与 SSE 对话)
todo/          Todo 模块 (个人每日待办，today-view 四分组)
note/          Note 模块 (学习笔记：md/txt/docx 上传、纯文本抽取、搜索)
```

**认证流程（Spring Security + JWT，无状态）**

```
注册 → BCrypt 加密密码入库 + 生成情侣码
登录 → 校验密码 → 签发 JWT(HS256, 载荷: userId, coupleId, 有效期7天)
请求 → JwtAuthenticationFilter 解析 Bearer token → 放入 SecurityContext
     → 接口通过 Principal 拿 userId/coupleId，无需每次查库
未带/失效 token → AuthenticationEntryPoint 返回 401 JSON
WebSocket 握手 → ChatHandshakeInterceptor 从 ?token= 解析 JWT → 校验已绑定 → 写入 session attributes
```

**关键决策**
- 用 MyBatis-Plus 而非原生 MyBatis：单表 CRUD 零 XML，复杂查询用 `LambdaQueryWrapper` 或 `@Select`，保持 Mapper 分层。
- `FileStorageService` 抽象出接口：本地实现先行，后续换阿里云 OSS 只需新增实现类 + 改一行注入。
- 统一 `Result<T>` + 全局异常处理：业务错误抛 `BusinessException(code, msg)`，前端 axios 拦截器统一提示；`code` 直接作为 HTTP 状态码（含 404）。
- 分页有两种约定：管理类列表用 MyBatis-Plus `IPage`（`records/total/current/size`）；聊天消息用**游标分页裸数组**（`beforeId`/`afterId`），避免深分页与新消息插入导致的错位。
- 聊天选择**原生 WebSocket**（`spring-boot-starter-websocket`）而非 STOMP：协议自定、帧结构简单，前端用原生 `WebSocket` 即可。
- AI 能力放在 **Python 侧**，Java 只做透传与降级（超时/熔断/静态文案），保证 Python 未就绪时主业务完全不受影响。

## 阶段 4：前端开发

目录结构（`frontend/src/`）：

```
api/           auth/moment/anniversary/album/file/chat/notification/todo/agent 各模块接口封装
utils/request.js   axios 实例 + token 注入 + 401 跳登录 + 统一错误提示
stores/        user.js(token/userInfo/partner/couple)  notification.js(未读红点)
router/        路由 + 登录守卫
layout/        Layout.vue 侧边栏布局 + 顶部通知铃铛 + 头像上传
views/         Login / Register / Bind / Home(时间轴) / Anniversary / Album / AlbumDetail
               Chat(悄悄话) / AiChat(AI 助手) / Todo(每日待办) / Note(学习笔记) / Notification(通知)
components/    MomentCard(动态卡片)、EmptyState(空态) 等
```

**首页（时间轴）数据流**

```
进入 Home → fetchMe(拉取用户/伴侣/在一起天数) + anniversary.summary(倒计时)
         + moment.list(分页时间轴) + moment.months/dates(快速定位侧栏)
发布动态 → 先传图拿 url → POST /api/moments → 刷新列表
滚动分页 → current+1 → 追加渲染
```

**聊天页（Chat.vue）关键实现**

```
进入 → GET /api/chat/messages(size=30) 拉最新一页 → reverse 成正序渲染
连接 → new WebSocket(`/ws/chat?token=${jwt}`)；onclose 指数退避重连
发送 → 本地先插入占位气泡(带 clientMsgId) → ws.send → 收到广播帧(同 id) 后合并
断线重连 → 先重发失败队列(同 clientMsgId，服务端幂等去重) → after(本地最大id) 补增量
已读 → 取本地最大真实 id，600ms 节流 PATCH /api/chat/read
@bot → 内容以 @bot 开头 → 服务端异步调 Python → AGENT_STREAM 帧流式追加到气泡
```

**AI 助手页（AiChat.vue）**：`fetch` + `ReadableStream` 解析 SSE（非 EventSource，因为需要 POST + Authorization 头），会话历史存 localStorage。

## 阶段 5：联调与部署

本地联调：Vite 代理 `/api`、`/uploads` → `localhost:8080`，`/ws` 开启 `ws: true` 代理升级请求，前后端独立热更新。
生产部署：见 `docs/deploy.md`（Docker Compose 一键起三服务，含 HTTPS 与备份方案）。

**部署侧必须注意的三点**（已在配置中处理）：
1. Nginx 需为 `/ws/` 转发 `Upgrade`/`Connection` 头，否则生产聊天必然连不上；
2. Nginx `client_max_body_size` 需 ≥ 250m，否则大视频上传被 413 拒绝；
3. AI Agent（Python）不在 compose 编排内，默认 `AGENT_ENABLED=false`，需要时通过 `host.docker.internal` 访问宿主机服务。

## 阶段 6：扩展规划与落地情况

| 扩展 | 状态 | 说明 / 实现思路 | 预留点 |
|------|------|------------------|--------|
| 实时聊天 | ✅ 已实现 | 原生 WebSocket `/ws/chat` + REST 历史/增量/已读；幂等键防重、断线重连补增量 | `chat_message` / `chat_read` 表、无状态 JWT 适配握手 |
| 纪念日提醒 | ✅ 已实现 | 每天 08:00 定时任务，`remind_days` 命中即发站内通知；`reminder_log` 唯一键防重 | `anniversary.remind_days` 字段 |
| 每日待办 | ✅ 已实现（形态调整） | 原计划"情侣任务打卡"，实际先落地**纯个人待办**（`user_id` 隔离，伴侣不可见），四分组聚合视图 | `user_todo` 表、Controller-Service-Mapper 分层 |
| 学习笔记 | ✅ 已实现 | 上传 `.md`/`.txt`/`.docx`，抽取纯文本入库供搜索与 AI 检索；原文件可下载；解析失败不阻断上传。正对应契约里预留的 `biz_type=note` / `scope=personal` / `note_u{userId}` | `note` 表、`IngestEvent` 通道、`FileStorageService.storeDocument()` |
| AI 助手 / 语义检索 | ✅ Java 侧 / ✅ Python 核心 / ⏳ 图谱 | Java 侧全部就绪（ingest 同步动态 / 照片 / 学习笔记向量 + SSE 对话透传 + `/v1/summarize` 摘要调用，含超时 / 熔断 / 降级，已改真流式）。Python 侧 `agent-service/` 已实现四接口 + 向量检索 + SQLite 多轮会话，**不依赖智能体框架**（LLM / 嵌入 / 向量库三个客户端自研，直连 httpx 与 qdrant-client），**无 mock 开关**。**Neo4j 实体/关系抽取未实现**。契约 `docs/agent-api.md`，施工 `docs/agent-service-build-guide.md`，实现 `agent-service/README.md` | `agent` 模块 + `IngestEvent` 事件 |
| 情侣任务打卡 | ⏳ 未实现 | `task`/`task_record` 表 + 打卡接口 + 首页卡片（共享任务，区别于个人待办） | 表结构（init.sql 注释）、分层规范 |
| 积分商城 | ⏳ 未实现 | `point_record` 表 + 积分增减服务 + 兑换接口 | 表结构、Result 统一规范 |
| 相册换 OSS | ⏳ 未实现 | 新增 `OssFileStorageServiceImpl`，上传时直传/服务端签名 | `FileStorageService` 接口抽象 |
| 纪念日公历→农历 | ⏳ 未实现 | 引入 lunar 库，date 存公历 + 附加字段 | 表结构可加列，无破坏 |
| 消息推送（微信/邮件） | ⏳ 未实现 | 在站内通知的基础上增加外部通道 | `notification` 表 + 提醒任务已有触发点 |

**扩展时的硬性约定**
- 新模块一律 `controller/service/mapper` 三层；
- 接口遵循 `docs/api-contract.md` 的 Result 与分页约定（注意区分 IPage 分页与游标分页）；
- 涉及两人共享数据的查询必须带 `couple_id` 过滤；个人数据必须带 `user_id` 过滤；
- 写业务数据后如需同步向量，通过发布 `IngestEvent` 而非直接调用 Agent 客户端。
