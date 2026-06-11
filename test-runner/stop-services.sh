#!/usr/bin/env bash
# =============================================================================
# LeaderOS — Stop All Services
# Запуск: ./test-runner/stop-services.sh [--service JavaMemoryService]
# =============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

TARGET_SERVICE=""
while [[ $# -gt 0 ]]; do
  case $1 in
    --service) TARGET_SERVICE="$2"; shift 2 ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

SERVICES=("JavaMemoryService" "JavaMailAgent" "JavaRagService")

echo "============================================="
echo "  LeaderOS Stop Services — $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================="

for SERVICE in "${SERVICES[@]}"; do
  if [[ -n "$TARGET_SERVICE" && "$TARGET_SERVICE" != "$SERVICE" ]]; then
    continue
  fi

  PID_FILE="logs/${SERVICE}.pid"
  if [[ -f "$PID_FILE" ]]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
      kill "$PID"
      echo "  ⛔ $SERVICE остановлен (PID $PID)"
    else
      echo "  ⚠️  $SERVICE — процесс $PID уже не запущен"
    fi
    rm -f "$PID_FILE"
  else
    echo "  —  $SERVICE — PID-файл не найден, пропускаем"
  fi
done

echo "============================================="
