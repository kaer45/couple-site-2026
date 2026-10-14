"""框架 API 假设守卫。

我们的实现依赖若干 hello-agents 0.2.9 **没有在文档里承诺**的行为：

    - `QdrantVectorStore` 的 `vector_size` 默认是 384（不是 1024）
    - `add_vectors()` 收的是**已算好的向量**，不是文本
    - `add_vectors()` 会把**非 UUID 的字符串 id 静默替换成随机 UUID**
    - `DashScopeEmbedding.__init__` 会发一次真实网络请求探测维度
    - 存在 `create_embedding_model_with_fallback()`，会在失败时静默降级到 1000 维 TF-IDF

一旦有人升级 hello-agents，这些假设可能静默失效并导致**数据静默损坏**。
本脚本把它们变成可执行断言，升级后先跑这个。

运行：
    cd agent-service
    python tests/check_framework_api.py
"""

from __future__ import annotations

import ast
import inspect
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.core import names  # noqa: E402

_FAILURES: list[str] = []
_CHECKS = 0

EXPECTED_VERSION = "0.2.9"


def check(name: str, condition: bool, detail: str = "") -> None:
    global _CHECKS
    _CHECKS += 1
    if condition:
        print(f"  ok   {name}")
    else:
        _FAILURES.append(name)
        print(f"  FAIL {name}" + (f"  ← {detail}" if detail else ""))


def main() -> int:
    print("=" * 68)
    print("hello-agents 框架 API 假设守卫")
    print("=" * 68)

    # ---------- 版本 ----------
    print("\n[1] 版本")
    import hello_agents

    version = getattr(hello_agents, "__version__", "?")
    check(f"版本为 {EXPECTED_VERSION}", version == EXPECTED_VERSION, f"实际 {version}")

    # ---------- LLM ----------
    print("\n[2] HelloAgentsLLM")
    from hello_agents import HelloAgentsLLM

    for method in ("invoke", "stream_invoke", "think"):
        check(f"有 {method}()", hasattr(HelloAgentsLLM, method))
    llm_params = inspect.signature(HelloAgentsLLM.__init__).parameters
    for param in ("model", "api_key", "base_url", "timeout"):
        check(f"__init__ 接受 {param}", param in llm_params)

    # 构造期不应发网络请求（否则启动就会被拖慢）
    try:
        llm = HelloAgentsLLM(
            model="deepseek-chat",
            api_key="sk-dummy",
            base_url="https://api.deepseek.com",
            timeout=5,
        )
        check("可用假 key 构造（构造期不发请求）", True)
        closer = getattr(llm, "close", None) or getattr(getattr(llm, "_client", None), "close", None)
        if callable(closer):
            closer()
    except Exception as exc:  # noqa: BLE001
        check("可用假 key 构造（构造期不发请求）", False, f"{type(exc).__name__}: {exc}")

    # ---------- 嵌入 ----------
    print("\n[3] DashScopeEmbedding")
    from hello_agents.memory import embedding as embedding_mod

    check("存在 DashScopeEmbedding", hasattr(embedding_mod, "DashScopeEmbedding"))
    check(
        "存在 create_embedding_model_with_fallback（我们必须避开它）",
        hasattr(embedding_mod, "create_embedding_model_with_fallback"),
    )
    ds_params = inspect.signature(embedding_mod.DashScopeEmbedding.__init__).parameters
    for param in ("model_name", "api_key", "base_url"):
        check(f"__init__ 接受 {param}", param in ds_params)

    # ---------- Qdrant ----------
    print("\n[4] QdrantVectorStore / QdrantConnectionManager")
    from hello_agents.memory.storage.qdrant_store import (
        QdrantConnectionManager,
        QdrantVectorStore,
    )

    init_params = inspect.signature(QdrantVectorStore.__init__).parameters
    check("__init__ 有 vector_size 参数", "vector_size" in init_params)
    default_size = init_params["vector_size"].default if "vector_size" in init_params else None
    check(
        "vector_size 默认是 384（≠ DashScope 的 1024，所以我们必须显式传值）",
        default_size == 384,
        f"默认={default_size}",
    )

    get_params = inspect.signature(QdrantConnectionManager.get_instance).parameters
    for param in ("url", "api_key", "collection_name", "vector_size"):
        check(f"get_instance 接受 {param}", param in get_params)

    add_params = inspect.signature(QdrantVectorStore.add_vectors).parameters
    check("add_vectors 收 vectors（不是 texts）", "vectors" in add_params and "texts" not in add_params)
    check("add_vectors 接受 ids（幂等所需）", "ids" in add_params)

    search_params = inspect.signature(QdrantVectorStore.search_similar).parameters
    check("search_similar 接受 where（域隔离所需）", "where" in search_params)
    check("search_similar 接受 score_threshold", "score_threshold" in search_params)

    # ---------- 最关键：point id 必须是合法 UUID ----------
    print("\n[5] point id 必须是合法 UUID（否则框架会换成随机值）")
    for biz_type, biz_id in (("note", 1), ("note", "6"), ("moment", 42), ("photo", "abc")):
        pid = names.point_id_for(biz_type, biz_id)
        try:
            uuid.UUID(pid)
            valid = True
        except Exception:  # noqa: BLE001
            valid = False
        check(f"point_id_for({biz_type!r}, {biz_id!r}) 是合法 UUID", valid, pid)

    # ---------- 断言我们自己的源码没有踩那个静默降级函数 ----------
    print("\n[6] 源码层面：不得调用 create_embedding_model_with_fallback()")
    offenders: list[str] = []
    for py in (ROOT / "app").rglob("*.py"):
        tree = ast.parse(py.read_text(encoding="utf-8"), filename=str(py))
        for node in ast.walk(tree):
            if isinstance(node, ast.Call):
                func = node.func
                callee = getattr(func, "id", None) or getattr(func, "attr", None)
                if callee == "create_embedding_model_with_fallback":
                    offenders.append(f"{py.relative_to(ROOT)}:{node.lineno}")
    check("未调用（用 AST 精确检查，不受注释/文档字符串干扰）", not offenders, str(offenders))

    # ---------- 结果 ----------
    print("\n" + "=" * 68)
    if _FAILURES:
        print(f"结果：{_CHECKS - len(_FAILURES)}/{_CHECKS} 通过，{len(_FAILURES)} 项失败")
        for name in _FAILURES:
            print(f"  - {name}")
        print("\n提示：若刚升级过 hello-agents，请重新核对 app/services/ 下的三个客户端封装。")
        return 1
    print(f"结果：全部 {_CHECKS} 项假设成立 ✅")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
