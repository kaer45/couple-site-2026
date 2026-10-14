# Agent 服务契约（Java ↔ Python）

> 版本：v1.1（2026-09-29 依据代码校对）
> 唯一基准：Java 侧 AgentClient / AgentChatClient 与本文档一致，Python 侧按此实现；**若本文档与代码冲突，以代码为准**（已核对项见 §8）
> 范围：`/v1/ingest`（业务数据同步入向量库）、`/v1/chat`（对话 SSE）、`/v1/summarize`（内容摘要）、`/v1/health`（探活）
> 对齐 Java `Result<T>` 风格：HTTP 层成功一律 200；body 为 `{code, message, data}`（code=0 成功）
>
> **实现状态**：`agent-service/`（Python / FastAPI）**核心四接口已实现** —— 含向量检索、
> SQLite 多轮会话、离线 mock 开关；Neo4j 知识图谱待实现。
> 实现说明见 `agent-service/README.md`，施工与踩坑记录见 `docs/agent-service-build-guide.md`。
> 本文档是双边的**唯一契约**，实现方式不受其约束。

---

## 0. 通用约定

| 约定 | 说明 |
|---|---|
| 传输 | Java 内网调用 Python（`agent.url`），不暴露公网 |
| 鉴权 | 请求头 `X-Agent-Key: <共享密钥>`（`agent.key`），两侧配置一致 |
| trace_id | 每次请求由 Java 生成 16 位小写十六进制，贯穿日志用于链路追踪 |
| 失败上报（现状） | ingest 仅以 HTTP 状态判定成败（4xx/5xx 抛异常才算失败），**不校验 body.code**；对话失败用 SSE event 表达错误（见 §2.2） |
| 幂等（现状） | ingest 按 `biz_type:biz_id:action` 幂等（同 key 覆盖/取消），**不含 scope_id**；Java 侧另有 5s 窗口去重（该 Map 只写不清理） |
| 超时（Java 侧执行） | 对话：连接 3s、阅读 30s、整体 35s；摘要：连接 3s、整体 60s；**ingest 侧未配置任何超时**（裸 `RestTemplate`） |
| 熔断（Java 侧执行） | `/v1/chat` 与 `/v1/summarize` **共用**失败计数：连续 5 次失败 → 30s 熔断（窗口过后放一次试探），期间返回静态降级文案 |

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
  - **现状**：Java 侧已发布 `moment`（动态新增/删除）、`photo`（照片上传/改描述/删除）、`note`（学习笔记上传/改标题/删除）三类事件；`note` 使用 `scope=personal`、`scope_id=userId`。
- `content`：upsert 必填，为 ready-to-vector 文本；delete 可空
  - moment：动态正文 + 地点（Java 已拼好 `正文 地点：xxx`）
  - photo：相册名 + 照片描述（Java 已拼好 `相册名 描述`）
  - note：笔记标题 + 抽取出的正文（Java 已拼好 `标题\n正文`）
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
失败判定（Java 侧现状）：只有 HTTP 4xx/5xx（`RestTemplate` 抛异常）才算失败。**HTTP 200 但 body `code != 0` 会被当成成功**，因此 Python 侧如遇内部错误，请同时返回非 200 状态码。
失败后 Java 记录日志并重试 3 次，实际 sleep 间隔为 **5s、30s**（`RETRY_DELAYS_MS` 中的第 3 个 2min 因循环条件永不执行），仍失败则放弃并记 error（可人工补同步）。重试**不区分错误码**（401/400 同样会重试）。

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

**字段命名对照（重要）**：本文档 §2.1/§2.2 描述的是 **Java ↔ Python** 的字段，增量键名为 `content`。而 Java 转发给**前端**时会换名：

| 链路 | 增量字段 | 结束标记 | 说明 |
|---|---|---|---|
| Python → Java | `{"content":"..."}` | `data: [DONE]` | 本文档约定 |
| Java → 前端（`/api/agent/chat`，SSE） | `{"delta":"..."}` | `data:[DONE]` | 见 `docs/api-contract.md` §10 |
| Java → 前端（聊天内 `@bot`，WebSocket） | `{"type":"AGENT_STREAM","clientMsgId":"...","delta":"..."}` | `finalFrame:true` + `messageId` | 见 `docs/api-contract.md` §9.3；聊天内不使用 SSE |

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

## 3. POST /v1/summarize —— 内容摘要（普通 JSON）

**用途**：学习笔记模块的「生成摘要」按钮。Java 把笔记正文发过来，Python 返回一段摘要文本。
**触发时机**：**用户在页面上手动点击**（不自动触发），因此可以慢一些。

### 3.1 请求

```
POST {agent.url}/v1/summarize
Header: Content-Type: application/json
        X-Agent-Key: <key>
```

```json
{
  "trace_id": "a1b2c3d4e5f60718",
  "user_id": 1,
  "note_id": 3,
  "title": "Redis 学习笔记",
  "content": "抽取出的笔记正文全文"
}
```

- `content`：**完整正文**（Java 侧抽取上限 20 万字）。**Python 侧需自行处理超长输入**（截断 / 分段 map-reduce），Java 不做裁剪。
- `title` 可用于让摘要更贴合主题，也可忽略。

### 3.2 响应

成功（HTTP 200）：

```json
{ "code": 0, "message": "success", "data": { "summary": "这篇笔记主要讲了……" } }
```

- `data.summary` **必填且非空白**：Java 会校验，空白视为失败。
- 建议长度 100–300 字（Java 侧不限制，但前端卡片只显示两行）。
- 失败时请返回 **4xx/5xx**；若返回 200 但 `code != 0`，Java 也会按失败处理。

### 3.3 Java 侧行为

- 超时：连接 3s、读 60s、整体 60s（比对话更宽）。
- 与 `/v1/chat` **共用熔断计数**：连续 5 次失败 → 30s 熔断。
- **失败不写库**：Java 只在成功时把摘要落到 `note.summary_ai`，并把失败原因转成 503 返回给前端。
  这样能避免把「服务不可用」这类提示当成摘要存下来。

---

## 4. GET /v1/health —— 探活

```
GET {agent.url}/v1/health
Header: X-Agent-Key: <key>
```

成功：HTTP 200，body 任意（建议 `{code:0,message:"ok",data:null}`）。
Java 目前不主动轮询 health，仅靠调用结果判定；本接口供人工/容器存活检查。

---

## 5. 错误码表（非 SSE 接口）

与 Java `Result` 语义对齐：

| HTTP | body.code | 含义 | Java 处理 |
|---|---|---|---|
| 200 | 0 | 成功 | 正常返回 |
| 400 | 400 | 参数错误（缺字段等） | 日志 + 重试（最终放弃） |
| 401 | 401 | X-Agent-Key 错误 | 日志；**当前实现仍会重试**（未区分错误码，属已知偏差） |
| 500 | 500 | 内部错误（embedding/向量库/模型失败） | 日志 + 重试 3 次（实际间隔 5s / 30s） |
| 408 | 408 | 超时 | 同 500 处理 |

SSE 接口无 body-code，错误用 `{"error":...}` 事件或由 Java 侧超时/熔断兜底。

---

## 6. 数据域与命名空间总览（Python 侧隔离设计）

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

## 7. Java 侧灰度与默认

- `agent.enabled`（默认值以 `application.yml` 为准：**`true`**；`AgentProperties` 字段默认值为 `false`，仅在无 yml 绑定时生效。Docker 部署的 `.env` 默认设为 `false`）：ingest 静默跳过；`/v1/chat` 直接返回"AI 助手尚未启用"
- `agent.url`：默认 `http://localhost:8001`
- `agent.key`：默认 `change-me`，生产必改
- 环境变量：`AGENT_ENABLED` / `AGENT_URL` / `AGENT_KEY`
- 仅以上三个 `agent.*` 配置键（无 `agent.timeout` / `agent.retry` 等）

## 8. 实现与本文档的已知偏差（以代码为准）

| 项 | 本文档原描述 | 代码实际 |
|---|---|---|
| ingest 幂等键 | `biz_type + biz_id + scope_id` | `biz_type:biz_id:action`（不含 scope_id，但区分 action） |
| ingest 失败判定 | 非 200 或 `body.code != 0` | 仅 HTTP 4xx/5xx；HTTP 200 + `code != 0` 视为成功 |
| ingest 重试间隔 | 5s / 30s / 2min | 实际只 sleep 5s、30s（第 3 个延迟不生效） |
| 401 处理 | 不重试（配置问题） | 未区分错误码，仍会重试 3 次 |
| ingest 超时 | 连接 3s | 未配置任何超时（裸 `RestTemplate`） |
| 对话错误类型 | 4 类 | 5 类：`DISABLED` / `CIRCUIT_OPEN` / `UNREACHABLE` / `TIMEOUT` / `FAILED`（4xx 与 5xx 均归 `FAILED`） |
| `agent.enabled` 默认 | `false` | `application.yml` 为 `true`（`.env`/compose 中为 `false`） |
| 增量字段名 | 全程 `content` | Python→Java 为 `content`，Java→前端为 `delta`（见 §2.2 对照表） |

## 9. 变更记录

| 时间 | 变更 |
|---|---|
| 2026-09-22 | 初版定稿（三接口 + SSE 事件格式 + 域规范） |
| 2026-09-29 | 依据代码核对更新：修正幂等键/失败判定/重试/超时/错误类型/默认值与增量字段名，新增 §8 偏差说明与 §2.2 字段命名对照 |
| 2026-10-01 | 新增 **§3 `/v1/summarize`** 内容摘要接口（学习笔记手动生成摘要），后续章节顺延 |