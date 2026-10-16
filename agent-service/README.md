# couple-site Agent 服务（Python / FastAPI）

Java ↔ Python 契约的 **Python 侧实现**。

- **接口契约（唯一基准）**：[`../docs/agent-api.md`](../docs/agent-api.md)
- **施工说明与设计取舍**：[`../docs/agent-service-build-guide.md`](../docs/agent-service-build-guide.md)

技术栈：FastAPI + **httpx** + **qdrant-client** + SQLite。

> **不依赖任何智能体框架。** 三个外部依赖客户端（LLM / 嵌入 / 向量库）都在
> `app/services/` 里自研，分别是 100 多行直连 HTTP 或官方 SDK 的实现，
> 行为完全可预测，也没有 `openai<2` 这类版本锁。

知识图谱（Neo4j 实体/关系抽取）尚未实现。

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

**没配 key / 没起 Qdrant 也能验证代码** —— 见 §8 的契约测试，它用 HTTP 传输层替身
与客户端替身覆盖了协议解析和全部接口，不需要网络。

---

## 2. 接口速查

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

## 3. 配置项

见 `.env.example`（已带注释）。几个关键项：

| 变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_KEY` | `change-me` | 与 Java `agent.key` 一致，生产必改 |
| `HOST` / `PORT` | `127.0.0.1` / `8001` | Java 默认 `agent.url=http://localhost:8001` |
| `LLM_BASE_URL` / `LLM_MODEL_ID` / `LLM_API_KEY` | DeepSeek | OpenAI 兼容接口 |
| `LLM_TEMPERATURE` / `LLM_MAX_TOKENS` | `0.7` / `0`（不发） | `0` 表示不发送 max_tokens |
| `LLM_CONNECT_TIMEOUT` / `LLM_READ_TIMEOUT` | `10` / `60` | 秒。契约要求摘要整体 60s 内返回 |
| `DASHSCOPE_API_KEY` | — | 嵌入模型 key |
| `EMBED_DIM` | `1024` | ⚠️ **必须与模型实际维度一致**，见 §4.1 |
| `EMBED_BATCH_SIZE` | `10` | DashScope 单次批量上限较小，分批提交 |
| `QDRANT_URL` | `http://localhost:6333` | |
| `SQLITE_PATH` | `./data/agent.db` | 会话历史 + ingest 审计 |
| `RETRIEVAL_ENABLED` / `RETRIEVAL_TOP_K` | `true` / `4` | 检索增强 |
| `SESSION_MAX_TURNS` | `10` | 保留最近 N 条消息（user + assistant 合计） |
| `SESSION_TTL_HOURS` | `24` | 过期自动清理 |

---

## 4. 四条设计约束（都是踩过坑得来的）

这些约束**与用不用框架无关** —— 它们是 Qdrant / 嵌入模型 / ASGI 本身的特性。
自己写客户端时同样要守住，否则会重新引入静默数据损坏。

### 4.1 `EMBED_DIM` 必须与模型维度一致

Qdrant 建 collection 时就锁定了向量维度。如果 `EMBED_DIM` 与模型实际输出不一致，
后果不是报错而是**静默的数据损坏**：写入被拒或检索结果错乱。

本服务的做法：建 collection 时**显式**用 `EMBED_DIM`（绝不依赖任何默认值），
并且在写入 / 检索前用 `_check_dim` 再自检一次，不一致直接 500。

### 4.2 point id 必须是确定性 UUID

Qdrant 的 point id **只接受无符号整数或 UUID 字符串**。传 `"note:6"` 这种形式不会
被"容错处理"，而是写入失败。

所以 `core.names.point_id_for()` 用 **UUIDv5** 生成：同一 `(biz_type, biz_id)` 永远
得到同一个 id —— 这是 upsert 幂等（重复同步只覆盖同一个点）与精确删除的根基（契约 §1.3）。
**不要改成随机 id。**

### 4.3 嵌入失败绝不降级

外面有些封装库在嵌入失败时会**静默回退**到本地模型或 TF-IDF（1000 维）。
一旦发生，同一个 collection 里就混进了不同维度的向量，检索从此失效且**全程不报错**。

本服务的嵌入客户端**没有任何回退链**：失败就抛 `AgentError`，让 `/v1/ingest` 返回
非 200 —— 这样 Java 会重试，数据不会静默丢失。

### 4.4 SSE 生成器里不能用 ContextVar token 做 set/reset

Starlette 的 `StreamingResponse` 会把同步生成器交给 `iterate_in_threadpool`，
它对**每一次 `next()`** 单独发起一次线程调用 —— 每一步都可能落在不同的工作线程、
拿到不同的 context 副本。而 `ContextVar.set()` 返回的 token 只能在**同一个 Context** 内
`reset()`，跨 Context 会抛：

    ValueError: Token ... was created in a different Context

这个异常发生在**响应已经开始推送之后**，异常处理器兜不住，整个 SSE 请求直接 500。

本服务的做法：

- 流式路径用 `logging_conf.set_trace()` —— 只设置、不恢复，每一步重设一次；
- 非流式路径（同步路由 / lifespan）才用 `with bind_trace(...)`，
  且 `bind_trace` 改成「保存旧值 + 恢复」，不用 token。

> 这个 bug 是跑契约测试时抓到的，静态语法检查完全看不出来。

---

## 5. 降级矩阵

设计原则：**按能力降级，不做整体 fail-fast**；但单次请求内的失败必须响亮（非 200）。

| 依赖不可用 | `/v1/health` | `/v1/ingest` | `/v1/chat` | `/v1/summarize` |
|---|---|---|---|---|
| Qdrant 不通 | `degraded` | **503** | 正常（无检索上下文） | 正常 |
| 嵌入模型不可用 | `degraded` | **503** | 正常（无检索上下文） | 正常 |
| LLM 不可用 | `degraded` | 正常 | **503 / error 事件** | **503** |
| SQLite 不可用 | `degraded` | 正常（审计跳过） | 正常（无历史） | 正常 |

原因：这几块能力彼此独立。Qdrant 没起就把 `/v1/chat` 一起弄挂，是最糟的取舍。
但**单次请求内的失败必须返回非 200**，否则 Java 会以为同步成功。

---

## 6. 排错

| 现象 | 原因 / 处理 |
|---|---|
| 所有请求 401 | `AGENT_KEY` 与 Java `.env` 的 `AGENT_KEY` 不一致 |
| Java 日志反复重试 ingest | Java **不区分错误码**，401 也会重试 3 次（间隔 5s / 30s）。先看 Python 日志里的 401 |
| `/v1/chat` 前端一直转圈 | 检查是否发了 `data: [DONE]`；用 `curl.exe -N` 看是不是逐块返回 |
| 前端很久才一次性出全文 | SSE 被反代缓冲。已设 `X-Accel-Buffering: no`；若还有问题查 Nginx 配置 |
| `/v1/ingest` 返回 500 且日志说维度不符 | `EMBED_DIM` 与模型实际维度不一致（见 §4.1） |
| 改一次笔记就多一条向量 | point id 不是确定性 UUID（见 §4.2） |
| 摘要返回空 | Java 会把空摘要判为失败且**不写库**。查 LLM 接口返回 |
| 启动后 `/v1/health` 全绿但 ingest 报 502 | 上游（DashScope / DeepSeek）返回 4xx/5xx，看错误信息里的状态码与响应体 |
| 启动就崩 | 本服务设计为不 fail-fast。真崩了多半是 import 期出错，看完整堆栈 |

日志里每条都带 `trace_id`，可以直接和 Java 日志对照 grep。

---

## 7. 目录结构

```
agent-service/
├── .env.example
├── requirements.txt
├── run.py                       # 本地启动入口
├── tests/
│   ├── test_core.py             # 纯逻辑单测（零第三方依赖）
│   └── test_contract.py         # 客户端协议层 + 接口契约层（不需要网络/key/Qdrant）
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
    │   ├── llm_client.py        # httpx 直连 OpenAI 兼容 /chat/completions（自解 SSE）
    │   ├── embedding_client.py  # httpx 直连 DashScope /embeddings（无回退链）
    │   ├── vector_store.py      # 直接用 qdrant-client（自管 collection 与索引）
    │   ├── retrieval.py         # 检索（失败降级为空上下文）
    │   ├── session_store.py     # 多轮会话（SQLite）
    │   ├── ingest_service.py
    │   ├── chat_service.py
    │   ├── summarize_service.py
    │   └── types.py
    ├── db/sqlite_store.py       # 连接 + 建表（会话、ingest 审计）
    └── prompts/                 # 提示词当代码管
```

`qdrant_client` 是懒加载的，所以纯逻辑测试与协议层测试在没有 Qdrant 的环境里也能跑。

---

## 8. 测试

两个脚本，都不需要 API key、网络或 Qdrant：

| 脚本 | 用途 | 需要什么 |
|---|---|---|
| `tests/test_core.py` | 纯逻辑单测（17 项）：point id 确定性、namespace、biz/scope 校验、文本归一化与分段 | **零第三方依赖** |
| `tests/test_contract.py` | **客户端协议层 + 接口契约层（58 项）** | fastapi + httpx |

```powershell
python tests/test_core.py             # 最快，先跑这个
python tests/test_contract.py         # 最有价值：真跑客户端解析代码 + 打四个接口
# 装了 pytest 也可以：
python -m pytest tests/ -v
```

`test_contract.py` 分两层：

- **A 层（协议层）**：用 `httpx.MockTransport` 伪造上游 HTTP 响应，**真正执行**
  自研客户端里的 SSE 解析、错误翻译、维度守卫代码。这是风险最高、也最该被测的部分。
- **B 层（契约层）**：用 `fastapi.testclient` 打四个接口，三个客户端注入测试替身。

覆盖的关键点：缺 key → 401、参数校验 → 400（不是 FastAPI 默认的 422）、
SSE 字段名是 `content` 且以 `data: [DONE]` 结束、重复 upsert 后 collection 里仍只有
1 个点、delete 不存在的 id 仍返回 200、超长正文走 map-reduce、错误维度抛错而不是静默丢弃。

> 测试替身只存在于 `tests/` 里，**生产代码没有任何 mock 分支或开关**。

---

## 9. 实测依赖版本

### 9.1 代码已验证通过的版本组合

（实跑 `tests/test_core.py` 与 `tests/test_contract.py` 的环境）

| 包 | 版本 |
|---|---|
| Python | 3.12.5 |
| fastapi | 0.141.1 |
| starlette | 0.52.1 |
| pydantic | 2.13.5 |
| pydantic-settings | 2.15.0 |
| httpx | 0.28.1 |
| qdrant-client | 1.9.0（代码同时兼容 ≥1.10 的 `query_points()`） |

> `qdrant-client` 的真实读写**尚未联调**（协议层与契约层测试用的是替身与 MockTransport），
> 首次接真库时请留意。

### 9.2 你自己的环境

> ⚠️ 首次安装后请把实际解析出的版本记在这里，这是排查「版本对不上」的唯一依据。
> 生成命令：
> ```powershell
> python -c "import fastapi, httpx, qdrant_client, pydantic; print('fastapi', fastapi.__version__); print('httpx', httpx.__version__); print('pydantic', pydantic.VERSION); import importlib.metadata as md; print('qdrant-client', md.version('qdrant-client'))"
> ```

| 包 | 版本 |
|---|---|
| Python | 待填（需 ≥3.10） |
| fastapi | 待填 |
| httpx | 待填 |
| pydantic | 待填 |
| qdrant-client | 待填 |
