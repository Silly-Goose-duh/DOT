#!/usr/bin/env bash
# Serialized Gradle runner for concurrent agents.
#
# This machine has ~12 GB RAM total and little free. Several agents each
# spawning a 2.5 GB Gradle daemon will swap or die. This wrapper takes an
# atomic lock (mkdir is atomic on Windows/MSYS and on Linux) so exactly one
# Gradle invocation runs at a time, and everyone else waits their turn.
#
# Usage:  ./gtest.sh <gradle-args...>
#   ./gtest.sh :app:testDebugUnitTest
#   ./gtest.sh test assembleDebug
set -u

PROJECT_DIR="D:/Android/dot"
LOCK_DIR="/d/Android/dot/.gradle-agent-lock"
LOG_DIR="/d/Android/dot/.gradle-agent-logs"
MAX_WAIT_SECONDS="${DOT_BUILD_LOCK_TIMEOUT:-3600}"

mkdir -p "$LOG_DIR"

acquire() {
  local waited=0
  while ! mkdir "$LOCK_DIR" 2>/dev/null; do
    sleep 5
    waited=$((waited + 5))
    if [ "$waited" -ge "$MAX_WAIT_SECONDS" ]; then
      echo "TIMEOUT: waited ${waited}s for the Gradle lock. Another agent is building." >&2
      return 1
    fi
    # A lock older than 45 min belongs to a dead process; break it.
    if [ -f "$LOCK_DIR/owner" ]; then
      local age=$(( $(date +%s) - $(stat -c %Y "$LOCK_DIR/owner" 2>/dev/null || echo 0) ))
      if [ "$age" -gt 2700 ]; then
        echo "Breaking stale lock (${age}s old)." >&2
        rm -rf "$LOCK_DIR"
      fi
    fi
  done
  echo "$$ $(date)" > "$LOCK_DIR/owner"
  return 0
}

release() {
  rm -rf "$LOCK_DIR"
}

trap release EXIT INT TERM

acquire || exit 99

STAMP="$(date +%H%M%S)_$$"
LOG="$LOG_DIR/build_${STAMP}.log"

cd "$PROJECT_DIR" || exit 1

echo "[gtest] gradle $* (log: $LOG)"
./gradlew "$@" --console=plain > "$LOG" 2>&1
STATUS=$?

echo "[gtest] exit=$STATUS"
if [ "$STATUS" -ne 0 ]; then
  echo "----- last 120 lines of $LOG -----"
  tail -120 "$LOG"
  echo "----- end (full log: $LOG) -----"
fi
exit $STATUS
