"""纯逻辑单测：不依赖 fastapi / httpx / 网络，因此随时可跑。

运行方式（二选一）：
    cd agent-service && python -m pytest tests/test_core.py -v
    cd agent-service && python tests/test_core.py
"""

from __future__ import annotations

import sys
import uuid
from pathlib import Path

# 让 `python tests/test_core.py` 也能 import 到 app 包
ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.core import names, text  # noqa: E402


# ---------------------------------------------------------------------------
# core.names
# ---------------------------------------------------------------------------
def test_point_id_is_deterministic_uuid() -> None:
    """同 (biz_type, biz_id) 必须得到同一个 UUID 字符串。

    这是幂等的根基，也是绕开框架「非 UUID 字符串会被替换成随机 UUID」那个坑的手段。
    """
    first = names.point_id_for("note", 6)
    second = names.point_id_for("note", 6)
    assert first == second
    # 必须能被 uuid 解析，否则框架会把它换成随机值
    assert str(uuid.UUID(first)) == first


def test_point_id_differs_across_biz() -> None:
    a = names.point_id_for("note", 6)
    b = names.point_id_for("note", 7)
    c = names.point_id_for("moment", 6)
    assert len({a, b, c}) == 3


def test_point_id_accepts_int_and_str() -> None:
    assert names.point_id_for("note", 6) == names.point_id_for("note", "6")


def test_namespace_for() -> None:
    assert names.namespace_for("couple", 11) == "moment_c11"
    assert names.namespace_for("personal", 1) == "note_u1"


def test_namespace_for_rejects_unknown_scope() -> None:
    try:
        names.namespace_for("public", 1)
    except ValueError:
        return
    raise AssertionError("未知 scope 应当抛 ValueError")


def test_validate_biz_scope() -> None:
    names.validate_biz_scope("moment", "couple")
    names.validate_biz_scope("photo", "couple")
    names.validate_biz_scope("note", "personal")
    for biz, scope in (("note", "couple"), ("moment", "personal")):
        try:
            names.validate_biz_scope(biz, scope)
        except ValueError:
            continue
        raise AssertionError(f"{biz}+{scope} 应当被拒绝")


# ---------------------------------------------------------------------------
# core.text
# ---------------------------------------------------------------------------
def test_normalize_cleans_whitespace_and_zero_width() -> None:
    raw = "标题\u200b\r\n\r\n\r\n正文  "
    assert text.normalize(raw) == "标题\n\n正文"


def test_normalize_handles_none() -> None:
    assert text.normalize(None) == ""


def test_truncate() -> None:
    assert text.truncate("abc", 10) == "abc"
    assert text.truncate("abcdef", 3) == "abc"


def test_split_short_text_returns_single_chunk() -> None:
    assert text.split_for_summary("很短的一段话。") == ["很短的一段话。"]


def test_split_empty_returns_empty() -> None:
    assert text.split_for_summary("") == []
    assert text.split_for_summary("   \n\n  ") == []


def test_split_long_text_is_bounded_and_covers_tail() -> None:
    size = 200
    paragraphs = [f"第{i}段。" + "内容" * 40 for i in range(40)]
    source = "\n\n".join(paragraphs)

    chunks = text.split_for_summary(source, size=size, overlap=20)

    assert len(chunks) > 1, "长文本应当被切成多块"
    assert all(chunk.strip() for chunk in chunks), "不应产生空块"
    assert max(len(c) for c in chunks) <= size + 1, "每块不应超过 size"
    # 结尾必须被覆盖到，否则 map-reduce 会丢结论
    assert chunks[-1].endswith(text.normalize(source)[-20:])


def test_split_never_loops_forever_with_big_overlap() -> None:
    """size <= overlap 的边界情况也必须能终止。"""
    chunks = text.split_for_summary("内容" * 500, size=50, overlap=50)
    assert len(chunks) > 1


def test_first_line_strips_markdown_heading() -> None:
    assert text.first_line("## Redis 学习笔记\n正文内容") == "Redis 学习笔记"
    assert text.first_line("\n\n> 引用开头\n正文") == "引用开头"
    assert text.first_line("") == ""


def test_first_line_respects_limit() -> None:
    assert len(text.first_line("甲" * 200, limit=10)) == 10


def test_safe_excerpt() -> None:
    assert text.safe_excerpt("abc", 10) == "abc"
    assert text.safe_excerpt("a" * 20, 5) == "aaaaa…"


def test_max_summary_input_matches_contract() -> None:
    # 契约 §3.1：Java 侧正文上限 20 万字
    assert text.MAX_SUMMARY_INPUT == 200_000


# ---------------------------------------------------------------------------
# 不装 pytest 时的兜底执行
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    passed = 0
    failed: list[tuple[str, BaseException]] = []
    for name, func in sorted(list(globals().items())):
        if name.startswith("test_") and callable(func):
            try:
                func()
                passed += 1
                print(f"ok   {name}")
            except BaseException as exc:  # noqa: BLE001
                failed.append((name, exc))
                print(f"FAIL {name}: {exc!r}")
    print(f"\n{passed} passed, {len(failed)} failed")
    if failed:
        raise SystemExit(1)
