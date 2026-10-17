#!/usr/bin/env python3
"""把**用户可见**的 Coomi 文案改成 Comax（不动任何标识符与 URL）。

规则（只做这一件窄事，宁可少改也不要错改）：
  · 保护 `Coomi` + 字母/数字/下划线 —— 那是标识符（CoomiIcon / CoomiTheme /
    CoomiAndroid / Theme_Coomi_Abyss…），改了会直接编译不过或桥接失联。
  · 保护 URL 里出现的 Coomi —— github.com/TensorHub-ORG/Coomi 是**上游仓库**，
    改成 Comax 会指向一个不存在的地址。
  · 其余裸 Coomi（后面跟空格、标点、行尾）才是显示给用户看的，替换成 Comax。

用法：
  python3 rename-brand.py            # 只报告会改多少处，不写文件
  python3 rename-brand.py --apply    # 真正写入
"""
import io
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

EXTS = {".vue", ".ts", ".rs", ".java", ".xml", ".md", ".html", ".css", ".py", ".sh", ".json"}
SKIP_DIRS = {
    "node_modules", "target", "build", ".git", "dist", ".gradle", "caches",
    "runtime-v2-dist", "jniLibs", "assets", "backups", "site_assets",
    # vendored 的 Termux 库：里面 "Coomi" 是当初把 Termux 替换出来的，
    # 有一部分还带着路径/前缀语义，整块不动最稳。
    "termux-shared",
}
SKIP_FILES = {"latest.json", "package-lock.json", "Cargo.lock", "rename-brand.py"}

# 受保护：URL（含上游仓库地址）
URL_PATTERN = re.compile(r"https?://[^\s<>\"')]*")
# 待替换：Coomi 必须是一个「独立词」。
#
# 前后都要排除标识符字符，而且**后面还要排除点号和连字符** —— 这是本脚本最容易
# 踩的坑：安卓的资源引用长这样
#     @style/Theme.Coomi        ← 后面是引号，看着像普通词
#     R.style.Theme_Coomi;      ← 前面是下划线，看着也像普通词
#     @style/Coomi.Row          ← 后面是点
# 只按"后面不是字母数字"来判断，这三处全会被改掉，然后整个 App 编不过。
#
# 这里刻意用 [A-Za-z0-9_] 而不是 \w：Python 的 \w 在 str 模式下**包含中日韩汉字**，
# 于是「Coomi都会提示你」这种"紧跟中文"的写法会被当成标识符放过 —— 实测漏了 4 处。
# 显式列出 ASCII 词字符，才是我们要的"词边界"。
BRAND_PATTERN = re.compile(r"(?<![A-Za-z0-9_.])Coomi(?![A-Za-z0-9_.\-])")

# 例外：Coomi-Android 是界面上显示的构建名（"当前版本 Coomi-Android 1.5.x"），
# 该改；而 Coomi-main 是本地源码目录名，绝不能动。两者后缀不同，可以精确定位。
BRAND_HYPHEN_EXCEPTION = ("Coomi-Android", "Comax-Android")


def rename(text):
    """返回 (新文本, 改动处数)。URL 先摘出来，替换完再放回去。"""
    urls = []

    def stash(match):
        urls.append(match.group(0))
        return f"\x01{len(urls) - 1}\x01"

    masked = URL_PATTERN.sub(stash, text)
    needle, replacement = BRAND_HYPHEN_EXCEPTION
    exception_hits = masked.count(needle)
    if exception_hits:
        masked = masked.replace(needle, replacement)
    count = exception_hits + len(BRAND_PATTERN.findall(masked))
    if count == 0:
        return text, 0
    masked = BRAND_PATTERN.sub("Comax", masked)
    restored = re.sub(r"\x01(\d+)\x01", lambda m: urls[int(m.group(1))], masked)
    return restored, count


def walk():
    for root, dirs, files in os.walk(REPO):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in files:
            if name in SKIP_FILES or os.path.splitext(name)[1] not in EXTS:
                continue
            yield os.path.join(root, name)


def self_test():
    cases = [
        # 可见文案：要改
        ("Coomi 正在思考", "Comax 正在思考", 1),
        ("给 Coomi 下达任务…", "给 Comax 下达任务…", 1),
        ("<title>Coomi Agent</title>", "<title>Comax Agent</title>", 1),
        # 标识符：一律不动
        ("<CoomiIcon name='x' />", "<CoomiIcon name='x' />", 0),
        ("window.CoomiAndroid?.importFiles?.()", "window.CoomiAndroid?.importFiles?.()", 0),
        ("Theme_Coomi_Abyss_Page", "Theme_Coomi_Abyss_Page", 0),
        ("coomi_ember_bg", "coomi_ember_bg", 0),
        # 安卓资源引用：前后都是标识符边界，绝不能改
        ('android:theme="@style/Theme.Coomi"', 'android:theme="@style/Theme.Coomi"', 0),
        ("R.style.Theme_Coomi;", "R.style.Theme_Coomi;", 0),
        ('style="@style/Coomi.Row"', 'style="@style/Coomi.Row"', 0),
        ('<style name="Coomi.Text.Body">', '<style name="Coomi.Text.Body">', 0),
        # Coomi-Android 是可见构建名/UA，属于要改的（与 Coomi-main 目录名区分开）
        ('"Coomi-Android/" + code', '"Comax-Android/" + code', 1),
        # 但资源里的用户可见值要改
        ('<string name="x">Coomi 控制台</string>', '<string name="x">Comax 控制台</string>', 1),
        # 紧跟中文也算词边界（\w 会把汉字当词字符而漏掉）
        ("Coomi都会提示你", "Comax都会提示你", 1),
        # Coomi-Android 是可见的构建名，要改
        ("Coomi-Android 1.5", "Comax-Android 1.5", 1),
        # Coomi-main 是源码目录名，绝不能改
        ("/workspace/coomi-src-cn/Coomi-main", "/workspace/coomi-src-cn/Coomi-main", 0),
        # URL：不动
        ("https://github.com/TensorHub-ORG/Coomi", "https://github.com/TensorHub-ORG/Coomi", 0),
        # 混在一起
        (
            "Coomi 的图标是 <CoomiIcon />，仓库在 https://github.com/TensorHub-ORG/Coomi",
            "Comax 的图标是 <CoomiIcon />，仓库在 https://github.com/TensorHub-ORG/Coomi",
            1,
        ),
    ]
    for source, expected, expected_count in cases:
        actual, count = rename(source)
        assert actual == expected, f"输入 {source!r}\n期望 {expected!r}\n实际 {actual!r}"
        assert count == expected_count, f"输入 {source!r} 计数期望 {expected_count} 实际 {count}"
    print("self-test ok：可见文案改、标识符与 URL 不动")


def main():
    if "--self-test" in sys.argv:
        self_test()
        return
    apply = "--apply" in sys.argv
    total_files = 0
    total_hits = 0
    for path in walk():
        try:
            text = io.open(path, "r", encoding="utf-8").read()
        except (UnicodeDecodeError, OSError):
            continue
        new_text, count = rename(text)
        if count == 0:
            continue
        total_files += 1
        total_hits += count
        print(f"{'改  ' if apply else '将改'} {os.path.relpath(path, REPO)}: {count} 处")
        if apply:
            io.open(path, "w", encoding="utf-8", newline="").write(new_text)
    print(f"\n{'已改' if apply else '预计改'} {total_files} 个文件，共 {total_hits} 处")
    if not apply:
        print("（加 --apply 才会真正写入）")


if __name__ == "__main__":
    main()
