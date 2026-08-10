#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MaaFramework Android 预编译库下载与部署脚本喵～

用法：
    python scripts/setup_maaframework.py                    # 下载最新 release 并部署
    python scripts/setup_maaframework.py --tag v2.0.0       # 下载指定 tag
    python scripts/setup_maaframework.py --skip-download     # 仅从缓存部署

它会从 MaaFramework GitHub Release 下载 MAA-android-aarch64.zip，
把 lib*.so 解压到 app/src/main/jniLibs/arm64-v8a/，
把 include/ 解压到 app/src/main/cpp/include/ 供 JNI/JNA 绑定使用喵。
"""

import argparse
import json
import os
import shutil
import sys
import urllib.request
import urllib.error
import zipfile
from pathlib import Path

DEFAULT_GITHUB_REPO = "MaaXYZ/MaaFramework"
API_BASE = f"https://api.github.com/repos/{DEFAULT_GITHUB_REPO}"

# release job 生成的 asset 名称关键字
ASSET_KEYWORD = "MAA-android-aarch64"

PROJECT_ROOT = Path(__file__).resolve().parent.parent
CACHE_DIR = PROJECT_ROOT / ".maaframework-cache"
JNI_DIR = PROJECT_ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
INCLUDE_DIR = PROJECT_ROOT / "app" / "src" / "main" / "cpp" / "include"
VERSION_FILE = PROJECT_ROOT / ".maaframework-version"

# 这些 so 是 MaaFramework 本体 + 控制器，必须保留
KEEP_SO_PREFIXES = (
    "libMaaFramework",
    "libMaaToolkit",
    "libMaaAndroidNativeControlUnit",
    "libMaaUtils",
    "libMaaCustomControlUnit",
    "libMaaAdbControlUnit",
    "libMaaDbgControlUnit",
)

# 依赖 so：OpenCV / ONNX / FastDeploy / Boost 等
# MaaFramework 安装目录里通常会有 lib/ 子目录，里面全是依赖
# 这里不过滤，全部保留喵


def fetch_json(url: str) -> dict:
    req = urllib.request.Request(url)
    req.add_header("Accept", "application/vnd.github.v3+json")
    req.add_header("User-Agent", "MAAFW-Naruto-Setup")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def download_file(url: str, dest: Path):
    print(f"  [DOWNLOAD] {dest.name}")
    req = urllib.request.Request(url)
    req.add_header("Accept", "application/octet-stream")
    req.add_header("User-Agent", "MAAFW-Naruto-Setup")
    with urllib.request.urlopen(req, timeout=600) as resp:
        total = int(resp.headers.get("Content-Length", 0))
        downloaded = 0
        with open(dest, "wb") as f:
            while True:
                chunk = resp.read(1024 * 1024)
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                if total > 0:
                    pct = downloaded * 100 // total
                    mb = downloaded / (1024 * 1024)
                    total_mb = total / (1024 * 1024)
                    print(f"\r    {mb:.1f}/{total_mb:.1f} MB ({pct}%)", end="", flush=True)
        print()


def get_release_assets(tag: str = None):
    if tag:
        url = f"{API_BASE}/releases/tags/{tag}"
    else:
        url = f"{API_BASE}/releases/latest"
    print(f"[FETCH] 获取 release 信息: {url}")
    data = fetch_json(url)
    tag_name = data.get("tag_name", "unknown")
    print(f"  Tag: {tag_name}")
    return tag_name, data.get("assets", [])


def find_android_asset(assets: list):
    for asset in assets:
        name = asset.get("name", "")
        if ASSET_KEYWORD in name and name.endswith(".zip"):
            return asset
    return None


def clean_deploy_dirs():
    if JNI_DIR.exists():
        # 只删除 MaaFramework 相关的 so，保留 libbridge.so / liblauncher.so
        for f in JNI_DIR.iterdir():
            if f.suffix != ".so":
                continue
            if f.name.startswith("libMaa") or f.name in ("libonnxruntime.so", "libopencv_world.so"):
                f.unlink()
                print(f"    [DELETE] {f.name}")
    else:
        JNI_DIR.mkdir(parents=True, exist_ok=True)

    if INCLUDE_DIR.exists():
        shutil.rmtree(INCLUDE_DIR)
    INCLUDE_DIR.mkdir(parents=True, exist_ok=True)


def extract_and_deploy(zip_path: Path, version: str):
    clean_deploy_dirs()

    print(f"  [EXTRACT] {zip_path.name}")
    with zipfile.ZipFile(zip_path, "r") as zf:
        members = zf.namelist()

        # 统计
        so_count = 0
        inc_count = 0

        for member in members:
            # 跳过目录
            if member.endswith("/"):
                continue
            parts = Path(member).parts

            # lib/*.so -> jniLibs
            if "lib" in parts and member.endswith(".so"):
                name = Path(member).name
                dest = JNI_DIR / name
                with zf.open(member) as src:
                    dest.write_bytes(src.read())
                so_count += 1
                continue

            # include/* -> cpp/include
            if "include" in parts:
                inc_idx = list(parts).index("include")
                rel_parts = parts[inc_idx + 1:]
                if not rel_parts:
                    continue
                dest = INCLUDE_DIR / Path(*rel_parts)
                dest.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(member) as src:
                    dest.write_bytes(src.read())
                inc_count += 1
                continue

            # 其他文件（resource、sample、docs 等）暂时不需要，跳过

    VERSION_FILE.write_text(version + "\n", encoding="utf-8")
    print(f"  [VERSION] 写入 {VERSION_FILE.name}: {version}")
    print(f"\n  so 文件: {so_count} 个, include 文件: {inc_count} 个")


def main():
    parser = argparse.ArgumentParser(description="下载并部署 MaaFramework Android 预编译库")
    parser.add_argument("--repo", "-r", default=DEFAULT_GITHUB_REPO, help="GitHub 仓库 (owner/repo)")
    parser.add_argument("--tag", "-t", help="指定 release tag（默认 latest）")
    parser.add_argument("--skip-download", "-s", action="store_true", help="跳过下载，仅用缓存")
    args = parser.parse_args()

    global API_BASE
    API_BASE = f"https://api.github.com/repos/{args.repo}"

    CACHE_DIR.mkdir(parents=True, exist_ok=True)

    print("=" * 60)
    print("==> MaaFramework Android 预编译库下载部署")
    print("=" * 60)

    version = None
    zip_path = None

    if not args.skip_download:
        version, assets = get_release_assets(args.tag)
        asset = find_android_asset(assets)
        if not asset:
            print(f"[ERROR] 没找到 Android aarch64 构建产物，关键词：{ASSET_KEYWORD}")
            print("[HINT] MaaFramework 的 release asset 名称类似：")
            print("       MAA-android-aarch64-v2.0.0.zip")
            sys.exit(1)

        print(f"\n[INFO] 找到产物: {asset['name']} ({asset.get('size', 0) / 1024 / 1024:.1f} MB)")
        zip_path = CACHE_DIR / asset["name"]
        if zip_path.exists():
            print(f"  [CACHE] 已存在，跳过下载")
        else:
            download_file(asset["browser_download_url"], zip_path)
    else:
        print("[SKIP] 跳过下载，使用缓存")
        zips = sorted(CACHE_DIR.glob("*.zip"))
        if not zips:
            print("[ERROR] 缓存里没有 zip，先跑一次不带 --skip-download 的喵")
            sys.exit(1)
        zip_path = zips[-1]
        version = args.tag or "unknown"

    print(f"\n[DEPLOY] 部署到项目...")
    extract_and_deploy(zip_path, version)

    print("\n" + "=" * 60)
    print("[DONE] 部署完成喵～")
    print("=" * 60)
    print(f"  jniLibs: {JNI_DIR}")
    print(f"  include: {INCLUDE_DIR}")
    if JNI_DIR.exists():
        so_files = sorted(f.name for f in JNI_DIR.iterdir() if f.suffix == ".so")
        print(f"  so 列表: {', '.join(so_files)}")


if __name__ == "__main__":
    main()