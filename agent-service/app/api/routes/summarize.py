"""`POST /v1/summarize` —— 笔记摘要（契约 §3）。

同步 `def`：模型调用是阻塞的，交给 FastAPI 的线程池执行。
契约要求：`data.summary` 非空且非空白；失败返回 4xx/5xx（Java 失败不写库）。
"""

from __future__ import annotations

from fastapi import APIRouter

from app.core.errors import ok
from app.logging_conf import bind_trace
from app.schemas.common import ResultEnvelope
from app.schemas.summarize import SummarizeRequest
from app.services import summarize_service

router = APIRouter(prefix="/v1", tags=["summarize"])


@router.post(
    "/summarize",
    response_model=ResultEnvelope,
    summary="生成笔记摘要（普通 JSON，非流式）",
)
def summarize(req: SummarizeRequest) -> dict:
    with bind_trace(req.trace_id):
        summary = summarize_service.summarize(req)
    return ok({"summary": summary})
