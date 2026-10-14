"""`GET /v1/health` —— 探活与自检（契约 §4）。

Java **不主动轮询**本接口（它只靠调用结果判定），这里主要给人看和给容器做存活检查。
因为是同步 `def`，FastAPI 会把它整体丢进线程池，不会阻塞事件循环。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter

from app import __version__
from app.config import get_settings
from app.core.errors import ok
from app.db.sqlite_store import get_sqlite
from app.services.embedding_client import get_embedding
from app.services.llm_client import get_llm
from app.services.vector_store import get_vector_store

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/v1", tags=["health"])


@router.get("/health", summary="探活与依赖自检")
def health() -> dict:
    settings = get_settings()

    components: dict[str, dict] = {}

    # LLM
    components["llm"] = get_llm().describe()

    # 嵌入模型（不主动触发探测，只看配置；真正探测在启动预热时做过）
    components["embedding"] = get_embedding().describe()

    # Qdrant
    try:
        qdrant_ok, qdrant_msg = get_vector_store().health()
    except Exception as exc:  # noqa: BLE001
        qdrant_ok, qdrant_msg = False, str(exc)[:200]
    components["qdrant"] = {"ready": qdrant_ok, "message": qdrant_msg}

    # SQLite
    try:
        get_sqlite().query("SELECT 1 AS ok")
        components["sqlite"] = {"ready": True, "message": str(settings.sqlite_file)}
    except Exception as exc:  # noqa: BLE001
        components["sqlite"] = {"ready": False, "message": str(exc)[:200]}

    degraded = any(not c.get("ready", True) for c in components.values())

    return ok(
        {
            "version": __version__,
            "mock": settings.agent_mock,
            "degraded": degraded,
            "config": settings.summary(),
            "components": components,
        },
        message="degraded" if degraded else "ok",
    )
