#!/usr/bin/env python3
"""
把 pipeline 里的 MultiSwipe 独立转换为 Android 兼容的 Custom action。

转换后：
    "action": "Custom",
    "custom_action": "MultiSwipeCustom",
    "custom_action_param": {"swipes": [...]}

用法：
    python3 scripts/convert_multiswipe.py app/src/main/assets/resource/base/pipeline
"""

import argparse
import json
from pathlib import Path


def strip_jsonc(text: str) -> str:
    return "\n".join(
        line for line in text.splitlines() if not line.lstrip().startswith("//")
    )


def convert_file(path: Path) -> int:
    try:
        raw = path.read_text(encoding="utf-8")
        data = json.loads(strip_jsonc(raw))
    except Exception as e:
        print(f"  [SKIP] {path.name} 解析失败: {e}")
        return 0

    if not isinstance(data, dict):
        return 0

    changed = 0
    for node_name, node in data.items():
        if not isinstance(node, dict):
            continue
        if node.get("action") != "MultiSwipe":
            continue
        swipes = node.get("swipes")
        if not isinstance(swipes, list):
            continue

        node["action"] = "Custom"
        node["custom_action"] = "MultiSwipeCustom"
        node["custom_action_param"] = {"swipes": swipes}
        node.pop("swipes", None)
        changed += 1

    if changed:
        path.write_text(
            json.dumps(data, ensure_ascii=False, indent=4) + "\n",
            encoding="utf-8",
        )
        print(f"  [CONVERT] {path.name}: {changed} MultiSwipe -> MultiSwipeCustom")
    else:
        print(f"  [NONE] {path.name}")
    return changed


def convert_pipeline_dir(pipeline_dir: Path) -> int:
    files = sorted(pipeline_dir.glob("*.json"))
    if not files:
        print(f"  [WARN] {pipeline_dir} 里没有 JSON pipeline。")
        return 0

    total = sum(convert_file(path) for path in files)
    print(f"  [CONVERT] 共转换 {total} 个 MultiSwipe 节点")
    return total


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="把 pipeline 里的 MultiSwipe 转换为 Android Custom action"
    )
    parser.add_argument(
        "pipeline_dir",
        type=Path,
        help="pipeline 目录，例如 app/src/main/assets/resource/base/pipeline",
    )
    args = parser.parse_args(argv)

    if not args.pipeline_dir.is_dir():
        parser.error(f"pipeline 目录不存在: {args.pipeline_dir}")

    convert_pipeline_dir(args.pipeline_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
