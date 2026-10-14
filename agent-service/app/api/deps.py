"""路由依赖：`X-Agent-Key` 鉴权。

契约 §0：所有接口都要求请求头 `X-Agent-Key: <共享密钥>`，两侧配置一致。
Java 侧对应 `agent.key`（默认 `change-me`，生产必改）。
"""

from __future__ import annotations

import logging

from fastapi import Header

from app.config import get_settings
from app.core.errors import unauthorized

logger = logging.getLogger(__name__)


async def verify_key(x_agent_key: str = Header(default="", alias="X-Agent-Key")) -> None:
    """校验共享密钥。密钥未配置时放行并告警（仅便于本地调试）。"""
    expected = get_settings().agent_key
    if not expected:
        logger.warning("AGENT_KEY 未配置，跳过鉴权（生产环境务必配置）")
        return
    if x_agent_key != expected:
        # 401：契约 §5。注意 Java 侧目前不区分错误码，401 也会重试 3 次，
        # 所以「密钥写错」在 Java 日志里表现为反复重试 —— 排查时先看这里。
        raise unauthorized()
