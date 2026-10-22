#!/usr/bin/env bash
# Start the glm-claude-proxy (if not already running) and launch Claude Code against it.
# Any arguments are passed through to `claude`.
#
# Env overrides:
#   PROXY_PORT            port the proxy listens on (default 8948)
#   PROXY_STARTUP_TIMEOUT seconds to wait for the proxy to become healthy (default 180)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${PROXY_PORT:-8948}"
BASE_URL="http://localhost:${PORT}"
STARTUP_TIMEOUT="${PROXY_STARTUP_TIMEOUT:-180}"
LOG_DIR="${SCRIPT_DIR}/build"
LOG_FILE="${LOG_DIR}/proxy.log"

command -v claude >/dev/null 2>&1 || { echo "error: 'claude' not found on PATH" >&2; exit 1; }

healthy() { curl -fsS --max-time 2 "${BASE_URL}/health" >/dev/null 2>&1; }

PROXY_PID=""
cleanup() {
    if [[ -n "${PROXY_PID}" ]] && kill -0 "${PROXY_PID}" 2>/dev/null; then
        echo "Stopping proxy (pid ${PROXY_PID})..." >&2
        # Kill the whole process group so the JVM spawned by ./kotlin goes down too.
        kill -- "-${PROXY_PID}" 2>/dev/null || kill "${PROXY_PID}" 2>/dev/null || true
        wait "${PROXY_PID}" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

if healthy; then
    echo "Proxy already running at ${BASE_URL}, reusing it." >&2
else
    mkdir -p "${LOG_DIR}"
    echo "Starting proxy on port ${PORT} (logs: ${LOG_FILE})..." >&2
    # set -m puts the background job in its own process group for clean shutdown.
    set -m
    (cd "${SCRIPT_DIR}" && PROXY_PORT="${PORT}" exec ./run.sh) >"${LOG_FILE}" 2>&1 &
    PROXY_PID=$!
    set +m

    for ((i = 0; i < STARTUP_TIMEOUT; i++)); do
        healthy && break
        if ! kill -0 "${PROXY_PID}" 2>/dev/null; then
            echo "error: proxy exited during startup, last log lines:" >&2
            tail -n 30 "${LOG_FILE}" >&2
            exit 1
        fi
        sleep 1
    done
    healthy || { echo "error: proxy not healthy after ${STARTUP_TIMEOUT}s, see ${LOG_FILE}" >&2; exit 1; }
    echo "Proxy is up." >&2
fi

# The proxy doesn't check auth, but Claude Code needs some token to skip login.
export ANTHROPIC_BASE_URL="${BASE_URL}"
export ANTHROPIC_AUTH_TOKEN="${ANTHROPIC_AUTH_TOKEN:-glm-proxy}"
unset ANTHROPIC_API_KEY
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1

# Not exec: we need to stay alive to stop the proxy when claude exits.
CLAUDE_CODE_MAX_CONTEXT_TOKENS=1000000 claude "$@"
