#!/usr/bin/env python3
"""同步官网 index.html 里被硬编码的版本信息。

页面上有两处必须手改的地方（其余都由 latest.json 在运行时覆盖）：
  1. 静态兜底文案：标题、tagline、版本号、包大小、下载链接、更新说明标题；
     断网或 latest.json 拉取失败时，用户看到的就是这一份，不能停在旧版本。
  2. 「历史版本安装包」列表：它是写死的 HTML，需要按线上实际存在的包重建，
     否则新版发布后这一栏会一直停在很早以前。

用法：
  patch-site-index.py <index.html> --version V --code N --apk NAME --source NAME \\
      --size BYTES --apks "Comax-1.5.2o2-complete-signed.apk,Comax-1.5.2o1-...apk"
"""
import argparse
import re
import sys

FILE_PREFIX = re.compile(r"^(?:Cubee|Comax)-")
FILE_SUFFIXES = ("-complete-signed", "-signed", "-release")


def version_from_file(name: str) -> str:
    """Comax-1.5.2o4beta-complete-signed.apk → 1.5.2o4beta

    注意不能用正则一把梭：`(.+)-(?:complete-)?signed` 里贪婪的 `.+` 会把
    `-complete` 一起吃进版本号，非贪婪又会在没有 complete 段时错位。
    按已知后缀逐个剥离，语义明确。
    """
    base = FILE_PREFIX.sub("", name.strip())
    if not base.endswith(".apk"):
        return ""
    base = base[: -len(".apk")]
    for suffix in FILE_SUFFIXES:
        if base.endswith(suffix):
            return base[: -len(suffix)]
    return base


def version_sort_key(version: str):
    """1.5.2o4beta > 1.5.2o2 > 1.5.1o3 > 1.5.1-preview 这类版本号的排序键。"""
    match = re.match(r"^(\d+)\.(\d+)\.(\d+)(.*)$", version)
    if not match:
        return (0, 0, 0, 0, 0, 0)
    major, minor, patch, rest = match.groups()
    suffix = re.match(r"^o(\d+)", rest)
    o_number = int(suffix.group(1)) if suffix else 0
    tail = rest[suffix.end():] if suffix else rest.lstrip("-")
    # 正式版 > beta > 其它预发布标记
    if not tail:
        pre_rank = 2
    elif tail.startswith("beta"):
        pre_rank = 1
    else:
        pre_rank = 0
    return (int(major), int(minor), int(patch), 1 if suffix else 0, o_number, pre_rank)


def replace_once(text: str, pattern: str, replacement: str, label: str) -> str:
    new_text, count = re.subn(pattern, replacement, text, count=1)
    if count != 1:
        print(f"FAIL {label}: matched {count} times", file=sys.stderr)
        sys.exit(1)
    print(f"ok   {label}")
    return new_text


def self_test() -> None:
    """版本号解析与排序的不变量 —— 这两处错了页面会静默给出错误的历史列表。"""
    cases = {
        "Comax-1.5.2o4beta-complete-signed.apk": "1.5.2o4beta",
        "Comax-1.5.2o2-complete-signed.apk": "1.5.2o2",
        "Comax-1.5.1-preview-complete-signed.apk": "1.5.1-preview",
        "Cubee-1.4.6o1-complete-signed.apk": "1.4.6o1",
    }
    for name, expected in cases.items():
        actual = version_from_file(name)
        assert actual == expected, f"{name}: 期望 {expected}，实际 {actual}"

    order = ["1.5.1-preview", "1.5.2o4beta", "1.5.1o3", "1.5.2o2", "1.5.1o1", "1.5.2o1", "1.5.1o2"]
    sorted_order = sorted(order, key=version_sort_key, reverse=True)
    expected_order = ["1.5.2o4beta", "1.5.2o2", "1.5.2o1", "1.5.1o3", "1.5.1o2", "1.5.1o1", "1.5.1-preview"]
    assert sorted_order == expected_order, f"排序错误：{sorted_order}"
    # 预发布不能压过同号的正式版（1.5.2o2 必须排在 1.5.2o2beta 之前）
    assert version_sort_key("1.5.2o2") > version_sort_key("1.5.2o2beta")
    print("self-test ok：版本解析与排序符合预期")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("index", nargs="?")
    parser.add_argument("--version")
    parser.add_argument("--code")
    parser.add_argument("--apk")
    parser.add_argument("--source")
    parser.add_argument("--size")
    parser.add_argument("--apks", default="")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return
    if not args.index:
        parser.error("需要 index.html 路径（或 --self-test）")
    for required in ("version", "code", "apk", "source", "size"):
        if getattr(args, required) is None:
            parser.error(f"--{required} 是必需的")

    version = args.version
    size_mb = f"{int(args.size) / (1024 * 1024):.0f}"
    with open(args.index, "r", encoding="utf-8") as handle:
        text = handle.read()

    # ── 1. 静态兜底文案 ──
    text = replace_once(
        text,
        r'(<span id="tagline-text">)[^<]*(</span>)',
        rf"\g<1>Android {version} · Comax 魔改版\g<2>",
        "tagline",
    )
    text = replace_once(
        text,
        r'(<dd id="spec-version">)[^<]*(</dd>)',
        rf"\g<1>{version}\g<2>",
        "spec-version",
    )
    text = replace_once(
        text,
        r'(<dd id="spec-size">)[\s\S]*?(<span class="unit">)',
        rf"\g<1>{size_mb} \g<2>",
        "spec-size",
    )
    text = replace_once(
        text,
        r'(<h2 id="notes-title" data-split>)[^<]*(</h2>)',
        rf"\g<1>{version} 更新说明\g<2>",
        "notes-title",
    )
    text = replace_once(
        text,
        r'(id="apk-link" href=")[^"]*(")',
        rf"\g<1>/coomi/download/{args.apk}\g<2>",
        "apk-link",
    )
    text = replace_once(
        text,
        r'(<span id="apk-label">)[^<]*(</span>)',
        rf"\g<1>下载 Comax {version} APK\g<2>",
        "apk-label",
    )
    text = replace_once(
        text,
        r'(id="src-link" href=")[^"]*(")',
        rf"\g<1>/coomi/{args.source}\g<2>",
        "src-link",
    )
    text = replace_once(
        text,
        r'(<span class="val" id="meta-version">)[^<]*(</span>)',
        rf"\g<1>{version}\g<2>",
        "meta-version",
    )
    text = replace_once(
        text,
        r'(<span class="val" id="meta-size">)[^<]*(</span>)',
        rf"\g<1>{size_mb} MB\g<2>",
        "meta-size",
    )

    # ── 2. 历史版本列表：按线上实际存在的包重建 ──
    versions = []
    for name in filter(None, (item.strip() for item in args.apks.split(","))):
        parsed = version_from_file(name)
        if parsed:
            versions.append((parsed, name))
    versions.sort(key=lambda item: version_sort_key(item[0]), reverse=True)
    if not versions:
        # 拿不到线上清单时至少保住当前版本这一行，而不是留一份过期列表。
        versions = [(version, args.apk)]

    rows = []
    for item_version, name in versions:
        if item_version == version:
            rows.append(
                f'          <div class="ver-row"><span class="name">{item_version}（当前）</span>'
                f'<a href="#download">下载 ↓</a></div>'
            )
        else:
            rows.append(
                f'          <div class="ver-row"><span class="name">{item_version}</span>'
                f'<a href="/coomi/download/{name}" download>下载 ↓</a></div>'
            )
    listing = "\n".join(rows)
    text = replace_once(
        text,
        r'(<div id="history-list">)[\s\S]*?(\n        </div>\s*</details>)',
        rf"\g<1>\n{listing}\g<2>",
        f"history-list ({len(versions)} 个版本)",
    )

    with open(args.index, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)
    print(f"index.html 已同步到 {version}（vc{args.code}），包大小 {size_mb} MB")


if __name__ == "__main__":
    main()
