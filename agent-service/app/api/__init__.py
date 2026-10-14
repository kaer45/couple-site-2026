"""HTTP 接口层。

纪律：只做「收参数 → 校验 → 调 service → 包 Result」，不写业务逻辑。
本层是唯一允许 import fastapi 的地方。
"""
