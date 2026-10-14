"""`/v1/summarize` 的业务实现：笔记摘要（契约 §3）。

要点：
- `content` 最长 20 万字，**Python 自己负责截断 / 分段**（Java 不裁）。
- 短文本一次调用；超长文本走 map-reduce（先分段概括，再合并）。
- 返回空内容视为失败 —— Java 会校验 `data.summary` 非空且非空白，
  且**失败不写库**，返回空串等于让用户白等。
"""

from __future__ import annotations

import logging
import re

from app.core import text
from app.core.errors import AgentError
from app.prompts import summarize as prompt
from app.schemas.summarize import SummarizeRequest
from app.services.llm_client import get_llm

logger = logging.getLogger(__name__)

# 摘要输出的防御性上限（正常应为 100–300 字）
MAX_SUMMARY_OUTPUT = 1000

# 清掉模型可能残留的 Markdown 标记（前端卡片只显示两行，标记会很难看）
_MD_INLINE = re.compile(r"(\*\*|__|`{1,3})")
_MD_LEAD = re.compile(r"^[\s>]*(#{1,6}|[-*+]|\d+\.)\s*", re.MULTILINE)
_MULTI_NEWLINE = re.compile(r"\n{2,}")


def summarize(req: SummarizeRequest) -> str:
    content = text.truncate(text.normalize(req.content), text.MAX_SUMMARY_INPUT)
    if not content:
        raise AgentError("笔记正文为空，无法生成摘要", status_code=400, code=400)

    title = (req.title or "").strip() or text.first_line(content) or "未命名笔记"
    chunks = text.split_for_summary(content)

    llm = get_llm()
    if len(chunks) <= 1:
        logger.info("摘要：单段模式 title=%s len=%s", title, len(content))
        raw = llm.invoke(
            [
                {"role": "system", "content": prompt.SYSTEM},
                {
                    "role": "user",
                    "content": prompt.USER_TEMPLATE.format(title=title, content=content),
                },
            ]
        )
    else:
        logger.info("摘要：map-reduce 模式 title=%s len=%s chunks=%s", title, len(content), len(chunks))
        raw = _map_reduce(llm, title, chunks)

    summary = _plain_text(raw)
    if not summary:
        raise AgentError("摘要生成失败：模型返回了空内容", status_code=500, code=500)
    return summary[:MAX_SUMMARY_OUTPUT]


def _map_reduce(llm, title: str, chunks: list[str]) -> str:
    partials: list[str] = []
    total = len(chunks)
    for index, chunk in enumerate(chunks, start=1):
        partial = llm.invoke(
            [
                {"role": "system", "content": prompt.CHUNK_SYSTEM},
                {
                    "role": "user",
                    "content": prompt.CHUNK_USER_TEMPLATE.format(
                        index=index, total=total, content=chunk
                    ),
                },
            ]
        )
        if partial:
            partials.append(f"- {partial.strip()}")
        logger.debug("摘要分片 %s/%s 完成", index, total)

    if not partials:
        raise AgentError("摘要生成失败：分段概括全部为空", status_code=500, code=500)

    return llm.invoke(
        [
            {"role": "system", "content": prompt.MERGE_SYSTEM},
            {
                "role": "user",
                "content": prompt.MERGE_USER_TEMPLATE.format(
                    title=title, content="\n".join(partials)
                ),
            },
        ]
    )


def _plain_text(raw: str | None) -> str:
    """把模型输出规整成纯文本段落。"""
    value = (raw or "").strip()
    if not value:
        return ""
    value = _MD_INLINE.sub("", value)
    value = _MD_LEAD.sub("", value)
    value = _MULTI_NEWLINE.sub("\n", value)
    return value.strip().strip("\"“”'‘’")
