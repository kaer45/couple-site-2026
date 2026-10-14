# couple-site Agent 服务（Python / FastAPI）

Java ↔ Python 契约的 **Python 侧实现**。

- **接口契约（唯一基准）**：[`../docs/agent-api.md`](../docs/agent-api.md)
- **施工说明与踩坑记录**：[`../docs/agent-service-build-guide.md`](../docs/agent-service-build-guide.md)

技术底座：[hello-agents](https://pypi.org/project/hello-agents/) **0.2.9**（Agent 框架）
+ FastAPI + Qdrant + SQLite。知识图谱（Neo4j 实体/关系抽取）尚未实现。

---

## 1. 快速开始

```powershell
cd agent-service

# 1) 虚拟环境
python -m venv .venv
.\.venv\Scripts\Activate.ps1          # Linux/macOS: source .venv/bin/activate

# 2) 依赖
pip install -r requirements.txt

# 3) 配置
copy .env.example .env                # Linux/macOS: cp
#    然后填 LLM_API_KEY(DeepSeek) / DASHSCOPE_API_KEY / AGENT_KEY

# 4) 起依赖服务（Qdrant 必需；Neo4j 本阶段不需要）
docker compose -f ..\docker-compose.yml up -d qdrant

# 5) 启动
python run.py
#    或： uvicorn app.main:app --host 127.0.0.1 --port 8001 --reload
```

启动后打开 **http://127.0.0.1:8001/docs** 可以直接点着测。

自检：

```powershell
curl.exe -s -H "X-Agent-Key: change-me" http://127.0.0.1:8001/v1/health
```

`data.degraded=true` 说明某个依赖没就绪，看 `data.components` 里哪一项 `ready=false`。

---

## 2. 离线 mock 模式（先验证协议，再调模型）

`.env` 里设 `AGENT_MOCK=true`，服务不会调用任何外部模型：

| 能力 | mock 行为 |
|---|---|
| 对话 | 固定文本切片的假流式 |
| 摘要 | 固定文本 |
| 嵌入 | 由文本 sha256 派生的**确定性**向量（同文本永远同向量） |
| 向量库 | 进程内内存实现（带余弦相似度检索） |

用途：**没有 API key 或没有网络时，先把 Java ↔ Python 的协议打通**——
字段名是不是 `content`、有没有发 `data: [DONE]`、中文有没有被转义、首字节延迟够不够。
协议问题解决了再去调模型，能省掉大量「到底是协议错还是模型错」的瞎猜。

```powershell
$env:AGENT_MOCK="true"; python run.py
```

⚠️ 仅供联调，生产必须 `false`。

---

## 3. 接口速查

| 方法 | 路径 | 说明 | 契约 |
|---|---|---|---|
| GET | `/v1/health` | 探活与依赖自检 | §4 |
| POST | `/v1/ingest` | 业务数据向量同步（幂等，支持 upsert / delete） | §1 |
| POST | `/v1/chat` | 对话，SSE 流式 | §2 |
| POST | `/v1/summarize` | 笔记摘要，普通 JSON | §3 |

全部要求请求头 `X-Agent-Key`（须与 Java 侧 `agent.key` 一致）。

**两个最容易搞错的点：**

1. `/v1/chat` 的增量字段名是 **`content`**，不是 `delta`。
   `delta` 是 Java 转发给**前端**时用的名字（Java 会在中间改名）。
2. `/v1/ingest` 失败时**必须返回非 200**。Java 对这个接口只看 HTTP 状态码、
   不看 `body.code`，返回 200 就等于告诉它「同步成功」，数据从此再也不会重试。

---

## 4. 配置项

见 `.env.example`（已带注释）。几个关键项：

| 变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_KEY` | `change-me` | 与 Java `agent.key` 一致，生产必改 |
| `HOST` / `PORT` | `127.0.0.1` / `8001` | Java 默认 `agent.url=http://localhost:8001` |
| `LLM_BASE_URL` / `LLM_MODEL_ID` / `LLM_API_KEY` | DeepSeek | 变量名由 hello-agents 直接读取，**不要改名** |
| `LLM_TIMEOUT` | `60` | 契约要求摘要整体 60s 内返回，别设更大 |
| `DASHSCOPE_API_KEY` | — | 嵌入模型 key |
| `EMBED_DIM` | `1024` | ⚠️ **必须与模型实际维度一致**，见 §5 |
| `QDRANT_URL` | `http://localhost:6333` | |
| `SQLITE_PATH` | `./data/agent.db` | 会话历史 + ingest 审计 |
| `RETRIEVAL_ENABLED` / `RETRIEVAL_TOP_K` | `true` / `4` | 检索增强 |
| `SESSION_MAX_TURNS` | `10` | 保留最近 N 条消息（user + assistant 合计） |
| `SESSION_TTL_HOURS` | `24` | 过期自动清理 |
| `AGENT_MOCK` | `false` | 离线联调开关 |

---

## 5. 四个必须知道的实现细节（都踩过）

### 5.1 `EMBED_DIM` 必须与模型维度一致

hello-agents 的 `QdrantVectorStore` 的 `vector_size` **默认是 384**，
而 DashScope `text-embedding-v3` 是 **1024**。维度不符时框架的行为是：

```python
if vlen != self.vector_size:
    logger.warning(...)
    continue          # ← 逐条丢弃，不抛异常，只返回 False
```

本服务已强制把 `vector_size = EMBED_DIM`，并在写入/检索前再自检一次维度，
不一致直接 500——避免「看起来同步成功、其实一条没进去」。

### 5.2 point id 必须是确定性 UUID

框架的 `add_vectors()` 对**非 UUID 的字符串 id 会静默替换成随机 UUID**：

```python
except Exception:
    safe_id = str(uuid.uuid4())     # ← 幂等和删除都会失效
```

所以绝不能用 `f"{biz_type}:{biz_id}"` 这种形式。本服务用
`core.names.point_id_for()` 生成 **UUIDv5**（同输入永远同输出），
保证重复 upsert 覆盖同一个点、delete 能精确命中。

### 5.3 绝不用框架的嵌入回退链

`create_embedding_model_with_fallback()` 的回退顺序是 `dashscope → local → tfidf`。
DashScope 一旦失败，它会**静默降级到 TFIDFEmbedding（1000 维）**，
把不同维度的向量写进同一个 collection，检索从此静默失效且全程不报错。

本服务直接实例化 `DashScopeEmbedding`，失败就返回 503 / 空上下文，**不降级**。

### 5.4 SSE 生成器里不能用 ContextVar token 做 set/reset

Starlette 的 `StreamingResponse` 会把同步生成器交给 `iterate_in_threadpool`，
它对**每一次 `next()`** 单独发起一次线程调用 —— 也就是每一步都可能落在不同的工作线程、
拿到不同的 context 副本。而 `ContextVar.set()` 返回的 token 只能在**同一个 Context** 内
`reset()`，跨 Context 会抛：

    ValueError: Token ... was created in a different Context

这个异常发生在**响应已经开始推送之后**，异常处理器兜不住，整个 SSE 请求直接 500。

本服务的做法：

- 流式路径用 `logging_conf.set_trace()` —— 只设置、不恢复，每一步重设一次；
- 非流式路径（同步路由 / lifespan）才用 `with bind_trace(...)`，
  且 `bind_trace` 改成「保存旧值 + 恢复」，不用 token。

> 这个 bug 是跑 `tests/smoke_api.py` 时抓到的，静态语法检查完全看不出来。

---

## 6. 降级矩阵

设计原则：**按能力降级，不做整体 fail-fast**；但单次请求内的失败必须响亮（非 200）。

| 依赖不可用 | `/v1/health` | `/v1/ingest` | `/v1/chat` | `/v1/summarize` |
|---|---|---|---|---|
| Qdrant 不通 | `degraded` | **503** | 正常（无检索上下文） | 正常 |
| 嵌入模型不可用 | `degraded` | **503** | 正常（无检索上下文） | 正常 |
| LLM 不可用 | `degraded` | 正常 | **503 / error 事件** | **503** |
| SQLite 不可用 | `degraded` | 正常（审计跳过） | 正常（无历史） | 正常 |
| 全部不可用 + `AGENT_MOCK=true` | `ok` | 正常（内存） | 正常（假流式） | 正常（假文本） |

原因：这三块能力彼此独立。Qdrant 没起就把 `/v1/chat` 一起弄挂，是最糟的取舍。

---

## 7. 排错

| 现象 | 原因 / 处理 |
|---|---|
| 所有请求 401 | `AGENT_KEY` 与 Java `.env` 的 `AGENT_KEY` 不一致 |
| Java 日志反复重试 ingest | Java **不区分错误码**，401 也会重试 3 次（间隔 5s / 30s）。先看 Python 日志里的 401 |
| `/v1/chat` 前端一直转圈 | 检查是否发了 `data: [DONE]`；用 `curl.exe -N` 看是不是逐块返回 |
| 前端很久才一次性出全文 | SSE 被反代缓冲。已设 `X-Accel-Buffering: no`；若还有问题查 Nginx 配置 |
| `/v1/ingest` 返回 200 但 Qdrant 里没有点 | 看日志有没有「向量维度不匹配」。确认 `EMBED_DIM` 与模型一致 |
| 改一次笔记就多一条向量 | point id 不是确定性 UUID。检查是否误用了别的 id 生成方式 |
| 摘要返回空 | Java 会把空摘要判为失败且**不写库**。查 LLM 日志 |
| 启动就崩 | 本服务设计为不 fail-fast。若真崩了，看是不是 `AGENT_MOCK=false` 且缺少必需环境变量导致 import 期报错 |

日志里每条都带 `trace_id`，可以直接和 Java 日志对照 grep。

---

## 8. 目录结构

```
agent-service/
├── .env.example
├── requirements.txt
├── run.py                       # 本地启动入口
├── tests/test_core.py           # 纯逻辑单测（无需第三方依赖）
└── app/
    ├── main.py                  # FastAPI 实例 + lifespan + 降级预热
    ├── config.py                # pydantic-settings 读 .env
    ├── logging_conf.py          # trace_id 贯穿的日志
    ├── core/
    │   ├── errors.py            # Result 信封 + 异常处理器
    │   ├── names.py             # namespace + UUIDv5 point id
    │   └── text.py              # 截断 / 分段 / 标题推断
    ├── schemas/                 # 与 agent-api.md 字段一一对应
    ├── api/
    │   ├── deps.py              # X-Agent-Key 鉴权
    │   └── routes/              # health / ingest / chat / summarize
    ├── services/                # 业务逻辑（禁止 import fastapi）
    │   ├── llm_client.py        # 薄封装 HelloAgentsLLM（含 mock）
    │   ├── embedding_client.py  # 薄封装 DashScopeEmbedding（含 mock）
    │   ├── vector_store.py      # 薄封装 QdrantVectorStore（含 mock）
    │   ├── retrieval.py         # 检索（失败降级为空上下文）
    │   ├── session_store.py     # 多轮会话（框架没有，自己写）
    │   ├── ingest_service.py
    │   ├── chat_service.py
    │   ├── summarize_service.py
    │   └── types.py
    ├── db/sqlite_store.py       # 连接 + 建表（会话、ingest 审计）
    └── prompts/                 # 提示词当代码管
```

`hello_agents` / `qdrant_client` 全部**懒加载**，所以 mock 模式下不装这些包也能启动。

---

## 9. 测试

三个脚本，按需选跑：

| 脚本 | 用途 | 需要什么 |
|---|---|---|
| `tests/test_core.py` | 纯逻辑单测：point id 确定性、namespace、biz/scope 校验、文本归一化与分段 | **零第三方依赖** |
| `tests/smoke_api.py` | **契约端到端**：mock 模式跑通四个接口（36 项断言） | fastapi + httpx（**不需要 key / 网络 / Qdrant**） |
| `tests/check_framework_api.py` | 框架 API 假设守卫（29 项断言） | hello-agents |

```powershell
python tests/test_core.py             # 最快，先跑这个
python tests/smoke_api.py             # 最有价值：真打请求，能抓到静态检查发现不了的 bug
python tests/check_framework_api.py   # 升级 hello-agents 之后必跑
# 装了 pytest 也可以：
python -m pytest tests/ -v
```

`smoke_api.py` 逐条对应施工指南 §10 的「完成定义」，包含最容易写错的几项：
缺 key → 401、参数校验 → 400（不是 FastAPI 默认的 422）、SSE 字段名是 `content`
且以 `data: [DONE]` 结束、重复 upsert 后 collection 里仍只有 1 个点、
delete 不存在的 id 仍返回 200、超长正文走 map-reduce、错误维度抛错而不是静默丢弃。

---

## 10. 实测依赖版本

### 10.1 代码已验证通过的版本组合

（实跑 `tests/smoke_api.py` 与 `tests/check_framework_api.py` 的环境）

| 包 | 版本 |
|---|---|
| Python | 3.12.5 |
| hello-agents | **0.2.9** |
| fastapi | 0.141.1 |
| starlette | 0.52.1 |
| pydantic | 2.13.5 |
| pydantic-settings | 2.15.0 |
| httpx | 0.28.1 |
| qdrant-client | 未参与验证（mock 模式绕开；真实读写待你首次联调） |

### 10.2 你自己的环境

> ⚠️ 首次安装后请把实际解析出的版本记在这里，这是排查「教程对不上」的唯一依据。
> 生成命令：
> ```powershell
> python -c "import hello_agents,fastapi,openai,pydantic,qdrant_client;print('hello-agents',hello_agents.__version__);print('fastapi',fastapi.__version__);print('openai',openai.__version__);print('pydantic',pydantic.VERSION)"
> ```

| 包 | 版本 |
|---|---|
| Python | 待填（需 ≥3.10） |
| hello-agents | 待填（应锁定 0.2.9） |
| fastapi | 待填 |
| openai | 待填（会被 hello-agents 锁在 1.x） |
| pydantic | 待填 |
| qdrant-client | 待填 |
