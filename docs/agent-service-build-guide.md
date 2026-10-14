# Agent 服务施工指南（hello-agents 0.2.9 版）

> 用途：在 `couple-site/agent-service/`（Python / FastAPI）搭建 Agent 服务的**施工顺序 + 验收清单**。
> 技术底座：**`hello-agents==0.2.9`**（PyPI 包名 `hello-agents`；源码已核实，位于
> `D:\project\agent\hello-agents\venv\Lib\site-packages\hello_agents`）
> 契约基准：**`docs/agent-api.md`**（Java 侧已实现，Python 侧按它实现；冲突以代码为准）。
> 本文件是「怎么做」，`agent-api.md` 是「必须长什么样」。两者不重复。
> 状态：**核心部分已实现**，代码在 `agent-service/`（四接口 + 向量检索 + SQLite 多轮会话 + 离线 mock）。
> 本文件保留原始施工顺序与踩坑记录，「该怎么做 / 为什么」依然成立；
> **实际代码与本文件的差异见 §12 实现现状**。Neo4j 知识图谱尚未实现。
> 选型说明：0.2.9 与教程章节对齐、发布半年较稳定。1.0.1（2026-10-06 发布）虽在 README 中声称带
> SSE / 会话持久化 / GraphRAG，但**尚未读源码验证**，本次不采用。

---

## 1. 先搞清楚这个服务站在哪

```
浏览器  ──→  Java (Spring Boot, :8080)  ──→  Python (FastAPI, :8001)
                  ↑ 唯一调用方，内网        ↑ 不暴露公网、不直接服务前端
```

三条必须记住的边界：

1. **Python 不碰 MySQL。** 它拿不到业务库，所有业务数据都是 Java 通过 `/v1/ingest` 主动推过来的文本。
2. **Python 只有 Java 一个客户。** 不需要考虑多租户鉴权框架、CORS、限流（Java 已做）。
3. **Java 已经帮你做了这些，你不用实现**：超时（对话 3s/30s/35s、摘要 3s/60s）、熔断（连续 5 次失败 → 30s）、失败降级文案、重试（ingest 3 次，间隔 5s/30s）、事务提交后触发。你只管把接口做对。

### 数据流

```
用户在页面点「生成摘要」
  Java /api/notes/{id}/summary → POST /v1/summarize → 返回文本 → Java 存 note.summary_ai
用户在 @bot / /ai 提问
  Java /api/agent/chat         → POST /v1/chat (SSE)  → Java 转发 → 前端打字机
动态/相册/笔记 增删改
  Java 事务提交后异步           → POST /v1/ingest       → Python 写/删 Qdrant 向量
```

**关键区分**：`chat` / `summarize` 是用户当场等结果的；`ingest` 是后台同步，慢一点无所谓。这个区别决定了施工顺序（见 §4）。

### Python 需要的外部依赖

| 依赖 | 用途 | 形态 | 由谁调用 |
|---|---|---|---|
| DeepSeek（`deepseek-chat`） | 对话 + 摘要 | HTTP API，需 key | `HelloAgentsLLM` |
| DashScope `text-embedding-v3` | 文本向量化 | HTTP API，需 key | `DashScopeEmbedding` |
| Qdrant | 向量库 | 本地服务 `:6333` | `QdrantVectorStore` |
| Neo4j | 知识图谱 | 本地服务 `:7687` | `memory/storage/neo4j_store.py` |
| SQLite | 会话历史 + 审计日志 | **本地文件，不用装服务** | 你自己写（框架没有） |

---

## 2. 目录结构

```
agent-service/
├── .venv/                     # 虚拟环境（已在 .gitignore）
├── .env                       # 真实密钥（已在 .gitignore）
├── .env.example               # 模板（进 git）
├── requirements.txt
├── README.md                  # 启动方式 + 实测依赖版本
├── run.py                     # 本地入口，方便 IDE 断点调试
├── tests/
│   └── test_core.py           # 纯逻辑单测：namespace、point id、截断
└── app/
    ├── __init__.py
    ├── main.py                # FastAPI 实例 + lifespan + 异常处理 + 挂路由
    ├── config.py              # pydantic-settings 读 .env
    ├── logging_conf.py        # 日志格式（含 trace_id）
    ├── core/
    │   ├── errors.py          # 统一异常 + Result 信封 + 异常处理器
    │   ├── names.py           # namespace / point id 生成的唯一出处
    │   └── text.py            # 截断 / 清洗（分块用框架的 DocumentProcessor）
    ├── schemas/               # Pydantic 模型，与 agent-api.md 字段一一对应
    │   ├── common.py          # Result 信封
    │   ├── ingest.py
    │   ├── chat.py
    │   └── summarize.py
    ├── api/
    │   ├── deps.py            # X-Agent-Key 鉴权依赖
    │   └── routes/
    │       ├── health.py
    │       ├── ingest.py
    │       ├── chat.py
    │       └── summarize.py
    ├── services/              # 业务逻辑：本层禁止 import fastapi
    │   ├── llm_client.py      # 薄封装 HelloAgentsLLM（含 mock + 异常翻译）
    │   ├── embedding_client.py# 薄封装 DashScopeEmbedding（含 mock + 维度自检）
    │   ├── vector_store.py    # 薄封装 QdrantVectorStore（含 mock + 维度自检）
    │   ├── retrieval.py       # 检索（失败降级为空上下文）
    │   ├── ingest_service.py
    │   ├── chat_service.py
    │   ├── summarize_service.py
    │   ├── session_store.py   # 多轮会话（框架没有，必须自己写）
    │   ├── graph_store.py     # Neo4j 读写（⏳ 下一轮）
    │   ├── graph_extract.py   # 实体/关系抽取（⏳ 下一轮）
    │   └── types.py           # 内部数据类（避免循环 import）
    ├── db/
    │   └── sqlite_store.py
    └── prompts/               # 提示词当代码管，别散落在业务逻辑里
        ├── chat.py
        └── summarize.py
```

**分层纪律**（新手最容易混掉的地方）：

- `api/routes/` 只做四件事：收参数 → 交给 Pydantic 校验 → 调 service → 包 `Result`。**不写业务**。
- `services/` 是纯业务，**不 import fastapi**。好处：能写单测、能在脚本里直接复用、以后换 Web 框架不用改。
- `db/` 只负责包外部客户端，把「连不上 / 超时 / 认证失败」翻译成统一异常，别让 SDK 原始异常漏到路由层。
- **框架对象只在 `services/` 里出现**，`api/` 层不认识 `HelloAgentsLLM`。这样将来换框架只改 service。

---

## 2.5 hello-agents 0.2.9 承担哪一层

> 版本**必须写死 `==0.2.9`**。1.0.0 改过顶层导出与模块结构，用 `>=` 会在某次 `pip install` 后静默炸掉。

### 它替你做的事

| 能力 | 组件 | 你原本要手写的部分 |
|---|---|---|
| LLM 调用 | `HelloAgentsLLM`（`invoke()` / `stream_invoke()` / `think()`） | DeepSeek 客户端封装 |
| 嵌入 | `DashScopeEmbedding`（默认 `text-embedding-v3`） | DashScope 客户端封装 |
| 向量库 | `QdrantVectorStore` + `QdrantConnectionManager` | Qdrant 读写封装 |
| 图存储 | `memory/storage/neo4j_store.py` | Neo4j driver 封装 |
| 文本分块 | `DocumentProcessor` / `Document` / `DocumentChunk` | 分块算法 |
| 工具 | `ToolRegistry` / `ToolChain` / `AsyncToolExecutor` | 工具注册 |
| Agent 范式 | `SimpleAgent` / `FunctionCallAgent` / `ReActAgent` / `ReflectionAgent` / `PlanAndSolveAgent` | ReAct 循环 |

### 它**不**替你做 —— 而这些恰好是「Python 服务」的主体

| 缺口 | 已核实的事实 |
|---|---|
| **HTTP 层完全没有** | 全包搜不到 `fastapi` / `uvicorn` / `StreamingResponse` / `text/event-stream`。路由、`{code,message,data}` 信封、`X-Agent-Key` 鉴权、SSE 格式全部自己写 |
| **没有会话持久化** | `document_store.py` 里的 `sqlite3` 是存 RAG 文档的，不是会话历史 |
| **没有 GraphRAG 抽取** | `neo4j_store.py` 只是存储层；实体/关系抽取要自己写 |
| **没有可观测性** | trace / 运行轨迹要自己接 |

### 架构

```
FastAPI 路由层          ← 你写（本文档 §3、§5）
   ↓
service 层（薄封装）      ← 你写：契约字段 ↔ 框架调用 的翻译
   ↓
hello-agents 0.2.9      ← 承担 LLM / 嵌入 / 向量库 / 分块
   ↓
DeepSeek · DashScope · Qdrant · Neo4j
```

分层意义：**契约层与框架完全解耦**。哪天换 1.0.1 或弃用框架，只改 service 层，路由和 Java 侧一行不动。

### 已核实的框架行为（读源码确认，不是推测）

| 项 | 事实 | 对你意味着什么 |
|---|---|---|
| 环境变量 | `LLM_MODEL_ID` / `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_TIMEOUT`（默认 60s） | DeepSeek：`LLM_BASE_URL=https://api.deepseek.com` + `LLM_MODEL_ID=deepseek-chat` |
| Qdrant 连接缓存 | `QdrantConnectionManager._instances` 的 key 是 **`(url, collection_name)`** | 「一个 namespace 一个 collection」天然成立：`get_instance(collection_name=...)` 即得独立 store，正好对上契约 §6 |
| DashScope 嵌入 | 传了 `base_url` 就走 **OpenAI 兼容 REST**（内部用 `requests`），不 import `dashscope` SDK | 不用装 `dashscope`；设 `base_url=https://dashscope.aliyuncs.com/compatible-mode/v1` |
| 嵌入构造 | `DashScopeEmbedding.__init__` 末尾会**发一次真实网络请求**探测维度 | 网络 / key 有问题时**构造函数即抛异常**；必须放 lifespan 并容错，别在模块顶层实例化 |
| 嵌入回退链 | `create_embedding_model_with_fallback()` = `dashscope → local → tfidf` | ⚠️ **禁用**，见 §6 第 1 条 |
| 同步性 | `stream_invoke()` / `stream_run()` 返回 `Iterator[str]`；`encode()` 用 `requests`；Qdrant / Neo4j 客户端也同步 | 全部同步，路由写法见 §6 第 3 条 |
| openai 版本 | `Requires-Dist: openai<2.0.0,>=1.0.0` | 锁到 1.x；自己别再写 3.x 风格代码，统一走 `HelloAgentsLLM` |
| 工具调用 | `SimpleAgent` 靠**文本解析**（`_parse_tool_calls(text)`）；`FunctionCallAgent` 走原生 | 需要工具时优先用 `FunctionCallAgent` |
| Python | `Requires-Python >=3.10` | 本机 3.12.5 满足 |

### 直接复用框架 vs 仍然要包一层

**直接复用框架的类**（不要自己重写）：

| 能力 | 用框架的 |
|---|---|
| LLM | `HelloAgentsLLM`（`invoke` / `stream_invoke`） |
| 嵌入 | `DashScopeEmbedding` |
| 向量库 | `QdrantVectorStore` / `QdrantConnectionManager` |
| 分块 | `DocumentProcessor`（实现里摘要场景另写了 `core/text.split_for_summary`，因为它按段落边界切、更好控） |

**仍然包了一层薄封装** —— `services/{llm_client,embedding_client,vector_store}.py`。
这不是重复造轮子，而是为了三件框架不提供的事：

1. **mock 开关**：没有 key / 没有网络时先验证 Java 侧协议；
2. **维度自检**：框架 `vector_size` 默认 384，且维度不符时只 warning + 丢弃（静默丢数据）；
3. **异常翻译**：把框架原始异常转成统一的 `AgentError`（HTTP 状态码 + `body.code`）。

**必须从零写**：`main.py`、`config.py`、`logging_conf.py`、整个 `api/`、`schemas/`、
`core/{errors,names,text}.py`、`services/session_store.py`、三个业务 service、`db/sqlite_store.py`。

### 开工前先读这 3 个签名

不要凭猜写代码，先打开源码确认：

1. `QdrantVectorStore.add_vectors(...)` —— **收向量还是收文本？** 如果它内部也调嵌入，别再自己 `encode()` 一遍（会重复计费，还可能用错嵌入模型）。
2. `QdrantVectorStore.search_similar(...)` —— 返回结构是什么？**是否支持 payload 过滤**（你按 `scope_id` / `user_id` 做隔离检索要用到）。
3. `HelloAgentsLLM.stream_invoke(...)` —— chunk 粒度是 token 还是句子？这决定你 SSE 的 flush 频率。

---

## 3. FastAPI 最小必要知识

只列你真正用得上的，够写完这个服务。

### 3.1 一个接口长什么样

```python
# app/api/routes/summarize.py
from fastapi import APIRouter, Depends
from app.api.deps import verify_key
from app.core.errors import ok
from app.schemas.summarize import SummarizeRequest
from app.services import summarize_service

router = APIRouter(prefix="/v1", tags=["summarize"])

# 注意：普通 def，不是 async def —— 因为 service 内部是同步调用
@router.post("/summarize", dependencies=[Depends(verify_key)])
def summarize(req: SummarizeRequest):
    summary = summarize_service.summarize(req)
    return ok({"summary": summary})
```

要点：
- 参数标成 Pydantic 模型 → FastAPI 自动解析 JSON + 校验，**你不用手写 if 判空**。
- `dependencies=[Depends(verify_key)]` → 鉴权，挂在 `APIRouter` 上对该路由所有接口生效。
- 函数名随意，对外路径完全由装饰器决定。

### 3.2 怎么启动

```powershell
cd agent-service
.\.venv\Scripts\Activate.ps1
uvicorn app.main:app --host 127.0.0.1 --port 8001 --reload
```

- `app.main:app` = 「模块 `app/main.py` 里的变量 `app`」。
- `--reload` 只在开发用（改代码自动重启），生产去掉。
- 自带交互文档 **http://127.0.0.1:8001/docs** —— 这是你调试的主力工具，比 curl 省事得多。

### 3.3 `app/main.py` 骨架

```python
from contextlib import asynccontextmanager
from fastapi import FastAPI

from app.api.routes import chat, health, ingest, summarize
from app.core.errors import register_exception_handlers


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 启动阶段：建 SQLite 表；按 namespace 预建 Qdrant collection
    # ⚠️ DashScopeEmbedding / QdrantVectorStore 在这里实例化，并 try/except
    ...
    yield
    # 关闭阶段：关连接、flush


app = FastAPI(title="couple-site agent", version="1.0.0", lifespan=lifespan)
register_exception_handlers(app)
for m in (health, ingest, chat, summarize):
    app.include_router(m.router)
```

**别用 `@app.on_event("startup")`**，新版 FastAPI 已废弃，用 `lifespan`。

**别在模块顶层实例化 `DashScopeEmbedding`** —— 它的构造函数里有一次真实网络请求（见 §2.5），失败会让服务直接起不来。

### 3.4 SSE 流式怎么写

```python
import json
from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

def event_stream(req):
    # chat_service.stream 内部走 HelloAgentsLLM.stream_invoke，返回同步 Iterator[str]
    for piece in chat_service.stream(req):
        yield f'data: {json.dumps({"content": piece}, ensure_ascii=False)}\n\n'
    yield "data: [DONE]\n\n"

@router.post("/chat")
async def chat(req: ChatRequest, _=Depends(verify_key)):
    return StreamingResponse(
        event_stream(req),                      # 同步生成器可以直接传
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
```

四个必须记住的点：

1. **两个换行 `\n\n` 才表示一个 SSE 事件结束**，一个 `\n` 不够，Java 收不到完整事件。
2. **`data: [DONE]` 必须发**。Java 靠它结束流；不发的话前端一直转圈、消息不落库。
3. **`ensure_ascii=False`**，否则中文变成 `\u4f60\u597d`（Java 能解析，但抓包和日志没法看）。
4. **同步生成器直接传给 `StreamingResponse` 是安全的** —— Starlette 会内部用 `iterate_in_threadpool` 包一层，不会阻塞事件循环。危险的是在 `async def` 的函数体里**直接调用**同步函数（见 §6 第 3 条）。

### 3.5 统一响应信封

`app/core/errors.py`：

```python
from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse


def ok(data=None, message="success"):
    return {"code": 0, "message": message, "data": data}


class AgentError(Exception):
    """业务异常：带 HTTP 状态码和 body.code，由处理器统一转成信封。"""
    def __init__(self, message: str, status_code: int = 500, code: int = 500):
        self.message, self.status_code, self.code = message, status_code, code


def register_exception_handlers(app):
    @app.exception_handler(AgentError)
    async def _agent_error(request: Request, exc: AgentError):
        return JSONResponse(status_code=exc.status_code,
                            content={"code": exc.code, "message": exc.message, "data": None})

    @app.exception_handler(RequestValidationError)
    async def _invalid(request: Request, exc: RequestValidationError):
        # 关键：默认 422，Java 会当成失败并重试 3 次；转成 400 更符合契约
        return JSONResponse(status_code=400,
                            content={"code": 400, "message": "参数错误", "data": None})

    @app.exception_handler(Exception)
    async def _unhandled(request: Request, exc: Exception):
        return JSONResponse(status_code=500,
                            content={"code": 500, "message": "内部错误", "data": None})
```

**为什么必须有这个**：契约 §1.2 明确写了，Java 对 `/v1/ingest` **只看 HTTP 状态码，不看 `body.code`**。所以内部出错时你**必须返回非 200**，否则 Java 认为成功、不重试，数据就静默丢了，而且没有任何报错。

### 3.6 鉴权依赖

```python
# app/api/deps.py
from fastapi import Header
from app.config import settings
from app.core.errors import AgentError

async def verify_key(x_agent_key: str = Header(default="")):
    # Header 名 x_agent_key 会自动映射成请求头 X-Agent-Key（大小写不敏感、下划线转横线）
    if x_agent_key != settings.agent_key:
        raise AgentError("invalid agent key", status_code=401, code=401)
```

注意：`AGENT_KEY` 是**你自己定义的**（与 Java `agent.key` 对齐），框架不读它。

---

## 4. 施工顺序（每一步都能跑、都能验）

**原则：先打通 Java 已经做好的 UI，再补后台同步。** Java 侧 `/api/agent/chat`（SSE）和 `/api/notes/{id}/summary` 都已经写完了，你这边一通，前端立刻就能用 —— 反馈最快，也最容易定位问题。

| 步 | 做什么 | 完成标志 |
|---|---|---|
| 0 | venv + `requirements.txt` + `.env` | `pip list` 里有 `hello-agents 0.2.9` / fastapi / uvicorn |
| 1 | `main.py` + `/v1/health` | `curl.exe` 返回 `{"code":0,...}` |
| 2 | `config.py`（读 `LLM_*`）+ `deps.verify_key` | 不带 header → 401；带对了 → 200 |
| 3 | `/v1/chat` **假流式**（不调框架，固定切成 5 片，每片 `sleep(0.2)`） | 前端 @bot 出现打字机效果，回答正常落库 |
| 4 | 换成 `HelloAgentsLLM.stream_invoke()` 真流式 | 前端能看到真实回答 |
| 5 | `/v1/summarize` 用 `HelloAgentsLLM.invoke()`（先直接截断，不做 map-reduce） | 笔记页点「生成摘要」出结果，卡片显示 AI 摘要 |
| 6 | `/v1/ingest`：`DashScopeEmbedding.encode()` + `QdrantVectorStore` | 发一条假 ingest，能在 Qdrant 里查到点 |
| 7 | `session_store`（自己写 SQLite 多轮上下文） | 连问两句，第二句能引用第一句 |
| 8 | `retrieval`：`QdrantVectorStore.search_similar()` 结果拼进 prompt | 问一个只有你笔记里才有的内容，回答正确 |
| 9 | Neo4j 图谱（自己写抽取 + 多跳） | 最后做，最复杂，**不做也不影响前面全部功能** |

### 为什么第 3 步要先做「假流式」

它能让你在完全不知道 LLM 怎么调的情况下，先把 **Java ↔ Python 的协议**验证对：字段名 `content`、结束标记 `[DONE]`、换行格式、中文编码、首字节延迟。

协议错误和模型错误是最容易混在一起的两类 bug。先用假数据把协议这一侧钉死，后面调 LLM 时出问题就一定是 LLM 的问题 —— 这一步能省掉大量瞎猜时间。

---

## 5. 每个接口的验收标准

### GET `/v1/health`

- 请求头 `X-Agent-Key`。
- Java **不轮询**它（只靠调用结果判定），供人工和容器存活检查用。
- 注意：`verify_key` 若挂在全局 `dependencies` 上，这个接口也会要 key。Java 不调它无所谓，但你自己 curl 时别忘了带。

### POST `/v1/ingest`

必做：

- 字段：`trace_id, biz_type, biz_id, content, scope, scope_id, action`（契约 §1.1）
- `action=upsert`：`content` 必填 → `DashScopeEmbedding.encode()` → `QdrantVectorStore.add_vectors(...)`；**同 `biz_type+biz_id` 必须覆盖旧向量**，不能越积越多
- `action=delete`：`QdrantVectorStore.delete_vectors(ids)` 是**按 point id 删**，所以删除和写入必须共用同一套确定性 id
- `action=delete` 且目标不存在 → **也要返回成功**（幂等，契约 §1.3）
- namespace 映射（**只写在一个地方**，即 `core/names.py`，别在四个文件里各拼一遍字符串）：
  - `moment` / `photo` + `scope=couple` → `moment_c{scope_id}`
  - `note` + `scope=personal` → `note_u{scope_id}`
  - 用 `QdrantConnectionManager.get_instance(collection_name=...)` 取 store；它按 `(url, collection_name)` 缓存，天然支持一 namespace 一 collection
- **point id 必须确定性**：如 `hash(f"{biz_type}:{biz_id}")`（或直接用该字符串）。这样重复 upsert 覆盖同一个点，delete 也能精确命中
- 向量 payload 至少存：`biz_type, biz_id, scope, scope_id, content, created_at`
- 失败 → 返回 **4xx/5xx**（原因见 §3.5）

注意 Java 的重试行为（契约 §1.2）：失败会重试 3 次，实际间隔 **5s / 30s**。所以：

- 别做超过 ~30s 的事，否则 Java 重试期间你可能重复写入 —— 幂等键要能兜住。
- Java 侧 ingest **没有配置任何超时**（裸 `RestTemplate`），你 hang 住会白占 Java 的异步线程。给所有外部调用（嵌入、Qdrant）显式设超时。

### POST `/v1/chat`（SSE）

必做：

- 事件格式 `data: {"content":"增量"}\n\n`，键名是 **`content`**
  - ⚠️ **不是 `delta`**。`delta` 是 Java 转发给**前端**时用的名字（契约 §2.2 有对照表）。用错 Java 会解析出空字符串。
- 结束发 `data: [DONE]\n\n`
- 出错发 `data: {"error":"人类可读信息"}\n\n` 然后结束
- 文本来源：`HelloAgentsLLM.stream_invoke(messages)`；需要工具时用 `FunctionCallAgent.stream_run(...)`（别用 `SimpleAgent`，它的工具调用靠文本解析）
- `session_id` / `scope` / `scope_id` 由 Java 传，你按 `user_id + session_id` 存历史（契约 §2.3 建议 10 条 / 24h 过期）—— **0.2.9 没有会话存储，这层你写**
- **首字节要在 2s 内产出**（契约 §2.3：Java 读超时 30s 是从首字节后持续读，所以不能憋很久不出字）
- 共享问题查 `moment_c*`，个人问题查 `note_u{userId}`（契约 §6）

### POST `/v1/summarize`

必做：

- 文本来源：`HelloAgentsLLM.invoke(messages)`（非流式，一次拿全文）
- 响应 `data.summary` **非空且非空白** —— Java 会校验，空白视为失败（契约 §3.2）
- `content` 最长 **20 万字**，**Python 自己负责截断 / 分段 map-reduce**，Java 不做裁剪（契约 §3.1）
- 建议输出 100–300 字
- 预算 60s
- 失败 → 4xx/5xx。**Java 失败不写库**（契约 §3.3），所以你返回空串等于让用户白等
- `title` 可以用也可以忽略，用在 prompt 里能让摘要更贴主题
- 提示词放 `app/prompts/summarize.py`

---

## 6. 一定会踩的坑

| # | 坑 | 现象 | 解法 |
|---|---|---|---|
| 1 | **用了 `create_embedding_model_with_fallback()`** | DashScope 一旦失败就静默降级到 `TFIDFEmbedding`（1000 维），往同一 collection 里写入不同维度向量 —— **检索静默失效，全程不报错** | **禁用该函数**。直接 `DashScopeEmbedding(model_name="text-embedding-v3", api_key=..., base_url=...)`，让它响亮地失败 |
| 2 | 嵌入模型实例化时机不对 | 服务启动即崩（构造函数里有真实网络请求） | 在 lifespan 里实例化 + `try/except`；绝不放模块顶层 |
| 3 | 同步调用没隔离 | 一个请求执行期间，其他请求全部无响应 | `/v1/chat`：同步生成器直接交给 `StreamingResponse`（Starlette 自动线程池化）。`/v1/summarize`、`/v1/ingest`：路由用**普通 `def`**。在 `async def` 里直接调 `llm.invoke()` / `encode()` 会卡死整个服务 |
| 4 | 必须在 `async def` 里调同步函数 | 同上 | 用 `await run_in_threadpool(fn, ...)`（`from fastapi.concurrency import run_in_threadpool`） |
| 5 | 校验失败返回 422 | Java 收 422 → 判为失败 → 重试 3 次 | 加 `RequestValidationError` 处理器转 400（§3.5） |
| 6 | 忘发 `[DONE]` | 前端一直转圈、消息不落库 | 生成器末尾无条件发 |
| 7 | SSE 被缓冲 | 前端等很久才一次性出现全部文字 | `media_type="text/event-stream"` + `X-Accel-Buffering: no`；别在 yield 前 await 大块工作 |
| 8 | 中文变 `\uXXXX` | 日志 / 抓包不可读 | `json.dumps(..., ensure_ascii=False)` |
| 9 | Pydantic v2 语法 | `@validator` 直接报错 | 用 `@field_validator` + `model_config = ConfigDict(...)` |
| 10 | Qdrant collection 不存在 | 第一次 ingest 报 404 | 启动时按 namespace 预建，或写前先 `collection_exists` |
| 11 | point id 用了随机 uuid | 改一次笔记就多一条向量，越积越多 | 用**确定性** id（`hash(f"{biz_type}:{biz_id}")`） |
| 12 | 版本写成 `>=0.2.9` | 某天 `pip install` 后 API 全变，静默炸 | 写死 `hello-agents==0.2.9` |
| 13 | 装了 `[rag]` / `[memory]` extras | 多下几个 GB（torch / transformers / spacy） | 只装基础包 + `qdrant-client neo4j` |
| 14 | 注册了 `terminal_tool` | 有用户输入的服务 = 开放任意命令执行 | **别注册** `tools/builtin/terminal_tool.py` |
| 15 | PowerShell 里的 `curl` | `-H` 报错、行为怪异 | PowerShell 的 `curl` 是 `Invoke-WebRequest` 的别名 → 用 **`curl.exe`**，或 `Invoke-RestMethod` |
| 16 | 依赖版本和教程对不上 | 照 1.x 教程写，实际装的是 3.x | 见 §7，装完先记版本再写代码 |
| 17 | **在 SSE 生成器里用 ContextVar token 做 set/reset** | `iterate_in_threadpool` 对**每一次 `next()`** 单独发起线程调用，每一步可能落在不同线程、拿到不同 context 副本；跨 Context 的 `reset()` 抛 `ValueError: Token ... was created in a different Context`。**该异常发生在响应已开始推送之后，异常处理器兜不住，整个 SSE 请求 500** | 流式路径改用「只设置、不恢复，每一步重设一次」（`logging_conf.set_trace`）；只有非流式路径（同步路由 / lifespan）才用 `with bind_trace(...)`。静态检查发现不了，必须真跑 |
| 18 | 在受限环境用 `tempfile.mkdtemp()` 放 SQLite | `sqlite3` 报 `unable to open database file`（该目录的 ACL 与普通 `mkdir` 不同） | 测试脚本改用普通 `mkdir` 的目录，见 `tests/smoke_api.py` |
| 19 | 只做语法检查就以为写完 | 语法全过、导入全过，但 SSE 路径照炸 | **必须真跑**：`tests/smoke_api.py` 在 mock 模式下覆盖全部四个接口 |

---

## 7. 依赖与版本

`requirements.txt`：

```
hello-agents==0.2.9
fastapi>=0.115,<1.0
uvicorn[standard]>=0.30,<1.0
qdrant-client>=1.9
neo4j>=5.0
```

说明：

- **`hello-agents==0.2.9` 写死版本**，1.0.0 改过顶层导出与模块结构。
- 它会**自动带上** `openai<2` / `pydantic>=2` / `requests` / `python-dotenv` / `beautifulsoup4` / `numpy>=2` / `networkx` / `tiktoken` —— **不要重复声明**。
- **不要装 extras**：
  - `[rag]` = torch + transformers + sentence-transformers + markitdown + pypdf + pdfminer.six → **好几个 GB**，本方案完全不需要。
  - `[memory]` = qdrant-client + neo4j + **spacy** + scikit-learn → `spacy` 也不小。手工只补 `qdrant-client` 和 `neo4j` 即可。
- **DashScope 走 REST 模式时不需要 `dashscope` SDK**（传了 `base_url` 就改走 `requests`）。
- **不要声明用不到的依赖**（上一份草稿声明了 `tenacity` 却从头到尾没 import 一次）。
- `python-multipart` 不需要（本服务全是 JSON，无表单上传）。

**装完立刻做一次版本存档**（这是你后面排查「教程对不上」的唯一依据）：

```powershell
python -c "import hello_agents, fastapi, uvicorn, openai, pydantic, qdrant_client, neo4j; print('hello-agents', hello_agents.__version__); print('fastapi', fastapi.__version__); print('openai', openai.__version__); print('pydantic', pydantic.VERSION); print('neo4j', neo4j.__version__)"
```

把输出抄进 `agent-service/README.md`。

注意 `openai` 会被 hello-agents 锁在 **1.x** —— 之前全局安装时误装到 `3.22.1`，正是因为当时没有这个约束。这也是本次选型顺带解决掉的一个隐患。

接着做**最小调用验证**，每个能力各跑通一次，再写业务：

```python
import os
os.environ.update({
    "LLM_BASE_URL": "https://api.deepseek.com",
    "LLM_MODEL_ID": "deepseek-chat",
    "LLM_API_KEY": "sk-...",
})

from hello_agents import HelloAgentsLLM
with HelloAgentsLLM() as llm:            # 先确认能连
    print(llm.invoke([{"role": "user", "content": "说一句你好"}]))
    for chunk in llm.stream_invoke([{"role": "user", "content": "数到五"}]):
        print(chunk, end="", flush=True)  # 确认流式粒度

from hello_agents.memory.embedding import DashScopeEmbedding
emb = DashScopeEmbedding(                 # ⚠️ 这一步就会发网络请求
    model_name="text-embedding-v3",
    api_key="sk-...",
    base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
)
print(len(emb.encode("测试")))            # 确认维度（v3 默认 1024）

from qdrant_client import QdrantClient
print(QdrantClient(url="http://localhost:6333").get_collections())  # 确认 Qdrant 在跑
```

---

## 8. `.env` 要与 Java 对齐的项

`agent-service/.env`（**独立于根目录那份**，根目录 `.env` 是给 Docker 的）：

| 变量 | 示例 | 由谁读 | 必须与谁一致 |
|---|---|---|---|
| `AGENT_KEY` | 随机串 | 你自己的 `config.py` | Java `agent.key`（根 `.env.example` 默认 `change-me`） |
| 监听端口 | `8001` | uvicorn 启动参数 | Java `agent.url`（`application.yml` 默认 `http://localhost:8001`） |
| `LLM_BASE_URL` | `https://api.deepseek.com` | **框架** | — |
| `LLM_MODEL_ID` | `deepseek-chat` | **框架** | — |
| `LLM_API_KEY` | `sk-...` | **框架** | — |
| `LLM_TIMEOUT` | `60` | **框架**（默认 60s） | — |
| `DASHSCOPE_API_KEY` | `sk-...` | 你自己传给 `DashScopeEmbedding` | — |
| `QDRANT_URL` | `http://localhost:6333` | 你自己 | — |
| `NEO4J_URI` / `NEO4J_USER` / `NEO4J_PASSWORD` | `bolt://localhost:7687` / `neo4j` | 你自己 | — |
| `SQLITE_PATH` | `./data/agent.db` | 你自己 | — |

两点注意：

- `.gitignore` 里 `.env` 规则**不带前导斜杠**，所以 `agent-service/.env` 天然被忽略；`data/` 同理。不用再改 `.gitignore`。
- **先别动 Java 的 `agent.enabled`**（`application.yml` 里现在是 `true`）。你服务没起来时，前端 @bot 会显示「AI 助手暂时不可用」之类的降级文案 —— 这是**预期行为**（Java 熔断 + 降级），不是 bug。等 `/v1/health` 通了再去测前端。

---

## 9. 自测（不经过 Java）

```powershell
# 1. 探活
curl.exe -s -H "X-Agent-Key: change-me" http://127.0.0.1:8001/v1/health

# 2. 摘要（注意 PowerShell 里 JSON 内层引号要转义）
curl.exe -s -X POST http://127.0.0.1:8001/v1/summarize `
  -H "Content-Type: application/json" -H "X-Agent-Key: change-me" `
  -d '{\"trace_id\":\"a1b2c3d4e5f60718\",\"user_id\":1,\"note_id\":3,\"title\":\"Redis 笔记\",\"content\":\"正文……\"}'

# 3. SSE（-N 关掉 curl 自身缓冲，才能看出是不是逐块返回）
curl.exe -N -X POST http://127.0.0.1:8001/v1/chat `
  -H "Content-Type: application/json" -H "X-Agent-Key: change-me" `
  -d '{\"trace_id\":\"a1b2c3d4e5f60718\",\"user_id\":1,\"couple_id\":11,\"session_id\":\"user1\",\"message\":\"你好\",\"scope\":\"personal\",\"scope_id\":1}'
```

更省事的办法：直接开 **http://127.0.0.1:8001/docs**，在 Swagger 里点着测。

`trace_id` 用 16 位小写十六进制（契约 §0），测试时随手编一个就行。

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
- [ ] 代码里**没有** `create_embedding_model_with_fallback` 调用
- [ ] `DashScopeEmbedding` 在 lifespan 里实例化且有容错，不在模块顶层
- [ ] 没有注册 `terminal_tool`
- [ ] `requirements.txt` 里 `hello-agents==0.2.9` 写死，且无 `[rag]` / `[memory]` extras
- [ ] `agent-service/.env.example` 完整，`.env` 与 `.venv` 已被 git 忽略
- [ ] `agent-service/README.md` 写清启动命令 + 实测依赖版本
- [ ] 前端实测：@bot 打字机正常、笔记「生成摘要」正常

---

## 11. 相关文档

| 文档 | 关系 |
|---|---|
| `docs/agent-api.md` | **接口契约，唯一基准** |
| `docs/deploy.md` | §3.1 Python Agent 部署 |
| `docs/project-overview.md` | 全项目结构与数据流 |
| [hello-agents on PyPI](https://pypi.org/project/hello-agents/) | 框架主页。⚠️ 该页面描述的是 **1.0.1**，与本方案选的 0.2.9 API 不同，别照着它写代码 |
| [HelloAgents on GitHub](https://github.com/jjyaoao/HelloAgents) | 源码；`learn_version` 分支对应教程 |

---

## 12. 实现现状（与本文档的差异）

代码已在 `agent-service/`，核心部分完成。**本文档的施工顺序与踩坑记录依然有效**，
实际实现有几处偏离，详见 `agent-service/README.md`：

| 项 | 本文档原计划 | 实际实现 | 原因 |
|---|---|---|---|
| 客户端封装 | 直接用框架类，不再包一层 | 仍包了 `llm_client` / `embedding_client` / `vector_store` 三个薄封装 | 需要 mock 开关、维度自检、异常翻译（见 §2.5） |
| 长文本分块 | 用框架的 `DocumentProcessor` | `core/text.split_for_summary()` | 需要落在段落边界 + 保证终止性，且可被零依赖单测覆盖 |
| 启动策略 | 未明确 | 按能力降级、不 fail-fast；但单次请求失败必须返回非 200 | Qdrant 没起不该把 `/v1/chat` 一起弄挂；而 ingest 必须让 Java 知道失败 |
| SQLite 表 | 只提会话历史 | 会话历史 + `ingest_log` 审计表 | 契约 §1.2 提到「可人工补同步」，需要可查记录 |
| Neo4j 图谱 | §4 第 9 步 | **未实现** | 本轮范围外，端口也未启动 |

### 12.1 已验证到什么程度

| 验证项 | 方式 | 结果 |
|---|---|---|
| 语法 | 全量 `ast.parse` | 37 个文件 0 错误 |
| 纯逻辑 | `tests/test_core.py` | 17 / 17 通过 |
| **契约端到端** | `tests/smoke_api.py`（mock 模式 + `TestClient`） | **36 / 36 通过**：401 / 400（非 422）、SSE 用 `content` 且以 `[DONE]` 结束、重复 upsert 后仍只有 1 个点、delete 幂等、refs 检索、多轮会话落库、维度守卫 |
| 框架 API 假设 | `tests/check_framework_api.py` | 29 / 29 成立 |
| **真实外部依赖** | ❌ **未验证** | 没有 DeepSeek / DashScope key，Qdrant 与 Neo4j 端口都是关的。**首次真跑仍可能需要在真实 key 下微调** |

验证环境：Python 3.12.5 + hello-agents 0.2.9 + fastapi 0.141.1 + pydantic 2.13.5。

### 12.2 跑测试时抓到的真实 bug（值得记住）

`tests/smoke_api.py` 一次跑出两个问题，其中一个是**代码真 bug**：

1. **SSE 跨 Context 的 ContextVar token** —— 用 `with bind_trace(...)` 包住整个 SSE 生成器，
   因为 `iterate_in_threadpool` 对每次 `next()` 单独发起线程调用，`reset()` 在别的 Context 里执行，
   抛 `ValueError: Token ... was created in a different Context`，`/v1/chat` 直接 500。
   修法：流式路径改用 `set_trace()`（只设置、不恢复，每步重设）。见 §6 第 17 条。
2. 测试脚本用 `tempfile.mkdtemp()` 放 SQLite，在受限环境下报 `unable to open database file`，
   改成普通 `mkdir` 的目录即可。见 §6 第 18 条。

> 结论：**语法通过 ≠ 能工作**。这个 bug 全量 AST 检查、导入检查都发现不了，
> 只有真的把请求打进去才会暴露。写完一定要跑 `tests/smoke_api.py`。
