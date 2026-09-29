# API 契约文档（前后端唯一基准）

> 后端与前端必须严格遵循本契约。修改任何接口需同步更新本文件。
> 覆盖范围：`/api/auth`、`/api/moments`、`/api/anniversaries`、`/api/albums`、`/api/files`、`/api/notifications`、`/api/todos`、`/api/chat`、`/ws/chat`、`/api/agent`。
> Java ↔ Python（AI Agent）的服务契约另见 `docs/agent-api.md`。

## 0. 通用约定

- **Base URL**：后端 `http://localhost:8080`（开发），生产经 Nginx 同源反代 `/api`。
- **统一响应体**（HTTP 状态码与 body.code 一致）：

```json
{ "code": 0, "message": "success", "data": { } }
```

| code | 含义 |
|------|------|
| 0    | 成功 |
| 400  | 参数校验失败 / 业务参数错误 |
| 401  | 未登录 / Token 失效 |
| 403  | 无权限（含未绑定情侣） |
| 404  | 资源不存在，或该资源不属于当前用户（越权统一按 404 处理，不泄露存在性） |
| 500  | 服务器内部错误 |

> 业务异常抛 `BusinessException(code, msg)`，`code` 直接作为 HTTP 状态码返回。

- **认证方式**：请求头 `Authorization: Bearer <token>`。JWT（HS256，默认有效期 7 天）。
- **公开接口**：仅 `/api/auth/**`（注册/登录）与 `/uploads/**`、`/ws/**`（握手自行鉴权）。其余接口一律需登录。
- **"需登录、已绑定"的含义**：需携带有效 Token **且** 当前用户已有 `couple_id`；未绑定时返回
  `403 {"code":403,"message":"未绑定情侣关系，无法聊天","data":null}`（聊天）或
  `403 {"code":403,"message":"请先绑定情侣","data":null}`（相册等，文案以实现为准）。
  标记为"仅需登录"的模块（待办、通知、AI 助手）**不要求已绑定**。
- **分页有两种风格，注意区分**：

  1. **页码分页**（MyBatis-Plus IPage 序列化），参数 `current`（默认 1）、`size`（默认 10）：

  ```json
  { "records": [], "total": 0, "size": 10, "current": 1, "pages": 0 }
  ```

  2. **游标分页（裸数组）**：仅聊天消息使用，游标为自增主键 `id`，参数 `beforeId` / `afterId`，不返回 `total`。

- **时间格式**：`LocalDateTime` → `yyyy-MM-dd HH:mm:ss`；`LocalDate` → `yyyy-MM-dd`。
- **错误响应示例**：`{ "code": 401, "message": "未登录或Token已过期", "data": null }`

## 1. 认证模块 `/api/auth`

### 1.1 注册 `POST /api/auth/register`（公开）

请求：

```json
{ "username": "lover_a", "password": "123456", "nickname": "小鹿" }
```

响应 `data`（注册成功即返回 token + 情侣码，提示用户保存并转发给伴侣）：

```json
{
  "token": "eyJhbGciOi...",
  "coupleCode": "AB12CD",
  "user": { "id": 1, "username": "lover_a", "nickname": "小鹿", "avatar": null, "coupleId": null, "bound": false }
}
```

校验：username 3-20 位字母数字下划线；password 6-32 位；nickname 1-20 位。

### 1.2 登录 `POST /api/auth/login`（公开）

请求：`{ "username": "lover_a", "password": "123456" }`

响应 `data`：

```json
{
  "token": "eyJhbGciOi...",
  "user": { "id": 1, "username": "lover_a", "nickname": "小鹿", "avatar": null, "coupleId": 1, "bound": true }
}
```

### 1.3 绑定情侣 `POST /api/auth/bind`（需登录，且当前用户未绑定）

请求：

```json
{ "coupleCode": "AB12CD", "startDate": "2024-01-01" }
```

- `coupleCode`：伴侣注册时得到的码，必填。
- `startDate`：在一起的那天，选填，默认当天。

逻辑：校验码有效 → 创建 `couple` 行（user_a=码主人，user_b=当前用户）→ 双方 `couple_id` 更新、双方 `couple_code` 置空 → 自动创建 `anniversary`（name="在一起的那天"，is_start=1，date=startDate）。

响应 `data`：

```json
{ "coupleId": 1, "startDate": "2024-01-01", "partner": { "id": 1, "nickname": "小鹿", "avatar": null } }
```

错误：400 `该情侣码已被使用` / `情侣码不存在` / `当前账号已绑定` / `不能绑定自己的情侣码`。

### 1.4 当前用户信息 `GET /api/auth/me`（需登录）

响应 `data`：

```json
{
  "user": { "id": 2, "username": "lover_b", "nickname": "小熊", "avatar": null, "coupleId": 1, "bound": true },
  "partner": { "id": 1, "nickname": "小鹿", "avatar": null } | null,
  "couple": { "id": 1, "startDate": "2024-01-01", "daysTogether": 123 } | null
}
```

### 1.5 更新头像 `PUT /api/auth/avatar`（需登录）

请求（头像先经 `/api/files/upload` 上传拿到 URL）：

```json
{ "avatarUrl": "/uploads/2025/06/01/abc123.jpg" }
```

响应 `data`：UserVO（同 1.1）。

## 2. 动态模块 `/api/moments`

### 2.1 发布动态 `POST /api/moments`（需登录、已绑定）

请求：

```json
{ "content": "今天一起看了日落 🌇", "images": ["/uploads/xxx.jpg"], "location": "杭州西湖" }
```

- content 必填，≤ 2000 字；images 为上传接口返回的 URL 数组，可空。

响应 `data`（MomentVO）：

```json
{
  "id": 12, "userId": 1, "nickname": "小鹿", "avatar": null,
  "content": "今天一起看了日落 🌇", "images": ["/uploads/xxx.jpg"], "location": "杭州西湖",
  "createdAt": "2025-06-01 18:30:00"
}
```

> 发布/删除动态后，后端会在事务提交后异步通知 AI Agent 更新向量库（`agent.enabled=false` 时静默跳过），见 `docs/agent-api.md`。

### 2.2 时间轴分页 `GET /api/moments?current=1&size=10`（需登录、已绑定）

按 `created_at` 倒序，仅返回本情侣关系下的动态。

可选参数 `anchorDate`（yyyy-MM-dd）：非空时只返回**该日期当天及更早**的动态（`created_at <= 当天 23:59:59`），用于时间轴"跳到某一天"的快速定位。

响应 `data`：

```json
{
  "records": [ { "id": 12, "userId": 1, "nickname": "小鹿", "avatar": null, "content": "...", "images": [], "location": null, "createdAt": "2025-06-01 18:30:00" } ],
  "total": 23, "size": 10, "current": 1, "pages": 3
}
```

### 2.3 删除动态 `DELETE /api/moments/{id}`（需登录）

只能删除自己发布的；否则 403。

### 2.4 动态日期列表 `GET /api/moments/dates`（需登录、已绑定）

返回本情侣所有发布动态的日期（yyyy-MM-dd，倒序），供时间轴右侧日期快速跳转栏使用。

响应 `data`：

```json
[ "2025-06-01", "2025-05-20", "2025-05-01" ]
```

### 2.5 动态月份列表 `GET /api/moments/months`（需登录、已绑定）

返回本情侣所有发布动态的月份（yyyy-MM，倒序），供月份级快速定位使用。

响应 `data`：

```json
[ "2026-08", "2026-06", "2025-10" ]
```

## 3. 纪念日模块 `/api/anniversaries`

### 3.1 首页汇总 `GET /api/anniversaries/summary`（需登录、已绑定）

响应 `data`：

```json
{
  "daysTogether": 123,
  "nextAnniversary": { "id": 2, "name": "第一次旅行", "date": "2025-10-01", "daysLeft": 45 },
  "upcoming": [ { "id": 3, "name": "认识1000天", "date": "2025-12-31", "daysLeft": 136 } ]
}
```

- `daysTogether`：今天 − couple.start_date 的天数。
- `nextAnniversary`：最近一个未来到达的纪念日（不含"在一起的那天"）；没有则 null。
- `upcoming`：未来 180 天内到达的纪念日列表（不含 start）。

### 3.2 纪念日列表 `GET /api/anniversaries`（需登录、已绑定）

响应 `data`：

```json
[ { "id": 2, "name": "第一次旅行", "date": "2025-10-01", "isStart": false, "remindDays": 7, "createdAt": "2025-01-01 10:00:00" } ]
```

### 3.3 新增纪念日 `POST /api/anniversaries`

请求：`{ "name": "第一次旅行", "date": "2025-10-01", "remindDays": 7 }`

响应 `data`：完整 anniversary 对象（同 3.2 单条）。

### 3.4 修改纪念日 `PUT /api/anniversaries/{id}`

请求：`{ "name": "第一次旅行", "date": "2025-10-02", "remindDays": 7 }`

### 3.5 删除纪念日 `DELETE /api/anniversaries/{id}`

`is_start=1` 的"在一起的那天"不可删除（403）。

### 3.6 周期与提醒口径（重要）

- **纪念日按"月-日"每年重复**：计算"下一次到达"时把纪念日的月-日套到今年，今年已过则套到明年；`2/29` 在非闰年退化为 `2/28`。
- `remindDays`：提前提醒天数；为 `0` 或 `null` 表示**当天**提醒。
- **提醒任务**：定时任务每天 **08:00** 执行（`AnniversaryReminderTask`，cron `0 0 8 * * ?`），遍历所有纪念日（含"在一起的那天"），仅当"距下次到达的天数 == remindDays"时才发通知，并写入 `reminder_log`（`UNIQUE(anniversary_id, remind_date)`）防重复。
- 开发环境可手动触发 `POST /api/test/remind/trigger`（仅 `dev` profile 生效）。

## 4. 相册模块 `/api/albums`

> 未绑定情侣时统一 `403 请先绑定情侣`；相册不存在**或不属于本情侣**统一 `404 相册不存在`。

### 4.1 相册列表 `GET /api/albums`（需登录、已绑定）

按 `created_at` 倒序。响应 `data`：

```json
[ { "id": 1, "name": "2024旅行", "description": "大理和丽江", "coverUrl": "/uploads/cover.jpg", "photoCount": 42, "createdAt": "2024-05-01 10:00:00" } ]
```

- `photoCount`：实时统计的照片数（含视频）。
- `coverUrl`：**手动封面优先**；未手动设置时取最新一张**图片**（跳过视频），无图片则 null。

### 4.2 创建相册 `POST /api/albums`

请求：`{ "name": "2024旅行", "description": "大理和丽江" }`（name 必填 ≤100；description 可空 ≤255）

响应 `data`：相册对象（同 4.1 单条，`photoCount` 为 0）。

### 4.3 相册详情 `GET /api/albums/{id}`（需登录、已绑定，仅本情侣）

照片按 `created_at` 倒序。响应 `data`：

```json
{
  "id": 1, "name": "2024旅行", "description": "大理和丽江", "coverUrl": "/uploads/cover.jpg",
  "createdAt": "2024-05-01 10:00:00",
  "photos": [
    {
      "id": 88, "userId": 1, "url": "/uploads/p1.jpg", "thumbnailUrl": null,
      "type": "IMAGE", "description": "洱海边", "uploaderNickname": "小鹿",
      "createdAt": "2024-06-01 10:00:00"
    },
    {
      "id": 89, "userId": 2, "url": "/uploads/v1.mp4", "thumbnailUrl": null,
      "type": "VIDEO", "description": "海鸥", "uploaderNickname": "小熊",
      "createdAt": "2024-06-01 10:05:00"
    }
  ]
}
```

> 详情**不含** `photoCount`。`thumbnailUrl` 与 `takenAt` 目前无代码写入，恒为 `null` / 不暴露。

### 4.4 上传照片/视频 `POST /api/albums/{id}/photos`（JSON）

请求体（文件先经 `/api/files/upload` 逐张上传拿 URL，再统一绑定到相册）：

```json
{ "urls": ["/uploads/a.jpg", "/uploads/v.mp4"], "types": ["IMAGE", "VIDEO"], "description": "洱海边" }
```

- `urls` 必填非空；`description` 可选，对该批**所有**文件生效。
- `types` 可选，与 `urls` **同序**；仅识别 `VIDEO`（忽略大小写），其余/缺省一律按 `IMAGE`。`types` 比 `urls` 短时后续按 `IMAGE`，长于 `urls` 的多余项被忽略。

响应 `data`：上传成功的照片对象数组（同 4.3 photos 单条结构）。

### 4.5 删除照片 `DELETE /api/albums/{id}/photos/{photoId}`

- 只能删除**自己上传**的，否则 403 `只能删除自己上传的照片`；照片不存在或不属于该相册 → 404 `照片不存在`。
- 同时尽力删除磁盘文件；若删掉的正是当前封面，自动回退为最新一张图片并**恢复自动封面模式**（`cover_manual=0`）。
- 通知 AI Agent 删除该照片向量。

### 4.6 删除相册 `DELETE /api/albums/{id}`

级联删除相册下所有照片，并逐张删除磁盘文件与向量数据。

### 4.7 更新相册 `PUT /api/albums/{id}`

请求体同 4.2（`{ "name": "...", "description": "..." }`，`name` 必填）。响应 `data`：更新后的相册对象（同 4.1 单条）。

> 更新后会对该相册下每张照片重新同步向量（文本 = 新相册名 + 照片描述）。

### 4.8 更新照片描述 `PUT /api/albums/{id}/photos/{photoId}`

请求：`{ "description": "洱海边" }`（可空，传空即清空）

响应 `data`：照片对象（同 4.3 单条）。注意此接口返回的 `uploaderNickname` 为 `null`。

权限：**不校验上传者** —— 伴侣双方都可以修改对方上传照片的描述（与 4.5 的删除规则不同）。

### 4.9 设置封面 `PUT /api/albums/{id}/cover`

请求：`{ "coverUrl": "/uploads/2025/06/01/xxx.jpg" }`（必填）

响应 `data`：`null`。

规则：
- 设置后 `cover_manual=1`，**后续上传新图片不会覆盖该封面**；
- 后端**不校验** `coverUrl` 是否属于本相册 / 是否为 `/uploads/` 前缀 / 是否为图片，请前端自行保证（前端"从相册选择"只列出非视频照片）；
- 删除该封面照片时自动回退为最新图片并恢复自动模式。

## 5. 文件上传 `/api/files`

### 5.1 上传文件 `POST /api/files/upload`（multipart/form-data，需登录）

字段：`file`（单个文件）。

- 允许类型：`contentType` 以 `image/` 或 `video/` 开头，否则 400 `仅支持上传图片或视频文件`。
- 大小上限：**200MB**（超出 400 `文件大小不能超过200MB`）；后端 multipart 配置为单文件 200MB / 单请求 250MB。
- 扩展名映射：`image/jpeg→.jpg`、`image/png→.png`、`image/gif→.gif`、`image/webp→.webp`、`image/bmp→.bmp`、`video/mp4→.mp4`、`video/webm→.webm`、`video/quicktime→.mov`；未命中则取原始文件名后缀，再兜底 `.jpg`。

响应 `data`：

```json
{ "url": "/uploads/2025/06/01/abc123.jpg", "filename": "abc123.jpg", "size": 102400 }
```

- 文件按 **yyyy/MM/dd** 目录存储（`/uploads/2025/06/01/xxx.jpg`），保存到后端 `upload.dir`（默认 `./uploads`，Docker 中为 `/data/uploads` 卷）。
- 后端将 `/uploads/**` 映射为静态资源，供浏览器直接访问。
- 前端在 element-plus `el-upload` 中使用 `action: '/api/files/upload'` 并带 Authorization 头，拿到 url 后存入业务字段。
- **前端自行校验**：头像/图片 ≤10MB、相册视频 ≤200MB，与后端上限一致。
- **生产 Nginx 必须放开上传体积**：`frontend/nginx.conf` 的 `client_max_body_size` 需 ≥ 250m，否则大视频会被 Nginx 以 413 拒绝。

## 6. 通知 `/api/notifications`

> **按 `user_id` 隔离**，仅需登录，**不要求已绑定情侣**。当前唯一的通知来源是纪念日提醒，`type` 只有 `ANNIVERSARY_REMIND`。

NotificationVO：

```json
{
  "id": 5, "type": "ANNIVERSARY_REMIND", "title": "纪念日提醒",
  "content": "距离「第一次旅行」还有 3 天", "isRead": false, "createdAt": "2025-06-01 08:00:00"
}
```

> VO 不暴露 `userId` / `coupleId` / `relatedId`，因此前端无法从通知跳转到关联纪念日。

### 6.1 通知分页 `GET /api/notifications?current=1&size=10`（需登录）

按 `created_at` 倒序，仅返回当前用户的通知。响应 `data`：标准页码分页结构（见 §0）。

```json
{
  "records": [ { "id": 5, "type": "ANNIVERSARY_REMIND", "title": "纪念日提醒", "content": "距离「第一次旅行」还有 3 天", "isRead": false, "createdAt": "2025-06-01 08:00:00" } ],
  "total": 12, "size": 10, "current": 1, "pages": 2
}
```

### 6.2 未读数 `GET /api/notifications/unread-count`（需登录）

响应 `data`：**裸数字**（不是对象），未读条数。

```json
3
```

### 6.3 标记单条已读 `PUT /api/notifications/{id}/read`（需登录）

无请求体。响应 `data`：`null`。

错误：404 `通知不存在`（`id` 不存在或不属于当前用户）。

### 6.4 全部已读 `PUT /api/notifications/read-all`（需登录）

无请求体。响应 `data`：`null`（幂等，重复调用仍成功）。

注意这是**单段路径** `/read-all`，与 `/api/notifications/{id}/read` 不冲突。

### 6.5 通知的产生

- 唯一写入方：纪念日提醒（`type=ANNIVERSARY_REMIND`，`title="纪念日提醒"`，`content="距离「X」还有 N 天"`，`relatedId=纪念日 id`）。
- 按 `couple_id` 查出该情侣下全部用户，**每人一条**；触发时机见 §3.6。

## 7. 每日待办 `/api/todos`

> **纯个人数据，按 `user_id` 硬隔离，伴侣不可见**；仅需登录，**不要求已绑定情侣**。
> `user_id` 一律取自 JWT，前端**禁止**传（DTO 中无该字段，传了也会被忽略）。

TodoVO：

```json
{
  "id": 7, "title": "买牛奶", "done": false, "priority": false,
  "dueDate": "2025-06-01", "overdue": false,
  "completedAt": null, "createdAt": "2025-06-01 08:00:00"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| id | number | 待办 ID |
| title | string | 待办内容，入库前 `trim()` |
| done | boolean | 是否完成 |
| priority | boolean | `false`=普通，`true`=重要 |
| dueDate | string\|null | 归属日期 `yyyy-MM-dd`；**`null` = 未排期** |
| overdue | boolean | 服务端按"今天"实时计算：`dueDate != null && dueDate < 今天`。**与所在分组无关**，`completed` 组内也可能为 `true` |
| completedAt | string\|null | 完成时间；未完成或被改回未完成时为 `null` |
| createdAt | string | 创建时间 |

> VO **不暴露** `userId` / `updatedAt`。

### 7.1 今日视图 `GET /api/todos/today-view`（需登录）

无参数、无请求体。响应 `data`：**四分组聚合**，一次拉取即可渲染。

```json
{
  "overdue":  [ { "id": 3, "title": "交房租", "done": false, "priority": true,  "dueDate": "2025-05-28", "overdue": true,  "completedAt": null, "createdAt": "2025-05-20 09:12:00" } ],
  "today":    [ { "id": 7, "title": "买牛奶", "done": false, "priority": false, "dueDate": "2025-06-01", "overdue": false, "completedAt": null, "createdAt": "2025-06-01 08:00:00" } ],
  "upcoming": [ { "id": 9, "title": "订机票", "done": false, "priority": false, "dueDate": null,         "overdue": false, "completedAt": null, "createdAt": "2025-06-01 08:05:00" } ],
  "completed":[ { "id": 4, "title": "取快递", "done": true,  "priority": false, "dueDate": "2025-06-01", "overdue": false, "completedAt": "2025-06-01 20:30:00", "createdAt": "2025-05-31 22:00:00" } ]
}
```

- 四个字段均为数组，**永不为 `null`**，无数据时为 `[]`。
- 分组条件是**互斥**的（同一条只会出现在一组）：

| 分组 | 命中条件 |
|------|----------|
| `overdue` | `done=false` 且 `dueDate < 今天` |
| `today` | `done=false` 且 `dueDate = 今天` |
| `upcoming` | `done=false` 且（`dueDate > 今天` 或 `dueDate = null`） |
| `completed` | `done=true` 且 `completedAt` 在**今天**范围内 |

- `completed` 只包含**今天完成**的待办；历史完成记录**不会**返回（当前也没有"全部待办/按日期查询"接口）。
- 排序：前 3 组共用 `ORDER BY priority DESC, created_at ASC`（重要置顶，同优先级先创建的在前）；`completed` 按 `completed_at DESC`。
- 无分页参数。

### 7.2 新增待办 `POST /api/todos`（需登录）

请求（新增/更新共用同一 DTO）：

```json
{ "title": "买牛奶", "priority": false, "dueDate": "2025-06-01", "done": false }
```

| 字段 | 校验 / 默认 |
|------|-------------|
| title | **必填**，`@NotBlank("待办内容不能为空")`，`@Size(max=200,"待办内容不能超过 200 字")` |
| priority | 选填，缺省/`null` 视为 `false` |
| dueDate | 选填，`null` = 未排期 |
| done | 选填，缺省/`null` 视为 `false`；传 `true` 时服务端同时写入 `completedAt=当前时间` |

响应 `data`：单条 TodoVO。

### 7.3 更新待办 `PUT /api/todos/{id}`（需登录）

> **全量覆盖语义**：四个字段全部按请求体重建，前端必须回传全部字段。未传的字段会被当作 `false` / `null` 落库（`priority` 变 `false`、`dueDate` 排期被清空、`done` 变 `false`）。

请求体同 7.2（`title` 仍必填）。`done` 状态机：

| 变化 | `completedAt` |
|------|---------------|
| `false → true` | 写入当前时间 |
| `true → true` | 保留原完成时间，不刷新 |
| `true → false` | 置 `null` |
| `false → false` | 保持 `null` |

响应 `data`：更新后的单条 TodoVO。

错误：404 `待办不存在`（`id` 不存在**或不属于当前用户**）。

### 7.4 删除待办 `DELETE /api/todos/{id}`（需登录）

无请求体。响应 `data`：`null`。物理删除，无软删除。

错误：404 `待办不存在`（归属校验同 7.3）。

## 8. 实时聊天 `/api/chat`

> 实时收发走 WebSocket `/ws/chat`（见 §9），`/api/chat` 只承担**历史拉取 / 增量拉取 / 已读上报 / 未读数**。
> 全部接口**需登录 + 已绑定**；`coupleId`/`userId` 一律取自 JWT，不接受前端传参。
> ⚠️ 消息列表**不是** IPage 分页对象，而是**游标分页的裸数组**，游标为自增主键 `id`。

MessageVO：

```json
{
  "id": 128, "senderId": 1, "type": "TEXT", "content": "晚安",
  "clientMsgId": "b3f1...uuid", "botFlag": false, "createdAt": "2025-06-01 22:13:04"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| id | number | 自增消息 id，全链路排序 / 游标依据 |
| senderId | number | 发送者用户 id；**AGENT 回复记录的是触发者 id** |
| type | string | `TEXT` / `IMAGE` / `AGENT` |
| content | string | 文本内容或图片 URL |
| clientMsgId | string\|null | 客户端幂等键；AGENT 消息为服务端生成的 UUID |
| botFlag | boolean | 是否 @bot 相关 |
| createdAt | string | `yyyy-MM-dd HH:mm:ss` |

### 8.1 历史消息 `GET /api/chat/messages?beforeId=128&size=30`（需登录、已绑定）

- `beforeId` 可选：不传取**最新** `size` 条；传则取 `id < beforeId` 的 `size` 条。
- `size` 可选，默认 20，上限 100（`size<=0` 时回落为 20）。

响应 `data`：MessageVO 数组，**按 id 倒序**。前端首屏 `size=30`，滚动到顶部再用本地最小 id 作为 `beforeId` 继续拉，拿到后自行 `reverse()`。

### 8.2 增量消息 `GET /api/chat/messages/after?afterId=128&limit=200`（需登录、已绑定）

- `afterId` **必填**；`limit` 可选，默认 200，上限 200。语义为 `id > afterId`，用于 **WS 断线重连后补齐**。
- 超过上限需用返回末条 id 再拉一次（前端目前只拉一次）。

响应 `data`：MessageVO 数组，**按 id 正序**。

### 8.3 上报已读位点 `PATCH /api/chat/read`（需登录、已绑定）

请求：`{ "lastReadMessageId": 128 }`（`@NotNull("lastReadMessageId 不能为空")`）

响应 `data`：`null`。

- 把**当前登录用户**的已读水位推进到该 id；SQL 用 `GREATEST(...)`，**只增不减**，乱序/旧值上报不会导致未读回退。
- **不校验**该 id 是否存在、是否属于本情侣。
- 前端取本地最大真实 id，600ms 节流上报。

### 8.4 未读数 `GET /api/chat/unread-count`（需登录、已绑定）

响应 `data`：

```json
{ "unread": 3 }
```

- 定义：**对方发给我的、`id > 我的已读位点`** 的消息条数；自己发的恒不计入；无已读记录按 0 处理。
- ⚠️ AGENT 回复的 `senderId` 记的是**触发者**，因此"我 @bot 产生的 AI 回复"会被算进**对方**的未读数。

### 8.5 消息类型归一化（服务端权威）

- `IMAGE` / `AGENT`（忽略大小写）→ 转大写原样存；**其它任何值（含 `TEXT`、乱值）→ `TEXT`**。
- WS 上行若伪造 `type:"AGENT"` 会被原样落库，业务上应只发 `TEXT`/`IMAGE`（服务端无白名单校验）。
- 落库时 `content` 会 `trim()`；空内容静默丢弃。

## 9. WebSocket 聊天通道

### 9.1 握手与鉴权

- 路径：`/ws/chat`，完整 URL：`ws://<host>/ws/chat?token=<JWT>`（https 页面为 `wss://`）。
- `token` 为**原始 JWT**（仅 `encodeURIComponent`，**不带 `Bearer ` 前缀**），**只走 query**，因为浏览器原生 WebSocket 无法自定义 header。
- 校验通过后把 `userId` / `coupleId` 写入 session attributes（`coupleId` 取库中最新值，不信 JWT 里的旧值）。**不设置 Principal**。
- 拒绝条件（握手返回 false，连接不升级）：无 token / 解析异常 / 用户不存在 / **未绑定情侣**。
- Security 层放行 `/ws/**`，握手鉴权完全由拦截器负责，因此握手失败**不会**返回 Security 的 401 JSON。
- 生产 Nginx 必须为 `/ws/` 转发 `Upgrade` / `Connection` 头并放宽读超时（见 `frontend/nginx.conf`）。

### 9.2 上行帧（客户端 → 服务端）

**只有一种帧**——消息帧：

```json
{ "type": "TEXT", "content": "晚安", "clientMsgId": "9f0c1e2a-...-uuid" }
```

- 仅处理**文本帧**，二进制帧被忽略；服务端**不回 ack**，确认即 9.3 的广播帧。
- **没有** ping/pong、read/typing/online 帧；已读只能走 REST `PATCH /api/chat/read`。保活只能靠任意文本帧（见 9.5）。

### 9.3 下行帧（服务端 → 客户端）

客户端按 `type` 字段区分两类帧。

**(1) 消息广播帧** = MessageVO 原样序列化（**没有**外层包装）：

```json
{ "id": 128, "senderId": 1, "type": "TEXT", "content": "晚安", "clientMsgId": "9f0c1e2a-...", "botFlag": false, "createdAt": "2025-06-01 22:13:04" }
```

- 广播给本情侣的**全部在线连接，含发送者自己**；前端因此不区分收发，统一入口渲染。
- 只有落库成功的消息才广播（撞幂等唯一键时静默丢弃、不广播、不触发 @bot）。
- AGENT 回复**不会**以 MessageVO 形式广播，只落库 + 走下面的 `AGENT_STREAM` 帧。

**(2) AGENT 流式帧** `type` 固定为 `"AGENT_STREAM"`（null 字段省略）：

```json
{ "type": "AGENT_STREAM", "clientMsgId": "<服务端UUID>", "delta": "我们上次", "finalFrame": false }
{ "type": "AGENT_STREAM", "clientMsgId": "<服务端UUID>", "finalFrame": true, "messageId": 129 }
{ "type": "AGENT_STREAM", "clientMsgId": "<服务端UUID>", "finalFrame": true, "messageId": 130, "error": "AI 助手服务暂不可用，请稍后再试。" }
```

- 字段名确认为 `finalFrame`（不是 `final`）。
- 前端按 `(type==='AGENT' && clientMsgId===frame.clientMsgId)` 找到气泡并追加 `delta`；`finalFrame=true` 时用 `messageId` 回填真实 id 并结束；`error` 仅在气泡内容为空时作为内容展示。
- 帧内 `clientMsgId` 是**服务端**生成的 UUID，与触发消息的 `clientMsgId` 无关。
- 该帧同样广播给本情侣全部连接（触发者 + 伴侣），无定向投递。

### 9.4 `@bot` / AI 机器人链路（WS 内，非 SSE）

1. 客户端把 `@bot` 放在输入框开头，以**普通 TEXT 消息帧**发出。
2. 服务端判定规则：**`content.trim()` 以 `@bot` 开头**（句中不触发）。
3. 触发消息本身正常落库（`type=TEXT`、`botFlag=0`）并**先广播**双方，内容保留 `@bot` 原文。
4. 然后异步（`@Async`，专用线程池 core 2 / max 8 / queue 200）调用 Python：
   `scope="couple"`、`scopeId=coupleId`、`sessionId="couple{coupleId}"`（一对情侣共享一个会话）。
5. 回复通过 `AGENT_STREAM` 帧下发，并把**完整回复落库**为 `type=AGENT`、`botFlag=1`、`senderId=触发者` 的消息 → 历史/重连可见。
6. 降级文案（Python 不可用时）：

| 场景 | 文案 |
|------|------|
| `agent.enabled=false` | `AI 助手尚未启用，请稍后再试。` |
| 熔断中 / 拒连 | `AI 助手服务暂不可用，请稍后再试。` |
| 超时 | `AI 助手响应超时，请稍后重试。` |
| 其它失败 | `AI 助手服务繁忙，请稍后重试。` |

### 9.5 心跳 / 断线 / 重连

- **服务端**每 30s 扫描，**90s 内没收到任何上行文本帧**的连接被主动关闭（关闭码 4001 `heartbeat timeout`）。
- ⚠️ **前端当前不发心跳帧** → 静默超过 90s 会被服务端断开，再由前端重连。
- 前端重连：指数退避 `min(1000 * 2^n, 30000)`；重连成功后先重发失败队列（透传原 `clientMsgId` 靠服务端唯一键去重），再调 8.2 补齐增量。**无重连次数上限**。

### 9.6 规则汇总

- 幂等：表级 `UNIQUE KEY (couple_id, client_msg_id)`；撞键时捕获异常返回 null → 不重复入库/广播/触发 @bot。
- 空内容静默丢弃，**WS 通道内的业务失败一律不回报错误帧**。
- 发送即已读：普通消息落库后自动把**发送者**的已读位点推到该消息 id；`botFlag=1` 的消息不推进。
- 隔离域：一切以 `couple_id` 为域；`senderId` 只从 session 取，前端无法伪造。
- 排序：全链路按自增 `id`，WS 广播顺序 = 历史顺序。

## 10. AI 助手 `/api/agent/chat`

> 独立 `/ai` 页使用；**只走 SSE，不落库**（会话历史由前端存 localStorage，key = `ai-chat-{userId}`）。
> 聊天内 `@bot` 走 §9.4 的 WebSocket 通道，是另一套帧。

### 10.1 发起对话 `POST /api/agent/chat`（需登录，**不要求已绑定**）

```
POST /api/agent/chat
Header: Content-Type: application/json
        Authorization: Bearer <token>
```

```json
{ "message": "我们上次去哪里玩了？" }
```

- `message` 必填（`@NotBlank("消息不能为空")`）；校验失败返回 **HTTP 400 JSON**（不是 SSE）。
- 未登录返回 **HTTP 401 JSON**。
- `coupleId` 可为 `null` 并原样透传给 Python。

### 10.2 响应（SSE 事件流）

`Content-Type: text/event-stream`；**所有事件都无 event 名**，只有 `data:` 行。SseEmitter 超时 120s。

```
data:{"delta":"我们上次"}
data:{"delta":"去了大理。"}
data:{"refs":[{"biz_type":"moment","biz_id":9,"title":"海边的一天"}]}
data:[DONE]
```

| 事件 | 说明 |
|------|------|
| `{"delta":"..."}` | 增量文本，可多次；前端顺序拼接 |
| `{"refs":[...]}` | 引用出处，仅在非空时发一次；元素为 Python 返回原样（snake_case，前端取 `biz_type` / `title`） |
| `{"error":"..."}` | 仅失败时一条，值为中文降级文案 |
| `data:[DONE]` | 成功与降级**都必然发送**，前端据此结束 |

- 前端**不用 EventSource**，而是 `fetch` + `ReadableStream` 按 `\n` 切行、只认 `data:` 前缀；读到 `[DONE]` 触发结束，流自然结束也会兜底。
- 对客户端而言 HTTP 状态**永远是 200**（降级也走 SSE）；唯一的非 200 是未登录 401 / 参数为空 400。
- 字段名差异提醒：Java→**前端**的增量字段是 `delta`；Java→**Python** 契约里是 `content`（见 `docs/agent-api.md`）。

### 10.3 服务端会话映射

`session_id = "user{userId}"`、`scope="personal"`、`scope_id=userId`。

### 10.4 相关配置

| 配置键 | application.yml 默认 | 环境变量 |
|--------|----------------------|----------|
| `agent.enabled` | **`true`** | `AGENT_ENABLED` |
| `agent.url` | `http://localhost:8001` | `AGENT_URL` |
| `agent.key` | `change-me`（生产必改） | `AGENT_KEY` |

> 注意：代码里的 `AgentProperties` 字段默认值是 `false`，但 `application.yml` 显式写了 `${AGENT_ENABLED:true}`，因此**实际默认启用**。Docker 部署的 `.env` 默认设为 `false`（`docker-compose.yml`），因为 compose 未内置 Python 服务。

超时与熔断：连接 3s、读 30s、整体 35s；连续 5 次失败熔断 30s（窗口过后放一次试探）。

## 11. 扩展预留说明

- 情侣任务打卡 / 积分商城的接口后续按同样风格追加，表结构已在 `sql/init.sql` 末尾以注释预留。
- AI 向量同步的 `biz_type` 已预留 `note`（个人学习笔记）取值，但**当前没有任何发布点**，仅有 `moment` / `photo` 在用。
- Java ↔ Python 的完整契约（`/v1/ingest`、`/v1/chat`、`/v1/health`）见 `docs/agent-api.md`。
