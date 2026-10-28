#!/usr/bin/env python3
"""管理器包名一致性校验（不允许"只改一半"）。

背景：内核编译期宏 `KSU_PACKAGE_NAME` 必须与 manager APK 的 applicationId
逐字节相同，否则 `throne_tracker` 永远匹配不到管理器，app 只能以 preview
模式运行——能装、能跑，只是所有内核功能都是灰的，报错信息里也不会提到包名。
签名证书（EXPECTED_HASH）是另一条独立的不变式，本脚本不检查也不修改。

唯一来源：仓库根 `manager-package.properties`。本脚本断言三件事：
  1. 该文件本身合法，且 PR 变体 == 基础包名 + ".pr"；
  2. `kernel/manager-package.mk` 用真实 make 求值出的取值 == 期望值；
  3. （--gradle）Gradle 真实 applicationId == 期望值，即与内核侧相同；
  4. 关键注入点里没有重新写死包名字面量。

用法：
    python3 scripts/check_manager_package.py            # 文件 + 内核侧 + 字面量（无需 JDK）
    python3 scripts/check_manager_package.py --gradle   # 额外比对 Gradle 侧（CI 使用）
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

BASE_KEY = "KSU_MANAGER_PACKAGE_NAME"
PR_KEY = "KSU_MANAGER_PACKAGE_NAME_PR"
PACKAGE_RE = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")

failures: list[str] = []


def ok(message: str) -> None:
    print(f"[ ok ] {message}")


def fail(message: str) -> None:
    failures.append(message)
    print(f"[FAIL] {message}")


def read_single_source(source: Path) -> dict[str, str]:
    """严格解析 KEY=value；格式必须是 make / Properties / shell 三边通吃的子集。"""
    if not source.is_file():
        fail(f"缺少包名单一真源：{source}")
        return {}

    values: dict[str, str] = {}
    for lineno, raw in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"{source.name}:{lineno} 不是 KEY=value：{raw!r}")
            continue
        key, _, value = line.partition("=")
        if key != key.strip() or value != value.strip():
            fail(f"{source.name}:{lineno} 键或值两侧带空白（make/shell 会读歪）：{raw!r}")
        values[key.strip()] = value.strip()

    for key in (BASE_KEY, PR_KEY):
        if not values.get(key):
            fail(f"{source.name} 缺少非空的 {key}")
    return values


def check_values(base: str, pr: str) -> None:
    if not PACKAGE_RE.match(base):
        fail(f"{BASE_KEY}={base!r} 不是合法的 Android applicationId（要求全小写、至少两段）")
    else:
        ok(f"{BASE_KEY} = {base}")

    if pr != f"{base}.pr":
        fail(f"{PR_KEY}={pr!r} 必须等于 {BASE_KEY} + '.pr'（= {base}.pr）")
    elif not PACKAGE_RE.match(pr):
        fail(f"{PR_KEY}={pr!r} 不是合法的 Android applicationId")
    else:
        ok(f"{PR_KEY} = {pr}")


def check_length(base: str, pr: str, repo_root: Path) -> None:
    """内核里 sizeof(KSU_PACKAGE_NAME) < KSU_MAX_PACKAGE_NAME，这里提前拦一下。"""
    header = repo_root / "uapi" / "app_profile.h"
    if not header.is_file():
        return
    match = re.search(
        r"#define\s+KSU_MAX_PACKAGE_NAME\s+(\d+)", header.read_text(encoding="utf-8", errors="replace")
    )
    if not match:
        return
    limit = int(match.group(1))
    for key, value in ((BASE_KEY, base), (PR_KEY, pr)):
        if len(value) + 1 >= limit:
            fail(f"{key}={value!r} 长度 {len(value)} + NUL 不小于 KSU_MAX_PACKAGE_NAME={limit}")
    ok(f"包名长度在 KSU_MAX_PACKAGE_NAME={limit} 以内")


def kernel_value(repo_root: Path, pr: bool) -> str | None:
    """用真实 make 逻辑求内核侧取值，而不是在 Python 里重写一遍分支。"""
    make = shutil.which("make")
    if not make:
        # CI 里缺 make 是硬错误；本地（Windows 没装 make 很常见）只提示，
        # 否则本地永远一片红，反而会让人习惯性忽略整个脚本。
        message = "找不到 make，无法校验内核侧取值（需要 GNU make）"
        if os.environ.get("CI"):
            fail(message)
        else:
            print(f"[skip] {message}；本地可跳过，CI 会强制校验")
        return None

    command = [
        make,
        "-s",
        "-C",
        str(repo_root),
        "-f",
        "kernel/manager-package.mk",
        "KSU_MANAGER_PACKAGE_MK_STANDALONE=1",
        "print-ksu-package-name",
    ]
    if pr:
        command.append("KSU_IS_PR_BUILD=1")

    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        fail(f"make 求值失败（PR={pr}）：{result.stderr.strip() or result.stdout.strip()}")
        return None

    lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if not lines:
        fail(f"make 没有输出包名（PR={pr}）")
        return None
    return lines[-1]


def gradle_value(repo_root: Path, pr: bool) -> str | None:
    """取 Gradle 真正会写进 APK 的 applicationId。"""
    manager_dir = repo_root / "manager"
    wrapper = manager_dir / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        fail(f"找不到 Gradle wrapper：{wrapper}")
        return None

    command = [str(wrapper), "-q", ":app:printManagerPackageName"]
    if pr:
        command.append("-PIS_PR_BUILD=true")
    if os.name == "nt":
        command = ["cmd", "/c", *command]

    result = subprocess.run(command, cwd=manager_dir, capture_output=True, text=True)
    if result.returncode != 0:
        fail(
            "Gradle 求值失败（PR={}）：{}".format(
                pr, (result.stderr or result.stdout).strip()[:2000]
            )
        )
        return None

    for line in reversed(result.stdout.splitlines()):
        line = line.strip()
        if PACKAGE_RE.match(line):
            return line
    fail(f"Gradle 未打印包名（PR={pr}）：{result.stdout.strip()[:500]}")
    return None


def check_injection_points(repo_root: Path, base: str, pr: str) -> None:
    """注入点里不许再出现包名字面量；结构上必须派生自单一真源。

    分两档：
      - strict：构建脚本/工作流里任何一份包名都不许硬编码（连旧包名也不行，
        旧包名一旦被重新写死就是"只改一半"的现场）；
      - loose：Kbuild 与 build.gradle.kts 允许出现旧包名——那是 Kotlin
        `namespace`（源码命名空间）和历史注释，与 applicationId 无关，
        这两个文件改用结构断言（applicationId / KSU_PACKAGE_NAME 不许是字面量）。
    """
    strict_files = [
        ".github/workflows/build-manager.yml",
        ".github/workflows/build-lkm.yml",
        ".github/workflows/ddk-lkm.yml",
        ".github/workflows/ksud.yml",
        ".github/workflows/release.yml",
        "kernel/manager-package.mk",
        "userspace/ksud/build.rs",
        "manager/gradle.properties",
        "repack_apk.py",
        "split_apk_by_arch.py",
    ]
    loose_files = [
        "kernel/Kbuild",
        "manager/app/build.gradle.kts",
    ]
    # 旧包名也一并禁掉：它是"只改一半"时最容易残留的值，一旦被重新写死就是事故现场。
    # 注意这里刻意把旧值写成本脚本的字面量——它是断言，不是默认值，不参与任何构建取值。
    strict_forbidden = {base, pr, "com.sukisu.ultra", "com.sukisu.ultra.pr"} - {""}
    loose_forbidden = {base, pr} - {""}

    for rel, forbidden in [
        *((path, strict_forbidden) for path in strict_files),
        *((path, loose_forbidden) for path in loose_files),
    ]:
        path = repo_root / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for value in sorted(forbidden):
            if value in text:
                fail(f"{rel} 里出现了包名字面量 {value}；应当从 manager-package.properties 派生")

    structural = [
        (
            "manager/app/build.gradle.kts",
            r'applicationId\s*=\s*"',
            "applicationId 被写成了字符串字面量，必须用 managerPackageName 派生",
        ),
        (
            "kernel/Kbuild",
            r"KSU_PACKAGE_NAME\s*\?=\s*[A-Za-z0-9_.]+",
            "Kbuild 里重新写死了 KSU_PACKAGE_NAME 默认值，必须 include manager-package.mk",
        ),
        (
            "userspace/ksud/build.rs",
            r"KSU_PACKAGE_NAME=[A-Za-z0-9_.]+",
            "build.rs 里重新写死了 KSU_PACKAGE_NAME（cargo:rustc-env=KSU_PACKAGE_NAME=...）",
        ),
    ]
    for rel, pattern, message in structural:
        path = repo_root / rel
        if not path.is_file():
            fail(f"缺少关键注入点文件：{rel}")
            continue
        if re.search(pattern, path.read_text(encoding="utf-8", errors="replace")):
            fail(f"{rel}: {message}")

    for rel, expected in (
        ("kernel/Kbuild", "manager-package.mk"),
        ("kernel/manager-package.mk", "manager-package.properties"),
        ("userspace/ksud/build.rs", "manager-package.properties"),
        ("manager/app/build.gradle.kts", "manager-package.properties"),
    ):
        path = repo_root / rel
        if path.is_file() and expected not in path.read_text(encoding="utf-8", errors="replace"):
            fail(f"{rel} 没有引用 {expected}（单一真源断链）")


def check_identity_invariants(repo_root: Path, source: Path) -> None:
    """守住「applicationId」与「类名 / JNI 符号」这条边界。

    applicationId 改名后，所有以 com.sukisu.ultra 开头的 *类名、组件名、广播
    action、JNI 符号* 必须保持原样——它们由 Kotlin namespace 决定，不随
    applicationId 变化。这些字符串在源码里与包名逐字相同，肉眼无法区分，
    是"全局替换"最容易误伤的地方，而且失效时不会崩溃：

      - zygotePreloadName 被改 → Magica 免 root 链路静默不工作
      - late_load 里 MainActivity 类名被改 → am start 找不到组件，
        ksud 先 force-stop 再启动失败，用户看到的是"app 从桌面消失"
      - JNI 符号被改 → UnsatisfiedLinkError，app 直接启动崩溃

    因此这里把它们显式钉死，任何一次"顺手全替换"都会在 CI 被拦下。
    """
    invariants = [
        (
            "manager/app/build.gradle.kts",
            r'namespace\s*=\s*"com\.sukisu\.ultra"\s*$',
            "Kotlin namespace 被改动了；改 applicationId 不应影响 namespace（源码类名不随它变）",
        ),
        (
            "manager/app/src/main/AndroidManifest.xml",
            r'com\.sukisu\.ultra\.magica\.AppZygotePreload',
            "android:zygotePreloadName 被改成了新包名；它是类名，改了会让 Magica 静默失效",
        ),
        (
            "manager/app/src/main/AndroidManifest.xml",
            r'com\.sukisu\.ultra\.magica\.LAUNCH',
            "自定义广播 action 被改动，与 BootCompletedReceiver 里的常量不再配对",
        ),
        (
            "manager/app/src/main/java/com/sukisu/ultra/magica/BootCompletedReceiver.java",
            r'com\.sukisu\.ultra\.magica\.LAUNCH',
            "BootCompletedReceiver 的 action 常量被改动，与 AndroidManifest 不再配对",
        ),
        (
            "userspace/ksud/src/late_load.rs",
            r'com\.sukisu\.ultra\.ui\.MainActivity',
            "late_load 的 Activity 类名被改成了新包名；am start -n 会找不到组件",
        ),
        (
            "manager/app/src/main/cpp/jni.cc",
            r'Java_com_sukisu_ultra_Natives_',
            "JNI 符号名被改动；native 方法会 UnsatisfiedLinkError",
        ),
    ]

    for rel, pattern, message in invariants:
        path = repo_root / rel
        if not path.is_file():
            fail(f"缺少关键身份文件：{rel}")
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if not re.search(pattern, text, re.MULTILINE):
            fail(f"{rel}: {message}")

    # 真源文件必须是纯 LF：make 不会剥掉 \r，而 Python 会静默规范化，
    # 会造成"校验通过但内核编译进带 \r 的包名"这种无法自查的故障。
    raw = source.read_bytes()
    if b"\r" in raw:
        fail(
            "manager-package.properties 含 CR 字符（CRLF 行尾）；"
            "make 会把 \\r 一起编进内核的 KSU_PACKAGE_NAME，导致永远无法认主。"
            "请改为 LF，并确认 .gitattributes 里的 eol=lf 规则生效"
        )

    gitattributes = repo_root / ".gitattributes"
    if not gitattributes.is_file() or not re.search(
        r"^manager-package\.properties\s+text\s+eol=lf\s*$",
        gitattributes.read_text(encoding="utf-8", errors="replace"),
        re.MULTILINE,
    ):
        fail(
            ".gitattributes 缺少 `manager-package.properties text eol=lf` 规则；"
            "Windows 检出后该文件会变成 CRLF"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description="校验管理器包名的单一真源与三方取值一致性")
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
        help="仓库根目录（默认取本脚本的上一级）",
    )
    parser.add_argument(
        "--gradle",
        action="store_true",
        help="额外运行 Gradle 取真实 applicationId 并与内核侧比对（需要 JDK，CI 使用）",
    )
    args = parser.parse_args()

    repo_root: Path = args.repo_root.resolve()
    source = repo_root / "manager-package.properties"
    print(f"仓库根：{repo_root}")
    print(f"单一真源：{source}")

    values = read_single_source(source)
    if not values:
        return 1

    base = values.get(BASE_KEY, "")
    pr = values.get(PR_KEY, "")
    check_values(base, pr)
    check_length(base, pr, repo_root)

    expectations = {False: base, True: pr}
    for is_pr, expected in expectations.items():
        actual = kernel_value(repo_root, is_pr)
        label = f"内核侧 KSU_PACKAGE_NAME（KSU_IS_PR_BUILD={'1' if is_pr else '0'}）"
        if actual is None:
            continue
        if actual != expected:
            fail(f"{label} = {actual!r}，与单一真源的 {expected!r} 不一致")
        else:
            ok(f"{label} = {actual}")

    if args.gradle:
        for is_pr, expected in expectations.items():
            actual = gradle_value(repo_root, is_pr)
            label = f"Gradle applicationId（IS_PR_BUILD={'true' if is_pr else 'false'}）"
            if actual is None:
                continue
            if actual != expected:
                fail(f"{label} = {actual!r}，与单一真源的 {expected!r} 不一致")
            else:
                ok(f"{label} = {actual}")

    forbidden_before = len(failures)
    check_injection_points(repo_root, base, pr)
    if len(failures) == forbidden_before:
        ok("注入点均派生自单一真源，未发现重新写死的包名字面量")

    identity_before = len(failures)
    check_identity_invariants(repo_root, source)
    if len(failures) == identity_before:
        ok("类名 / 组件名 / JNI 符号未被包名替换误伤，真源为 LF 行尾")

    print()
    if failures:
        print(f"校验失败：{len(failures)} 项")
        for index, message in enumerate(failures, 1):
            print(f"  {index}. {message}")
        return 1
    print("校验通过：管理器包名只有一个来源，且内核侧与管理器侧取值一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
