# Agent 服务施工指南（无框架手写版）

> 版本：**v2.0**
> 用途：`agent-service/`（Python / FastAPI）的**施工顺序、技术选型与设计约束**。
> 契约基准：**`docs/agent-api.md`** —— 只约定线格式（字段 / SSE / 错误码 / namespace），
> 与实现技术选型无关。本文件讲「怎么做」和「为什么这么做」。
> 运行手册（启动 / 配置 / 排错）见 `agent-service/README.md`。
>
> **v1.x → v2.0 变更**：v1.x 是「基于 hello-agents 0.2.9 框架」的版本；
> 现已**移除框架依赖、移除 mock 模式**，三个外部依赖客户端改为自研直连。
> §3 记录了选型理由；§6 的坑表按「问题本身的坑」重新归类 —— 它们**与用不用框架无关**。

---

## 1. 服务定位

```
浏览器  ──→  Java (Spring Boot, :8080)  ──→  Python (FastAPI, :8001)
                  ↑ 唯一调用方，内网        ↑ 不暴露公网、不直接服务前端
```

三条边界：

1. **Python 不碰 MySQL。** 它拿不到业务库，业务数据都是 Java 通过 `/v1/ingest` 推过来的文本。
2. **Python 只有 Java 一个客户。** 不需要多租户鉴权框架、CORS、限流（Java 已做）。
3. **Java 已经做了这些，不用实现**：超时（对话 3s/30s/35s、摘要 3s/60s）、
   熔断（连续 5 次失败 → 30s）、失败降级文案、ingest 重试（3 次，间隔 5s/30s）、
   事务提交后触发。

### 数据流

```
用户在页面点「生成摘要」
  Java /api/notes/{id}/summary → POST /v1/summarize → 返回文本 → Java 存 note.summary_ai
用户在 @bot / /ai 提问
  Java /api/agent/chat         → POST /v1/chat (SSE)  → Java 转发 → 前端打字机
动态/相册/笔记 增删改
  Java 事务提交后异步           → POST /v1/ingest       → Python 写/删 Qdrant 向量
```

**关键区分**：`chat` / `summarize` 是用户当场等结果的；`ingest` 是后台同步，慢一点无所谓。
这个区别决定了施工顺序（§5）。

### 外部依赖

| 依赖 | 用途 | 形态 | 本服务怎么调 |
|---|---|---|---|
| DeepSeek（`deepseek-chat`） | 对话 + 摘要 | OpenAI 兼容 HTTP | `httpx` 直连 `/chat/completions` |
| DashScope `text-embedding-v3` | 文本向量化 | OpenAI 兼容 HTTP | `httpx` 直连 `/embeddings` |
| Qdrant | 向量库 | 本地服务 `:6333` | `qdrant-client` |
| SQLite | 会话历史 + 审计 | 本地文件 | 标准库 `sqlite3` |
| Neo4j | 知识图谱 | 本地服务 `:7687` | ⏳ 未实现（下一轮） |

---

## 2. 目录结构

```
agent-service/
├── .venv/                       # 虚拟环境（已在 .gitignore）
├── .env                         # 真实密钥（已在 .gitignore）
├── .env.example                 # 模板（进 git）
├── requirements.txt
├── README.md                    # 运行手册：启动、配置、降级、排错
├── run.py                       # 本地启动入口
├── tests/
│   ├── test_core.py             # 纯逻辑单测（零第三方依赖）
│   └── test_contract.py         # 客户端协议层 + 接口契约层
└── app/
    ├── main.py                  # FastAPI 实例 + lifespan + 降级预热
    ├── config.py                # pydantic-settings 读 .env
    ├── logging_conf.py          # trace_id 贯穿日志（含流式专用的 set_trace）
    ├── core/
    │   ├── errors.py            # 统一异常 + Result 信封 + 异常处理器
    │   ├── names.py             # namespace / point id 生成的唯一出处
    │   └── text.py              # 截断 / 分段 / 标题推断
    ├── schemas/                 # Pydantic 模型，与 agent-api.md 字段一一对应
    │   ├── common.py            # Result 信封
    │   ├── ingest.py
    │   ├── chat.py
    │   └── summarize.py
    ├── api/
    │   ├── deps.py              # X-Agent-Key 鉴权
    │   └── routes/              # health / ingest / chat / summarize
    ├── services/                # 业务逻辑：本层禁止 import fastapi
    │   ├── llm_client.py        # httpx 直连 /chat/completions，自解 SSE
    │   ├── embedding_client.py  # httpx 直连 /embeddings，无回退链
    │   ├── vector_store.py      # 直接用 qdrant-client，自管 collection 与索引
    │   ├── retrieval.py         # 检索（失败降级为空上下文）
    │   ├── session_store.py     # 多轮会话（SQLite）
    │   ├── ingest_service.py
    │   ├── chat_service.py
    │   ├── summarize_service.py
    │   └── types.py             # 内部数据类（避免循环 import）
    ├── db/sqlite_store.py       # 连接 + 建表（会话、ingest 审计）
    └── prompts/                 # 提示词当代码管，别散落在业务逻辑里
```

**分层纪律**：

- `api/routes/` 只做四件事：收参数 → 交给 Pydantic 校验 → 调 service → 包 `Result`。**不写业务**。
- `services/` 是纯业务，**不 import fastapi**。好处：能单测、能在脚本里复用、换 Web 框架不用改。
- `db/` 只负责包外部客户端，把「连不上 / 超时 / 认证失败」翻译成统一异常。
- **外部依赖只出现在 `services/` 的 `llm_client` / `embedding_client` / `vector_store` 里**，
  `api/` 层不认识 `httpx` 或 `QdrantClient`。这样换实现只改 service。

---

## 3. 技术选型：为什么不使用智能体框架

### 3.1 曾经的方案

v1.x 用 [hello-agents](https://pypi.org/project/hello-agents/) **0.2.9** 做底座，
它提供 `HelloAgentsLLM`（LLM）、`DashScopeEmbedding`（嵌入）、
`QdrantVectorStore`（向量库）、`DocumentProcessor`（分块）等组件。

### 3.2 放弃的理由（都是实测出来的）

| # | 问题 | 后果 |
|---|---|---|
| 1 | `QdrantVectorStore.vector_size` 默认 **384**，而 DashScope v3 是 **1024** | 不显式传值就建成 384 维 collection；之后写入时维度不符只打 warning 然后 `continue` —— **逐条静默丢弃** |
| 2 | `add_vectors()` 对**非 UUID 的字符串 id 会静默替换成随机 UUID** | 幂等与删除一起失效：改一次笔记就多一条向量，删也删不掉 |
| 3 | 存在 `create_embedding_model_with_fallback()`，回退链 `dashscope → local → tfidf` | DashScope 一失败就**静默降级到 1000 维 TF-IDF**，同一 collection 混入不同维度向量，检索永久失效且不报错 |
| 4 | `add_vectors()` 失败时 `return False` 而不抛异常 | `/v1/ingest` 返回 200，Java 以为同步成功，数据再也补不上 |
| 5 | 版本锁 `openai<2.0.0`，且 1.0.0 改过顶层导出与模块结构 | 与项目其他依赖互相牵制；升级会静默炸 |
| 6 | 基础依赖拖进 `tiktoken` / `networkx` / `beautifulsoup4` / `numpy`；`[rag]` extra 是 `torch + transformers`（数 GB） | 依赖面积远大于实际需要 |
| 7 | 许可证 **CC-BY-NC-SA-4.0**（非商业 + 相同方式共享） | 对任何商业化可能都是约束，且是「内容许可证套在软件上」 |
| 8 | `tools/builtin/terminal_tool.py` 提供任意命令执行能力 | 接到有用户输入的服务里等于开了个口子 |

**核心问题不是「框架不好用」，而是它把多个静默失败点藏在了默认值里。**
本项目对向量数据的正确性要求很高（幂等、可删除、维度一致），
而这些恰恰是它的默认行为会破坏的东西。

### 3.3 替代方案对比

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 继续用 hello-agents | 组件现成 | §3.2 的 8 条 | ❌ 放弃 |
| 官方 SDK（`openai` + `dashscope`） | 解析健壮、自带重试 | 两个额外依赖；`openai` 自身版本漂移（1.x → 3.x 改过 API）；DashScope SDK 与兼容模式行为不一致 | ❌ |
| **`httpx` 直连 + `qdrant-client`** | 依赖最少（只加 1 个）；协议层完全可控；不受任何 SDK 大版本变更影响 | SSE 解析要自己写（约 25 行） | ✅ **采用** |

**关键判断**：DeepSeek 与 DashScope 都是 **OpenAI 兼容接口**，直接发 HTTP 就够，
没必要为「少写 25 行 SSE 解析」引入两个有版本风险的 SDK。

### 3.4 依赖清单

```
fastapi>=0.115,<1.0
uvicorn[standard]>=0.30,<1.0
pydantic-settings>=2.3,<3.0
httpx>=0.27          # 出站 HTTP；同时被 fastapi.testclient 复用
qdrant-client>=1.9   # 代码兼容新旧 API
neo4j>=5.0           # 下一轮图谱（懒加载，不装也能跑）
```

比框架方案少了 `hello-agents` / `openai` / `dashscope` / `tiktoken` / `networkx` /
`beautifulsoup4` / `numpy`，**依赖面积小了一个数量级**。

---

## 4. FastAPI 最小必要知识

### 4.1 一个接口长什么样

```python
# app/api/routes/summarize.py
router = APIRouter(prefix="/v1", tags=["summarize"])

# 注意：普通 def，不是 async def —— 因为 service 内部是同步阻塞调用
@router.post("/summarize", response_model=ResultEnvelope)
def summarize(req: SummarizeRequest) -> dict:
    with bind_trace(req.trace_id):
        summary = summarize_service.summarize(req)
    return ok({"summary": summary})
```

- 参数标成 Pydantic 模型 → 自动解析 + 校验，**不用手写 if 判空**。
- `dependencies=[Depends(verify_key)]` 挂在路由或 `include_router` 上做鉴权。
- 同步 `def` 路由会被 FastAPI 自动丢进线程池；`async def` 里调阻塞函数会卡死事件循环。

### 4.2 启动

```powershell
cd agent-service
.\.venv\Scripts\Activate.ps1
python run.py
# 或： uvicorn app.main:app --host 127.0.0.1 --port 8001 --reload
```

自带交互文档 **http://127.0.0.1:8001/docs**，是调试主力工具。

### 4.3 SSE 怎么写

```python
def _event(payload: dict) -> str:
    # ensure_ascii=False：否则中文变成 \uXXXX，抓包和日志都没法看
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"

def _sse(req: ChatRequest) -> Iterator[str]:
    trace = req.trace_id
    events = chat_service.run(req)
    try:
        while True:
            set_trace(trace)          # 每一步都重设（见 §6 第 14 条）
            try:
                kind, payload = next(events)
            except StopIteration:
                break
            ...
    finally:
        set_trace(trace)
        events.close()                # 触发下游 finally，保存已产出内容
    set_trace(trace)
    yield "data: [DONE]\n\n"

@router.post("/chat")
async def chat(req: ChatRequest) -> StreamingResponse:
    return StreamingResponse(
        _sse(req),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
```

四个必须记住的点：

1. **两个换行 `\n\n` 才是一个 SSE 事件结束**，一个 `\n` 不够。
2. **`data: [DONE]` 必须发**，Java 靠它结束流；不发前端一直转圈、消息不落库。
3. **`ensure_ascii=False`**。
4. **同步生成器可以直接交给 `StreamingResponse`** —— Starlette 内部会用
   `iterate_in_threadpool` 包一层，不会阻塞事件循环。危险的是在 `async def`
   函数体里**直接调用**同步函数。

### 4.4 统一响应信封

```python
def ok(data=None, message="success"):
    return {"code": 0, "message": message, "data": data}

def register_exception_handlers(app):
    @app.exception_handler(AgentError)
    async def _agent_error(request, exc):
        return JSONResponse(status_code=exc.status_code,
                            content={"code": exc.code, "message": exc.message, "data": None})

    @app.exception_handler(RequestValidationError)
    async def _invalid(request, exc):
        # 关键：默认 422，Java 会当成失败并重试 3 次；转成 400 更符合契约
        return JSONResponse(status_code=400,
                            content={"code": 400, "message": "参数错误", "data": None})
```

**为什么必须有**：契约 §1.2 明确 —— Java 对 `/v1/ingest` **只看 HTTP 状态码，不看 `body.code`**。
内部出错时必须返回非 200，否则 Java 认为成功、不重试，数据静默丢失且无任何报错。

### 4.5 鉴权

```python
async def verify_key(x_agent_key: str = Header(default="", alias="X-Agent-Key")) -> None:
    expected = get_settings().agent_key
    if not expected:
        logger.warning("AGENT_KEY 未配置，跳过鉴权（生产务必配置）")
        return
    if x_agent_key != expected:
        raise unauthorized()
```

`AGENT_KEY` 是自定义的（与 Java `agent.key` 对齐），与任何框架无关。

---

## 5. 施工顺序

**原则：先打通 Java 已经做好的 UI，再补后台同步。**

| 步 | 做什么 | 完成标志 |
|---|---|---|
| 0 | venv + `requirements.txt` + `.env` | `pip list` 里有 fastapi / httpx / qdrant-client |
| 1 | `main.py` + `/v1/health` | `curl.exe` 返回 `{"code":0,...}` |
| 2 | `config.py` + `deps.verify_key` | 不带 header → 401；带对了 → 200 |
| 3 | `llm_client.py` + `/v1/chat` | 前端 @bot 出现打字机、回答落库 |
| 4 | `summarize_service.py` + `/v1/summarize` | 笔记页点「生成摘要」出结果 |
| 5 | `embedding_client.py` + `vector_store.py` + `/v1/ingest` | 发一条 ingest，Qdrant 里查到点；重复发仍只有 1 个点 |
| 6 | `session_store.py`（SQLite 多轮上下文） | 连问两句，第二句能引用第一句 |
| 7 | `retrieval.py`（检索结果拼进 prompt） | 问只有你笔记里才有的内容，回答正确 |
| 8 | Neo4j 图谱（实体/关系抽取 + 多跳） | 最后做，最复杂，**不做也不影响前面全部功能** |

### 为什么第 3 步先做 `/v1/chat`

Java 侧的 `/api/agent/chat`（SSE）和 `/api/notes/{id}/summary` 都已经写完了。
Python 这边一通，前端立刻能用 —— 反馈最快，也最容易定位问题。

### 顺序上的一个变化（v1.x → v2.0）

v1.x 建议「先做假流式（mock）验证协议，再调模型」。现在**没有 mock 模式**，
替代做法是 `tests/test_contract.py`：它用 `httpx.MockTransport` 伪造上游响应，
**真正执行**客户端的 SSE 解析与错误翻译代码，因此不需要 key 也不需要网络，
同样能把「协议错」和「模型错」分开。

---

## 6. 一定会踩的坑

> 前三条是**问题本身的坑**（Qdrant / 嵌入模型 / ASGI 的特性），与用不用框架无关。
> 自己写客户端时同样要守住 —— 否则会重新引入静默数据损坏。

| # | 坑 | 现象 | 解法 |
|---|---|---|---|
| 1 | **Qdrant collection 维度与嵌入维度不一致** | 写入被拒或检索错乱；若封装库「容错」则被**逐条静默丢弃** | 建 collection 时**显式**传 `EMBED_DIM`，绝不依赖默认值；写入/检索前自检维度 |
| 2 | **point id 用了非 UUID 字符串** | Qdrant 只接受无符号整数或 UUID 字符串，写入失败；若封装库「容错」则被换成随机 id，幂等与删除一起失效 | 用 **UUIDv5** 生成确定性 id（`core.names.point_id_for`），同输入永远同输出 |
| 3 | **嵌入失败时静默降级** | 换成不同维度的向量，同一 collection 数据被污染，检索永久失效且不报错 | **不做任何回退链**：失败就抛错 → `/v1/ingest` 返回非 200 → Java 重试 |
| 4 | 校验失败返回 422 | Java 收 422 → 判为失败 → 重试 3 次 | 加 `RequestValidationError` 处理器转 400（§4.4） |
| 5 | 忘发 `[DONE]` | 前端一直转圈、消息不落库 | 生成器末尾无条件发 |
| 6 | SSE 被缓冲 | 前端等很久才一次性出现全部文字 | `media_type="text/event-stream"` + `X-Accel-Buffering: no` |
| 7 | 中文变 `\uXXXX` | 日志/抓包不可读 | `json.dumps(..., ensure_ascii=False)` |
| 8 | Pydantic v2 语法 | `@validator` 直接报错 | 用 `@field_validator` + `model_config = ConfigDict(...)` |
| 9 | 在 `async def` 里调同步函数 | 一个请求执行期间其他请求全部无响应 | 用同步 `def` 路由，或 `await run_in_threadpool(fn, ...)` |
| 10 | 上游错误体没读完就取 `.text` | httpx 抛 `ResponseNotRead` | 流式响应里先 `resp.read()` 再读 `.text` |
| 11 | SSE 里把注释行当数据 | `: keep-alive` 解析失败 | 只处理 `data:` 前缀的行；解析失败只 debug 日志跳过 |
| 12 | 忽略只带 `usage` / `role` 的块 | 产出空字符串增量 | 取 `choices[].delta.content` 并判空 |
| 13 | 嵌入接口返回顺序不保证 | 向量与文本错位 | 按返回里的 `index` 排序后再用 |
| 14 | **在 SSE 生成器里用 ContextVar token 做 set/reset** | `iterate_in_threadpool` 每次 `next()` 都可能换线程/Context，跨 Context `reset()` 抛 `Token was created in a different Context`；**异常发生在响应已开始推送之后，处理器兜不住，整个 SSE 请求 500** | 流式路径用「只设置、不恢复，每一步重设一次」；非流式路径才用 `with` |
| 15 | 在受限环境用 `tempfile.mkdtemp()` 放 SQLite | `sqlite3` 报 `unable to open database file` | 测试脚本改用普通 `mkdir` 的目录 |
| 16 | 批量替换脚本把定义本身也替换了 | 产生 `--x: var(--x)` 自引用，该变量失效并连带所有引用方失效 | 替换脚本先排除定义块；验收加「自引用检查」 |
| 17 | 只做语法检查就以为写完 | 语法全过、导入全过，但 SSE 路径照炸 | **必须真跑** `tests/test_contract.py` |
| 18 | PowerShell 里的 `curl` | `-H` 报错 | PowerShell 的 `curl` 是 `Invoke-WebRequest` 别名 → 用 **`curl.exe`** |

---

## 7. 每个接口的验收标准

### GET `/v1/health`

- 要求 `X-Agent-Key`。
- Java **不轮询**它（只靠调用结果判定），供人工和容器存活检查用。
- 返回 `data.degraded` 与 `data.components`，逐项说明哪个依赖没就绪。

### POST `/v1/ingest`

- 字段：`trace_id, biz_type, biz_id, content, scope, scope_id, action`（契约 §1.1）
- `action=upsert`：`content` 必填 → 嵌入 → 写入对应 namespace；**同 `biz_type+biz_id` 必须覆盖**
- `action=delete`：按 point id 删；**不存在也要返回成功**（契约 §1.3）
- namespace 映射**只写在一个地方**（`core/names.py`）：
  - `moment` / `photo` + `scope=couple` → `moment_c{scope_id}`
  - `note` + `scope=personal` → `note_u{scope_id}`
- payload 至少含：`biz_type, biz_id, scope, scope_id, content, title, created_at`
- 失败 → **返回 4xx/5xx**（见 §4.4）

注意 Java 的重试行为（契约 §1.2）：失败重试 3 次，实际间隔 **5s / 30s**。
所以别做超过 ~30s 的事；Java 侧 ingest **没有配置任何超时**，hang 住会白占它的异步线程。

### POST `/v1/chat`（SSE）

- 事件格式 `data: {"content":"增量"}\n\n`，键名是 **`content`**
  - ⚠️ **不是 `delta`**。`delta` 是 Java 转发给**前端**时用的名字（契约 §2.2）
- 结束发 `data: [DONE]\n\n`；出错发 `data: {"error":"..."}\n\n`
- `session_id` / `scope` / `scope_id` 由 Java 传，按 `user_id + session_id` 存历史
- **首字节 2s 内产出**（契约 §2.3）
- 共享问题查 `moment_c*`，个人问题查 `note_u{userId}`（契约 §6）

### POST `/v1/summarize`

- 响应 `data.summary` **非空且非空白** —— Java 会校验，空白视为失败（契约 §3.2）
- `content` 最长 **20 万字**，Python 自己负责截断 / 分段 map-reduce
- 建议 100–300 字；预算 60s
- 失败 → 4xx/5xx。**Java 失败不写库**，返回空串等于让用户白等

---

## 8. 配置

`agent-service/.env`（**独立于根目录那份**，根目录 `.env` 是给 Docker 的）：

| 变量 | 示例 | 必须与谁一致 |
|---|---|---|
| `AGENT_KEY` | 随机串 | Java `agent.key`（默认 `change-me`） |
| `HOST` / `PORT` | `127.0.0.1` / `8001` | Java `agent.url`（默认 `http://localhost:8001`） |
| `LLM_BASE_URL` / `LLM_MODEL_ID` / `LLM_API_KEY` | DeepSeek | — |
| `LLM_TEMPERATURE` / `LLM_MAX_TOKENS` | `0.7` / `0`（不发） | — |
| `LLM_CONNECT_TIMEOUT` / `LLM_READ_TIMEOUT` | `10` / `60` | 别比契约的 60s 更大 |
| `DASHSCOPE_API_KEY` / `DASHSCOPE_BASE_URL` | DashScope 兼容模式 | — |
| `EMBED_MODEL_NAME` / `EMBED_DIM` | `text-embedding-v3` / `1024` | ⚠️ 两者必须匹配 |
| `QDRANT_URL` / `QDRANT_TIMEOUT` | `http://localhost:6333` / `30` | — |
| `SQLITE_PATH` | `./data/agent.db` | — |
| `RETRIEVAL_ENABLED` / `RETRIEVAL_TOP_K` / `RETRIEVAL_SCORE_THRESHOLD` | `true` / `4` / `0.35` | — |
| `SESSION_MAX_TURNS` / `SESSION_TTL_HOURS` | `10` / `24` | 契约 §2.3 的建议值 |

两点注意：

- `.gitignore` 里 `.env` 规则**不带前导斜杠**，所以 `agent-service/.env` 天然被忽略；
  `data/` 同理。不用改 `.gitignore`。
- **先别动 Java 的 `agent.enabled`**（`application.yml` 里现在是 `true`）。
  服务没起来时前端 @bot 会显示「AI 助手暂时不可用」—— 这是**预期行为**（Java 熔断 + 降级）。

---

## 9. 自测

```powershell
# 1. 探活
curl.exe -s -H "X-Agent-Key: change-me" http://127.0.0.1:8001/v1/health

# 2. 摘要（PowerShell 里 JSON 内层引号要转义）
curl.exe -s -X POST http://127.0.0.1:8001/v1/summarize `
  -H "Content-Type: application/json" -H "X-Agent-Key: change-me" `
  -d '{\"trace_id\":\"a1b2c3d4e5f60718\",\"user_id\":1,\"note_id\":3,\"title\":\"Redis 笔记\",\"content\":\"正文……\"}'

# 3. SSE（-N 关掉 curl 自身缓冲，才能看出是不是逐块返回）
curl.exe -N -X POST http://127.0.0.1:8001/v1/chat `
  -H "Content-Type: application/json" -H "X-Agent-Key: change-me" `
  -d '{\"trace_id\":\"a1b2c3d4e5f60718\",\"user_id\":1,\"couple_id\":11,\"session_id\":\"user1\",\"message\":\"你好\",\"scope\":\"personal\",\"scope_id\":1}'
```

更省事：开 **http://127.0.0.1:8001/docs** 点着测。

**不需要 key 也能跑的两层测试**：

```powershell
python tests/test_core.py        # 纯逻辑，零依赖
python tests/test_contract.py    # 协议层（MockTransport）+ 契约层（testclient 替身）
```

---

## 10. 完成定义（Done）

- [ ] 4 个接口都能在 `/docs` 里看到，鉴权生效
- [ ] 不带 `X-Agent-Key` → **401**
- [ ] 参数校验失败 → **400**（不是 422）
- [ ] `/v1/chat` 首字节 < 2s，结束时收到 `data: [DONE]`
- [ ] `/v1/summarize` 喂 20 万字不崩，60s 内返回非空摘要
- [ ] `/v1/ingest` 对同一 `biz_id` upsert 两次，Qdrant 里**仍只有 1 个 point**
- [ ] `/v1/ingest` delete 一个不存在的 id → 返回 200（幂等）
- [ ] 内部错误 → 返回 **5xx**（不是 200 + `code != 0`）
- [ ] `services/` 里没有任何 `import fastapi`
- [ ] 代码里**没有**任何回退式嵌入降级（§6 第 3 条）
- [ ] `EMBED_DIM` 与模型实际维度一致，且建 collection 时是显式传值
- [ ] 生产代码里**没有 mock 开关或分支**
- [ ] `.env.example` 完整、`.env` 与 `.venv` 已被 git 忽略
- [ ] `tests/test_core.py` + `tests/test_contract.py` 全绿
- [ ] 前端实测：@bot 打字机正常、笔记「生成摘要」正常

---

## 11. 实现现状

### 11.1 已完成

| 项 | 说明 |
|---|---|
| 四个接口 | `/v1/health`、`/v1/ingest`、`/v1/chat`(SSE)、`/v1/summarize` |
| 三个自研客户端 | `llm_client`（httpx + 自解 SSE）、`embedding_client`（httpx，无回退）、`vector_store`（qdrant-client 直连） |
| 向量检索 | 检索结果拼进 prompt，失败降级为空上下文 |
| 多轮会话 | SQLite，按 `u{user_id}:{session_id}` 隔离，TTL 清理 |
| ingest 审计 | `ingest_log` 表，便于人工补同步 |
| 降级策略 | 按能力降级、不 fail-fast；单次请求失败返回非 200 |
| 框架解绑 | **已移除 hello-agents**，依赖只剩 fastapi / httpx / qdrant-client 等 |
| mock 移除 | **生产代码无 mock 分支**；测试替身只存在于 `tests/` |

### 11.2 验证程度

| 验证项 | 方式 | 结果 |
|---|---|---|
| 语法 | 全量 `ast.parse` | 全部通过 |
| 纯逻辑 | `tests/test_core.py` | 17 / 17 |
| **客户端协议层** | `tests/test_contract.py`（`httpx.MockTransport`） | 覆盖真实 SSE 解析、错误翻译、维度守卫 |
| **接口契约层** | `tests/test_contract.py`（`testclient` + 替身） | 401 / 400 / SSE / 幂等 / 检索 / 多轮会话 |
| **合计** | | **58 / 58 通过** |
| 框架解绑 | 用 import 拦截器屏蔽 `hello_agents` 后重跑 | **58 / 58 仍通过** → 确认无残留依赖 |
| **真实外部依赖** | ❌ **未验证** | 没有 DeepSeek / DashScope key，Qdrant 未联调 —— 首次真跑可能需要微调 |

验证环境：Python 3.12.5 + fastapi 0.141.1 + httpx 0.28.1 + qdrant-client 1.9.0。

### 11.3 待办

- **Neo4j 知识图谱**：实体/关系抽取 + 多跳检索，完全未实现。
- **Qdrant 真实读写联调**：协议层与契约层测试用的是替身，真实建 collection、
  写入、检索的路径尚未跑过真库。
- **`qdrant-client` 版本兼容**：代码同时兼容 1.9 的 `search()` 与 ≥1.10 的
  `query_points()`，但只在 1.9.0 上验证过。

---

## 12. 相关文档

| 文档 | 关系 |
|---|---|
| `docs/agent-api.md` | **接口契约，唯一基准（只约定线格式）** |
| `agent-service/README.md` | 运行手册：启动、配置、降级矩阵、排错、测试 |
| `docs/deploy.md` | §3.1 Python Agent 部署 |
| `docs/project-overview.md` | 全项目结构与数据流 |
| [hello-agents on PyPI](https://pypi.org/project/hello-agents/) | 曾用框架（已移除）。注意该页面描述的是 1.0.1，与曾用的 0.2.9 API 不同 |
