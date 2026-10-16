"""嵌入客户端：用 httpx 直连 DashScope 的 OpenAI 兼容 `POST /embeddings`。

⚠️ 一条硬规则：**绝不做静默降级**。
    之前依赖的框架带一个 `create_embedding_model_with_fallback()`，回退链是
    `dashscope → local(sentence-transformers) → tfidf`。DashScope 一旦失败
    （key 过期、限流、断网），它会静默换成 **1000 维的 TF-IDF 向量** ——
    于是同一个向量集合里混入不同维度的数据，检索从此失效且**全程不报错**。

    本实现没有任何回退：失败就抛 AgentError，让调用方返回非 200。
    维度不符同样直接报错，绝不"尽力而为"。

接口约定（DashScope 兼容模式）：
    请求  POST {base_url}/embeddings
          {"model": "text-embedding-v3", "input": ["文本1", "文本2"]}
    响应  {"data": [{"index": 0, "embedding": [...]}, ...]}
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from app.config import Settings, get_settings
from app.core.errors import AgentError, unavailable, upstream_error

logger = logging.getLogger(__name__)


class EmbeddingClient:
    """同步嵌入客户端。"""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self._client: httpx.Client | None = None

    # ---------- 状态 ----------
    def describe(self) -> dict:
        return {
            "model": self.settings.embed_model_name,
            "dim": self.settings.embed_dim,
            "base_url": self.settings.dashscope_base_url,
            "ready": self.available,
        }

    @property
    def available(self) -> bool:
        return self.settings.embedding_configured

    # ---------- 内部 ----------
    def _http(self) -> httpx.Client:
        """懒构造 httpx 客户端（构造期不发请求）。"""
        if self._client is None:
            if not self.settings.embedding_configured:
                raise unavailable("DASHSCOPE_API_KEY 未配置，无法向量化")
            self._client = httpx.Client(
                base_url=self.settings.dashscope_base_url.rstrip("/"),
                headers={
                    "Authorization": f"Bearer {self.settings.dashscope_api_key}",
                    "Content-Type": "application/json",
                },
                timeout=httpx.Timeout(
                    connect=10.0,
                    read=self.settings.embed_timeout,
                    write=30.0,
                    pool=10.0,
                ),
            )
        return self._client

    def _post(self, inputs: list[str]) -> list[list[float]]:
        client = self._http()
        payload: dict[str, Any] = {
            "model": self.settings.embed_model_name,
            "input": inputs,
        }
        try:
            resp = client.post("/embeddings", json=payload)
        except httpx.TimeoutException as exc:
            raise AgentError("嵌入接口响应超时", status_code=504, code=504) from exc
        except httpx.HTTPError as exc:
            raise upstream_error("嵌入接口请求失败", exc) from exc

        if resp.status_code >= 400:
            # DashScope 的错误体形如 {"code": "...", "message": "..."}
            raise AgentError(
                f"嵌入接口返回 {resp.status_code}: {resp.text[:300]}",
                status_code=502,
                code=502,
            )

        try:
            body = resp.json()
        except ValueError as exc:
            raise upstream_error("嵌入接口返回非 JSON", exc) from exc

        data = body.get("data")
        if not isinstance(data, list) or not data:
            raise AgentError(
                f"嵌入接口返回结构异常: {str(body)[:200]}", status_code=502, code=502
            )

        # 按 index 排序：接口不保证返回顺序与输入一致
        try:
            ordered = sorted(data, key=lambda item: item.get("index", 0))
            vectors = [[float(x) for x in item["embedding"]] for item in ordered]
        except (KeyError, TypeError, ValueError) as exc:
            raise upstream_error("嵌入接口返回向量字段异常", exc) from exc

        if len(vectors) != len(inputs):
            raise AgentError(
                f"嵌入返回条数不符（实际 {len(vectors)}，期望 {len(inputs)}）",
                status_code=502,
                code=502,
            )
        return vectors

    @staticmethod
    def _check_dim(vectors: list[list[float]], expected: int) -> None:
        """维度必须严格一致。

        一旦把不同维度的向量写进同一个 collection，后果是**静默的数据损坏**：
        Qdrant 建 collection 时已锁定维度，之后要么写入被拒、要么检索错乱。
        所以这里宁可报错也不放行。
        """
        for idx, vec in enumerate(vectors):
            if len(vec) != expected:
                raise AgentError(
                    f"嵌入维度与 EMBED_DIM 不一致（第 {idx} 条：实际 {len(vec)}，"
                    f"期望 {expected}）。请核对 EMBED_MODEL_NAME 与 EMBED_DIM"
                    "（text-embedding-v3 默认 1024）",
                    status_code=500,
                    code=500,
                )

    # ---------- 对外接口 ----------
    def encode(self, text: str) -> list[float]:
        vectors = self.encode_many([text])
        return vectors[0] if vectors else []

    def encode_many(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []

        batch_size = max(1, self.settings.embed_batch_size)
        out: list[list[float]] = []
        for start in range(0, len(texts), batch_size):
            batch = texts[start : start + batch_size]
            vectors = self._post(batch)
            self._check_dim(vectors, self.settings.embed_dim)
            out.extend(vectors)

        if len(out) != len(texts):
            raise AgentError("嵌入结果数量与输入不一致", status_code=500, code=500)
        return out

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
_client: EmbeddingClient | None = None


def get_embedding() -> EmbeddingClient:
    global _client
    if _client is None:
        _client = EmbeddingClient()
    return _client


def reset_embedding() -> None:
    """测试用：清掉单例。"""
    global _client
    if _client is not None:
        _client.close()
    _client = None


def set_embedding(client: EmbeddingClient | None) -> None:
    """测试用：注入替身，这样契约测试不需要网络与 API key。"""
    global _client
    _client = client
