"""LLM 客户端：薄封装 hello-agents 的 `HelloAgentsLLM`。

为什么还要再包一层：

1. 框架**全同步**（`stream_invoke()` / `think()` 返回 `Iterator[str]`，`invoke()` 返回 `str`）。
   这里统一暴露同步接口，路由层用普通 `def` 或 `StreamingResponse`（Starlette 会自动
   把同步生成器丢进线程池），避免阻塞事件循环。
2. 需要 mock 模式：没有 API key / 没有网络时，先验证 Java ↔ Python 的协议是否打通。
3. 需要把框架的原始异常翻译成统一的 `AgentError`。

`hello_agents` 是**懒加载**的：只有真正要调用模型时才 import，
所以 mock 模式下即使没装框架，服务也能启动。
"""

from __future__ import annotations

import logging
import time
from typing import Iterator

from app.config import Settings, get_settings
from app.core.errors import unavailable, upstream_error

logger = logging.getLogger(__name__)

# mock 模式下的假回答。特意包含中文、标点和换行，用来检验 SSE 协议与编码。
_MOCK_REPLY = (
    "（mock 模式）我是小空，这是一段模拟的流式回答。\n"
    "你能看到这句话，说明 Java → Python 的 SSE 链路是通的：\n"
    "1. 增量字段名是 content，不是 delta；\n"
    "2. 每次事件的结尾是两个换行；\n"
    "3. 流以 data: [DONE] 结束；\n"
    "4. 中文没有变成 \\uXXXX 转义。"
)

_MOCK_SUMMARY = (
    "（mock 模式）这是一段模拟摘要，用于验证摘要链路是否打通。"
    "真实摘要需要配置 LLM_API_KEY 后关闭 AGENT_MOCK 才会生成。"
    "摘要接口的验收要点：data.summary 非空且非空白、60 秒内返回、失败时返回 4xx/5xx。"
)


class LLMClient:
    """同步 LLM 客户端。"""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self._llm = None  # type: ignore[assignment]

    # ---------- 状态 ----------
    @property
    def mock(self) -> bool:
        return self.settings.agent_mock

    def describe(self) -> dict:
        return {
            "mock": self.mock,
            "model": self.settings.llm_model_id,
            "base_url": self.settings.llm_base_url,
            "ready": True if self.mock else self.settings.llm_configured,
        }

    # ---------- 内部 ----------
    def _get(self):
        """首次调用时才构造框架对象（懒加载）。"""
        if self._llm is None:
            if not self.settings.llm_configured:
                raise unavailable("LLM_API_KEY 未配置，无法调用对话模型")
            from hello_agents import HelloAgentsLLM  # 懒加载

            self._llm = HelloAgentsLLM(
                model=self.settings.llm_model_id,
                api_key=self.settings.llm_api_key or None,
                base_url=self.settings.llm_base_url or None,
                timeout=self.settings.llm_timeout,
            )
        return self._llm

    # ---------- 对外接口 ----------
    def stream(self, messages: list[dict[str, str]]) -> Iterator[str]:
        """流式产出文本增量（同步生成器）。"""
        if self.mock:
            yield from _mock_stream(_MOCK_REPLY)
            return

        llm = self._get()
        try:
            for delta in llm.stream_invoke(messages):
                if delta:
                    yield delta
        except Exception as exc:  # noqa: BLE001 - 统一翻译成 AgentError
            raise upstream_error("对话模型调用失败", exc) from exc

    def invoke(self, messages: list[dict[str, str]], **kwargs) -> str:
        """一次性拿到完整回答。"""
        if self.mock:
            return _MOCK_SUMMARY

        llm = self._get()
        try:
            content = llm.invoke(messages, **kwargs)
        except Exception as exc:  # noqa: BLE001
            raise upstream_error("对话模型调用失败", exc) from exc
        return (content or "").strip()

    def close(self) -> None:
        """尽力释放底层 HTTP 连接，失败不影响退出。"""
        if self._llm is None:
            return
        for target in (self._llm, getattr(self._llm, "_client", None)):
            closer = getattr(target, "close", None)
            if callable(closer):
                try:
                    closer()
                    return
                except Exception:  # noqa: BLE001
                    pass


def _mock_stream(text: str) -> Iterator[str]:
    """把固定文本切成小片逐步吐出，模拟打字机效果。"""
    step = 6
    for i in range(0, len(text), step):
        time.sleep(0.05)
        yield text[i : i + step]


# ---------------------------------------------------------------------------
# 进程内单例
# ---------------------------------------------------------------------------
_client: LLMClient | None = None


def get_llm() -> LLMClient:
    global _client
    if _client is None:
        _client = LLMClient()
    return _client


def reset_llm() -> None:
    """测试用：清掉单例。"""
    global _client
    if _client is not None:
        _client.close()
    _client = None
