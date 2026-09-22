# Agent 服务契约（Java ↔ Python）

> 版本：v1.0（2026-09-22）
> 唯一基准：Java 侧 AgentClient / AgentChatClient 与本文档一致，Python 侧按此实现
> 范围：`/v1/ingest`（业务数据同步入向量库）、`/v1/chat`（对话 SSE）、`/v1/health`（探活）
> 对齐 Java `Result<T>` 风格：HTTP 层成功一律 200；body 为 `{code, message, data}`（code=0 成功）

---

## 0. 通用约定

| 约定 | 说明 |
|---|---|
| 传输 | Java 内网调用 Python（`agent.url`），不暴露公网 |
| 鉴权 | 请求头 `X-Agent-Key: <共享密钥>`（`agent.key`），两侧配置一致 |
| trace_id | 每次请求由 Java 生成 16 位小写十六进制，贯穿日志用于链路追踪 |
| 失败上报 | Java 侧收到非 200 或异常视为失败；流式接口用 event 表达错误（见 §2.2） |
| 幂等 | ingest 按 `biz_type + biz_id + scope_id` 幂等（同 key 覆盖/取消），Java 侧 5s 窗口去重 |
| 超时（Java 侧执行） | 普通请求：连接 3s；对话：阅读 30s、整体 35s |
| 熔断（Java 侧执行） | `/v1/chat` 连续 5 次失败 → 30s 熔断，期间返回静态降级文案 |

---

## 1. POST /v1/ingest —— 业务数据向量同步

**用途**：动态/相册照片写库后（AFTER_COMMIT 提交成功）通知 Python 入库或删除向量。
**触发时机**：Java 在业务事务提交后异步调用；`agent.enabled=false` 时全部静默跳过。

### 1.1 请求

```
POST {agent.url}/v1/ingest
Header: Content-Type: application/json
        X-Agent-Key: <key>
```

```json
{
  "trace_id": "a1b2c3d4e5f60718",
  "biz_type": "moment | photo | note",
  "biz_id": 123,
  "content": "要向量化的正文文本（delete 时可为 null/空）",
  "scope": "couple | personal",
  "scope_id": 11,
  "action": "upsert | delete"
}
```

字段语义：
- `biz_type`：`moment`=动态、`photo`=相册照片、`note`=学习笔记（后续模块复用同一通道）
- `content`：upsert 必填，为 ready-to-vector 文本；delete 可空
  - moment：动态正文 + 地点（Java 已拼好 `正文 地点：xxx`）
  - photo：相册名 + 照片描述（Java 已拼好 `相册名 描述`）
- `scope` / `scope_id`：数据域
  - `couple`：情侣共享（动态/相册）→ Python 侧 namespace `moment_c{coupleId}`
  - `personal`：个人私有（笔记）→ Python 侧 namespace `note_u{userId}`
- `action`：`upsert`=新增或更新（同 biz_id 覆盖旧向量）；`delete`=删除该 biz_id 全部向量
- 建议 Python 侧向量 payload/metadata 至少含：`biz_type, biz_id, scope, scope_id, content, created_at`

### 1.2 响应

成功（HTTP 200）：
```json
{ "code": 0, "message": "success", "data": null }
```
失败（HTTP 4xx/5xx 或 body code!=0）：Java 记录日志并按 5s / 30s / 2min 重试 3 次，仍失败放弃并记 error（可人工补同步）。

### 1.3 幂等 / 删除语义

- 同 `biz_type+biz_id+scope_id` 重复 upsert：覆盖，不产生重复向量
- 同 `biz_type+biz_id+scope_id` 重复 delete：幂等，返回成功即可
- 相册删除：Java 逐照片发 `biz_type=photo` 的 delete（biz_id=每张照片 id）

---

## 2. POST /v1/chat —— 对话（SSE 流式）

**用途**：聊天内 `@bot` 与独立 `/ai` 页共用此接口，Java 转发 SSE 给前端。

### 2.1 请求

```
POST {agent.url}/v1/chat
Header: Content-Type: application/json
        X-Agent-Key: <key>
Accept: text/event-stream
```

```json
{
  "trace_id": "a1b2c3d4e5f60718",
  "user_id": 1,
  "couple_id": 11,
  "session_id": "couple11 或 user1",
  "message": "用户输入（@bot 后的完整文本）",
  "scope": "couple | personal",
  "scope_id": 11
}
```

`session_id` 约定（Java 生成）：
- 聊天内 @bot → `couple{coupleId}`、`scope=couple`
- /ai 页 → `user{userId}`、`scope=personal`

### 2.2 响应（SSE 事件流）

`Content-Type: text/event-stream`，每个事件一行 `data: <json>`：

| 事件 | data 内容 | 说明 |
|---|---|---|
| delta | `{"content":"增量文本"}` | 增量片段，可多次；Java 拼接成全文本供前端打字机 + 落库 |
| refs（可选） | `{"refs":[{"biz_type":"moment","biz_id":9,"title":"..."}]}` | 引用出处，/ai 页展示（聊天内忽略） |
| 结束 | `data: [DONE]` | 必须发，Java 据此结束流 |
| 错误（可选） | `{"error":"人类可读错误信息"}` | Python 主动报错；若流中途断开/超时，Java 侧自行兜底降级文案 |

推荐实现（兼容 Java 解析）：
```
data: {"content":"你"}
data: {"content":"好"}
data: {"content":"。"}
data: {"refs":[{"biz_type":"moment","biz_id":9,"title":"海边的一天"}]}
data: [DONE]
```

Java 侧兜底（Python 不必实现）：宕机/拒连 → `UNREACHABLE`；超时 → `TIMEOUT`；HTTP 5xx → `FAILED`；连续 5 次失败进入 30s 熔断。前端展示文案由 Java 统一给出。

### 2.3 注意

- 建议 `wss` 之外用普通 HTTP + SSE（Java 是唯一消费者，无需公网）
- 单次会话上下文（Redis 10 条 / 24h 过期）由 Python 按 `user_id+session_id` 维护，Java 不感知
- 响应文本生成时长较长时请按增量尽快 flush（Java 读超时 30s 是从首字节后持续读，尽量 2s 内有块产出）

---

## 3. GET /v1/health —— 探活

```
GET {agent.url}/v1/health
Header: X-Agent-Key: <key>
```

成功：HTTP 200，body 任意（建议 `{code:0,message:"ok",data:null}`）。
Java 目前不主动轮询 health，仅靠调用结果判定；本接口供人工/容器存活检查。

---

## 4. 错误码表（非 SSE 接口）

与 Java `Result` 语义对齐：

| HTTP | body.code | 含义 | Java 处理 |
|---|---|---|---|
| 200 | 0 | 成功 | 正常返回 |
| 400 | 400 | 参数错误（缺字段等） | 日志 + 重试（最终放弃） |
| 401 | 401 | X-Agent-Key 错误 | 日志，不重试（配置问题） |
| 500 | 500 | 内部错误（embedding/向量库/模型失败） | 日志 + 按 5s/30s/2min 重试 3 次 |
| 408 | 408 | 超时 | 同 500 处理 |

SSE 接口无 body-code，错误用 `{"error":...}` 事件或由 Java 侧超时/熔断兜底。

---

## 5. 数据域与命名空间总览（Python 侧隔离设计）

| 业务 | biz_type | scope | scope_id | namespace 建议 |
|---|---|---|---|---|
| 情侣动态 | moment | couple | coupleId | `moment_c{coupleId}` |
| 相册照片 | photo | couple | coupleId | `moment_c{coupleId}`（与动态同域，共用共享检索） |
| 学习笔记 | note | personal | userId | `note_u{userId}` |

检索规则（对应产品需求）：
- 共享问题（聊天 @bot / /ai 生活类）→ 查 `moment_c*`，无 user 过滤
- 个人问题（/ai 学习类）→ 查 `note_u{userId}`，仅本人
- 具体检索分片/意图识别由 Python 侧负责（Java 只透传 scope/session/会话）

---

## 6. Java 侧灰度与默认

- `agent.enabled=false`（默认）：ingest 静默跳过；`/v1/chat` 直接返回"AI 助手尚未启用"
- `agent.url`：默认 `http://localhost:8001`
- `agent.key`：默认 `change-me`，生产必改
- 环境变量：`AGENT_ENABLED` / `AGENT_URL` / `AGENT_KEY`

## 7. 变更记录

| 时间 | 变更 |
|---|---|
| 2026-09-22 | 初版定稿（三接口 + SSE 事件格式 + 域规范） |