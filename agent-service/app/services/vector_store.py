"""向量库：直接用 qdrant-client，一个 namespace 一个 collection（契约 §6）。

为什么不再套框架：
    之前依赖的框架在 Qdrant 上有**两个会静默丢数据的坑**：
    1. `vector_size` 默认 **384**，而 DashScope `text-embedding-v3` 是 **1024** ——
       不显式传就建成 384 维 collection，之后写入时维度不符只打一条 warning
       然后 `continue`（**逐条丢弃，不抛异常**）；
    2. `add_vectors()` 对**非 UUID 的字符串 id 会静默替换成随机 UUID**，
       幂等与删除一起失效；失败时还只是 `return False` 而不抛异常。

    直连 qdrant-client 后这些都不存在：维度由我们显式指定、id 原样写入、异常直接抛。
    但两道自检（`_check_dim`、`core.names.point_id_for`）**仍然保留** ——
    它们防的是调用方传错，与用不用框架无关。

设计：
- 一个 `QdrantClient` 服务所有 collection（它本身只是无状态的 HTTP 封装）
- 每个 namespace 首次使用时确保 collection 存在，并建 payload 索引
- 路由跑在线程池里，所以「确保 collection」这段用锁保护
- `search()` 兼容 qdrant-client 新旧 API：≥1.10 用 `query_points()`，1.9 用 `search()`
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Any

from app.config import Settings, get_settings
from app.core.errors import AgentError, upstream_error

logger = logging.getLogger(__name__)

# payload 中需要按等值过滤的字段 → Qdrant 索引类型
_PAYLOAD_INDEXES: dict[str, str] = {
    "biz_type": "keyword",
    "scope": "keyword",
    "scope_id": "integer",
}

# 探活结果缓存时长（秒），避免 /v1/health 被反复调用时打爆 Qdrant
_HEALTH_TTL = 10.0


class VectorStore:
    """按 namespace（= Qdrant collection）操作的向量库。"""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self._client = None  # type: ignore[assignment]
        self._ensured: set[str] = set()
        self._lock = threading.Lock()
        self._health_cache: tuple[float, bool, str] | None = None

    # ---------- 状态 ----------
    def describe(self) -> dict:
        return {
            "url": self.settings.qdrant_url,
            "vector_size": self.settings.embed_dim,
        }

    # ---------- 内部 ----------
    def _get_client(self):
        """懒构造客户端（构造期不建立连接）。"""
        if self._client is None:
            from qdrant_client import QdrantClient

            self._client = QdrantClient(
                url=self.settings.qdrant_url,
                api_key=self.settings.qdrant_api_key or None,
                timeout=self.settings.qdrant_timeout,
            )
        return self._client

    def _ensure_collection(self, namespace: str) -> None:
        """确保 collection 存在（幂等，进程内只做一次）。

        ⚠️ 维度**显式**取 settings.embed_dim —— 绝不依赖任何默认值。
        """
        if namespace in self._ensured:
            return

        with self._lock:
            if namespace in self._ensured:
                return

            from qdrant_client import models

            client = self._get_client()
            try:
                if not client.collection_exists(namespace):
                    client.create_collection(
                        collection_name=namespace,
                        vectors_config=models.VectorParams(
                            size=self.settings.embed_dim,
                            distance=models.Distance.COSINE,
                        ),
                    )
                    logger.info(
                        "已创建 Qdrant collection: %s (dim=%s)",
                        namespace,
                        self.settings.embed_dim,
                    )
                    self._create_indexes(client, namespace, models)
            except Exception as exc:  # noqa: BLE001
                raise upstream_error(f"确保 Qdrant collection 失败（{namespace}）", exc) from exc

            self._ensured.add(namespace)

    @staticmethod
    def _create_indexes(client, namespace: str, models) -> None:
        """建 payload 索引。失败只记日志 —— 索引只影响性能，不影响正确性。"""
        mapping = {
            "keyword": models.PayloadSchemaType.KEYWORD,
            "integer": models.PayloadSchemaType.INTEGER,
        }
        for field, kind in _PAYLOAD_INDEXES.items():
            try:
                client.create_payload_index(
                    collection_name=namespace,
                    field_name=field,
                    field_schema=mapping[kind],
                )
            except Exception as exc:  # noqa: BLE001
                logger.warning("创建 payload 索引失败（%s.%s）: %s", namespace, field, exc)

    def _check_dim(self, vector: list[float]) -> None:
        if len(vector) != self.settings.embed_dim:
            raise AgentError(
                f"向量维度 {len(vector)} 与 EMBED_DIM {self.settings.embed_dim} 不一致，"
                "拒绝写入以免污染 collection",
                status_code=500,
            )

    @staticmethod
    def _build_filter(where: dict[str, Any] | None):
        """把扁平 dict（如 `{"scope_id": 11}`）转成 Qdrant Filter。"""
        if not where:
            return None
        from qdrant_client import models

        conditions = [
            models.FieldCondition(key=key, match=models.MatchValue(value=value))
            for key, value in where.items()
        ]
        return models.Filter(must=conditions) if conditions else None

    # ---------- 对外接口 ----------
    def upsert(
        self, namespace: str, point_id: str, vector: list[float], payload: dict
    ) -> None:
        """写入 / 覆盖一个点。

        `point_id` 必须是确定性 UUID —— 见 `core.names.point_id_for`。
        同 id 重复 upsert 会覆盖，这就是幂等的来源（契约 §1.3）。
        """
        self._check_dim(vector)
        self._ensure_collection(namespace)

        from qdrant_client import models

        try:
            self._get_client().upsert(
                collection_name=namespace,
                points=[
                    models.PointStruct(id=point_id, vector=vector, payload=payload)
                ],
                wait=True,  # 同步等待写入完成，否则错误会被吞掉
            )
        except Exception as exc:  # noqa: BLE001
            raise upstream_error(f"写入 Qdrant 失败（{namespace}）", exc) from exc

    def delete(self, namespace: str, point_ids: list[str]) -> None:
        """按 point id 删除。幂等：目标不存在也算成功（契约 §1.3）。"""
        if not point_ids:
            return
        self._ensure_collection(namespace)

        from qdrant_client import models

        try:
            self._get_client().delete(
                collection_name=namespace,
                points_selector=models.PointIdsList(points=list(point_ids)),
                wait=True,
            )
        except Exception as exc:  # noqa: BLE001
            raise upstream_error(f"删除 Qdrant 向量失败（{namespace}）", exc) from exc

    def search(
        self,
        namespace: str,
        vector: list[float],
        limit: int = 4,
        score_threshold: float | None = None,
        where: dict[str, Any] | None = None,
    ) -> list[dict]:
        """相似检索，返回 `[{"id", "score", "metadata"}, ...]`。"""
        self._check_dim(vector)
        self._ensure_collection(namespace)

        client = self._get_client()
        query_filter = self._build_filter(where)

        try:
            if hasattr(client, "query_points"):
                # qdrant-client >= 1.10
                response = client.query_points(
                    collection_name=namespace,
                    query=vector,
                    query_filter=query_filter,
                    limit=limit,
                    score_threshold=score_threshold,
                    with_payload=True,
                    with_vectors=False,
                )
                points = response.points
            else:
                # qdrant-client 1.9 及更早
                points = client.search(
                    collection_name=namespace,
                    query_vector=vector,
                    query_filter=query_filter,
                    limit=limit,
                    score_threshold=score_threshold,
                    with_payload=True,
                    with_vectors=False,
                )
        except Exception as exc:  # noqa: BLE001
            raise upstream_error(f"检索 Qdrant 失败（{namespace}）", exc) from exc

        return [
            {
                "id": str(point.id),
                "score": float(point.score),
                "metadata": point.payload or {},
            }
            for point in points or []
        ]

    def health(self) -> tuple[bool, str]:
        """探活。返回 (是否可用, 说明)。结果缓存 10 秒。"""
        now = time.monotonic()
        if self._health_cache and now - self._health_cache[0] < _HEALTH_TTL:
            return self._health_cache[1], self._health_cache[2]

        try:
            self._get_client().get_collections()
            result = (True, "ok")
        except Exception as exc:  # noqa: BLE001
            result = (False, str(exc)[:200])

        self._health_cache = (now, result[0], result[1])
        return result

    def count(self, namespace: str) -> int:
        """某个 namespace 的点数。失败返回 -1（供排查幂等用）。"""
        try:
            self._ensure_collection(namespace)
            result = self._get_client().count(collection_name=namespace, exact=True)
            return int(getattr(result, "count", 0) or 0)
        except Exception as exc:  # noqa: BLE001
            logger.warning("统计 Qdrant 点数失败（%s）: %s", namespace, exc)
            return -1

    def close(self) -> None:
        if self._client is not None:
            try:
                self._client.close()
            except Exception:  # noqa: BLE001
                pass
            self._client = None


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
    if _store is not None:
        _store.close()
    _store = None


def set_vector_store(store: VectorStore | None) -> None:
    """测试用：注入替身，这样契约测试不需要真的起 Qdrant。"""
    global _store
    _store = store
