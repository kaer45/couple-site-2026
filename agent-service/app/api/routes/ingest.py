"""`POST /v1/ingest` —— 业务数据向量同步（契约 §1）。

同步 `def`：service 内部是阻塞调用（嵌入 + Qdrant），必须让 FastAPI 丢线程池。
"""

from __future__ import annotations

from fastapi import APIRouter

from app.core.errors import ok
from app.logging_conf import bind_trace
from app.schemas.ingest import IngestRequest
from app.schemas.common import ResultEnvelope
from app.services import ingest_service

router = APIRouter(prefix="/v1", tags=["ingest"])


@router.post(
    "/ingest",
    response_model=ResultEnvelope,
    summary="写入 / 删除向量（幂等）",
)
def ingest(req: IngestRequest) -> dict:
    # 成功返回 HTTP 200 + code=0；
    # 失败时由 core.errors 的处理器返回非 200 —— 这一点很关键，
    # 因为 Java 对 ingest **只看 HTTP 状态码**（契约 §1.2）。
    with bind_trace(req.trace_id):
        data = ingest_service.handle(req)
    return ok(data)
