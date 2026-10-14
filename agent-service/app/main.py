"""FastAPI 应用入口。

启动策略（重要）：**按能力降级，不做整体 fail-fast**。
    - SQLite 失败 → 记 exception，服务继续（会话功能退化）
    - Qdrant 不通 → 记 warning，服务继续（/v1/ingest 与检索返回 503 / 空上下文）
    - 嵌入模型不可用 → 记 warning，服务继续（只有 /v1/ingest 受影响）
    - LLM 不可用 → 服务继续（/v1/chat 与 /v1/summarize 返回 503）

理由：这三块能力彼此独立。Qdrant 没起就把 /v1/chat 一起弄挂，是最糟的取舍。
但**单次请求内的失败必须响亮**（非 200），因为 Java 对 ingest 只看状态码。
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI

from app import __version__
from app.api.deps import verify_key
from app.api.routes import chat, health, ingest, summarize
from app.config import Settings, get_settings
from app.core.errors import register_exception_handlers
from app.db.sqlite_store import get_sqlite
from app.logging_conf import bind_trace, setup_logging
from app.services import session_store
from app.services.embedding_client import get_embedding
from app.services.llm_client import get_llm
from app.services.vector_store import get_vector_store

logger = logging.getLogger(__name__)


def _warmup(settings: Settings) -> None:
    """预热外部依赖。全部 best-effort，失败只降级不阻断启动。"""
    if settings.agent_mock:
        logger.warning("AGENT_MOCK=true：跳过所有外部依赖预热（仅供联调，勿用于生产）")
        return

    # 1) Qdrant
    try:
        qdrant_ok, qdrant_msg = get_vector_store().health()
        if qdrant_ok:
            logger.info("Qdrant 探活成功: %s", settings.qdrant_url)
        else:
            logger.warning(
                "Qdrant 探活失败（/v1/ingest 与检索将不可用）: %s", qdrant_msg
            )
    except Exception as exc:  # noqa: BLE001
        logger.warning("Qdrant 探活异常（/v1/ingest 与检索将不可用）: %s", exc)

    # 2) 嵌入模型。注意它的构造函数末尾会发一次真实网络请求探测维度，
    #    所以这一步可能耗时几秒，且 key/网络有问题时会抛异常 —— 必须容错。
    if not settings.embedding_configured:
        logger.warning("DASHSCOPE_API_KEY 未配置：/v1/ingest 与检索将返回 503 / 空上下文")
    else:
        try:
            get_embedding().encode("warmup")
            logger.info(
                "嵌入模型预热成功: %s (dim=%s)",
                settings.embed_model_name,
                settings.embed_dim,
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("嵌入模型预热失败（服务继续启动）: %s", exc)

    # 3) LLM 只做配置检查，不主动发请求（省一次计费与启动耗时）
    if not settings.llm_configured:
        logger.warning("LLM_API_KEY 未配置：/v1/chat 与 /v1/summarize 将返回 503")


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    setup_logging(settings.log_level)

    with bind_trace(None):
        logger.info("Agent 服务启动中… version=%s mock=%s", __version__, settings.agent_mock)

        try:
            get_sqlite().init_schema()
            session_store.purge_expired()
        except Exception as exc:  # noqa: BLE001
            logger.exception("初始化 SQLite 失败（会话历史将不可用）: %s", exc)

        _warmup(settings)
        logger.info("Agent 服务已就绪，监听 %s:%s", settings.host, settings.port)

    yield

    with bind_trace(None):
        get_llm().close()
        logger.info("Agent 服务已停止")


app = FastAPI(
    title="couple-site Agent 服务",
    version=__version__,
    description=(
        "Java ↔ Python 契约的 Python 侧实现。契约见 docs/agent-api.md，"
        "施工说明见 docs/agent-service-build-guide.md。"
    ),
    lifespan=lifespan,
)

register_exception_handlers(app)

# 四个路由统一挂 X-Agent-Key 鉴权（契约 §0）。集中在这里，避免每个路由各写一遍。
_auth = [Depends(verify_key)]
app.include_router(health.router, dependencies=_auth)
app.include_router(ingest.router, dependencies=_auth)
app.include_router(chat.router, dependencies=_auth)
app.include_router(summarize.router, dependencies=_auth)
