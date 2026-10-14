"""namespace 与 point id 的唯一生成处（契约 §6）。

两个约束都来自 hello-agents 0.2.9 的实现细节，踩了就会静默出错：

1. **namespace = Qdrant collection 名**，一个 namespace 一个 collection。
   - moment / photo + scope=couple → `moment_c{scope_id}`（两者共用同一域）
   - note        + scope=personal → `note_u{scope_id}`

2. ⚠️ `QdrantVectorStore.add_vectors()` 对**非 UUID 的字符串 id 会静默替换成随机 UUID**：
       elif isinstance(point_id, str):
           try:
               uuid.UUID(point_id); safe_id = point_id
           except Exception:
               safe_id = str(uuid.uuid4())      # ← 幂等和删除都会失效
   所以 point id 必须是**确定性的 UUID 字符串**。这里用 uuid5（同输入永远同输出）。
   千万不要用 `f"{biz_type}:{biz_id}"` 这种形式。
"""

from __future__ import annotations

import uuid

# 固定命名空间常量。**改了它等于把所有已有向量的 id 全换掉**，不要动。
_UUID_NAMESPACE = uuid.UUID("6f9619ff-8b86-d011-b42d-00c04fc964ff")

# scope → collection 名模板
_SCOPE_TEMPLATE: dict[str, str] = {
    "couple": "moment_c{scope_id}",
    "personal": "note_u{scope_id}",
}

# 允许的 (biz_type, scope) 组合，与契约 §6 一致
_ALLOWED_PAIRS: frozenset[tuple[str, str]] = frozenset(
    {
        ("moment", "couple"),
        ("photo", "couple"),
        ("note", "personal"),
    }
)


def namespace_for(scope: str, scope_id: int) -> str:
    """按 scope 推断 collection 名。检索时只知道 scope / scope_id，用这个。"""
    template = _SCOPE_TEMPLATE.get(scope)
    if template is None:
        raise ValueError(f"未知 scope: {scope!r}（仅支持 couple / personal）")
    if scope_id is None:
        raise ValueError(f"scope={scope} 时 scope_id 不能为空")
    return template.format(scope_id=scope_id)


def validate_biz_scope(biz_type: str, scope: str) -> None:
    """ingest 时校验 (biz_type, scope) 组合是否合法。"""
    if (biz_type, scope) not in _ALLOWED_PAIRS:
        allowed = "、".join(f"{b}+{s}" for b, s in sorted(_ALLOWED_PAIRS))
        raise ValueError(f"非法的 biz_type/scope 组合: {biz_type}+{scope}（允许：{allowed}）")


def point_id_for(biz_type: str, biz_id: int | str) -> str:
    """确定性 point id：同 (biz_type, biz_id) 永远得到同一个 UUID 字符串。

    这样重复 upsert 会覆盖同一个点（幂等），delete 也能精确命中。
    """
    return str(uuid.uuid5(_UUID_NAMESPACE, f"couple-site:{biz_type}:{biz_id}"))
