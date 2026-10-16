"""服务配置：所有可变项集中在这里，从 .env / 环境变量读取。

设计说明：
- 用 pydantic-settings，字段名自动映射为大写环境变量（大小写不敏感）。
  例如 `llm_api_key` ← `LLM_API_KEY`。
- **不依赖任何智能体框架**：LLM 与嵌入都由 `app/services/` 里的自研客户端
  通过 httpx 直连官方 HTTP 接口，行为完全可预测。
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

    # ---------- LLM（DeepSeek，OpenAI 兼容接口）----------
    llm_base_url: str = "https://api.deepseek.com"
    llm_model_id: str = "deepseek-chat"
    llm_api_key: str = ""
    llm_temperature: float = 0.7
    # 0 表示不发送 max_tokens（用 0 而不是 None，避免空环境变量解析报错）
    llm_max_tokens: int = 0
    llm_connect_timeout: float = 10.0
    # 契约要求：/v1/chat 首字节 2s 内产出、/v1/summarize 整体 60s 内返回。
    # httpx 的 read timeout 是**每次读取**的超时，流式场景逐块重置，所以 60s 是安全的。
    llm_read_timeout: float = 60.0

    # ---------- 嵌入（DashScope OpenAI 兼容模式）----------
    dashscope_api_key: str = ""
    dashscope_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    embed_model_name: str = "text-embedding-v3"
    # ⚠️ 必须与模型实际维度一致：建 collection 时用它，写/查前再自检一次。
    #    text-embedding-v3 默认 1024。
    embed_dim: int = 1024
    # DashScope 单次批量上限较小，分批提交
    embed_batch_size: int = 10
    embed_timeout: float = 30.0

    # ---------- Qdrant ----------
    qdrant_url: str = "http://localhost:6333"
    qdrant_api_key: str = ""
    qdrant_timeout: float = 30.0

    # ---------- SQLite（会话历史 / 审计）----------
    sqlite_path: str = "./data/agent.db"

    # ---------- 向量检索 ----------
    retrieval_enabled: bool = True
    retrieval_top_k: int = 4
    retrieval_score_threshold: float = 0.35

    # ---------- 多轮会话 ----------
    # 契约 §2.3 建议：保留 10 条 / 24h 过期
    session_max_turns: int = 10
    session_ttl_hours: int = 24

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
