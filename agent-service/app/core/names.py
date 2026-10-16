"""namespace 与 point id 的唯一生成处（契约 §6）。

两条约束，踩了都会静默出错：

1. **namespace = Qdrant collection 名**，一个 namespace 一个 collection。
   - moment / photo + scope=couple → `moment_c{scope_id}`（两者共用同一域）
   - note        + scope=personal → `note_u{scope_id}`

2. ⚠️ Qdrant 的 point id **只接受无符号整数或 UUID 字符串**。
   传 `"note:6"` 这种形式不会被"容错处理"，而是写入失败或被拒绝。
   因此这里统一用 **uuid5** 生成确定性 UUID：同一 (biz_type, biz_id) 永远得到同一个
   id —— 这正是 upsert 幂等与精确删除的根基（契约 §1.3），不要改成随机 id。
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
