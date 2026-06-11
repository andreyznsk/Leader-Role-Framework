#!/usr/bin/env bash
# =============================================================================
# LeaderOS — Start All Services
# Запуск: ./test-runner/start-services.sh [--service JavaMemoryService] [--profile local]
# Остановка: ./test-runner/stop-services.sh
# Логи: logs/{service}.log, logs/{service}.pid
# =============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

PROFILE="local"
TARGET_SERVICE=""

# --- аргументы ---
while [[ $# -gt 0 ]]; do
  case $1 in
    --profile) PROFILE="$2"; shift 2 ;;
    --service) TARGET_SERVICE="$2"; shift 2 ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

mkdir -p logs

echo "============================================="
echo "  LeaderOS Start Services — $(date '+%Y-%m-%d %H:%M:%S')"
echo "  Profile: $PROFILE"
echo "  Root: $ROOT_DIR"
echo "============================================="

# --- Функция запуска одного сервиса ---
start_service() {
  local SERVICE=$1
  local JAR_PATTERN=$2
  local PORT=$3

  if [[ -n "$TARGET_SERVICE" && "$TARGET_SERVICE" != "$SERVICE" ]]; then
    return 0
  fi

  local PID_FILE="logs/${SERVICE}.pid"

  # Проверить — уже запущен?
  if [[ -f "$PID_FILE" ]]; then
    local OLD_PID
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
      echo ""
      echo "⚠️  $SERVICE уже запущен (PID $OLD_PID) на :$PORT"
      return 0
    else
      rm -f "$PID_FILE"
    fi
  fi

  # Найти JAR
  local JAR
  JAR=$(find "$SERVICE/target" -name "$JAR_PATTERN" ! -name "*sources*" 2>/dev/null | head -1)
  if [[ -z "$JAR" ]]; then
    echo ""
    echo "❌ $SERVICE — JAR не найден (${SERVICE}/target/${JAR_PATTERN})"
    echo "   Запусти сначала: ./test-runner/build.sh --service $SERVICE"
    return 1
  fi

  local LOG_FILE="logs/${SERVICE}.log"

  echo ""
  echo "🚀 Starting $SERVICE..."
  echo "   JAR:     $JAR"
  echo "   Profile: $PROFILE"
  echo "   Port:    $PORT"
  echo "   Log:     $LOG_FILE"

  # Запустить в фоне, логи в файл
  SPRING_PROFILES_ACTIVE="$PROFILE" \
  java -jar "$JAR" \
    > "$LOG_FILE" 2>&1 &

  local PID=$!
  echo $PID > "$PID_FILE"
  echo "   PID:     $PID"
}

# --- Запуск в порядке зависимостей ---
# 1. JavaMemoryService (нет зависимостей от других Java-сервисов)
start_service "JavaMemoryService" "memory-service*.jar" "8082"

# 2. JavaRagService (независим)
start_service "JavaRagService" "rag-service*.jar" "8081"

# 3. JavaMailAgent (зависит от JavaMemoryService)
start_service "JavaMailAgent" "mail-agent*.jar" "8080"

echo ""
echo "============================================="
echo "  Сервисы запущены. Логи:"
echo "    tail -f logs/JavaMemoryService.log"
echo "    tail -f logs/JavaMailAgent.log"
echo "    tail -f logs/JavaRagService.log"
echo ""
echo "  Проверка здоровья:"
echo "    ./test-runner/healthcheck.sh"
echo ""
echo "  Остановить все:"
echo "    ./test-runner/stop-services.sh"
echo "============================================="
