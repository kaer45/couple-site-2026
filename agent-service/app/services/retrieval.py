"""向量检索：把用户问题向量化，在对应 namespace 里找相关资料。

namespace 按契约 §6：
- `scope=couple`   → `moment_c{scope_id}`（情侣动态 + 相册照片共用同一域）
- `scope=personal` → `note_u{scope_id}`（仅本人可见）

降级策略（degrade-with-warnings，不做 fail-fast）：
检索失败**不影响对话本身** —— 记 warning 后返回空列表，模型基于通用知识回答。
原因：检索只是增强，让整条对话 500 掉是更差的体验。
"""

from __future__ import annotations

import logging

from app.config import get_settings
from app.core import names, text
from app.core.errors import AgentError
from app.services.embedding_client import get_embedding
from app.services.types import RetrievedDoc
from app.services.vector_store import get_vector_store

logger = logging.getLogger(__name__)


def retrieve(
    scope: str,
    scope_id: int | None,
    query: str,
    top_k: int | None = None,
    score_threshold: float | None = None,
) -> list[RetrievedDoc]:
    """检索相关资料。任何异常都被吞掉并降级为空列表。"""
    settings = get_settings()

    if not settings.retrieval_enabled:
        return []
    if not (query or "").strip():
        return []
    if scope_id is None:
        logger.warning("scope_id 缺失，跳过检索（scope=%s）", scope)
        return []

    try:
        namespace = names.namespace_for(scope, scope_id)
    except ValueError as exc:
        logger.warning("namespace 推断失败，跳过检索: %s", exc)
        return []

    limit = top_k or settings.retrieval_top_k
    threshold = (
        score_threshold
        if score_threshold is not None
        else settings.retrieval_score_threshold
    )

    try:
        vector = get_embedding().encode(query)
    except AgentError as exc:
        logger.warning("问题向量化失败，跳过检索: %s", exc.message)
        return []
    except Exception as exc:  # noqa: BLE001
        logger.warning("问题向量化异常，跳过检索: %s", exc)
        return []

    try:
        # where 是扁平 dict，框架会转成 Filter(must=[FieldCondition(...)])
        hits = get_vector_store().search(
            namespace=namespace,
            vector=vector,
            limit=limit,
            score_threshold=threshold if threshold > 0 else None,
            where={"scope_id": scope_id},
        )
    except AgentError as exc:
        logger.warning("检索失败，降级为无上下文: %s", exc.message)
        return []
    except Exception as exc:  # noqa: BLE001
        logger.warning("检索异常，降级为无上下文: %s", exc)
        return []

    docs: list[RetrievedDoc] = []
    for hit in hits:
        payload = hit.get("metadata") or {}
        content = text.safe_excerpt(payload.get("content") or "", 1200)
        if not content:
            continue
        docs.append(
            RetrievedDoc(
                biz_type=str(payload.get("biz_type") or ""),
                biz_id=payload.get("biz_id") if payload.get("biz_id") is not None else hit.get("id"),
                title=payload.get("title") or text.first_line(content),
                content=content,
                score=float(hit.get("score") or 0.0),
            )
        )

    if docs:
        logger.info(
            "检索命中 %s 条（namespace=%s, top_score=%.3f）",
            len(docs),
            namespace,
            docs[0].score,
        )
    else:
        logger.info("检索无命中（namespace=%s）", namespace)
    return docs
