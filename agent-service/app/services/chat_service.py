"""`/v1/chat` 的业务实现：多轮上下文 + 检索增强 + 流式产出。

事件流（服务层只产出语义事件，SSE 格式由路由层负责）：
    ("delta", "增量文本")     ← 可多次
    ("refs", [ {...}, ... ])  ← 可选，契约 §2.2，/ai 页用于展示引用出处

顺序与契约 §2.2 的推荐实现一致：先 content，再 refs，最后由路由层发 [DONE]。

注意：本模块**全部是同步生成器**（三个客户端都是同步的）。
路由层把同步生成器直接交给 `StreamingResponse`，Starlette 会自动用
`iterate_in_threadpool` 包一层，不会阻塞事件循环。
"""

from __future__ import annotations

import logging
from typing import Iterator

from app.config import get_settings
from app.prompts import chat as chat_prompt
from app.schemas.chat import ChatRequest
from app.services import retrieval, session_store
from app.services.llm_client import get_llm

logger = logging.getLogger(__name__)

# (事件类型, 负载)。类型取值见 services/types.ChatEventKind
ChatEvent = tuple[str, object]


def build_messages(
    req: ChatRequest, docs: list, history: list[dict[str, str]]
) -> list[dict[str, str]]:
    """拼装发给模型的消息列表：system(含检索资料) + 历史 + 本次提问。"""
    messages: list[dict[str, str]] = [
        {"role": "system", "content": chat_prompt.build_system_prompt(docs)}
    ]
    messages.extend(history)
    messages.append({"role": "user", "content": req.message})
    return messages


def run(req: ChatRequest) -> Iterator[ChatEvent]:
    """执行一次对话，流式产出事件。"""
    settings = get_settings()
    session_key = session_store.key_for(req.user_id, req.session_id)

    history = session_store.history(session_key, settings.session_max_turns)
    docs = retrieval.retrieve(req.scope, req.scope_id, req.message)
    messages = build_messages(req, docs, history)

    # 先落库用户提问：即使随后生成失败或被客户端中断，提问本身也不会丢
    session_store.append(session_key, "user", req.message)

    logger.info(
        "chat 开始: session=%s scope=%s scope_id=%s history=%s refs=%s",
        session_key,
        req.scope,
        req.scope_id,
        len(history),
        len(docs),
    )

    collected: list[str] = []
    try:
        for delta in get_llm().stream(messages):
            collected.append(delta)
            yield ("delta", delta)
    finally:
        # 无论是正常结束、异常还是被中断（GeneratorExit），都把已产出的部分存下来。
        # 这与 Java 侧「流中断时保存已推送内容」的行为保持一致。
        answer = "".join(collected).strip()
        if answer:
            session_store.append(session_key, "assistant", answer)
            logger.info("chat 结束: 已保存回答 %s 字", len(answer))

    if docs:
        yield ("refs", [doc.to_ref() for doc in docs])
