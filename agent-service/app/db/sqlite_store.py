"""SQLite 访问层：只负责连接、建表与最基础的读写。

设计说明：
- 路由是**同步 `def`**，FastAPI 会在线程池里执行，所以**不能跨线程复用连接**。
  这里每次操作新建一个连接（SQLite 打开成本极低），并开启 WAL 以支持并发读。
- 业务语义（会话轮次、过期清理、审计）放在 `app/services/` 里，本层保持哑。
"""

from __future__ import annotations

import logging
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

logger = logging.getLogger(__name__)

SCHEMA = """
-- 会话消息：多轮上下文（契约 §2.3 要求由 Python 侧维护）
CREATE TABLE IF NOT EXISTS chat_message (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_key TEXT    NOT NULL,
    role        TEXT    NOT NULL,
    content     TEXT    NOT NULL,
    created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chat_message_key ON chat_message(session_key, id);

-- 向量同步审计：出问题时可以据此人工补同步（契约 §1.2 提到「可人工补同步」）
CREATE TABLE IF NOT EXISTS ingest_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    trace_id   TEXT,
    biz_type   TEXT    NOT NULL,
    biz_id     TEXT    NOT NULL,
    scope      TEXT    NOT NULL,
    scope_id   INTEGER NOT NULL,
    action     TEXT    NOT NULL,
    namespace  TEXT    NOT NULL,
    point_id   TEXT    NOT NULL,
    success    INTEGER NOT NULL,
    message    TEXT,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ingest_log_biz ON ingest_log(biz_type, biz_id, created_at);
"""


class SqliteStore:
    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)

    # ---------- 连接 ----------
    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(str(self.path), timeout=10.0)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def init_schema(self) -> None:
        with self.connect() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript(SCHEMA)
        logger.info("SQLite 就绪: %s", self.path)

    # ---------- 基础操作 ----------
    def execute(self, sql: str, params: tuple | list = ()) -> int:
        with self.connect() as conn:
            cursor = conn.execute(sql, params)
            return cursor.rowcount

    def query(self, sql: str, params: tuple | list = ()) -> list[dict[str, Any]]:
        with self.connect() as conn:
            rows = conn.execute(sql, params).fetchall()
        return [dict(row) for row in rows]

    def query_one(self, sql: str, params: tuple | list = ()) -> dict[str, Any] | None:
        rows = self.query(sql, params)
        return rows[0] if rows else None


# ---------------------------------------------------------------------------
# 进程内单例
# ---------------------------------------------------------------------------
_store: SqliteStore | None = None


def get_sqlite() -> SqliteStore:
    global _store
    if _store is None:
        from app.config import get_settings

        _store = SqliteStore(get_settings().sqlite_file)
    return _store


def reset_sqlite() -> None:
    """测试用：清掉单例。"""
    global _store
    _store = None
