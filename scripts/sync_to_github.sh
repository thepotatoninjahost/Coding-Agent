#!/bin/bash
# Sync selected local source files to GitHub using safe-github-push payload rules.
# Content is always loaded from disk — never invented.
#
# Usage:
#   scripts/sync_to_github.sh [--owner OWNER] [--repo REPO] [--branch BRANCH] [--dry-run]
#
# Default mapping: the two current core files that this script can safely sync.
# LiveModules.kt was split into several runtime/store/bootstrap files; pass an
# explicit --file or SYNC_FILES mapping for whichever current file is intended.
# Override with SYNC_FILES env (newline-separated "repo/path=local/path" lines)
# or pass extra --file repo/path=local/path args.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKILL_BUILD="${SAFE_PUSH_BUILD:-/home/workdir/.grok/skills/safe-github-push/scripts/build_push_payload.py}"
SKILL_VERIFY="${SAFE_PUSH_VERIFY:-/home/workdir/.grok/skills/safe-github-push/scripts/verify_remote_match.py}"

OWNER="${SYNC_OWNER:-thepotatoninjahost}"
REPO="${SYNC_REPO:-Coding-Agent}"
BRANCH="${SYNC_BRANCH:-main}"
MESSAGE="${SYNC_MESSAGE:-Restore core Kotlin sources from verified local tree}"
OUT="${SYNC_OUT:-/tmp/coding-agent-sync-payload.json}"
META_OUT="${SYNC_META_OUT:-/tmp/coding-agent-sync-meta.json}"
DRY_RUN=0
MIN_CHARS="${SYNC_MIN_CHARS:-100}"

EXTRA_FILES=()

usage() {
  sed -n '1,20p' "$0" | tail -n +2
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --owner) OWNER="$2"; shift 2 ;;
    --repo) REPO="$2"; shift 2 ;;
    --branch) BRANCH="$2"; shift 2 ;;
    --message) MESSAGE="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --meta-out) META_OUT="$2"; shift 2 ;;
    --min-chars) MIN_CHARS="$2"; shift 2 ;;
    --file) EXTRA_FILES+=("$2"); shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "Unknown arg: $1" >&2; usage 1 ;;
  esac
done

if [[ ! -f "$SKILL_BUILD" ]]; then
  echo "This helper is not part of the app. It expects an external safe-github-push checkout at:" >&2
  echo "  $SKILL_BUILD" >&2
  echo "That path is not in this repository. Use git commit and git push instead." >&2
  exit 1
fi

DEFAULT_FILES=(
  "app/src/main/java/com/codingagent/workspace/ProjectWorkspace.kt=${ROOT}/app/src/main/java/com/codingagent/workspace/ProjectWorkspace.kt"
  "app/src/main/java/com/codingagent/agent/AutonomousAgent.kt=${ROOT}/app/src/main/java/com/codingagent/agent/AutonomousAgent.kt"
)

FILE_ARGS=()
if [[ -n "${SYNC_FILES:-}" ]]; then
  while IFS= read -r line; do
    [[ -z "$line" || "$line" =~ ^# ]] && continue
    FILE_ARGS+=(--file "$line")
  done <<< "$SYNC_FILES"
else
  for mapping in "${DEFAULT_FILES[@]}"; do
    FILE_ARGS+=(--file "$mapping")
  done
fi
for mapping in "${EXTRA_FILES[@]+"${EXTRA_FILES[@]}"}"; do
  FILE_ARGS+=(--file "$mapping")
done

echo "==> Building verified payload (disk only)"
python3 "$SKILL_BUILD" \
  --owner "$OWNER" \
  --repo "$REPO" \
  --branch "$BRANCH" \
  --message "$MESSAGE" \
  --min-chars "$MIN_CHARS" \
  "${FILE_ARGS[@]}" \
  --out "$OUT" \
  --meta-out "$META_OUT"

echo
echo "==> Payload summary"
python3 -c "
import json
m=json.load(open('$META_OUT'))
print('owner/repo:', m['owner']+'/'+m['repo'], 'branch:', m['branch'])
print('message:', m['message'][:120])
for f in m['files']:
    print(f\"  {f['path']}: {f['chars']} chars  sha256={f['sha256'][:12]}…  head={f['head'][:50]}\")
print('payload:', m['payload_path'], f\"({m['payload_bytes']} bytes)\")
"

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo
  echo "Dry run: not pushing. Payload at $OUT"
  echo "Next: call github___push_files with JSON from $OUT (do not retype content)."
  exit 0
fi

echo
echo "Refusing to update GitHub one file at a time."
echo "The previous Contents API loop could leave $OWNER/$REPO@$BRANCH half-updated if a later file failed."
echo "Commit the files with git and push that single commit instead."
echo "Payload, if the external builder ran, is at $OUT"
exit 2
