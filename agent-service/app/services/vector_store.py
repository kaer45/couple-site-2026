"""向量库封装：薄封装 hello-agents 的 `QdrantVectorStore` / `QdrantConnectionManager`。

⚠️ 两个致命细节，都来自框架源码（踩了会静默丢数据）：

1. **`vector_size` 默认 384**，而 DashScope `text-embedding-v3` 是 **1024**。
   不显式传就建成 384 维 collection，而 `add_vectors` 遇到维度不符时是：

       if vlen != self.vector_size:
           logger.warning(...)
           continue            # ← 逐条丢弃，不抛异常

   所以这里强制 `vector_size = settings.embed_dim`，并在写入前再自检一次。

2. **`add_vectors` 失败时 `return False`，不抛异常**。
   这里把它翻译成 500，否则 `/v1/ingest` 会返回 200 而 Java 认为同步成功 →
   向量永远补不上，且没有任何报错。

`qdrant_client` 是懒加载的：mock 模式下用内存实现，不需要安装。
"""

from __future__ import annotations

import logging
import math
import time
from typing import Any

from app.config import Settings, get_settings
from app.core.errors import AgentError, upstream_error

logger = logging.getLogger(__name__)


class VectorStore:
    """按 namespace（= Qdrant collection）操作的向量库。"""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        # mock：namespace -> {point_id: (vector, payload)}
        self._memory: dict[str, dict[str, tuple[list[float], dict]]] = {}
        self._health_cache: tuple[float, bool, str] | None = None

    # ---------- 状态 ----------
    @property
    def mock(self) -> bool:
        return self.settings.agent_mock

    def describe(self) -> dict:
        return {
            "mock": self.mock,
            "url": self.settings.qdrant_url,
            "vector_size": self.settings.embed_dim,
        }

    # ---------- 内部 ----------
    def _collection(self, namespace: str):
        """拿到某个 namespace 的 store。

        `QdrantConnectionManager.get_instance` 内部按 `(url, collection_name)` 缓存，
        正好支撑「一个 namespace 一个 collection」的设计（契约 §6）。
        """
        from hello_agents.memory.storage.qdrant_store import QdrantConnectionManager

        try:
            return QdrantConnectionManager.get_instance(
                url=self.settings.qdrant_url,
                api_key=self.settings.qdrant_api_key or None,
                collection_name=namespace,
                # ⚠️ 必须显式传，默认是 384
                vector_size=self.settings.embed_dim,
                timeout=self.settings.qdrant_timeout,
            )
        except Exception as exc:  # noqa: BLE001
            raise upstream_error(f"连接 Qdrant 失败（collection={namespace}）", exc) from exc

    def _check_dim(self, vector: list[float]) -> None:
        if len(vector) != self.settings.embed_dim:
            raise AgentError(
                f"向量维度 {len(vector)} 与 EMBED_DIM {self.settings.embed_dim} 不一致，"
                "拒绝写入以免污染 collection",
                status_code=500,
            )

    # ---------- 对外接口 ----------
    def upsert(
        self, namespace: str, point_id: str, vector: list[float], payload: dict
    ) -> None:
        """写入 / 覆盖一个点。point_id 必须是确定性 UUID（见 core.names）。"""
        self._check_dim(vector)

        if self.mock:
            self._memory.setdefault(namespace, {})[point_id] = (list(vector), dict(payload))
            return

        collection = self._collection(namespace)
        try:
            success = collection.add_vectors(
                vectors=[vector], metadata=[payload], ids=[point_id]
            )
        except Exception as exc:  # noqa: BLE001
            raise upstream_error(f"写入 Qdrant 失败（collection={namespace}）", exc) from exc

        if not success:
            # 框架内部吞掉了异常，只返回 False
            raise AgentError(
                f"写入 Qdrant 失败（collection={namespace}），请检查向量维度与 Qdrant 状态",
                status_code=500,
            )

    def delete(self, namespace: str, point_ids: list[str]) -> None:
        """按 point id 删除。幂等：目标不存在也算成功（契约 §1.3）。"""
        if not point_ids:
            return

        if self.mock:
            bucket = self._memory.get(namespace, {})
            for pid in point_ids:
                bucket.pop(pid, None)
            return

        collection = self._collection(namespace)
        try:
            success = collection.delete_vectors(ids=point_ids)
        except Exception as exc:  # noqa: BLE001
            raise upstream_error(f"删除 Qdrant 向量失败（collection={namespace}）", exc) from exc

        if not success:
            raise AgentError(
                f"删除 Qdrant 向量失败（collection={namespace}）", status_code=500
            )

    def search(
        self,
        namespace: str,
        vector: list[float],
        limit: int = 4,
        score_threshold: float | None = None,
        where: dict[str, Any] | None = None,
    ) -> list[dict]:
        """相似检索。

        `where` 是**扁平 dict**，框架会转成 `Filter(must=[FieldCondition(...)])`，
        例如 `{"scope_id": 11}`。
        注意：框架里查询向量维度不符时是 `return []`（静默），所以这里先自检。
        """
        self._check_dim(vector)

        if self.mock:
            return self._memory_search(namespace, vector, limit, score_threshold, where)

        collection = self._collection(namespace)
        try:
            hits = collection.search_similar(
                query_vector=vector,
                limit=limit,
                score_threshold=score_threshold,
                where=where,
            )
        except Exception as exc:  # noqa: BLE001
            raise upstream_error(f"检索 Qdrant 失败（collection={namespace}）", exc) from exc
        return hits or []

    def health(self) -> tuple[bool, str]:
        """探活。返回 (是否可用, 说明)。结果缓存 10 秒，避免 /v1/health 打爆 Qdrant。"""
        now = time.monotonic()
        if self._health_cache and now - self._health_cache[0] < 10:
            return self._health_cache[1], self._health_cache[2]

        if self.mock:
            result = (True, "mock 内存向量库")
        else:
            try:
                from qdrant_client import QdrantClient

                client = QdrantClient(
                    url=self.settings.qdrant_url,
                    api_key=self.settings.qdrant_api_key or None,
                    timeout=5,
                )
                client.get_collections()
                result = (True, "ok")
            except Exception as exc:  # noqa: BLE001
                result = (False, str(exc)[:200])

        self._health_cache = (now, result[0], result[1])
        return result

    def count(self, namespace: str) -> int:
        """某个 namespace 的点数（mock 与真实实现都支持，供排查幂等用）。"""
        if self.mock:
            return len(self._memory.get(namespace, {}))
        collection = self._collection(namespace)
        try:
            info = collection.get_collection_info()
            return int(info.get("points_count") or 0)
        except Exception:  # noqa: BLE001
            return -1

    # ---------- mock 实现 ----------
    def _memory_search(
        self,
        namespace: str,
        vector: list[float],
        limit: int,
        score_threshold: float | None,
        where: dict[str, Any] | None,
    ) -> list[dict]:
        bucket = self._memory.get(namespace, {})
        scored: list[tuple[float, str, dict]] = []
        for point_id, (stored_vec, payload) in bucket.items():
            if where and any(payload.get(k) != v for k, v in where.items()):
                continue
            score = _cosine(vector, stored_vec)
            if score_threshold is not None and score < score_threshold:
                continue
            scored.append((score, point_id, payload))

        scored.sort(key=lambda item: item[0], reverse=True)
        return [
            {"id": point_id, "score": score, "metadata": payload}
            for score, point_id, payload in scored[:limit]
        ]

    def reset_memory(self) -> None:
        """测试用：清空 mock 向量库。"""
        self._memory.clear()


def _cosine(a: list[float], b: list[float]) -> float:
    if len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a)) or 1.0
    nb = math.sqrt(sum(y * y for y in b)) or 1.0
    return dot / (na * nb)


# ---------------------------------------------------------------------------
# 进程内单例
# ---------------------------------------------------------------------------
_store: VectorStore | None = None


def get_vector_store() -> VectorStore:
    global _store
    if _store is None:
        _store = VectorStore()
    return _store


def reset_vector_store() -> None:
    """测试用：清掉单例。"""
    global _store
    _store = None
