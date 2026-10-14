"""纯文本处理工具。

全部无第三方依赖，因此可以直接单测（tests/test_core.py）。
契约 §3.1 明确：Java 最多发 20 万字正文，**Python 自己负责截断 / 分段**。
"""

from __future__ import annotations

import re

# 契约 §3.1：Java 侧抽取上限 20 万字
MAX_SUMMARY_INPUT = 200_000

# 单次送入模型的正文上限（约 8k–10k token，留足输出空间）
CHUNK_SIZE = 12_000
CHUNK_OVERLAP = 500

# refs 事件里标题的最大长度
TITLE_MAX = 60

_ZERO_WIDTH = dict.fromkeys(map(ord, "\u200b\u200c\u200d\ufeff"), None)
_BLANK_LINES = re.compile(r"\n{3,}")
_HEADING_PREFIX = re.compile(r"^[#>\-\*\s]+")


def normalize(text: str | None) -> str:
    """去掉零宽字符、统一换行、压缩连续空行。"""
    if not text:
        return ""
    text = text.translate(_ZERO_WIDTH)
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = _BLANK_LINES.sub("\n\n", text)
    return text.strip()


def truncate(text: str, limit: int = MAX_SUMMARY_INPUT) -> str:
    if len(text) <= limit:
        return text
    return text[:limit]


def split_for_summary(
    text: str, size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP
) -> list[str]:
    """把长正文切成若干块，尽量落在段落 / 句末边界上。"""
    text = normalize(text)
    if not text:
        return []
    if len(text) <= size:
        return [text]

    chunks: list[str] = []
    start = 0
    total = len(text)
    # 从窗口 60% 处开始向前找分隔符，避免切出过短的碎片
    min_cut = start + int(size * 0.6)

    while start < total:
        end = min(start + size, total)
        if end < total:
            search_from = max(min_cut, start)
            best = -1
            for sep in ("\n\n", "\n", "。", "！", "？", "；", ". "):
                idx = text.rfind(sep, search_from, end)
                if idx != -1:
                    best = max(best, idx + len(sep))
            if best > start:
                end = best
        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)
        if end >= total:
            break
        # 保证严格前进，避免 size <= overlap 时死循环
        start = max(end - overlap, start + 1)
        min_cut = start + int(size * 0.6) - overlap

    return chunks


def first_line(text: str, limit: int = TITLE_MAX) -> str:
    """用正文首行推断标题，供 /v1/chat 的 refs 事件使用。

    Java 推给 ingest 的 content 是「标题\\n正文」（契约 §1.1），所以首行就是标题。
    """
    for raw in normalize(text).split("\n"):
        line = _HEADING_PREFIX.sub("", raw).strip()
        if line:
            return line[:limit]
    return ""


def safe_excerpt(text: str, limit: int = 300) -> str:
    """给日志 / refs 用的安全截断，避免把整篇正文塞进日志。"""
    text = normalize(text)
    if len(text) <= limit:
        return text
    return text[:limit] + "…"
