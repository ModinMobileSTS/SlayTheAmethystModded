from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import zipfile
from datetime import date
from pathlib import Path


def repo_root() -> Path:
    return Path(__file__).resolve().parents[3]


def resolve_repo_path(path: str | Path) -> Path:
    path = Path(path)
    if path.is_absolute():
        return path.resolve()
    return (repo_root() / path).resolve()


def run(command: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None, check: bool = True) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        command,
        cwd=str(cwd or repo_root()),
        env=env,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if check and completed.returncode != 0:
        raise RuntimeError(f"Command failed with exit code {completed.returncode}: {' '.join(command)}")
    return completed


def run_capture(command: list[str], *, cwd: Path | None = None, check: bool = True) -> str:
    completed = subprocess.run(
        command,
        cwd=str(cwd or repo_root()),
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if check and completed.returncode != 0:
        output = "\n".join(part for part in (completed.stdout, completed.stderr) if part)
        raise RuntimeError(f"Command failed with exit code {completed.returncode}: {' '.join(command)}\n{output}")
    return completed.stdout


def read_gradle_property(name: str, default: str = "") -> str:
    path = repo_root() / "gradle.properties"
    if not path.exists():
        return default
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        if key.strip() == name:
            return value.strip()
    return default


def resolve_env_value(name: str, default: str = "") -> str:
    value = os.environ.get(name)
    if value and value.strip():
        return value
    if os.name == "nt":
        try:
            import winreg

            locations = (
                (winreg.HKEY_CURRENT_USER, r"Environment"),
                (winreg.HKEY_LOCAL_MACHINE, r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment"),
            )
            for root, subkey in locations:
                try:
                    with winreg.OpenKey(root, subkey) as key:
                        value, _ = winreg.QueryValueEx(key, name)
                        if str(value).strip():
                            return str(value)
                except OSError:
                    continue
        except ImportError:
            pass
    return default


def read_signing_properties(directory: Path) -> dict[str, str]:
    properties_file = directory / "signing.properties"
    if not properties_file.is_file():
        return {}
    values: dict[str, str] = {}
    for line in properties_file.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def resolve_gradle_user_home() -> Path:
    value = os.environ.get("GRADLE_USER_HOME")
    if value and value.strip():
        return Path(value).expanduser().resolve()
    home = Path.home()
    if not str(home).strip():
        raise RuntimeError("Could not resolve a Gradle user home.")
    return (home / ".gradle").resolve()


def resolve_gradle_wrapper() -> Path:
    root = repo_root()
    windows_wrapper = root / "gradlew.bat"
    unix_wrapper = root / "gradlew"
    if os.name == "nt" and windows_wrapper.exists():
        return windows_wrapper
    if unix_wrapper.exists():
        return unix_wrapper
    if windows_wrapper.exists():
        return windows_wrapper
    raise RuntimeError(f"Missing gradle wrapper under: {root}")


def gradle_command(wrapper: Path, args: list[str]) -> list[str]:
    if os.name == "nt":
        return [os.environ.get("COMSPEC") or "cmd.exe", "/c", str(wrapper), *args]
    if not os.access(wrapper, os.X_OK):
        return ["bash", str(wrapper), *args]
    return [str(wrapper), *args]


def run_gradle(tasks: list[str], *, env: dict[str, str] | None = None, failure_message: str = "Gradle task failed.") -> None:
    args = [*tasks, "--stacktrace", "--console=plain"]
    completed = run(gradle_command(resolve_gradle_wrapper(), args), cwd=repo_root(), env=env, check=False)
    if completed.returncode != 0:
        raise RuntimeError(failure_message)


def verified_repo_child_path(child_path: str | Path) -> Path:
    root = repo_root().resolve()
    child = (root / child_path).resolve()
    if root != child and root not in child.parents:
        raise RuntimeError(f"Refusing to clean path outside repository: {child}")
    return child


def clear_release_native_build_cache() -> None:
    for child in ("app/.cxx/RelWithDebInfo", "app/build/intermediates/cxx/RelWithDebInfo"):
        path = verified_repo_child_path(child)
        if path.exists():
            print(f"Removing release native build cache: {path}")
            shutil.rmtree(path)


def with_release_env(store_file: str, key_alias: str) -> dict[str, str]:
    signature_directory = repo_root() / "build-deps" / "release-signature"
    properties = read_signing_properties(signature_directory)

    if store_file.strip():
        resolved_store_file = resolve_repo_path(store_file)
    else:
        env_store_file = resolve_env_value("RELEASE_STORE_FILE")
        if env_store_file:
            resolved_store_file = resolve_repo_path(env_store_file)
        else:
            store_file_name = properties.get("storeFile", "").strip() or "keystore.jks"
            resolved_store_file = (signature_directory / store_file_name).resolve()

    if not resolved_store_file.exists():
        raise RuntimeError(
            f"Missing release keystore: {resolved_store_file}. "
            "Place it under build-deps/release-signature/ or pass --store-file."
        )

    store_password = resolve_env_value("RELEASE_STORE_PASSWORD", properties.get("storePassword", "").strip())
    if not store_password:
        raise RuntimeError(
            "Missing release store password: set RELEASE_STORE_PASSWORD or add storePassword to "
            "build-deps/release-signature/signing.properties."
        )
    resolved_key_alias = (
        key_alias.strip()
        or resolve_env_value("RELEASE_KEY_ALIAS", properties.get("keyAlias", "").strip())
        or "upload"
    )
    key_password = resolve_env_value(
        "RELEASE_KEY_PASSWORD",
        properties.get("keyPassword", "").strip() or store_password,
    )

    env = os.environ.copy()
    env["GRADLE_USER_HOME"] = str(resolve_gradle_user_home())
    env["RELEASE_STORE_FILE"] = str(resolved_store_file)
    env["RELEASE_STORE_PASSWORD"] = store_password
    env["RELEASE_KEY_ALIAS"] = resolved_key_alias
    env["RELEASE_KEY_PASSWORD"] = key_password
    print(f"Release keystore: {resolved_store_file}")
    print(f"Gradle user home: {env['GRADLE_USER_HOME']}")
    return env


def release_build(
    *,
    store_file: str,
    key_alias: str,
    skip_lint_check: bool,
    skip_native_cache_cleanup: bool,
    gradle_tasks: list[str],
    output_dirs: list[str],
    display_name: str,
    fast: bool,
) -> None:
    env = with_release_env(store_file, key_alias)
    if not skip_lint_check:
        run_gradle([":app:lintDebug"], env=env, failure_message="lintDebug failed.")
    if not skip_native_cache_cleanup:
        clear_release_native_build_cache()
    run_gradle(gradle_tasks, env=env, failure_message=f"{display_name} failed.")
    for output_dir in output_dirs:
        print(f"{display_name} APK directory: {repo_root() / output_dir}")
    if fast:
        print("Fast release skips lint by default, release native cache cleanup, R8 minification, and resource shrinking.")


def build_debug(application_id: str) -> None:
    if not application_id.strip():
        raise RuntimeError("ApplicationId cannot be empty.")
    run_gradle([":app:assembleDebug", f"-Papplication.id={application_id}"], failure_message="assembleDebug failed.")
    print(f"Debug APK directory: {repo_root() / 'app' / 'build' / 'outputs' / 'apk' / 'debug'}")
    print(f"Temporary applicationId: {application_id}")


def package_cloud_function(source_dir: str, output_zip: str) -> None:
    source_root = resolve_repo_path(source_dir)
    if not source_root.is_dir():
        raise RuntimeError(f"Cloud function source directory not found: {source_root}")
    output_file = resolve_repo_path(output_zip)
    output_file.parent.mkdir(parents=True, exist_ok=True)
    staging_root = Path(tempfile.mkdtemp(prefix="cloud-function-scf-"))
    try:
        for source_child in source_root.iterdir():
            if source_child.name in {".git", ".idea"}:
                continue
            destination = staging_root / source_child.name
            if source_child.is_dir():
                shutil.copytree(source_child, destination, ignore=shutil.ignore_patterns(".git", ".idea"))
            else:
                shutil.copy2(source_child, destination)
        bootstrap = staging_root / "scf_bootstrap"
        if not bootstrap.is_file():
            raise RuntimeError(f"Missing required bootstrap file: {bootstrap}")
        bootstrap.write_text("#!/bin/bash\nexec /var/lang/node20/bin/node app.js\n", encoding="utf-8", newline="\n")
        if output_file.exists():
            output_file.unlink()
        with zipfile.ZipFile(output_file, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in staging_root.rglob("*"):
                if path.is_file():
                    archive.write(path, path.relative_to(staging_root).as_posix())
        print(f"[cloud-function] Created deployable zip: {output_file}")
    finally:
        shutil.rmtree(staging_root, ignore_errors=True)


def confirm_yes_no(prompt: str, default: str = "N") -> bool:
    suffix = "[Y/n]" if default.upper() == "Y" else "[y/N]"
    while True:
        answer = input(f"{prompt} {suffix} ").strip()
        if not answer:
            answer = default
        if answer.lower() in {"y", "yes"}:
            return True
        if answer.lower() in {"n", "no"}:
            return False
        print("请输入 y 或 n。")


def create_release_note_template(note_file: Path, note_file_relative: str, tag_name: str) -> None:
    if note_file.exists():
        print(f"发布说明已存在，保留现有文件: {note_file_relative}")
        return
    note_file.parent.mkdir(parents=True, exist_ok=True)
    note_file.write_text(
        "\n".join(
            [
                f"发布日期: {date.today().isoformat()}",
                "",
                "## 新特性",
                "- ",
                "",
                "## 修复",
                "- ",
                "",
            ]
        ),
        encoding="utf-8",
    )
    print(f"已生成发布说明模板: {note_file_relative}")


def git(args: list[str], *, check: bool = True) -> str:
    return run_capture(["git", *args], cwd=repo_root(), check=check).strip()


def local_release_check(store_file: str, key_alias: str) -> None:
    print()
    print("开始执行本地发布预检（lintDebug + assembleRelease + assembleFullRelease）...")
    try:
        release_build(
            store_file=store_file,
            key_alias=key_alias,
            skip_lint_check=False,
            skip_native_cache_cleanup=False,
            gradle_tasks=[":app:assembleRelease"],
            output_dirs=["app/build/outputs/apk/release"],
            display_name="Slim release build",
            fast=False,
        )
        release_build(
            store_file=store_file,
            key_alias=key_alias,
            skip_lint_check=True,
            skip_native_cache_cleanup=False,
            gradle_tasks=[":app:assembleFullRelease"],
            output_dirs=["app/build/outputs/apk/fullRelease"],
            display_name="Full release build",
            fast=False,
        )
    except Exception as exc:
        raise RuntimeError(f"本地发布预检失败：{exc}") from exc
    print("本地发布预检通过。")


def prepare_release(store_file: str, key_alias: str, skip_local_check: bool) -> None:
    try:
        git(["rev-parse", "--show-toplevel"])
    except RuntimeError as exc:
        raise RuntimeError("当前脚本不在 Git 仓库中，无法继续。") from exc

    gradle_file = repo_root() / "gradle.properties"
    if not gradle_file.exists():
        raise RuntimeError(f"未找到 gradle.properties: {gradle_file}")
    version_name = read_gradle_property("application.version.name")
    if not version_name:
        raise RuntimeError("无法从 gradle.properties 读取 application.version.name。")
    tag_name = f"v{version_name}"
    note_file_relative = f"docs/release/note/{tag_name}.md"
    note_file = repo_root() / note_file_relative

    if not confirm_yes_no(f"是否要发布版本 {tag_name}？"):
        print("已取消发布。")
        return

    current_branch = git(["branch", "--show-current"])
    if not current_branch:
        raise RuntimeError("当前处于 detached HEAD，无法自动推送分支。")
    upstream_ref = git(["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"], check=False)
    remote_name = "origin"
    remote_branch = current_branch
    if upstream_ref and "/" in upstream_ref:
        remote_name, remote_branch = upstream_ref.split("/", 1)
    git(["remote", "get-url", remote_name])
    if git(["rev-parse", "-q", "--verify", f"refs/tags/{tag_name}"], check=False):
        raise RuntimeError(f"本地已存在 tag: {tag_name}")

    if skip_local_check:
        print("已跳过本地发布预检。")
    else:
        local_release_check(store_file, key_alias)

    create_release_note_template(note_file, note_file_relative, tag_name)
    print(f"请编辑 {note_file_relative} 并填写更新日志。")
    while True:
        answer = input("填写完成后输入 y 继续，输入 n 取消本次发布: ").strip()
        if answer.lower() in {"y", "yes"}:
            break
        if answer.lower() in {"n", "no"}:
            print("已取消发布。")
            return
        print("请输入 y 或 n。")

    current_version = read_gradle_property("application.version.name")
    current_tag_name = f"v{current_version}"
    if current_tag_name != tag_name:
        raise RuntimeError(f"gradle.properties 中的版本已变更为 {current_tag_name}，请重新运行脚本。")
    if not note_file.exists():
        raise RuntimeError(f"未找到发布说明文件: {note_file_relative}")
    gradle_dirty = bool(git(["status", "--porcelain", "--", "gradle.properties"], check=False))

    print()
    print("即将发布以下内容:")
    print(f"  版本: {tag_name}")
    print(f"  说明文件: {note_file_relative}")
    print("  附带文件: gradle.properties" if gradle_dirty else "  附带文件: 无 gradle.properties 本地改动")
    print(f"  提交信息: chore(release): prepare {tag_name}")
    print(f"  推送目标: {remote_name}/{remote_branch}")
    print()
    if not confirm_yes_no("确认提交并推送本次发布？"):
        print("已取消发布，未创建 commit 或 tag。")
        return

    remote_tag_output = git(["ls-remote", "--tags", remote_name, f"refs/tags/{tag_name}"], check=False)
    if remote_tag_output:
        raise RuntimeError(f"远端已存在 tag: {tag_name}")

    git(["add", "--", note_file_relative])
    commit_paths = [note_file_relative]
    if gradle_dirty:
        git(["add", "--", "gradle.properties"])
        commit_paths.append("gradle.properties")
    diff = subprocess.run(["git", "diff", "--cached", "--quiet", "--", *commit_paths], cwd=str(repo_root()), check=False)
    if diff.returncode == 0:
        raise RuntimeError(f"没有可提交的发布变更。请确认已编辑 {note_file_relative} 或修改 gradle.properties。")
    if diff.returncode > 1:
        raise RuntimeError("git diff --cached 执行失败。")
    commit_message = f"chore(release): prepare {tag_name}"
    git(["commit", "--only", "-m", commit_message, "--", *commit_paths])
    git(["tag", "-a", tag_name, "-m", f"Release {tag_name}"])
    git(["push", remote_name, f"HEAD:{remote_branch}"])
    git(["push", remote_name, f"refs/tags/{tag_name}"])
    print()
    print("发布准备完成:")
    print(f"  commit: {commit_message}")
    print(f"  tag: {tag_name}")
    print(f"  已推送到: {remote_name}/{remote_branch}")
