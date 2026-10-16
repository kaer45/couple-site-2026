"""契约测试：验证 Python 侧严格实现 docs/agent-api.md。

分两层，都不需要网络、API key 或 Qdrant：

  A. **客户端协议层** —— 用 `httpx.MockTransport` 伪造上游 HTTP 响应，
     直接验证自研客户端里风险最高的那部分代码：SSE 解析、错误翻译、维度守卫。
     （这是替代旧 `smoke_api.py` 的关键改进：旧版只测到了 mock 分支，
       真正的解析代码一行都没被执行过。）

  B. **接口契约层** —— 用 `fastapi.testclient` 打四个接口，三个客户端注入测试替身。

运行：
    cd agent-service
    python tests/test_contract.py
    # 或（装了 pytest 时）
    python -m pytest tests/test_contract.py -v

注意：环境变量必须在 `import app.*` **之前**设置 —— Settings 是 lru_cache 单例。
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

# ---------------------------------------------------------------------------
# 必须在 import app.* 之前设置
# ---------------------------------------------------------------------------
# ⚠️ 不要用 tempfile.mkdtemp()：受限环境下它创建的目录里 sqlite3 会报
#    "unable to open database file"。用普通 mkdir 得到的目录没有这个问题。
_TMP = Path(tempfile.gettempdir()) / f"agent-contract-{os.getpid()}"
_TMP.mkdir(parents=True, exist_ok=True)
os.environ["AGENT_KEY"] = "test-key"
os.environ["SQLITE_PATH"] = str(_TMP / "agent.db")
# 假向量是 sha256 派生的，不同文本余弦相似度接近 0；放开阈值才能测到 refs 链路
os.environ["RETRIEVAL_SCORE_THRESHOLD"] = "0"
os.environ["LOG_LEVEL"] = "WARNING"
# 客户端协议层需要"看起来已配置"的 key（实际请求会被 MockTransport 拦下）
os.environ["LLM_API_KEY"] = "sk-test-llm"
os.environ["DASHSCOPE_API_KEY"] = "sk-test-embed"

import httpx  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app.config import get_settings  # noqa: E402
from app.core.errors import AgentError  # noqa: E402
from app.main import app  # noqa: E402
from app.services import session_store  # noqa: E402
from app.services.embedding_client import EmbeddingClient, set_embedding  # noqa: E402
from app.services.llm_client import LLMClient, set_llm  # noqa: E402
from app.services.vector_store import VectorStore, set_vector_store  # noqa: E402

KEY = "test-key"
HEADERS = {"X-Agent-Key": KEY}
TRACE = "a1b2c3d4e5f60718"

_FAILURES: list[str] = []
_CHECKS = 0


def check(name: str, condition: bool, detail: str = "") -> None:
    global _CHECKS
    _CHECKS += 1
    if condition:
        print(f"  ok   {name}")
    else:
        _FAILURES.append(name)
        print(f"  FAIL {name}" + (f"  ← {detail}" if detail else ""))


# ===========================================================================
# 测试替身（只用于 B 层；A 层测的是真实客户端代码）
# ===========================================================================
def _fake_vector(text: str) -> list[float]:
    """确定性假向量：同一文本永远得到同一向量（便于验证幂等与检索）。"""
    dim = get_settings().embed_dim
    seed = hashlib.sha256(text.encode("utf-8")).digest()
    values: list[float] = []
    while len(values) < dim:
        seed = hashlib.sha256(seed).digest()
        values.extend((b / 255.0) - 0.5 for b in seed)
    values = values[:dim]
    norm = math.sqrt(sum(v * v for v in values)) or 1.0
    return [v / norm for v in values]


class FakeLLMClient:
    REPLY = "（测试替身）这是流式回答。"
    SUMMARY = "（测试替身）这是一段模拟摘要，用于验证摘要链路是否打通。"

    def __init__(self) -> None:
        self.invoke_count = 0

    def describe(self) -> dict:
        return {"model": "fake-llm", "base_url": "fake", "ready": True}

    def stream(self, messages, **kwargs):  # noqa: ANN001, ANN003
        for start in range(0, len(self.REPLY), 4):
            yield self.REPLY[start : start + 4]

    def invoke(self, messages, **kwargs) -> str:  # noqa: ANN001, ANN003
        self.invoke_count += 1
        return self.SUMMARY

    def close(self) -> None:
        pass


class FakeEmbeddingClient:
    def describe(self) -> dict:
        return {
            "model": "fake-embed",
            "dim": get_settings().embed_dim,
            "base_url": "fake",
            "ready": True,
        }

    @property
    def available(self) -> bool:
        return True

    def encode(self, text: str) -> list[float]:
        return _fake_vector(text)

    def encode_many(self, texts: list[str]) -> list[list[float]]:
        return [_fake_vector(t) for t in texts]

    def close(self) -> None:
        pass


class FakeVectorStore:
    """内存向量库，保留 upsert 覆盖语义、余弦检索与 where 过滤。"""

    def __init__(self) -> None:
        self.buckets: dict[str, dict[str, tuple[list[float], dict]]] = {}

    def describe(self) -> dict:
        return {"url": "fake", "vector_size": get_settings().embed_dim}

    def upsert(self, namespace: str, point_id: str, vector, payload) -> None:  # noqa: ANN001
        self.buckets.setdefault(namespace, {})[point_id] = (list(vector), dict(payload))

    def delete(self, namespace: str, point_ids: list[str]) -> None:
        bucket = self.buckets.get(namespace, {})
        for point_id in point_ids:
            bucket.pop(point_id, None)

    def search(self, namespace, vector, limit=4, score_threshold=None, where=None):  # noqa: ANN001
        bucket = self.buckets.get(namespace, {})
        scored = []
        for point_id, (stored, payload) in bucket.items():
            if where and any(payload.get(k) != v for k, v in where.items()):
                continue
            score = _cosine(vector, stored)
            if score_threshold is not None and score < score_threshold:
                continue
            scored.append((score, point_id, payload))
        scored.sort(key=lambda item: item[0], reverse=True)
        return [
            {"id": pid, "score": score, "metadata": payload}
            for score, pid, payload in scored[:limit]
        ]

    def health(self) -> tuple[bool, str]:
        return True, "fake 内存向量库"

    def count(self, namespace: str) -> int:
        return len(self.buckets.get(namespace, {}))

    def close(self) -> None:
        pass


def _cosine(a: list[float], b: list[float]) -> float:
    if len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a)) or 1.0
    nb = math.sqrt(sum(y * y for y in b)) or 1.0
    return dot / (na * nb)


# ===========================================================================
# A. 客户端协议层（真实代码 + MockTransport，无网络）
# ===========================================================================
def _llm_via_transport(handler) -> LLMClient:  # noqa: ANN001
    """构造真实 LLMClient，但把内部 httpx 客户端换成 MockTransport。"""
    settings = get_settings()
    client = LLMClient(settings)
    client._client = httpx.Client(  # noqa: SLF001 - 测试里刻意替换传输层
        base_url=settings.llm_base_url,
        headers={"Authorization": f"Bearer {settings.llm_api_key}"},
        transport=httpx.MockTransport(handler),
    )
    return client


def _embedding_via_transport(handler) -> EmbeddingClient:  # noqa: ANN001
    settings = get_settings()
    client = EmbeddingClient(settings)
    client._client = httpx.Client(  # noqa: SLF001
        base_url=settings.dashscope_base_url,
        headers={"Authorization": f"Bearer {settings.dashscope_api_key}"},
        transport=httpx.MockTransport(handler),
    )
    return client


def test_llm_client_parses_sse() -> None:
    print("\n[A1] LLMClient：SSE 解析（真实代码）")
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["auth"] = request.headers.get("authorization")
        captured["body"] = json.loads(request.content)
        # 刻意混入：空 content 的首块、SSE 注释行、没有 delta 的 usage 块
        body = (
            'data: {"choices":[{"delta":{"role":"assistant","content":""}}]}\n\n'
            ": keep-alive\n\n"
            'data: {"choices":[{"delta":{"content":"你"}}]}\n\n'
            'data: {"choices":[{"delta":{"content":"好"}}]}\n\n'
            'data: {"choices":[],"usage":{"total_tokens":3}}\n\n'
            "data: [DONE]\n\n"
        )
        return httpx.Response(
            200, content=body.encode("utf-8"),
            headers={"content-type": "text/event-stream"},
        )

    pieces = list(_llm_via_transport(handler).stream([{"role": "user", "content": "你好"}]))
    check("只产出有内容的增量（跳过空块与注释行）", pieces == ["你", "好"], str(pieces))
    check("请求打到 /chat/completions", captured.get("url", "").endswith("/chat/completions"), captured.get("url", ""))
    check("带上 Bearer 鉴权头", captured.get("auth") == "Bearer sk-test-llm", str(captured.get("auth")))
    check("请求体 stream=true", captured.get("body", {}).get("stream") is True, str(captured.get("body"))[:120])
    check("请求体带 model 与 messages", "model" in captured.get("body", {}) and "messages" in captured.get("body", {}))


def test_llm_client_translates_http_error() -> None:
    print("\n[A2] LLMClient：上游错误翻译")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error": {"message": "invalid api key"}})

    try:
        list(_llm_via_transport(handler).stream([{"role": "user", "content": "x"}]))
    except AgentError as exc:
        check("流式 401 → AgentError(502)", exc.status_code == 502, str(exc.status_code))
        check("错误信息含上游状态码", "401" in exc.message, exc.message[:120])
    else:
        check("流式 401 → AgentError(502)", False, "没有抛异常")

    try:
        _llm_via_transport(handler).invoke([{"role": "user", "content": "x"}])
    except AgentError as exc:
        check("非流式 401 → AgentError(502)", exc.status_code == 502, str(exc.status_code))
    else:
        check("非流式 401 → AgentError(502)", False, "没有抛异常")


def test_llm_client_invoke_parses_message() -> None:
    print("\n[A3] LLMClient：非流式解析")

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        check("invoke 请求 stream=false", body.get("stream") is False, str(body.get("stream")))
        return httpx.Response(200, json={"choices": [{"message": {"content": "  你好世界  "}}]})

    result = _llm_via_transport(handler).invoke([{"role": "user", "content": "x"}])
    check("取出 content 并 strip", result == "你好世界", repr(result))


def test_embedding_client_orders_by_index() -> None:
    print("\n[A4] EmbeddingClient：解析与按 index 排序（真实代码）")
    dim = get_settings().embed_dim
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        body = json.loads(request.content)
        captured["body"] = body
        count = len(body["input"])
        # 故意倒序返回，验证我们按 index 排序而不是信任返回顺序
        data = [
            {"index": i, "embedding": [float(i)] * dim} for i in range(count)
        ][::-1]
        return httpx.Response(200, json={"data": data, "usage": {"total_tokens": 1}})

    vectors = _embedding_via_transport(handler).encode_many(["a", "b", "c"])
    check("返回 3 条向量", len(vectors) == 3, str(len(vectors)))
    check("每条维度正确", all(len(v) == dim for v in vectors), str([len(v) for v in vectors]))
    check(
        "按 index 排序（首元素依次 0.0 / 1.0 / 2.0）",
        [v[0] for v in vectors] == [0.0, 1.0, 2.0],
        str([v[0] for v in vectors]),
    )
    check("请求打到 /embeddings", captured.get("url", "").endswith("/embeddings"), captured.get("url", ""))
    check("请求体带 model 与 input", captured.get("body", {}).get("model") == get_settings().embed_model_name)


def test_embedding_client_rejects_wrong_dim() -> None:
    print("\n[A5] EmbeddingClient：维度守卫（绝不静默降级）")

    def handler(request: httpx.Request) -> httpx.Response:
        # 返回 8 维，而 EMBED_DIM 是 1024 —— 必须报错而不是放行
        return httpx.Response(200, json={"data": [{"index": 0, "embedding": [0.0] * 8}]})

    try:
        _embedding_via_transport(handler).encode("x")
    except AgentError as exc:
        check("维度不符 → AgentError(500)", exc.status_code == 500, str(exc.status_code))
        check("错误信息点明 EMBED_DIM", "EMBED_DIM" in exc.message, exc.message[:120])
    else:
        check("维度不符 → AgentError(500)", False, "没有抛异常（会静默污染向量集合）")


def test_embedding_client_translates_http_error() -> None:
    print("\n[A6] EmbeddingClient：上游错误翻译")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"code": "Throttling", "message": "quota exceeded"})

    try:
        _embedding_via_transport(handler).encode("x")
    except AgentError as exc:
        check("429 → AgentError(502)", exc.status_code == 502, str(exc.status_code))
        check("错误信息含上游状态码", "429" in exc.message, exc.message[:120])
    else:
        check("429 → AgentError(502)", False, "没有抛异常")


def test_vector_store_dim_guard() -> None:
    print("\n[A7] VectorStore：维度守卫（不连接 Qdrant 也能验）")
    store = VectorStore()  # 只构造，不访问网络
    try:
        store._check_dim([0.0] * 8)  # noqa: SLF001 - 就是要在不连库的情况下验这个守卫
    except AgentError as exc:
        check("错误维度 → AgentError(500)", exc.status_code == 500, str(exc.status_code))
    else:
        check("错误维度 → AgentError(500)", False, "没有抛异常（会被 Qdrant 静默拒收）")

    expected = get_settings().embed_dim
    store._check_dim([0.0] * expected)  # noqa: SLF001
    check("正确维度不报错", True)


def test_point_id_is_stable_uuid() -> None:
    print("\n[A8] point id 稳定性（幂等的根基）")
    import uuid as uuid_mod

    from app.core import names

    pid = names.point_id_for("note", 6)
    check("是合法 UUID（Qdrant 只接受整数或 UUID 字符串）", str(uuid_mod.UUID(pid)) == pid, pid)
    check("同输入同输出", names.point_id_for("note", 6) == pid)


# ===========================================================================
# B. 接口契约层（TestClient + 测试替身）
# ===========================================================================
def check_health(client: TestClient) -> None:
    print("\n[B1] GET /v1/health 探活")
    resp = client.get("/v1/health", headers=HEADERS)
    check("返回 200", resp.status_code == 200, resp.text[:200])
    body = resp.json()
    check("body.code == 0", body.get("code") == 0, str(body)[:200])
    data = body.get("data") or {}
    check("含 components 自检信息", isinstance(data.get("components"), dict))
    check("已移除 mock 字段", "mock" not in data, str(list(data.keys())))
    check("全组件就绪时 degraded == false", data.get("degraded") is False, str(data.get("components")))


def check_auth(client: TestClient) -> None:
    print("\n[B2] X-Agent-Key 鉴权")
    resp = client.get("/v1/health")
    check("缺 X-Agent-Key → 401", resp.status_code == 401, f"got {resp.status_code}")
    check("401 的 body.code == 401", (resp.json() or {}).get("code") == 401)
    resp = client.get("/v1/health", headers={"X-Agent-Key": "wrong-key"})
    check("错误 key → 401", resp.status_code == 401, f"got {resp.status_code}")


def check_validation(client: TestClient) -> None:
    print("\n[B3] 参数校验（必须是 400，不能是 FastAPI 默认的 422）")
    resp = client.post("/v1/ingest", headers=HEADERS, json={"biz_type": "note", "biz_id": 1})
    check("缺字段 → 400", resp.status_code == 400, f"got {resp.status_code}")
    check("400 的 body.code == 400", (resp.json() or {}).get("code") == 400)

    resp = client.post(
        "/v1/ingest",
        headers=HEADERS,
        json={"biz_type": "note", "biz_id": 1, "content": "   ",
              "scope": "personal", "scope_id": 1, "action": "upsert"},
    )
    check("upsert 但 content 全空白 → 400", resp.status_code == 400, f"got {resp.status_code}")

    resp = client.post(
        "/v1/ingest",
        headers=HEADERS,
        json={"biz_type": "note", "biz_id": 1, "content": "正文",
              "scope": "couple", "scope_id": 1, "action": "upsert"},
    )
    check("非法 biz_type+scope 组合 → 400", resp.status_code == 400, f"got {resp.status_code}")

    resp = client.post("/v1/summarize", headers=HEADERS, json={"trace_id": TRACE, "content": "  "})
    check("摘要正文全空白 → 400", resp.status_code == 400, f"got {resp.status_code}")


def check_summarize(client: TestClient) -> None:
    print("\n[B4] POST /v1/summarize 摘要")
    resp = client.post(
        "/v1/summarize",
        headers=HEADERS,
        json={"trace_id": TRACE, "user_id": 1, "note_id": 3,
              "title": "Redis 学习笔记",
              "content": "# Redis\n\nRedis 是内存数据库，支持 RDB 与 AOF 两种持久化。"},
    )
    check("返回 200", resp.status_code == 200, resp.text[:200])
    summary = ((resp.json() or {}).get("data") or {}).get("summary")
    check("data.summary 非空且非空白", isinstance(summary, str) and summary.strip() != "", repr(summary)[:120])

    # 超长正文（超过 CHUNK_SIZE）必须走 map-reduce 而不是崩掉
    from app.core import text as text_util

    long_content = "第一段。" + "内容" * 15000
    resp = client.post(
        "/v1/summarize",
        headers=HEADERS,
        json={"trace_id": TRACE, "title": "长笔记", "content": long_content},
    )
    check("超长正文（map-reduce）返回 200", resp.status_code == 200, resp.text[:200])
    chunks = text_util.split_for_summary(text_util.normalize(long_content))
    check("长正文确实被切成多块", len(chunks) > 1, f"chunks={len(chunks)}")


def check_ingest(client: TestClient, store: FakeVectorStore) -> None:
    print("\n[B5] POST /v1/ingest 幂等性")
    base = {
        "trace_id": TRACE, "biz_type": "note", "biz_id": 6,
        "content": "Redis 学习笔记\n\nRedis 是内存数据库，支持持久化。",
        "scope": "personal", "scope_id": 1, "action": "upsert",
    }

    r1 = client.post("/v1/ingest", headers=HEADERS, json=base)
    check("upsert 返回 200", r1.status_code == 200, r1.text[:200])
    d1 = (r1.json() or {}).get("data") or {}
    check("namespace == note_u1", d1.get("namespace") == "note_u1", str(d1))

    r2 = client.post("/v1/ingest", headers=HEADERS, json={**base, "content": "Redis 学习笔记\n\n改过的正文。"})
    check("重复 upsert 返回 200", r2.status_code == 200, r2.text[:200])
    d2 = (r2.json() or {}).get("data") or {}
    check("point id 是确定性的（两次一致）", d1.get("point_id") == d2.get("point_id"),
          f"{d1.get('point_id')} vs {d2.get('point_id')}")
    check("幂等：collection 里仍只有 1 个点", store.count("note_u1") == 1, f"count={store.count('note_u1')}")

    rd = client.post("/v1/ingest", headers=HEADERS, json={**base, "action": "delete", "content": None})
    check("delete 返回 200", rd.status_code == 200, rd.text[:200])
    rd2 = client.post("/v1/ingest", headers=HEADERS, json={**base, "action": "delete", "content": None})
    check("重复 delete 仍返回 200（幂等）", rd2.status_code == 200, rd2.text[:200])
    check("删除后 count == 0", store.count("note_u1") == 0)


def check_chat(client: TestClient) -> None:
    print("\n[B6] POST /v1/chat SSE + 检索 + 多轮会话")
    client.post(
        "/v1/ingest",
        headers=HEADERS,
        json={"trace_id": TRACE, "biz_type": "note", "biz_id": 7,
              "content": "Redis 学习笔记\n\nRDB 是快照持久化，AOF 是追加日志持久化。",
              "scope": "personal", "scope_id": 1, "action": "upsert"},
    )

    chat_body = {
        "trace_id": TRACE, "user_id": 1, "session_id": "user1",
        "message": "我的 Redis 笔记讲了什么？", "scope": "personal", "scope_id": 1,
    }

    with client.stream("POST", "/v1/chat", headers=HEADERS, json=chat_body) as resp:
        check("返回 200", resp.status_code == 200)
        ctype = resp.headers.get("content-type", "")
        check("Content-Type 是 text/event-stream", ctype.startswith("text/event-stream"), ctype)
        check("带 X-Accel-Buffering: no（防反代缓冲）",
              resp.headers.get("x-accel-buffering") == "no", str(resp.headers.get("x-accel-buffering")))
        payload = resp.read().decode("utf-8")

    check('SSE 含 {"content": ...} 增量', '"content"' in payload, payload[:200])
    check('SSE 不含 "delta" 字段（契约要求 content）', '"delta"' not in payload, payload[:200])
    check("SSE 以 data: [DONE] 结束", payload.rstrip().endswith("data: [DONE]"), payload[-80:])
    check("含 refs 事件（检索链路打通）", '"refs"' in payload, payload[:300])
    check("refs 里有 biz_type/biz_id/title",
          all(k in payload for k in ('"biz_type"', '"biz_id"', '"title"')))

    with client.stream("POST", "/v1/chat", headers=HEADERS, json={**chat_body, "message": "再补充一下"}) as resp:
        resp.read()

    hist = session_store.history("u1:user1", 10)
    roles = [h["role"] for h in hist]
    check("多轮会话已落库（4 条）", len(hist) == 4, f"len={len(hist)}")
    check("角色序列 user/assistant 交替", roles == ["user", "assistant", "user", "assistant"], str(roles))


# ===========================================================================
def main() -> int:
    print("=" * 72)
    print("契约测试：客户端协议层 + 接口契约层（不需要网络 / API key / Qdrant）")
    print(f"临时数据目录: {_TMP}")
    print("=" * 72)

    try:
        # ---- A 层：真实客户端代码 + MockTransport ----
        test_llm_client_parses_sse()
        test_llm_client_translates_http_error()
        test_llm_client_invoke_parses_message()
        test_embedding_client_orders_by_index()
        test_embedding_client_rejects_wrong_dim()
        test_embedding_client_translates_http_error()
        test_vector_store_dim_guard()
        test_point_id_is_stable_uuid()

        # ---- B 层：接口契约，注入替身 ----
        store = FakeVectorStore()
        set_llm(FakeLLMClient())
        set_embedding(FakeEmbeddingClient())
        set_vector_store(store)

        with TestClient(app) as client:  # with 才会触发 lifespan（建表 + 预热）
            check_health(client)
            check_auth(client)
            check_validation(client)
            check_summarize(client)
            check_ingest(client, store)
            check_chat(client)
    finally:
        shutil.rmtree(_TMP, ignore_errors=True)

    print("\n" + "=" * 72)
    if _FAILURES:
        print(f"结果：{_CHECKS - len(_FAILURES)}/{_CHECKS} 通过，{len(_FAILURES)} 项失败")
        for name in _FAILURES:
            print(f"  - {name}")
        return 1
    print(f"结果：全部 {_CHECKS} 项检查通过 ✅")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
