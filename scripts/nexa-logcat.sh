#!/usr/bin/env bash
# Capture Nexa / NPU-related logcat for coding-agent generate failures.
# Usage:
#   ./scripts/nexa-logcat.sh              # live stream
#   ./scripts/nexa-logcat.sh --clear      # clear then live stream
#   ./scripts/nexa-logcat.sh --dump FILE  # dump buffer to FILE after you reproduce

set -euo pipefail

CLEAR=0
DUMP=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --clear|-c) CLEAR=1; shift ;;
    --dump|-d)
      DUMP="${2:-nexa-logcat.txt}"
      shift 2
      ;;
    -h|--help)
      sed -n '2,8p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found on PATH" >&2
  exit 1
fi

if [[ "$CLEAR" -eq 1 ]]; then
  adb logcat -c
  echo "logcat buffer cleared; reproduce the failure in the app..."
fi

# Primary tags: Nexa JNI + GenieX + FastRPC/QNN noise often appears under jni / app pid.
# Broad grep fallback catches [STDERR] lines without a stable tag.
FILTER='NexaSdk:V jni:V GenieXSdk:V AndroidRuntime:E libc:E *:S'

if [[ -n "$DUMP" ]]; then
  echo "Reproduce the failure, then press Ctrl+C is not needed — dumping current buffer to $DUMP"
  adb logcat -d -v threadtime $FILTER > "$DUMP"
  # Also append a wider grep pass for QNN/HTP/fastrpc lines missed by tag filter
  adb logcat -d -v threadtime | grep -iE 'NexaSdk|jni|GenieX|Qnn|QNN|HTP|cdsprpc|fastrpc|adsprpc|Hexagon|applyChatTemplate|generateStreamFlow|LlmWrapper|promptChars' >> "$DUMP" || true
  echo "Wrote $DUMP ($(wc -l < "$DUMP") lines)"
else
  echo "Streaming NexaSdk-focused logcat (Ctrl+C to stop)..."
  adb logcat -v threadtime $FILTER
fi
