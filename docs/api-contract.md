# API 契约文档（前后端唯一基准）

> 后端与前端必须严格遵循本契约。修改任何接口需同步更新本文件。

## 0. 通用约定

- **Base URL**：后端 `http://localhost:8080`（开发），生产经 Nginx 同源反代 `/api`。
- **统一响应体**（HTTP 状态码与 body.code 一致）：

```json
{ "code": 0, "message": "success", "data": { } }
```

| code | 含义 |
|------|------|
| 0    | 成功 |
| 400  | 参数校验失败 |
| 401  | 未登录 / Token 失效 |
| 403  | 无权限 |
| 500  | 服务器内部错误 |

- **认证方式**：请求头 `Authorization: Bearer <token>`。JWT（HS256，默认有效期 7 天）。
- **分页参数**：`current`（页码，默认 1）、`size`（每页条数，默认 10）。
- **分页响应**（MyBatis-Plus IPage 序列化）：

```json
{ "records": [], "total": 0, "size": 10, "current": 1, "pages": 0 }
```

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

## 4. 相册模块 `/api/albums`

### 4.1 相册列表 `GET /api/albums`（需登录、已绑定）

响应 `data`：

```json
[ { "id": 1, "name": "2024旅行", "description": "大理和丽江", "coverUrl": "/uploads/cover.jpg", "photoCount": 42, "createdAt": "2024-05-01 10:00:00" } ]
```

`coverUrl`：最新一张照片的 url，无照片则 null。

### 4.2 创建相册 `POST /api/albums`

请求：`{ "name": "2024旅行", "description": "大理和丽江" }`

响应 `data`：相册对象（同 4.1 单条）。

### 4.3 相册详情 `GET /api/albums/{id}`（需登录、已绑定，仅本情侣）

响应 `data`：

```json
{
  "id": 1, "name": "2024旅行", "description": "大理和丽江", "coverUrl": "/uploads/cover.jpg",
  "photos": [ { "id": 88, "userId": 1, "url": "/uploads/p1.jpg", "thumbnailUrl": null, "description": "洱海边", "uploaderNickname": "小鹿", "createdAt": "2024-06-01 10:00:00" } ]
}
```

### 4.4 上传照片 `POST /api/albums/{id}/photos`（JSON）

请求体（照片先经 `/api/files/upload` 逐张上传拿 URL，再统一绑定到相册）：

```json
{ "urls": ["/uploads/xxx.jpg", "/uploads/yyy.jpg"], "description": "洱海边" }
```

- urls 必填非空；description 可选。

响应 `data`：上传成功的照片对象数组（同 4.3 photos 单条结构）。

### 4.5 删除照片 `DELETE /api/albums/{id}/photos/{photoId}`

只能删除自己上传的，否则 403。删除后若该照片是封面，自动回退为最新一张。

### 4.6 删除相册 `DELETE /api/albums/{id}`

级联删除相册下所有照片。

## 5. 文件上传 `/api/files`

### 5.1 上传图片 `POST /api/files/upload`（multipart/form-data，需登录）

字段：`file`（单个文件，image/*，≤ 10MB）。

响应 `data`：

```json
{ "url": "/uploads/2025/06/01/abc123.jpg", "filename": "abc123.jpg", "size": 102400 }
```

- 文件按 **yyyy/MM/dd** 目录存储（`/uploads/2025/06/01/xxx.jpg`），保存到后端 `upload.dir`（默认 `./uploads`，Docker 中为 `/data/uploads` 卷）。
- 后端需将 `/uploads/**` 映射为静态资源，供浏览器直接访问。
- 前端在 element-plus `el-upload` 中使用 `action: '/api/files/upload'` 并带 Authorization 头，拿到 url 后存入业务字段。

## 6. 扩展预留说明

- 任务打卡 / 积分商城 / 实时聊天（WebSocket）的接口后续按同样风格追加，表结构已在 `sql/init.sql` 末尾预留。
