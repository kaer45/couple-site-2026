"""LLM 客户端：用 httpx 直连 OpenAI 兼容的 `POST /chat/completions`。

为什么不再用智能体框架：
    之前依赖 hello-agents 的 `HelloAgentsLLM`，但框架为了兼容多家 provider 做了层层抽象，
    实际问题（流式解析、超时语义、异常类型）反而更难点。这里直接对着 HTTP 协议写，
    只有一百多行，行为完全可预测，也不再有 `openai<2` 这类版本锁。

三个要点：
1. **全同步**。路由层用普通 `def` 或 `StreamingResponse`（Starlette 会把同步生成器
   丢进线程池），不会阻塞事件循环。
2. **SSE 自己解析**。OpenAI 兼容的流式格式是：
       data: {"choices":[{"delta":{"content":"你"}}]}
       data: [DONE]
   必须跳过：空行、`: keep-alive` 注释行、以及只带 role/usage 而没有 content 的块。
3. **异常语义统一**：所有失败翻译成 `AgentError` 并带上 HTTP 状态码，
   让上层能返回非 200（契约 §1.2：Java 对 ingest 只看状态码）。
"""

from __future__ import annotations

import json
import logging
from typing import Any, Iterator

import httpx

from app.config import Settings, get_settings
from app.core.errors import AgentError, unavailable, upstream_error

logger = logging.getLogger(__name__)


class LLMClient:
    """同步 LLM 客户端。"""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self._client: httpx.Client | None = None

    # ---------- 状态 ----------
    def describe(self) -> dict:
        return {
            "model": self.settings.llm_model_id,
            "base_url": self.settings.llm_base_url,
            "ready": self.settings.llm_configured,
        }

    # ---------- 内部 ----------
    def _http(self) -> httpx.Client:
        """懒构造 httpx 客户端（构造期不发请求）。"""
        if self._client is None:
            if not self.settings.llm_configured:
                raise unavailable("LLM_API_KEY 未配置，无法调用对话模型")
            self._client = httpx.Client(
                base_url=self.settings.llm_base_url.rstrip("/"),
                headers={
                    "Authorization": f"Bearer {self.settings.llm_api_key}",
                    "Content-Type": "application/json",
                },
                timeout=httpx.Timeout(
                    connect=self.settings.llm_connect_timeout,
                    read=self.settings.llm_read_timeout,
                    write=30.0,
                    pool=10.0,
                ),
            )
        return self._client

    def _payload(
        self, messages: list[dict[str, str]], stream: bool, **kwargs: Any
    ) -> dict:
        payload: dict[str, Any] = {
            "model": kwargs.get("model") or self.settings.llm_model_id,
            "messages": messages,
            "stream": stream,
        }
        temperature = kwargs.get("temperature")
        if temperature is None:
            temperature = self.settings.llm_temperature
        if temperature is not None:
            payload["temperature"] = temperature
        max_tokens = kwargs.get("max_tokens") or self.settings.llm_max_tokens
        if max_tokens:
            payload["max_tokens"] = max_tokens
        return payload

    @staticmethod
    def _error_from_response(resp: httpx.Response) -> AgentError:
        """把上游错误响应翻译成 AgentError。

        响应体截断到 300 字，避免把一整页 HTML 塞进日志和 `body.message`。
        上游 4xx（key / 模型名不对）与 5xx（上游故障）统一按 502 上报：
        对 Java 而言都是"Python 内部错误"，会记日志并（对 ingest）重试。
        """
        body = ""
        try:
            body = resp.text[:300]
        except Exception:  # noqa: BLE001
            pass
        return AgentError(
            f"对话模型返回 {resp.status_code}: {body}",
            status_code=502,
            code=502,
        )

    # ---------- 对外接口 ----------
    def stream(self, messages: list[dict[str, str]], **kwargs: Any) -> Iterator[str]:
        """流式产出文本增量（同步生成器）。"""
        client = self._http()
        payload = self._payload(messages, stream=True, **kwargs)
        try:
            with client.stream("POST", "/chat/completions", json=payload) as resp:
                if resp.status_code >= 400:
                    resp.read()  # 先把 body 读完，否则 resp.text 拿不到内容
                    raise self._error_from_response(resp)

                for raw_line in resp.iter_lines():
                    if not raw_line:
                        continue  # SSE 事件之间的空行
                    line = raw_line.strip()
                    if not line.startswith("data:"):
                        continue  # `: keep-alive` 之类的注释行
                    data = line[5:].strip()
                    if data == "[DONE]":
                        break
                    try:
                        chunk = json.loads(data)
                    except json.JSONDecodeError:
                        logger.debug("跳过无法解析的 SSE 数据: %s", data[:120])
                        continue
                    for choice in chunk.get("choices") or []:
                        piece = (choice.get("delta") or {}).get("content")
                        if piece:
                            yield piece
        except AgentError:
            raise
        except httpx.TimeoutException as exc:
            raise AgentError("对话模型响应超时", status_code=504, code=504) from exc
        except httpx.HTTPError as exc:
            raise upstream_error("对话模型请求失败", exc) from exc

    def invoke(self, messages: list[dict[str, str]], **kwargs: Any) -> str:
        """一次性拿到完整回答（非流式）。"""
        client = self._http()
        payload = self._payload(messages, stream=False, **kwargs)
        try:
            resp = client.post("/chat/completions", json=payload)
            if resp.status_code >= 400:
                raise self._error_from_response(resp)
            body = resp.json()
            choices = body.get("choices") or []
            if not choices:
                raise AgentError("对话模型返回结果为空", status_code=502, code=502)
            content = (choices[0].get("message") or {}).get("content") or ""
            return content.strip()
        except AgentError:
            raise
        except httpx.TimeoutException as exc:
            raise AgentError("对话模型响应超时", status_code=504, code=504) from exc
        except httpx.HTTPError as exc:
            raise upstream_error("对话模型请求失败", exc) from exc
        except (KeyError, ValueError, TypeError) as exc:
            raise upstream_error("对话模型返回格式异常", exc) from exc

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


def set_llm(client: LLMClient | None) -> None:
    """测试用：注入替身，这样契约测试不需要网络与 API key。"""
    global _client
    _client = client
