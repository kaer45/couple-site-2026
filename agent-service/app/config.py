"""服务配置：所有可变项集中在这里，从 .env / 环境变量读取。

设计说明：
- 用 pydantic-settings，字段名自动映射为大写环境变量（大小写不敏感）。
  例如 `llm_api_key` ← `LLM_API_KEY`。
- `LLM_*` 这几个变量名是 hello-agents 的 `HelloAgentsLLM` 自己读取的，
  这里定义同名字段只是为了在 /v1/health 里做自检与展示，不重复注入。
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # ---------- 服务自身 ----------
    agent_key: str = "change-me"
    host: str = "127.0.0.1"
    port: int = 8001
    log_level: str = "INFO"

    # ---------- LLM ----------
    llm_base_url: str = "https://api.deepseek.com"
    llm_model_id: str = "deepseek-chat"
    llm_api_key: str = ""
    llm_timeout: int = 60

    # ---------- 嵌入 ----------
    dashscope_api_key: str = ""
    dashscope_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    embed_model_name: str = "text-embedding-v3"
    embed_dim: int = 1024

    # ---------- Qdrant ----------
    qdrant_url: str = "http://localhost:6333"
    qdrant_api_key: str = ""
    qdrant_timeout: int = 30

    # ---------- SQLite ----------
    sqlite_path: str = "./data/agent.db"

    # ---------- 检索 ----------
    retrieval_enabled: bool = True
    retrieval_top_k: int = 4
    retrieval_score_threshold: float = 0.35

    # ---------- 会话 ----------
    session_max_turns: int = 10
    session_ttl_hours: int = 24

    # ---------- mock ----------
    agent_mock: bool = False

    # ---------- 派生属性 ----------
    @property
    def sqlite_file(self) -> Path:
        """SQLite 绝对路径，并确保父目录存在。"""
        path = Path(self.sqlite_path).expanduser()
        if not path.is_absolute():
            path = Path.cwd() / path
        path.parent.mkdir(parents=True, exist_ok=True)
        return path

    @property
    def llm_configured(self) -> bool:
        return bool(self.llm_api_key.strip())

    @property
    def embedding_configured(self) -> bool:
        return bool(self.dashscope_api_key.strip())

    def summary(self) -> dict:
        """给 /v1/health 用的配置快照，**不包含任何密钥明文**。"""
        return {
            "mock": self.agent_mock,
            "llm_model": self.llm_model_id,
            "llm_base_url": self.llm_base_url,
            "llm_key_configured": self.llm_configured,
            "embed_model": self.embed_model_name,
            "embed_dim": self.embed_dim,
            "embed_key_configured": self.embedding_configured,
            "qdrant_url": self.qdrant_url,
            "sqlite_path": str(self.sqlite_file),
            "retrieval_enabled": self.retrieval_enabled,
        }


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """进程内单例。测试里若要换配置，调用 get_settings.cache_clear()。"""
    return Settings()
