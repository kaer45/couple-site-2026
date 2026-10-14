"""`POST /v1/chat` —— 对话 SSE（契约 §2）。

SSE 格式（与契约 §2.2 严格一致，字段名是 **content** 不是 delta）：
    data: {"content":"增量"}\\n\\n
    data: {"refs":[{"biz_type":"moment","biz_id":9,"title":"..."}]}\\n\\n
    data: {"error":"人类可读信息"}\\n\\n        （仅出错时）
    data: [DONE]\\n\\n                          （必须发，Java 靠它结束流）
"""

from __future__ import annotations

import json
import logging
from typing import Iterator

from fastapi import APIRouter
from fastapi.responses import StreamingResponse

from app.core.errors import AgentError
from app.logging_conf import set_trace
from app.schemas.chat import ChatRequest
from app.services import chat_service

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/v1", tags=["chat"])


def _event(payload: dict) -> str:
    """一个 SSE 事件：`data: <json>` + **两个** 换行。"""
    # ensure_ascii=False：否则中文会变成 \uXXXX，抓包和日志都没法看
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"


def _sse(req: ChatRequest) -> Iterator[str]:
    """同步生成器。

    Starlette 的 StreamingResponse 会自动用 `iterate_in_threadpool` 包装同步可迭代对象，
    所以这里放阻塞调用（LLM 流式读取）不会卡住事件循环。

    ⚠️ 这里**必须**用 `set_trace()` 而不是 `with bind_trace(...)`：
    `iterate_in_threadpool` 对每一次 `next()` 单独发起一次线程调用，
    每一步可能落在不同的工作线程、拿到不同的 context 副本，
    跨 Context 做 ContextVar 的 set/reset 会抛
    「Token was created in a different Context」——整个 SSE 请求会 500。
    """
    trace = req.trace_id
    events = chat_service.run(req)

    try:
        while True:
            # 每次恢复执行都可能换了 Context，所以每一步都重设一次 trace_id
            set_trace(trace)
            try:
                kind, payload = next(events)
            except StopIteration:
                break
            except AgentError as exc:
                logger.warning("对话失败: %s", exc.message)
                yield _event({"error": exc.message})
                break
            except Exception as exc:  # noqa: BLE001
                # 流已经开始，不能再走 exception_handler，只能作为 error 事件发出
                logger.exception("对话未捕获异常: %s", exc)
                yield _event({"error": "AI 服务内部错误"})
                break

            if kind == "delta":
                yield _event({"content": payload})
            elif kind == "refs":
                yield _event({"refs": payload})
    finally:
        # 显式关闭下游生成器，触发它内部的 finally —— 保证「客户端中途断开」
        # 时也能把已产出的部分回答落库（与 Java 侧保存已推送内容的行为一致）。
        set_trace(trace)
        events.close()

    # 必须发结束标记；不发前端会一直转圈、消息不落库
    set_trace(trace)
    yield "data: [DONE]\n\n"


@router.post("/chat", summary="对话（SSE 流式）")
async def chat(req: ChatRequest) -> StreamingResponse:
    return StreamingResponse(
        _sse(req),  # 生成器是懒的：第一次 next() 才真正开始干活
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            # 防止 Nginx 等反代缓冲 SSE
            "X-Accel-Buffering": "no",
        },
    )
