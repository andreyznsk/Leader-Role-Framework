#!/usr/bin/env bash
# LeaderOS Test Runner
# Запуск: ./test-runner/run-tests.sh [--no-docker-down] [--service NAME] [--skip-build]

set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DATE=$(date +%Y-%m-%d)
REPORT_DIR="test-runner/reports"
REPORT="$REPORT_DIR/TEST-REPORT-$DATE.md"

# --- Аргументы ---
NO_DOCKER_DOWN=false
TARGET_SERVICE=""
SKIP_BUILD=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-docker-down)
            NO_DOCKER_DOWN=true
            shift
            ;;
        --service)
            TARGET_SERVICE="${2:-}"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        *)
            echo "Unknown flag: $1" >&2
            echo "Usage: $0 [--no-docker-down] [--service JavaMemoryService|JavaMailAgent|JavaRagService] [--skip-build]" >&2
            exit 1
            ;;
    esac
done

echo "🚀 LeaderOS Test Runner — $DATE"
echo "Root: $ROOT_DIR"
[[ -n "$TARGET_SERVICE" ]] && echo "Service filter: $TARGET_SERVICE"
[[ "$SKIP_BUILD" == "true" ]] && echo "Build: SKIPPED"
[[ "$NO_DOCKER_DOWN" == "true" ]] && echo "Docker down: DISABLED"

# Запустить агента
claude --print "
Ты — LeaderOS Test Runner.
Корень проекта: $ROOT_DIR
Прочитай инструкции из test-runner/AGENT.md и следуй им.

Параметры запуска:
- SKIP_BUILD=$SKIP_BUILD (если true — не пересобирать JAR, использовать существующие)
- NO_DOCKER_DOWN=$NO_DOCKER_DOWN (если true — не выполнять docker compose down после тестов)
- TARGET_SERVICE=$TARGET_SERVICE (если не пусто — прогнать только этот сервис)

Сохрани отчёт в: $REPORT
"
