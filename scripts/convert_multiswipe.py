#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 pipeline 里所有 "action": "MultiSwipe" 节点转换为 Custom action 喵～
转换后：
  "action": "Custom",
  "custom_action": "MultiSwipeCustom",
  "custom_action_param": {"swipes": [...]}
MaaFramework 的 AndroidNative controller 官方只支持单点触摸（"native android controller only
supports single touch"），MultiSwipe 多指会被拒绝；改为 Custom action 后由引擎进程
用 injectInputEvent 注入多指针 MotionEvent，绕过该限制喵。
"""
import json
import os
import re
import sys

PIPELINE_DIR = "/storage/emulated/0/火影MAA安卓脚本开发/MAAFW-Android-火影忍者手游/app/src/main/assets/resource/base/pipeline"

# 去掉 JSON 中的 // 行注释（MaaFramework 的 pipeline 允许 jsonc 风格注释）
def strip_jsonc(text: str) -> str:
    lines = text.splitlines()
    out = []
    for line in lines:
        stripped = line.lstrip()
        if stripped.startswith("//"):
            continue
        # 行内注释：不处理（避免破坏字符串），pipeline 里注释基本是整行
        out.append(line)
    return "\n".join(out)


def convert_file(path: str) -> int:
    with open(path, "r", encoding="utf-8") as f:
        raw = f.read()
    try:
        data = json.loads(strip_jsonc(raw))
    except Exception as e:
        print(f"  [SKIP] {os.path.basename(path)} 解析失败: {e}")
        return 0

    if not isinstance(data, dict):
        return 0

    changed = 0
    for node_name, node in data.items():
        if not isinstance(node, dict):
            continue
        if node.get("action") == "MultiSwipe":
            swipes = node.get("swipes")
            if not isinstance(swipes, list):
                continue
            # 保留原 MultiSwipe 语义：转换为 Custom action
            node["action"] = "Custom"
            node["custom_action"] = "MultiSwipeCustom"
            node["custom_action_param"] = {"swipes": swipes}
            # 移除已使用的 swipes 顶层字段，避免重复（custom_action_param 里已带）
            node.pop("swipes", None)
            changed += 1
            print(f"  转换节点: {node_name} ({(len(swipes))}指)")

    if changed:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=4)
        print(f"[DONE] {os.path.basename(path)}: 转换 {changed} 个 MultiSwipe 节点")
    else:
        print(f"[NONE] {os.path.basename(path)}")
    return changed


def main():
    total = 0
    for name in sorted(os.listdir(PIPELINE_DIR)):
        if not name.endswith(".json"):
            continue
        path = os.path.join(PIPELINE_DIR, name)
        total += convert_file(path)
    print(f"\n===== 共转换 {total} 个 MultiSwipe 节点 =====")


if __name__ == "__main__":
    main()
