"""会话历史：按 session_key 维护多轮上下文。

契约 §2.3：单次会话上下文由 **Python 侧**按 `user_id + session_id` 维护，Java 不感知，
建议保留 10 条 / 24h 过期。

⚠️ hello-agents 0.2.9 **不自带会话持久化**（它的 `memory/storage/document_store.py`
是存 RAG 文档的），所以这一层必须自己写。

session_key 约定：`u{user_id}:{session_id}`，比只用 session_id 更安全，
避免不同用户传入相同 session_id 时串话。user_id 缺失时退化为 `anon:{session_id}`。
"""

from __future__ import annotations

import logging
import time

from app.config import get_settings
from app.db.sqlite_store import get_sqlite

logger = logging.getLogger(__name__)


def key_for(user_id: int | None, session_id: str) -> str:
    owner = f"u{user_id}" if user_id is not None else "anon"
    return f"{owner}:{session_id}"


def append(session_key: str, role: str, content: str) -> None:
    """追加一条消息。失败只记日志，不影响主流程。"""
    if not content:
        return
    try:
        get_sqlite().execute(
            "INSERT INTO chat_message(session_key, role, content, created_at) "
            "VALUES (?, ?, ?, ?)",
            (session_key, role, content, int(time.time())),
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("写入会话历史失败: %s", exc)


def history(session_key: str, max_messages: int | None = None) -> list[dict[str, str]]:
    """取最近 N 条消息，按时间正序返回（直接可拼进 messages）。"""
    settings = get_settings()
    limit = max_messages if max_messages is not None else settings.session_max_turns
    try:
        rows = get_sqlite().query(
            "SELECT role, content FROM chat_message WHERE session_key = ? "
            "ORDER BY id DESC LIMIT ?",
            (session_key, max(0, limit)),
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("读取会话历史失败: %s", exc)
        return []

    rows.reverse()  # 数据库是倒序取的，这里翻回正序
    return [{"role": r["role"], "content": r["content"]} for r in rows]


def purge_expired() -> int:
    """清理超过 TTL 的消息。启动时调用一次即可。"""
    settings = get_settings()
    cutoff = int(time.time()) - settings.session_ttl_hours * 3600
    try:
        deleted = get_sqlite().execute(
            "DELETE FROM chat_message WHERE created_at < ?", (cutoff,)
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("清理过期会话失败: %s", exc)
        return 0
    if deleted:
        logger.info("清理过期会话消息 %s 条（TTL=%sh）", deleted, settings.session_ttl_hours)
    return deleted


def clear(session_key: str) -> int:
    return get_sqlite().execute(
        "DELETE FROM chat_message WHERE session_key = ?", (session_key,)
    )
