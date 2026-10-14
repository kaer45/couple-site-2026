"""`/v1/ingest` 的业务实现：把 Java 推来的业务文本写入 / 移出向量库。

关键约束：
- **幂等**：point id 由 `core.names.point_id_for(biz_type, biz_id)` 生成确定性 UUID，
  所以同一业务对象重复 upsert 只会覆盖同一个点，不会越积越多（契约 §1.3）。
- **delete 幂等**：删不存在的 id 也算成功。
- **失败必须抛 AgentError**：Java 只看 HTTP 状态码（契约 §1.2），
  返回 200 就等于告诉它「同步成功」，数据从此再也补不上。
"""

from __future__ import annotations

import logging
import time

from app.core import names, text
from app.core.errors import bad_request
from app.db.sqlite_store import get_sqlite
from app.schemas.ingest import IngestRequest
from app.services.embedding_client import get_embedding
from app.services.vector_store import get_vector_store

logger = logging.getLogger(__name__)


def handle(req: IngestRequest) -> dict:
    """处理一次向量同步，返回统计信息（用于日志与 /v1/ingest 的响应 data）。"""
    try:
        names.validate_biz_scope(req.biz_type, req.scope)
        namespace = names.namespace_for(req.scope, req.scope_id)
    except ValueError as exc:
        raise bad_request(str(exc)) from exc

    point_id = names.point_id_for(req.biz_type, req.biz_id)
    store = get_vector_store()

    if req.action == "delete":
        store.delete(namespace, [point_id])
        _audit(req, namespace, point_id, success=True, message="deleted")
        logger.info(
            "ingest delete: %s/%s → %s[%s]", req.biz_type, req.biz_id, namespace, point_id
        )
        return {"namespace": namespace, "point_id": point_id, "action": "delete"}

    # ---------- upsert ----------
    content = text.normalize(req.content)
    if not content:
        # Pydantic 已拦过一次，这里兜底（例如 content 全是零宽字符）
        raise bad_request("action=upsert 时 content 不能为空")

    payload = {
        "biz_type": req.biz_type,
        "biz_id": req.biz_id,
        "scope": req.scope,
        "scope_id": req.scope_id,
        "content": content,
        "title": text.first_line(content),
        "created_at": int(time.time()),
    }

    try:
        vector = get_embedding().encode(content)
        store.upsert(namespace, point_id, vector, payload)
    except Exception as exc:  # noqa: BLE001
        _audit(req, namespace, point_id, success=False, message=str(exc)[:300])
        raise

    _audit(req, namespace, point_id, success=True, message="upserted")
    logger.info(
        "ingest upsert: %s/%s → %s[%s] content_len=%s",
        req.biz_type,
        req.biz_id,
        namespace,
        point_id,
        len(content),
    )
    return {"namespace": namespace, "point_id": point_id, "action": "upsert"}


def _audit(
    req: IngestRequest, namespace: str, point_id: str, success: bool, message: str
) -> None:
    """写审计表。失败只记日志，不能因为它把主流程搞挂。"""
    try:
        get_sqlite().execute(
            "INSERT INTO ingest_log(trace_id, biz_type, biz_id, scope, scope_id, "
            "action, namespace, point_id, success, message, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                req.trace_id,
                req.biz_type,
                str(req.biz_id),
                req.scope,
                req.scope_id,
                req.action,
                namespace,
                point_id,
                1 if success else 0,
                message,
                int(time.time()),
            ),
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("写入 ingest 审计失败: %s", exc)
