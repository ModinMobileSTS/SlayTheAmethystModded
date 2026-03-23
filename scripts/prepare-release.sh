#!/usr/bin/env bash

set -euo pipefail

SKIP_LOCAL_CHECK=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-local-check)
      SKIP_LOCAL_CHECK=1
      ;;
    *)
      echo "未知参数: $1" >&2
      exit 1
      ;;
  esac
  shift
done

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

confirm_yes_no() {
  local prompt="$1"
  local default_answer="${2:-n}"
  local answer=""
  local suffix="[y/N]"

  if [[ "$default_answer" == "y" ]]; then
    suffix="[Y/n]"
  fi

  while true; do
    read -r -p "$prompt $suffix " answer || exit 1
    if [[ -z "$answer" ]]; then
      answer="$default_answer"
    fi

    case "$answer" in
      y|Y|yes|YES|Yes)
        return 0
        ;;
      n|N|no|NO|No)
        return 1
        ;;
      *)
        echo "请输入 y 或 n。"
        ;;
    esac
  done
}

read_secret_value() {
  local prompt="$1"
  local value=""
  read -r -s -p "$prompt: " value || exit 1
  echo
  if [[ -z "$value" ]]; then
    echo "Secret cannot be empty: $prompt" >&2
    exit 1
  fi
  printf '%s' "$value"
}

read_gradle_version_name() {
  awk -F= '
    $1 == "application.version.name" {
      value = $2
      sub(/\r$/, "", value)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      print value
      exit
    }
  ' "$1"
}

create_note_template() {
  local note_file="$1"
  local tag_name="$2"
  local today="$3"

  mkdir -p "$(dirname "$note_file")"

  if [[ -f "$note_file" ]]; then
    echo "发布说明已存在，保留现有文件: ${note_file#$REPO_ROOT/}"
    return 0
  fi

  cat >"$note_file" <<EOF
# $tag_name

发布日期: $today

## 新特性
- 

## 修复
- 
EOF

  echo "已生成发布说明模板: ${note_file#$REPO_ROOT/}"
}

run_gradle_wrapper() {
  local gradle_wrapper="$REPO_ROOT/gradlew"

  if [[ ! -f "$gradle_wrapper" ]]; then
    echo "未找到 gradle wrapper: $gradle_wrapper" >&2
    exit 1
  fi

  if [[ -x "$gradle_wrapper" ]]; then
    "$gradle_wrapper" "$@"
  else
    bash "$gradle_wrapper" "$@"
  fi
}

run_local_release_preflight() {
  local default_store_file="$REPO_ROOT/signing/stamethyst-upload.jks"
  local resolved_store_file="${RELEASE_STORE_FILE:-$default_store_file}"
  local resolved_store_password="${RELEASE_STORE_PASSWORD:-}"
  local resolved_key_alias="${RELEASE_KEY_ALIAS:-upload}"
  local resolved_key_password="${RELEASE_KEY_PASSWORD:-}"

  if [[ ! -f "$resolved_store_file" ]]; then
    echo "Missing release keystore: $resolved_store_file" >&2
    exit 1
  fi

  if [[ -z "$resolved_store_password" ]]; then
    resolved_store_password="$(read_secret_value 'RELEASE_STORE_PASSWORD')"
  fi
  if [[ -z "$resolved_key_password" ]]; then
    resolved_key_password="$(read_secret_value 'RELEASE_KEY_PASSWORD')"
  fi

  echo
  echo "开始执行本地发布预检（lintDebug + assembleRelease）..."
  (
    export RELEASE_STORE_FILE="$resolved_store_file"
    export RELEASE_STORE_PASSWORD="$resolved_store_password"
    export RELEASE_KEY_ALIAS="$resolved_key_alias"
    export RELEASE_KEY_PASSWORD="$resolved_key_password"

    cd "$REPO_ROOT"
    run_gradle_wrapper :app:lintDebug --stacktrace --console=plain
    run_gradle_wrapper :app:assembleRelease --stacktrace --console=plain
  )
  echo "本地发布预检通过。"
  echo "Release APK directory: $REPO_ROOT/app/build/outputs/apk/release"
}

require_command git
require_command awk
require_command date

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"

if [[ -z "$REPO_ROOT" ]]; then
  echo "当前脚本不在 Git 仓库中，无法继续。" >&2
  exit 1
fi

cd "$REPO_ROOT"

GRADLE_FILE="$REPO_ROOT/gradle.properties"

if [[ ! -f "$GRADLE_FILE" ]]; then
  echo "未找到 gradle.properties: $GRADLE_FILE" >&2
  exit 1
fi

VERSION_NAME="$(read_gradle_version_name "$GRADLE_FILE")"
if [[ -z "$VERSION_NAME" ]]; then
  echo "无法从 gradle.properties 读取 application.version.name。" >&2
  exit 1
fi

TAG_NAME="v$VERSION_NAME"
NOTE_DIR="$REPO_ROOT/docs/release/note"
NOTE_FILE_REL="docs/release/note/$TAG_NAME.md"
NOTE_FILE="$REPO_ROOT/$NOTE_FILE_REL"
TODAY="$(date +%F)"

if ! confirm_yes_no "是否要发布版本 $TAG_NAME？" "n"; then
  echo "已取消发布。"
  exit 0
fi

CURRENT_BRANCH="$(git branch --show-current)"
if [[ -z "$CURRENT_BRANCH" ]]; then
  echo "当前处于 detached HEAD，无法自动推送分支。" >&2
  exit 1
fi

UPSTREAM_REF="$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null || true)"
REMOTE_NAME="origin"
REMOTE_BRANCH="$CURRENT_BRANCH"

if [[ -n "$UPSTREAM_REF" ]]; then
  REMOTE_NAME="${UPSTREAM_REF%%/*}"
  REMOTE_BRANCH="${UPSTREAM_REF#*/}"
fi

if ! git remote get-url "$REMOTE_NAME" >/dev/null 2>&1; then
  echo "未找到可用远端: $REMOTE_NAME" >&2
  exit 1
fi

if git rev-parse -q --verify "refs/tags/$TAG_NAME" >/dev/null 2>&1; then
  echo "本地已存在 tag: $TAG_NAME" >&2
  exit 1
fi

if [[ "$SKIP_LOCAL_CHECK" -eq 1 ]]; then
  echo "已跳过本地发布预检。"
else
  run_local_release_preflight
fi

create_note_template "$NOTE_FILE" "$TAG_NAME" "$TODAY"

echo "请编辑 $NOTE_FILE_REL 并填写更新日志。"
while true; do
  read -r -p "填写完成后输入 y 继续，输入 n 取消本次发布: " answer || exit 1
  case "$answer" in
    y|Y)
      break
      ;;
    n|N)
      echo "已取消发布。"
      exit 0
      ;;
    *)
      echo "请输入 y 或 n。"
      ;;
  esac
done

CURRENT_VERSION_NAME="$(read_gradle_version_name "$GRADLE_FILE")"
CURRENT_TAG_NAME="v$CURRENT_VERSION_NAME"
if [[ "$CURRENT_TAG_NAME" != "$TAG_NAME" ]]; then
  echo "gradle.properties 中的版本已变更为 $CURRENT_TAG_NAME，请重新运行脚本。" >&2
  exit 1
fi

if [[ ! -f "$NOTE_FILE" ]]; then
  echo "未找到发布说明文件: $NOTE_FILE_REL" >&2
  exit 1
fi

GRADLE_IS_DIRTY=0
if [[ -n "$(git status --porcelain -- gradle.properties)" ]]; then
  GRADLE_IS_DIRTY=1
fi

echo
echo "即将发布以下内容:"
echo "  版本: $TAG_NAME"
echo "  说明文件: $NOTE_FILE_REL"
if [[ "$GRADLE_IS_DIRTY" -eq 1 ]]; then
  echo "  附带文件: gradle.properties"
else
  echo "  附带文件: 无 gradle.properties 本地改动"
fi
echo "  提交信息: chore(release): prepare $TAG_NAME"
echo "  推送目标: $REMOTE_NAME/$REMOTE_BRANCH"
echo

if ! confirm_yes_no "确认提交并推送本次发布？" "n"; then
  echo "已取消发布，未创建 commit 或 tag。"
  exit 0
fi

REMOTE_TAG_OUTPUT=""
if ! REMOTE_TAG_OUTPUT="$(git ls-remote --tags "$REMOTE_NAME" "refs/tags/$TAG_NAME" 2>/dev/null)"; then
  echo "无法查询远端 tag，请检查网络或仓库权限后重试。" >&2
  exit 1
fi

if [[ -n "$REMOTE_TAG_OUTPUT" ]]; then
  echo "远端已存在 tag: $TAG_NAME" >&2
  exit 1
fi

git add -- "$NOTE_FILE_REL"

COMMIT_PATHS=("$NOTE_FILE_REL")
if [[ "$GRADLE_IS_DIRTY" -eq 1 ]]; then
  git add -- "gradle.properties"
  COMMIT_PATHS+=("gradle.properties")
fi

if git diff --cached --quiet -- "${COMMIT_PATHS[@]}"; then
  echo "没有可提交的发布变更。请确认已编辑 $NOTE_FILE_REL 或修改 gradle.properties。" >&2
  exit 1
fi

COMMIT_MESSAGE="chore(release): prepare $TAG_NAME"
git commit --only -m "$COMMIT_MESSAGE" -- "${COMMIT_PATHS[@]}"
git tag -a "$TAG_NAME" -m "Release $TAG_NAME"

git push "$REMOTE_NAME" "HEAD:$REMOTE_BRANCH"
git push "$REMOTE_NAME" "refs/tags/$TAG_NAME"

echo
echo "发布准备完成:"
echo "  commit: $COMMIT_MESSAGE"
echo "  tag: $TAG_NAME"
echo "  已推送到: $REMOTE_NAME/$REMOTE_BRANCH"
