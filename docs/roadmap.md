# 从 0 到 1 开发路线图：双人情侣互动网站

> 参考开源项目：EchoMap（架构分层）、情侣小窝（功能设计）。本文给出完整落地路径，本项目代码已按此路线实现到 **阶段 4**，后续阶段按预留接口扩展。

## 总览

| 阶段 | 内容 | 状态 |
|------|------|------|
| 1 | 需求确认与技术选型 | ✅ 完成 |
| 2 | 数据库设计 | ✅ 完成（`sql/init.sql`） |
| 3 | 后端开发（认证/动态/纪念日/相册/上传） | ✅ 完成（`backend/`） |
| 4 | 前端开发（页面/组件/状态管理） | ✅ 完成（`frontend/`） |
| 5 | 联调与部署（Docker Compose） | ✅ 方案就绪（`docker-compose.yml` + `docs/deploy.md`） |
| 6 | 扩展功能（任务打卡/积分/聊天） | ⏳ 预留，见文末 |

---

## 阶段 1：需求与选型（已定）

- **用户**：仅两人，注册一方生成情侣码，另一方凭码绑定 → 天然隔离数据（所有业务表带 `couple_id`）。
- **后端**：Spring Boot 3.3 + Spring Security + JWT（无状态）+ MyBatis-Plus（Controller-Service-Mapper 分层）。
- **前端**：Vue 3 组合式 API + Vite + Element Plus + Pinia + Vue Router + Axios。
- **数据库**：MySQL 8.0，utf8mb4。
- **部署**：Docker Compose（MySQL + Backend + Nginx 前端），单机即可，方便迁移。

## 阶段 2：数据库设计

核心 6 张表（`sql/init.sql`，MySQL 8.0+）：

| 表 | 作用 | 关键字段 |
|----|------|----------|
| `user` | 双人账号 | username(唯一)、password(BCrypt)、couple_code(情侣码)、couple_id |
| `couple` | 情侣关系 | user_a_id、user_b_id、start_date(在一起那天) |
| `moment` | 动态时间轴 | couple_id、user_id、content、images(JSON)、location |
| `album` | 共享相册 | couple_id、name、cover_url |
| `photo` | 照片 | album_id、url、user_id、description |
| `anniversary` | 纪念日 | couple_id、name、anniversary_date、is_start |

**设计要点**
- 数据隔离靠 `couple_id`：所有业务查询先取当前用户的 couple_id，天然保证只看到自己两人的数据，无需复杂权限。
- `couple_code` 用后即废（绑定成功后置空），避免重放攻击。
- `moment.images` 用 JSON 列，前端九宫格直接消费；后期要拆大表也容易。
- `anniversary.is_start=1` 的"在一起的那天"在绑定时自动创建，不可删除，作为首页"在一起 X 天"的口径。
- 扩展表（task / task_record / point_record / chat_message）已以注释形式预留，启用时取消注释即可。

## 阶段 3：后端开发

目录结构（`backend/src/main/java/com/couple/`）：

```
config/        SecurityConfig, JwtAuthenticationFilter, WebConfig(静态资源)
common/        Result 统一响应, BusinessException, GlobalExceptionHandler
auth/          AuthController/Service, JwtUtil, dto(register/login/bind)
user/          User entity/mapper
couple/        Couple entity/mapper
moment/        Moment entity/mapper/service/controller (时间轴)
anniversary/   Anniversary 模块 (summary 倒计时)
album/         Album/Photo 模块 (共享相册)
file/          FileStorageService 接口 + 本地实现 (上传)
```

**认证流程（Spring Security + JWT，无状态）**

```
注册 → BCrypt 加密密码入库 + 生成情侣码
登录 → 校验密码 → 签发 JWT(HS256, 载荷: userId, coupleId, 有效期7天)
请求 → JwtAuthenticationFilter 解析 Bearer token → 放入 SecurityContext
     → 接口通过 Principal 拿 userId/coupleId，无需每次查库
未带/失效 token → AuthenticationEntryPoint 返回 401 JSON
```

**关键决策**
- 用 MyBatis-Plus 而非原生 MyBatis：单表 CRUD 零 XML，复杂查询用 `QueryWrapper` 或 `@Select`，保持 Mapper 分层。
- `FileStorageService` 抽象出接口：本地实现先行，后续换阿里云 OSS 只需新增实现类 + 改一行注入。
- 统一 `Result<T>` + 全局异常处理：业务错误抛 `BusinessException(code, msg)`，前端 axios 拦截器统一提示。
- 分页统一用 MyBatis-Plus `IPage`，前端按 `records/total/current/size` 渲染。

## 阶段 4：前端开发

目录结构（`frontend/src/`）：

```
api/           auth/moment/anniversary/album/file 各模块接口封装
utils/request.js   axios 实例 + token 注入 + 401 跳登录 + 统一错误提示
stores/user.js     Pinia: token/userInfo/partner/couple
router/        路由 + 登录守卫
layout/        Layout.vue 侧边栏布局
views/         Login / Register / Bind / Home(时间轴) / Anniversaries / Albums / AlbumDetail
components/    MomentCard(动态卡片) 等
```

**首页（时间轴）数据流**

```
进入 Home → fetchMe(拉取用户/伴侣/在一起天数) + anniversary.summary(倒计时)
         + moment.list(分页时间轴)
发布动态 → 先传图拿 url → POST /api/moments → 刷新列表
滚动分页 → current+1 → 追加渲染
```

## 阶段 5：联调与部署

本地联调：Vite 代理 `/api`、`/uploads` → `localhost:8080`，前后端独立热更新。
生产部署：见 `docs/deploy.md`（Docker Compose 一键起三服务，含 HTTPS 与备份方案）。

## 阶段 6：扩展规划（架构已预留）

| 扩展 | 实现思路 | 已预留点 |
|------|----------|----------|
| 情侣任务打卡 | `task`/`task_record` 表 + 打卡接口 + 首页卡片 | 表结构、Controller-Service-Mapper 分层 |
| 积分商城 | `point_record` 表 + 积分增减服务 + 兑换接口 | 表结构、Result 统一规范 |
| 实时聊天 | Spring WebSocket + `chat_message` 表 + 前端消息页；JWT 通过握手参数鉴权 | 表结构、无状态 JWT 天然适配 |
| 相册换 OSS | 新增 `OssFileStorageServiceImpl`，上传时直传/服务端签名 | FileStorageService 接口抽象 |
| 消息通知 | 纪念日前 `remind_days` 天推送（定时任务或聊天消息） | anniversary.remind_days 字段 |
| 纪念日公历→农历 | 引入 lunar 库，date 存公历 + 附加字段 | 表结构可加列，无破坏 |

**扩展时的硬性约定**：新模块一律 `controller/service/mapper` 三层；接口遵循 `docs/api-contract.md` 的 Result 与分页约定；涉及两人共享数据的查询必须带 `couple_id` 过滤。
