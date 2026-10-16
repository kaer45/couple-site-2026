"""业务逻辑层。

纪律：**本层禁止 import fastapi**。
好处是能单测、能在脚本里直接复用、以后换 Web 框架不用改。

本层不依赖任何智能体框架：`llm_client` / `embedding_client` / `vector_store`
三个客户端都是自研的，通过 httpx 与 qdrant-client 直连外部服务。
（`qdrant_client` 仍是懒加载，方便在没有 Qdrant 的环境里跑纯逻辑测试。）
"""
