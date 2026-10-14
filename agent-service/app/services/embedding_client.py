"""嵌入客户端：薄封装 hello-agents 的 `DashScopeEmbedding`（text-embedding-v3）。

⚠️⚠️ 绝对不要使用 hello_agents 的 `create_embedding_model_with_fallback()`。
    它的回退链是 `dashscope → local → tfidf`。DashScope 一旦失败（key 过期、断网、
    限流），它会**静默降级到 TFIDFEmbedding（max_features=1000 维）**，
    然后你往同一个 Qdrant collection 里写入的向量维度和之前完全不同 ——
    `add_vectors` 只会打一条 warning 然后 continue，检索从此静默失效，全程不报错。
    正确做法：直接用 `DashScopeEmbedding`，让它响亮地失败。

另外两个已核实的框架行为：
- `DashScopeEmbedding.__init__` 末尾会 `self.encode("health_check")` 发一次真实网络请求
  来探测维度，所以**构造即可能抛异常**。这里因此做了懒构造 + 失败冷却，
  避免每个请求都重新探测一次。
- 传了 `base_url` 就走 OpenAI 兼容 REST（内部用 `requests`），**不需要 dashscope SDK**。
"""

from __future__ import annotations

import hashlib
import logging
import math
import time

from app.config import Settings, get_settings
from app.core.errors import unavailable, upstream_error

logger = logging.getLogger(__name__)

# 构造失败后的冷却时间，避免每次请求都去探测一遍
_INIT_FAILURE_COOLDOWN_SECONDS = 30


class EmbeddingClient:
    """同步嵌入客户端。"""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self._model = None  # type: ignore[assignment]
        self._init_error: str | None = None
        self._init_failed_at: float = 0.0

    # ---------- 状态 ----------
    @property
    def mock(self) -> bool:
        return self.settings.agent_mock

    def describe(self) -> dict:
        return {
            "mock": self.mock,
            "model": self.settings.embed_model_name,
            "dim": self.settings.embed_dim,
            "ready": self.available,
            "last_error": self._init_error,
        }

    @property
    def available(self) -> bool:
        if self.mock:
            return True
        if not self.settings.embedding_configured:
            return False
        # 处于失败冷却期内视为不可用
        if self._init_error and (
            time.monotonic() - self._init_failed_at < _INIT_FAILURE_COOLDOWN_SECONDS
        ):
            return False
        return True

    # ---------- 内部 ----------
    def _get(self):
        if self._model is not None:
            return self._model

        if not self.settings.embedding_configured:
            raise unavailable("DASHSCOPE_API_KEY 未配置，无法向量化")

        now = time.monotonic()
        if self._init_error and now - self._init_failed_at < _INIT_FAILURE_COOLDOWN_SECONDS:
            raise unavailable(f"嵌入模型暂不可用：{self._init_error}")

        # 懒加载：只在真正需要时 import
        from hello_agents.memory.embedding import DashScopeEmbedding

        try:
            self._model = DashScopeEmbedding(
                model_name=self.settings.embed_model_name,
                api_key=self.settings.dashscope_api_key,
                base_url=self.settings.dashscope_base_url,
            )
        except Exception as exc:  # noqa: BLE001
            self._init_error = str(exc)[:200]
            self._init_failed_at = now
            logger.exception("初始化 DashScopeEmbedding 失败: %s", exc)
            raise unavailable(f"初始化嵌入模型失败：{self._init_error}") from exc

        self._init_error = None
        logger.info(
            "DashScopeEmbedding 就绪: model=%s dim=%s",
            self.settings.embed_model_name,
            self.settings.embed_dim,
        )
        return self._model

    # ---------- 对外接口 ----------
    def encode(self, text: str) -> list[float]:
        vectors = self.encode_many([text])
        return vectors[0] if vectors else []

    def encode_many(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []

        if self.mock:
            return [_mock_vector(t, self.settings.embed_dim) for t in texts]

        model = self._get()
        try:
            # 框架对 str 返回 list[float]，对 list[str] 返回 list[list[float]]
            raw = model.encode(texts if len(texts) > 1 else texts[0])
        except Exception as exc:  # noqa: BLE001
            raise upstream_error("嵌入模型调用失败", exc) from exc

        return _coerce(raw, len(texts), self.settings.embed_dim)


def _coerce(raw, expected_count: int, expected_dim: int) -> list[list[float]]:
    """把框架返回值规整成 `list[list[float]]`，并**严格校验维度**。

    维度不一致必须立刻抛错：一旦把不同维度的向量写进同一个 collection，
    框架只会 warning + 丢弃，属于静默数据损坏。
    """
    if raw is None:
        raise upstream_error("嵌入模型返回空结果", ValueError("empty response"))

    if expected_count == 1 and raw and not isinstance(raw[0], (list, tuple)):
        vectors = [list(raw)]
    else:
        vectors = [list(v) for v in raw]

    for idx, vec in enumerate(vectors):
        if len(vec) != expected_dim:
            raise upstream_error(
                f"嵌入维度与 EMBED_DIM 不一致（第 {idx} 条：实际 {len(vec)}，期望 {expected_dim}）",
                ValueError("embedding dimension mismatch"),
            )
    if len(vectors) != expected_count:
        raise upstream_error(
            f"嵌入返回条数不符（实际 {len(vectors)}，期望 {expected_count}）",
            ValueError("embedding count mismatch"),
        )
    return vectors


def _mock_vector(text: str, dim: int) -> list[float]:
    """确定性假向量：同一文本永远得到同一向量。

    这样离线也能验证「幂等覆盖」和「检索命中」两件事。
    """
    seed = hashlib.sha256(text.encode("utf-8")).digest()
    values: list[float] = []
    while len(values) < dim:
        seed = hashlib.sha256(seed).digest()
        values.extend((b / 255.0) - 0.5 for b in seed)
    values = values[:dim]
    norm = math.sqrt(sum(v * v for v in values)) or 1.0
    return [v / norm for v in values]


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
    _client = None
