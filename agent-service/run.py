"""本地启动入口。

等价于：uvicorn app.main:app --host <HOST> --port <PORT>
用法：python run.py
"""

from __future__ import annotations

import uvicorn

from app.config import get_settings


def main() -> None:
    settings = get_settings()
    # log_config=None：不要让 uvicorn 覆盖 app.logging_conf 里的日志格式
    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
        log_config=None,
    )


if __name__ == "__main__":
    main()
