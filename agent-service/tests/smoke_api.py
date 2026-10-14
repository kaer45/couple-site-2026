"""契约冒烟测试：在 `AGENT_MOCK=true` 下用 TestClient 跑通四个接口。

**不需要 API key、不需要 Qdrant、不需要网络。**
mock 模式会用「确定性假嵌入 + 内存向量库」替代外部依赖，
因此本脚本专门用来验证 **Java ↔ Python 的协议**是否严格符合 `docs/agent-api.md`。

它逐条对应施工指南 §10 的「完成定义」，包括最容易写错的几项：
    - 缺 X-Agent-Key → 401
    - 参数校验失败 → 400（不是 FastAPI 默认的 422）
    - SSE 增量字段是 content 而**不是** delta，且以 data: [DONE] 结束
    - 同一 biz_id 重复 upsert 后 collection 里仍只有 1 个点（幂等）
    - delete 不存在的 id 仍返回 200（幂等）
    - 超长正文走 map-reduce 不崩
    - 向量维度不符时抛错（而不是像框架那样静默丢弃）

运行：
    cd agent-service
    python tests/smoke_api.py

注意：环境变量必须在 `import app.*` **之前**设置 —— Settings 是 lru_cache 单例。
"""

from __future__ import annotations

import os
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

# ---------------------------------------------------------------------------
# 必须在 import app.* 之前设置环境变量
# ---------------------------------------------------------------------------
# ⚠️ 不要用 tempfile.mkdtemp()：在受限环境下它创建的目录里 sqlite3 会报
#    "unable to open database file"。用普通 mkdir 得到的目录没有这个问题。
_TMP = Path(tempfile.gettempdir()) / f"agent-smoke-{os.getpid()}"
_TMP.mkdir(parents=True, exist_ok=True)
os.environ["AGENT_MOCK"] = "true"
os.environ["AGENT_KEY"] = "test-key"
os.environ["SQLITE_PATH"] = str(_TMP / "agent.db")
os.environ["RETRIEVAL_ENABLED"] = "true"
# mock 向量是 sha256 派生的，不同文本余弦相似度接近 0，放开阈值才能测到 refs 链路
os.environ["RETRIEVAL_SCORE_THRESHOLD"] = "0"
os.environ["LOG_LEVEL"] = "WARNING"

from fastapi.testclient import TestClient  # noqa: E402

from app.core import text as text_util  # noqa: E402
from app.core.errors import AgentError  # noqa: E402
from app.main import app  # noqa: E402
from app.services import session_store  # noqa: E402
from app.services.vector_store import get_vector_store  # noqa: E402

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


# ---------------------------------------------------------------------------
# 各项检查
# ---------------------------------------------------------------------------
def check_health(client: TestClient) -> None:
    print("\n[1] GET /v1/health 探活")
    resp = client.get("/v1/health", headers=HEADERS)
    check("返回 200", resp.status_code == 200, resp.text[:200])
    body = resp.json()
    check("body.code == 0", body.get("code") == 0, str(body)[:200])
    data = body.get("data") or {}
    check("含 components 自检信息", isinstance(data.get("components"), dict))
    # mock 模式下四个组件都应就绪
    check("mock 模式下 degraded == false", data.get("degraded") is False, str(data.get("components")))


def check_auth(client: TestClient) -> None:
    print("\n[2] X-Agent-Key 鉴权")
    resp = client.get("/v1/health")  # 不带 header
    check("缺 X-Agent-Key → 401", resp.status_code == 401, f"got {resp.status_code}")
    check("401 的 body.code == 401", (resp.json() or {}).get("code") == 401)

    resp = client.get("/v1/health", headers={"X-Agent-Key": "wrong-key"})
    check("错误 key → 401", resp.status_code == 401, f"got {resp.status_code}")


def check_validation(client: TestClient) -> None:
    print("\n[3] 参数校验（必须是 400，不能是 422）")
    resp = client.post("/v1/ingest", headers=HEADERS, json={"biz_type": "note", "biz_id": 1})
    check("缺字段 → 400", resp.status_code == 400, f"got {resp.status_code}")
    check("400 的 body.code == 400", (resp.json() or {}).get("code") == 400)

    resp = client.post(
        "/v1/ingest",
        headers=HEADERS,
        json={
            "biz_type": "note",
            "biz_id": 1,
            "content": "   ",
            "scope": "personal",
            "scope_id": 1,
            "action": "upsert",
        },
    )
    check("upsert 但 content 全空白 → 400", resp.status_code == 400, f"got {resp.status_code}")

    resp = client.post(
        "/v1/ingest",
        headers=HEADERS,
        json={
            "biz_type": "note",  # note 只能配 personal
            "biz_id": 1,
            "content": "正文",
            "scope": "couple",
            "scope_id": 1,
            "action": "upsert",
        },
    )
    check("非法 biz_type+scope 组合 → 400", resp.status_code == 400, f"got {resp.status_code}")

    resp = client.post("/v1/summarize", headers=HEADERS, json={"trace_id": TRACE, "content": "  "})
    check("摘要正文全空白 → 400", resp.status_code == 400, f"got {resp.status_code}")


def check_summarize(client: TestClient) -> None:
    print("\n[4] POST /v1/summarize 摘要")
    resp = client.post(
        "/v1/summarize",
        headers=HEADERS,
        json={
            "trace_id": TRACE,
            "user_id": 1,
            "note_id": 3,
            "title": "Redis 学习笔记",
            "content": "# Redis\n\nRedis 是内存数据库，支持 RDB 与 AOF 两种持久化。",
        },
    )
    check("返回 200", resp.status_code == 200, resp.text[:200])
    summary = ((resp.json() or {}).get("data") or {}).get("summary")
    check("data.summary 非空且非空白", isinstance(summary, str) and summary.strip() != "", repr(summary)[:120])

    # 超长正文（超过 CHUNK_SIZE）应走 map-reduce 而不是崩掉或截断成空
    long_content = "第一段。" + "内容" * 15000  # 约 3 万字
    resp = client.post(
        "/v1/summarize",
        headers=HEADERS,
        json={"trace_id": TRACE, "title": "长笔记", "content": long_content},
    )
    check("超长正文（map-reduce）返回 200", resp.status_code == 200, resp.text[:200])
    chunks = text_util.split_for_summary(text_util.normalize(long_content))
    check("长正文确实被切成多块", len(chunks) > 1, f"chunks={len(chunks)}")


def check_ingest(client: TestClient) -> None:
    print("\n[5] POST /v1/ingest 幂等性")
    base = {
        "trace_id": TRACE,
        "biz_type": "note",
        "biz_id": 6,
        "content": "Redis 学习笔记\n\nRedis 是内存数据库，支持持久化。",
        "scope": "personal",
        "scope_id": 1,
        "action": "upsert",
    }

    r1 = client.post("/v1/ingest", headers=HEADERS, json=base)
    check("upsert 返回 200", r1.status_code == 200, r1.text[:200])
    d1 = (r1.json() or {}).get("data") or {}
    check("namespace == note_u1", d1.get("namespace") == "note_u1", str(d1))

    # 内容变了但 biz_id 不变 → 必须覆盖同一个点
    r2 = client.post("/v1/ingest", headers=HEADERS, json={**base, "content": "Redis 学习笔记\n\n改过的正文。"})
    check("重复 upsert 返回 200", r2.status_code == 200, r2.text[:200])
    d2 = (r2.json() or {}).get("data") or {}
    check("point id 是确定性的（两次一致）", d1.get("point_id") == d2.get("point_id"), f"{d1.get('point_id')} vs {d2.get('point_id')}")

    count = get_vector_store().count("note_u1")
    check("幂等：collection 里仍只有 1 个点", count == 1, f"count={count}")

    rd = client.post("/v1/ingest", headers=HEADERS, json={**base, "action": "delete", "content": None})
    check("delete 返回 200", rd.status_code == 200, rd.text[:200])
    rd2 = client.post("/v1/ingest", headers=HEADERS, json={**base, "action": "delete", "content": None})
    check("重复 delete 仍返回 200（幂等）", rd2.status_code == 200, rd2.text[:200])
    check("删除后 count == 0", get_vector_store().count("note_u1") == 0)


def check_chat(client: TestClient) -> None:
    print("\n[6] POST /v1/chat SSE + 检索 + 多轮会话")

    # 先放一条笔记进去，才能测到 refs 事件
    client.post(
        "/v1/ingest",
        headers=HEADERS,
        json={
            "trace_id": TRACE,
            "biz_type": "note",
            "biz_id": 7,
            "content": "Redis 学习笔记\n\nRDB 是快照持久化，AOF 是追加日志持久化。",
            "scope": "personal",
            "scope_id": 1,
            "action": "upsert",
        },
    )

    chat_body = {
        "trace_id": TRACE,
        "user_id": 1,
        "session_id": "user1",
        "message": "我的 Redis 笔记讲了什么？",
        "scope": "personal",
        "scope_id": 1,
    }

    with client.stream("POST", "/v1/chat", headers=HEADERS, json=chat_body) as resp:
        check("返回 200", resp.status_code == 200)
        ctype = resp.headers.get("content-type", "")
        check("Content-Type 是 text/event-stream", ctype.startswith("text/event-stream"), ctype)
        raw = resp.read()
    payload = raw.decode("utf-8")

    check('SSE 含 {"content": ...} 增量', '"content"' in payload, payload[:200])
    check('SSE 不含 "delta" 字段（契约要求 content）', '"delta"' not in payload, payload[:200])
    check("SSE 以 data: [DONE] 结束", payload.rstrip().endswith("data: [DONE]"), payload[-80:])
    check("SSE 每个事件以两个换行分隔", payload.count("data:") >= 2)
    check("含 refs 事件（检索链路打通）", '"refs"' in payload, payload[:300])
    check("refs 里有 biz_type/biz_id/title", all(k in payload for k in ('"biz_type"', '"biz_id"', '"title"')))

    # 第二轮，验证多轮会话落库
    with client.stream("POST", "/v1/chat", headers=HEADERS, json={**chat_body, "message": "再补充一下"}) as resp:
        resp.read()

    hist = session_store.history("u1:user1", 10)
    roles = [h["role"] for h in hist]
    check("多轮会话已落库（4 条）", len(hist) == 4, f"len={len(hist)}")
    check("角色序列 user/assistant 交替", roles == ["user", "assistant", "user", "assistant"], str(roles))


def check_dimension_guard() -> None:
    print("\n[7] 向量维度守卫（框架会静默丢弃，我们必须抛错）")
    store = get_vector_store()
    try:
        store.upsert("note_u1", "00000000-0000-0000-0000-000000000000", [0.0] * 8, {})
    except AgentError as exc:
        check("错误维度抛 AgentError(500)", exc.status_code == 500, str(exc.status_code))
    else:
        check("错误维度抛 AgentError(500)", False, "没有抛异常，会被静默丢弃")

    try:
        store.search("note_u1", [0.0] * 8, limit=1)
    except AgentError as exc:
        check("检索时错误维度同样抛 AgentError", exc.status_code == 500)
    else:
        check("检索时错误维度同样抛 AgentError", False, "没有抛异常")


# ---------------------------------------------------------------------------
def main() -> int:
    print("=" * 68)
    print("契约冒烟测试（AGENT_MOCK=true，不依赖网络 / Qdrant / API key）")
    print(f"临时数据目录: {_TMP}")
    print("=" * 68)

    try:
        with TestClient(app) as client:  # with 才会触发 lifespan（建表 + 预热）
            check_health(client)
            check_auth(client)
            check_validation(client)
            check_summarize(client)
            check_ingest(client)
            check_chat(client)
            check_dimension_guard()
    finally:
        shutil.rmtree(_TMP, ignore_errors=True)

    print("\n" + "=" * 68)
    if _FAILURES:
        print(f"结果：{_CHECKS - len(_FAILURES)}/{_CHECKS} 通过，{len(_FAILURES)} 项失败")
        for name in _FAILURES:
            print(f"  - {name}")
        return 1
    print(f"结果：全部 {_CHECKS} 项检查通过 ✅")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
