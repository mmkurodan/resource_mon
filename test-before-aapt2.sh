#!/usr/bin/env bash
set -euo pipefail

# Android test runner:
# - pre-AAPT2 checks: Java compilation
# - AAPT2+ checks: resources, artifact build, unit tests
# Usage:
#   ./test-before-aapt2.sh [module] [--pre-only|--from-aapt2|--full] [--apk-debug|--apk-release|--aab-debug|--aab-release] [--clean]

usage() {
  cat <<'EOF'
Usage: ./test-before-aapt2.sh [module] [--pre-only|--from-aapt2|--full] [--apk-debug|--apk-release|--aab-debug|--aab-release] [--clean]
  module         Android module name (default: app)
  --pre-only     Run only pre-AAPT2 compile tasks
  --from-aapt2   Run AAPT2 and later tasks (resource processing, artifact build, tests)
  --full         Run both pre-AAPT2 and AAPT2+ tasks (default)
  --apk-debug    Build debug APK (default)
  --apk-release  Build release APK (requires signing)
  --aab-debug    Build debug AAB
  --aab-release  Build release AAB (requires signing)
  --clean        Force-remove build artifacts and generated local.properties at exit
  note           On successful artifact build, it is copied to ~/downloads and intermediates are cleaned
EOF
}

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$REPO_ROOT"

MODULE="app"
MODE="full"
ARTIFACT_KIND="apk"
ARTIFACT_VARIANT="debug"
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
    --apk-debug)
      ARTIFACT_KIND="apk"
      ARTIFACT_VARIANT="debug"
      ;;
    --apk-release)
      ARTIFACT_KIND="apk"
      ARTIFACT_VARIANT="release"
      ;;
    --aab-debug)
      ARTIFACT_KIND="aab"
      ARTIFACT_VARIANT="debug"
      ;;
    --aab-release)
      ARTIFACT_KIND="aab"
      ARTIFACT_VARIANT="release"
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
DOWNLOADS_DIR="${APK_OUTPUT_DIR:-$HOME/downloads}"

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
echo "Artifact: $ARTIFACT_KIND/$ARTIFACT_VARIANT"

GENERATED_LOCAL_PROPERTIES=0
if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "${ANDROID_SDK_ROOT:-}" ] && [ ! -f "$REPO_ROOT/local.properties" ]; then
  printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "$REPO_ROOT/local.properties"
  GENERATED_LOCAL_PROPERTIES=1
fi
ARTIFACT_COPIED=0

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

ARTIFACT_TASK=":$MODULE:assembleDebug"
ARTIFACT_DIR="$REPO_ROOT/$MODULE/build/outputs/apk/debug"
ARTIFACT_PATTERN="*.apk"
ARTIFACT_LABEL="APK"
RESOURCE_TASK=":$MODULE:processDebugResources"

case "$ARTIFACT_KIND:$ARTIFACT_VARIANT" in
  apk:debug)
    ;;
  apk:release)
    ARTIFACT_TASK=":$MODULE:assembleRelease"
    ARTIFACT_DIR="$REPO_ROOT/$MODULE/build/outputs/apk/release"
    RESOURCE_TASK=":$MODULE:processReleaseResources"
    ;;
  aab:debug)
    ARTIFACT_TASK=":$MODULE:bundleDebug"
    ARTIFACT_DIR="$REPO_ROOT/$MODULE/build/outputs/bundle/debug"
    ARTIFACT_PATTERN="*.aab"
    ARTIFACT_LABEL="AAB"
    ;;
  aab:release)
    ARTIFACT_TASK=":$MODULE:bundleRelease"
    ARTIFACT_DIR="$REPO_ROOT/$MODULE/build/outputs/bundle/release"
    ARTIFACT_PATTERN="*.aab"
    ARTIFACT_LABEL="AAB"
    RESOURCE_TASK=":$MODULE:processReleaseResources"
    ;;
  *)
    echo "Unsupported artifact selection: $ARTIFACT_KIND/$ARTIFACT_VARIANT" >&2
    exit 2
    ;;
esac

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
      "$RESOURCE_TASK" \
      "$ARTIFACT_TASK" \
      ":$MODULE:testDebugUnitTest"; then
    :
  else
    RC=$?
  fi
fi

ARTIFACT_PATH="$(find "$ARTIFACT_DIR" -maxdepth 1 -type f -name "$ARTIFACT_PATTERN" 2>/dev/null | sort | tail -n 1 || true)"
if [ "$RC" -eq 0 ] && [ -n "$ARTIFACT_PATH" ]; then
  echo "$ARTIFACT_LABEL: $ARTIFACT_PATH"
  mkdir -p "$DOWNLOADS_DIR"
  ARTIFACT_DEST="$DOWNLOADS_DIR/$(basename "$ARTIFACT_PATH")"
  cp -f "$ARTIFACT_PATH" "$ARTIFACT_DEST"
  ARTIFACT_COPIED=1
  echo "$ARTIFACT_LABEL copied to: $ARTIFACT_DEST"
elif [ "$RC" -ne 0 ]; then
  echo "Skipping artifact copy because Gradle tasks failed."
else
  echo "$ARTIFACT_LABEL not found under: $ARTIFACT_DIR"
fi

cleanup() {
  if [ "$CLEAN_ON_EXIT" -ne 1 ] && [ "$ARTIFACT_COPIED" -ne 1 ]; then
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
