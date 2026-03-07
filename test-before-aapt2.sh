#!/usr/bin/env bash
set -euo pipefail

# Android test runner:
# - pre-AAPT2 checks: Java compilation
# - AAPT2+ checks: resources, APK assemble, unit tests
# Usage:
#   ./test-before-aapt2.sh [module] [--pre-only|--from-aapt2|--full] [--clean]

usage() {
  cat <<'EOF'
Usage: ./test-before-aapt2.sh [module] [--pre-only|--from-aapt2|--full] [--clean]
  module        Android module name (default: app)
  --pre-only    Run only pre-AAPT2 compile tasks
  --from-aapt2  Run AAPT2 and later tasks (resource processing, APK build, tests)
  --full        Run both pre-AAPT2 and AAPT2+ tasks (default)
  --clean       Remove build artifacts and generated local.properties at exit
EOF
}

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$REPO_ROOT"

MODULE="app"
MODE="full"
CLEAN_ON_EXIT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --pre-only)
      MODE="pre"
      ;;
    --from-aapt2|--post-only)
      MODE="post"
      ;;
    --full)
      MODE="full"
      ;;
    --clean)
      CLEAN_ON_EXIT=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      MODULE="$1"
      ;;
  esac
  shift
done

JDK_DIR="${JDK_DIR:-$HOME/.local/jdk-17}"
GRADLE_DIR="${GRADLE_DIR:-$HOME/.local/gradle/gradle-8.10.2}"

if [ -x "$JDK_DIR/bin/java" ]; then
  export JAVA_HOME="$JDK_DIR"
  export PATH="$JDK_DIR/bin:$PATH"
fi
if [ -x "$GRADLE_DIR/bin/gradle" ]; then
  export PATH="$GRADLE_DIR/bin:$PATH"
fi

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
if [ -d "$ANDROID_SDK_ROOT" ]; then
  export ANDROID_SDK_ROOT
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi

echo "Repository: $REPO_ROOT"
echo "Module: $MODULE"
echo "Mode: $MODE"
echo "Java:"
java -version 2>&1 | sed -n '1,2p' || true
echo "Gradle:"
gradle -v 2>/dev/null | sed -n '1,8p' || true

LOG_DIR="$REPO_ROOT/test_logs_$(date +%Y%m%d%H%M%S)"
mkdir -p "$LOG_DIR"

if [ -x "./gradlew" ]; then
  GRADLE_RUNNER="./gradlew"
else
  GRADLE_RUNNER="$(command -v gradle || true)"
fi

if [ -z "${GRADLE_RUNNER:-}" ]; then
  echo "No Gradle runner found (expected ./gradlew or gradle in PATH)." >&2
  exit 127
fi
echo "Gradle runner: $GRADLE_RUNNER"

GENERATED_LOCAL_PROPERTIES=0
if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "${ANDROID_SDK_ROOT:-}" ] && [ ! -f "$REPO_ROOT/local.properties" ]; then
  printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "$REPO_ROOT/local.properties"
  GENERATED_LOCAL_PROPERTIES=1
fi

resolve_aapt2_bin() {
  if [ -n "${AAPT2_BIN:-}" ] && [ -x "$AAPT2_BIN" ]; then
    printf '%s' "$AAPT2_BIN"
    return
  fi
  if [ -d "${ANDROID_SDK_ROOT:-}/build-tools" ]; then
    find "$ANDROID_SDK_ROOT/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt2 2>/dev/null | sort -V | tail -n 1
  fi
}

AAPT2_OVERRIDE=""
setup_aapt2_override() {
  local aapt2_bin
  aapt2_bin="$(resolve_aapt2_bin || true)"
  if [ -z "$aapt2_bin" ]; then
    return 0
  fi
  if "$aapt2_bin" version >/dev/null 2>&1; then
    AAPT2_OVERRIDE="$aapt2_bin"
    return 0
  fi
  if command -v qemu-x86_64 >/dev/null 2>&1 && [ -d /usr/x86_64-linux-gnu ]; then
    local wrapper_dir="$REPO_ROOT/.tmp-aapt2"
    local wrapper="$wrapper_dir/aapt2"
    mkdir -p "$wrapper_dir"
    cat > "$wrapper" <<EOF
#!/usr/bin/env bash
exec qemu-x86_64 -L /usr/x86_64-linux-gnu "$aapt2_bin" "\$@"
EOF
    chmod +x "$wrapper"
    if "$wrapper" version >/dev/null 2>&1; then
      AAPT2_OVERRIDE="$wrapper"
      return 0
    fi
  fi
  return 0
}

GRADLE_COMMON_ARGS=(--no-daemon --console=plain)
GRADLE_PROP_ARGS=()

run_gradle_tasks() {
  local logfile="$1"
  shift
  local cmd=("$GRADLE_RUNNER" "${GRADLE_PROP_ARGS[@]}" "$@" "${GRADLE_COMMON_ARGS[@]}")
  echo "Running: ${cmd[*]}"
  "${cmd[@]}" >"$logfile" 2>&1
}

RC=0
setup_aapt2_override || true
if [ -n "$AAPT2_OVERRIDE" ]; then
  GRADLE_PROP_ARGS+=("-Pandroid.aapt2FromMavenOverride=$AAPT2_OVERRIDE")
  echo "Using AAPT2 override: $AAPT2_OVERRIDE"
else
  echo "AAPT2 override unavailable; using AGP default AAPT2."
fi

if [ "$MODE" = "pre" ] || [ "$MODE" = "full" ]; then
  if run_gradle_tasks "$LOG_DIR/pre-aapt2.log" \
      ":$MODULE:compileDebugJavaWithJavac" \
      ":$MODULE:compileDebugUnitTestJavaWithJavac"; then
    :
  else
    RC=$?
  fi
fi

if [ "$MODE" = "post" ] || [ "$MODE" = "full" ]; then
  if run_gradle_tasks "$LOG_DIR/aapt2-plus.log" \
      ":$MODULE:processDebugResources" \
      ":$MODULE:assembleDebug" \
      ":$MODULE:testDebugUnitTest"; then
    :
  else
    RC=$?
  fi
fi

APK_GLOB="$REPO_ROOT/$MODULE/build/outputs/apk/debug"
APK_PATH="$(find "$APK_GLOB" -maxdepth 1 -type f -name '*.apk' 2>/dev/null | sort | tail -n 1 || true)"
if [ -n "$APK_PATH" ]; then
  echo "APK: $APK_PATH"
else
  echo "APK not found under: $APK_GLOB"
fi

cleanup() {
  if [ "$CLEAN_ON_EXIT" -ne 1 ]; then
    return
  fi
  echo "Cleaning build artifacts..."
  rm -rf "$REPO_ROOT/$MODULE/build" "$REPO_ROOT/build" "$REPO_ROOT/.gradle" || true
  if [ "$GENERATED_LOCAL_PROPERTIES" -eq 1 ] && [ -f "$REPO_ROOT/local.properties" ]; then
    rm -f "$REPO_ROOT/local.properties" || true
  fi
  rm -rf "$REPO_ROOT/.tmp-aapt2" || true
  echo "Cleanup complete."
}
trap cleanup EXIT

echo "Logs saved to: $LOG_DIR"
exit "$RC"
