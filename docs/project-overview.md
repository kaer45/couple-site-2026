# 双人情侣互动网站（Couple Site）项目梳理

> 本文档基于对仓库内源码、SQL、文档与构建产物的实际检查整理，作为项目现状的快速入口。
> 生成时间：2026-09-07 · 梳理范围：`D:\project\dsh\demo` 全仓（backend / frontend / sql / docs / 部署编排）

---

## 1. 项目概览

**定位**：仅限两个人使用的私密互动小站 —— 记录恋爱日常、共享照片回忆、纪念日倒计时。一方注册生成**情侣码**，另一方凭码绑定，所有业务数据按情侣关系（`couple_id`）隔离。

**核心功能**：

| 功能 | 说明 |
|------|------|
| 双人账号系统 | 注册即生成 6 位情侣码（剔除易混淆字符），伴侣凭码绑定；JWT 无状态认证 |
| 回忆时间轴 | 图文动态，时间倒序分页（类"情侣朋友圈"），支持按日期/月份快速定位 |
| 纪念日管理 | 首页展示"在一起 X 天" + 下一个纪念日倒计时；绑定关系时自动创建"在一起的那天" |
| 共享云相册 | 按主题建相册（如"2024旅行"），上传/浏览/预览照片，封面自动取最新一张 |

**技术栈**：

| 端 | 技术 |
|----|------|
| 后端 | Java 17 · Spring Boot 3.3.5 · Spring Security + JWT (jjwt 0.12, HS256) · MyBatis-Plus 3.5.7 · MySQL 8.0 · Lombok |
| 前端 | Vue 3（组合式 API）· Vite 5 · Element Plus · Pinia · Vue Router 4 · Axios · dayjs |
| 部署 | Docker Compose（MySQL 8.0 + Backend + Nginx），单机可跑，数据卷持久化 |

**开发进度**（对照 `docs/roadmap.md`）：阶段 1~4（需求/建库/后端/前端）已完成，阶段 5 部署方案就绪，阶段 6 扩展功能已预留表结构与扩展点。

---

## 2. 目录结构总览

```
demo/
├── backend/                 # Spring Boot 后端
│   ├── pom.xml              # Maven 配置（Boot 3.3.5 / Java 17 / MP / jjwt）
│   ├── Dockerfile           # 多阶段构建：maven 打包 → JRE17 运行
│   ├── uploads/             # 本地上传目录（已有真实图片，按 yyyy/MM/dd 归档）
│   ├── target/              # 已编译产物（classes，未打 jar）
│   └── src/main/
│       ├── java/com/couple/ # 见 §5 后端模块
│       └── resources/application.yml  # 端口/数据源/JWT/上传/CORS 配置
├── frontend/                # Vue 3 前端
│   ├── package.json         # Vue3/Element Plus/Pinia/Router/Axios/dayjs
│   ├── vite.config.js       # @别名 + /api、/uploads 开发代理 → 8080
│   ├── Dockerfile           # node 构建 → nginx 托管
│   ├── nginx.conf           # SPA 回退 + /api、/uploads 反代 → backend:8080
│   └── src/                 # api/stores/router/layout/views/components/utils
├── sql/
│   ├── init.sql             # 建库建表脚本（6 张表 + 4 张预留表注释）
│   └── fix_comment.sql      # 历史修复：补齐表/字段注释（已执行过的补丁脚本）
├── docs/
│   ├── api-contract.md      # ⭐ 前后端 API 契约（唯一基准）
│   ├── roadmap.md           # ⭐ 从 0 到 1 路线图（已实现至阶段 4）
│   ├── deploy.md            # ⭐ 云服务器部署指导
│   └── project-overview.md  # ⭐ 本文档（项目现状梳理）
├── docker-compose.yml       # 一键编排：mysql + backend + frontend
├── .env.example             # 环境变量模板（复制为 .env 后修改密钥）
├── .gitignore
└── README.md                # 项目入口 README
```

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
│ 图片访问: /uploads/**  → http://backend:8080               │
└──────────────────────────┬──────────────────────────────────┘
                           ▼
┌────────────── Backend (Spring Boot 容器, :8081) ────────────┐
│ Security 过滤链 → JwtAuthenticationFilter(解析 Bearer Token) │
│ Controller 层: auth / moment / anniversary / album / file   │
│ Service 层: 业务逻辑 + 事务 + 情侣码生成/校验                 │
│ Mapper 层: MyBatis-Plus 单表 CRUD / QueryWrapper / @Select  │
│ FileStorageService: 本地磁盘实现（接口抽象，可换 OSS）        │
└──────────────────────────┬──────────────────────────────────┘
                           ▼
               MySQL 8.0 (couple 库, utf8mb4)
               6 张业务表 + 4 张预留扩展表
```

### 3.2 认证与数据隔离设计

- **无状态 JWT**：登录/注册签发 HS256 Token（载荷含 `userId`、`coupleId`，默认 7 天有效）。请求经 `JwtAuthenticationFilter` 解析放入 SecurityContext，接口直接从 Principal 取用户，无需查库。
- **放行规则**：`/api/auth/**`（注册/登录）与 `/uploads/**`（图片）公开，其余接口一律要求认证；未带/失效 Token 返回统一 401 JSON，越权返回 403 JSON。
- **数据隔离**：所有业务表带 `couple_id`；查询前先从 Principal 取当前用户的 `couple_id` 再过滤，天然实现两人数据隔离，无需复杂权限模型。
- **情侣码安全**：6 位大写字母+数字（剔除 O/0/I/1），绑定成功后双方 `couple_code` 置空（用后即废，防重放）。

---

## 4. 数据模型（sql/init.sql）

| 表 | 作用 | 关键字段 / 约束 |
|----|------|------------------|
| `user` | 双人账号 | `username`(唯一)、`password`(BCrypt)、`couple_code`(可空)、`couple_id`(可空) |
| `couple` | 情侣关系 | `user_a_id`(发起方)、`user_b_id`(被绑定方)、`start_date`；双方均唯一约束 |
| `moment` | 动态时间轴 | `couple_id`、`user_id`、`content`、`images`(JSON 数组)、`location`；索引 `(couple_id, created_at DESC)` |
| `album` | 共享相册 | `couple_id`、`name`、`description`、`cover_url`(最新照片) |
| `photo` | 照片 | `album_id`、`user_id`、`url`、`thumbnail_url`、`taken_at` |
| `anniversary` | 纪念日 | `couple_id`、`name`、`anniversary_date`、`is_start`(1=在一起那天，自动创建不可删)、`remind_days`(预留) |

**预留扩展表**（init.sql 末尾以注释存在，启用时取消注释即可）：`task` / `task_record`（情侣任务打卡）、`point_record`（积分商城）、`chat_message`（实时聊天）。

**设计要点**：
- `moment.images` 用 JSON 列存图片 URL 数组，前端九宫格直接消费；
- "在一起 X 天"口径 = `couple.start_date` 到今天的 `ChronoUnit.DAYS` 差值；
- 相册封面 = 相册内最新一张照片；删除封面照片后自动回退。

---

## 5. 后端模块（backend/src/main/java/com/couple/）

| 包 | 内容 | 说明 |
|----|------|------|
| `CoupleApplication` | 启动类 | — |
| `config/` | `SecurityConfig`、`JwtAuthenticationFilter`、`CoupleUserPrincipal`、`WebConfig`(静态资源映射 /uploads)、`MybatisPlusConfig` | 安全过滤链、CORS、JWT 过滤器、MP 分页 |
| `common/` | `Result<T>`、`BusinessException`、`GlobalExceptionHandler`、`PageResult` | 统一响应体 + 全局异常兜底（400/401/403/500） |
| `auth/` | `AuthController/Service(Impl)`、`JwtUtil` + 9 个 DTO | 注册/登录/绑定/me/头像；情侣码生成（SecureRandom，20 次去重尝试） |
| `user/` | `User` entity + mapper | 用户表 |
| `couple/` | `Couple` entity + mapper | 关系表 |
| `moment/` | `MomentController/Service(Impl)` + DTO + entity + mapper | 发布/分页时间轴(anchorDate 快速定位)/删除/日期&月份列表 |
| `anniversary/` | `AnniversaryController/Service(Impl)` + DTO + entity + mapper | summary(在一起天数+下一个纪念日+未来 180 天列表)/CRUD，is_start=1 不可删 |
| `album/` | `Album/Photo` Controller/Service + DTO + entity + mapper | 相册 CRUD、照片上传绑定(JSON urls)、删除(仅本人)、级联删相册 |
| `file/` | `FileController`、`FileStorageService`(接口) + `LocalFileStorageServiceImpl` | 单文件上传（image/*，≤10MB），按 yyyy/MM/dd 存盘 |

**分层规范**：一律 `Controller → Service(接口+Impl) → Mapper`；单表 CRUD 用 MyBatis-Plus 零 XML，复杂查询用 `LambdaQueryWrapper` / `@Select`；业务错误抛 `BusinessException(code, msg)`，由全局异常处理器统一转为 JSON。

**可扩展点**：`FileStorageService` 接口抽象，换阿里云 OSS 只需新增实现类并改注入。

---

## 6. 前端模块（frontend/src/）

| 目录/文件 | 内容 | 说明 |
|-----------|------|------|
| `api/` | `auth.js` `moment.js` `anniversary.js` `album.js` `file.js` | 5 个模块的接口封装（axios） |
| `utils/request.js` | axios 实例 | 自动注入 `Authorization: Bearer <token>`；401 时清 token 跳登录；统一错误提示 |
| `stores/user.js` | Pinia store | token / userInfo / partner / couple 状态，`fetchMe()` 拉取当前用户 |
| `router/index.js` | 路由 + 全局前置守卫 | 需登录页无 token → 跳 /login 带 redirect；已绑定用户访问 login/register/bind → 回首页 |
| `layout/Layout.vue` | 侧边栏布局 | 主导航外壳（首页时间轴/纪念日/相册） |
| `views/` | `Home`(时间轴, 22KB 最大) `Login` `Register` `Bind` `Anniversary` `Album` `AlbumDetail` | 7 个页面 |
| `components/` | `MomentCard.vue` `EmptyState.vue` | 动态卡片、空态占位 |
| `styles/index.css` | 全局样式 | — |

**首页（时间轴）数据流**：进入 Home → 并行拉取 `auth/me`（用户/伴侣/在一起天数）+ `anniversaries/summary`（倒计时）+ `moments`（分页时间轴）；发布动态先经 `/api/files/upload` 逐张传图拿 URL，再 POST 到 `/api/moments`；滚动触发 `current+1` 追加渲染。

**路由清单**：`/login` `/register` `/bind`(需登录) `/`(首页时间轴) `/anniversaries` `/albums` `/albums/:id`，未知路径兜底回首页。

---

## 7. API 一览（详见 docs/api-contract.md）

统一响应体：`{ code, message, data }`；鉴权头：`Authorization: Bearer <token>`；分页参数：`current` / `size`。

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
| moments | GET | `/api/moments/dates` | 需登录 + 已绑定 |
| moments | GET | `/api/moments/months` | 需登录 + 已绑定 |
| anniversaries | GET | `/api/anniversaries/summary` | 需登录 + 已绑定 |
| anniversaries | GET/POST | `/api/anniversaries` | 需登录 + 已绑定 |
| anniversaries | PUT/DELETE | `/api/anniversaries/{id}` | 需登录 + 已绑定（is_start=1 不可删） |
| albums | GET/POST | `/api/albums` | 需登录 + 已绑定 |
| albums | GET | `/api/albums/{id}` | 需登录 + 已绑定（仅本情侣） |
| albums | POST | `/api/albums/{id}/photos` | 需登录 + 已绑定（JSON urls） |
| albums | DELETE | `/api/albums/{id}/photos/{photoId}` | 仅本人（403） |
| albums | DELETE | `/api/albums/{id}` | 需登录 + 已绑定（级联删照片） |
| files | POST | `/api/files/upload` | 需登录（multipart，≤10MB） |

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
```

### 8.3 图片上传链路

```
前端 el-upload ──► POST /api/files/upload(multipart) ──► 校验类型/大小(≤10MB)
  ──► 按 yyyy/MM/dd 存盘（本地 uploads/ 或 Docker /data/uploads 卷）
  ──► 返回 { url } ──► 前端把 url 写入动态 images / 相册 photos / 头像
浏览器访问 /uploads/** ──► WebConfig 静态映射（本地）/ Nginx 反代（Docker）
```

---

## 9. 运行与部署

### 9.1 本地开发

```bash
# 前置：JDK 17+、MySQL 8（先执行 sql/init.sql）、Node 18+
cd backend  && mvn spring-boot:run   # 后端（见 §10 端口注意事项）
cd frontend && npm install && npm run dev  # 前端 http://localhost:5173，/api、/uploads 已代理
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

HTTPS / 备份 / 迁移 / 常见故障见 `docs/deploy.md`。

---

## 10. 项目现状与注意事项

### 10.1 完成状态（基于产物核查）

| 项 | 状态 |
|----|------|
| 后端代码 | ✅ 已编写，`target/classes` 存在（编译过），未产出可部署 jar |
| 前端代码 | ✅ 已编写，`node_modules` 存在，**无 `dist`**（未执行过生产构建） |
| 数据库 | ✅ `sql/init.sql` 建表脚本就绪；`fix_comment.sql` 为表注释修复补丁（历史已用） |
| 联调痕迹 | ✅ `backend/uploads/2026/08` 存在真实上传图片，说明本地联调跑通过 |
| 版本管理 | ⚠️ 项目根目录**不是 git 仓库**（未初始化） |
| 文档 | ✅ README / API 契约 / 路线图 / 部署 / 本文档齐全 |

### 10.2 ⚠️ 发现的配置不一致（建议统一）

| 位置 | 端口配置 |
|------|----------|
| `backend/src/main/resources/application.yml` | `server.port: 8081` |
| `frontend/vite.config.js`（开发代理） | 代理到 `http://localhost:8080` |
| `frontend/nginx.conf`（生产反代） | `proxy_pass http://backend:8080` |
| `docker-compose.yml` | 映射 `"8080:8080"` |
| `backend/Dockerfile` | `EXPOSE 8080` |
| README / api-contract.md | 约定后端为 `8080` |

**影响**：本地 `mvn spring-boot:run` 起在 8081，Vite 代理指向 8080 会连不上；Docker 部署时后端容器实际监听 8081，而 Nginx 反代与端口映射均指向 8080，会导致生产环境 API 不通。
**建议**：以 8080 为唯一标准 —— 将 `application.yml` 改为 `server.port: 8080`（或启动时 `--server.port=8080`）；确认本地联调是否依赖 IDE 覆盖端口。

### 10.3 其他备注

- `out/production/demo` 为 IntelliJ IDEA 编译输出，可忽略/加入 gitignore；
- 上传目录 `backend/uploads/` 与 Docker 数据卷 `./data/uploads` 是两份独立数据，迁移/备份时注意区分；
- 扩展功能（任务打卡/积分/聊天/OSS/提醒）的设计思路与预留点见 `docs/roadmap.md` 阶段 6。

---

## 11. 快速上手路径（建议阅读顺序）

1. `README.md` — 项目定位与快速开始
2. `docs/api-contract.md` — 前后端接口唯一基准（联调必看）
3. `docs/roadmap.md` — 设计决策与扩展规划
4. `docs/project-overview.md` — 本文档，现状全貌
5. `docs/deploy.md` — 部署上线（需要时）
