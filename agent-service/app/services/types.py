"""services 之间共享的内部数据结构。

单独成模块是为了打断循环 import：
`app/prompts/chat.py` 需要 RetrievedDoc，而 services 又会 import prompts。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

# /v1/chat 的 SSE 事件类型（契约 §2.2）
ChatEventKind = Literal["delta", "refs"]


@dataclass(slots=True)
class RetrievedDoc:
    """一条检索命中结果。"""

    biz_type: str = ""
    biz_id: Any = ""
    title: str = ""
    content: str = ""
    score: float = 0.0

    def to_ref(self) -> dict:
        """转成契约 §2.2 里 refs 事件的元素格式。"""
        return {
            "biz_type": self.biz_type,
            "biz_id": self.biz_id,
            "title": self.title,
        }
