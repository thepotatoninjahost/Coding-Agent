#!/usr/bin/env bash
# Run Coding Agent's real loop against OpenRouter using a key you provide.
# The key is read from the environment. It is not printed and it is not stored in this repository.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [[ -z "${OPENROUTER_API_KEY:-}" ]]; then
  cat <<'EOF' >&2
This runs the app's real agent loop against OpenRouter.

1. Create a key at https://openrouter.ai/keys
2. In this terminal only:
     export OPENROUTER_API_KEY='paste-your-key-here'
3. Then:
     ./scripts/run_openrouter_agent.sh "Explain this project in one short paragraph. Do not change any files." /path/to/your/project

The second argument is the project folder to read. Leave it out to use a small temporary folder.
Waiting for you to say yes is not a successful run. The script stops and says so.

On the phone, open Setup. The web address and one free helper are already filled in.
Paste the same key into Secret password. It stays on the phone.

The key is not saved in this repository. This script does not print it.
EOF
  exit 1
fi

REQUEST="${1:-Explain this project in one short paragraph. Do not change any files.}"
PROJECT="${2:-${OPENROUTER_AGENT_PROJECT:-}}"
export OPENROUTER_AGENT_REQUEST="$REQUEST"
if [[ -n "$PROJECT" ]]; then
  if [[ ! -d "$PROJECT" ]]; then
    echo "That project folder does not exist: $PROJECT" >&2
    exit 1
  fi
  export OPENROUTER_AGENT_PROJECT="$(cd "$PROJECT" && pwd)"
fi

cd "$ROOT"
set +e
./gradlew :app:testDebugUnitTest --tests com.codingagent.core.OpenRouterAgentRunner -PrunOpenRouter --no-daemon
code=$?
set -e

RESULT="$ROOT/app/build/openrouter-agent-result.txt"
if [[ -f "$RESULT" ]]; then
  printf '\n--- agent result ---\n'
  cat "$RESULT"
fi
exit "$code"
