#!/usr/bin/env bash
# =============================================================================
# LeaderOS — Health Check
# Запуск: ./test-runner/healthcheck.sh
# Выводит статус всей инфраструктуры и Java-сервисов
# =============================================================================

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

TIMEOUT=3  # секунды на каждый запрос

echo "============================================="
echo "  LeaderOS Health Check — $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================="

ALL_OK=true

# --- Утилита проверки HTTP ---
check_http() {
  local NAME=$1
  local URL=$2
  local EXPECTED_BODY=${3:-""}  # опционально — строка в теле

  local HTTP_CODE
  local BODY
  BODY=$(curl -s --max-time "$TIMEOUT" -w "\n__HTTP_CODE__%{http_code}" "$URL" 2>/dev/null || true)
  HTTP_CODE=$(echo "$BODY" | tail -1 | sed 's/__HTTP_CODE__//')
  BODY=$(echo "$BODY" | head -n -1)

  if [[ "$HTTP_CODE" == "200" ]]; then
    if [[ -n "$EXPECTED_BODY" && "$BODY" != *"$EXPECTED_BODY"* ]]; then
      echo "  ❌  $NAME — HTTP 200 но тело не содержит: '$EXPECTED_BODY'"
      ALL_OK=false
    else
      echo "  ✅  $NAME — OK ($URL)"
    fi
  else
    echo "  ❌  $NAME — HTTP ${HTTP_CODE:-'no response'} ($URL)"
    ALL_OK=false
  fi
}

# --- Утилита проверки TCP-порта ---
check_port() {
  local NAME=$1
  local HOST=$2
  local PORT=$3

  if (echo >/dev/tcp/"$HOST"/"$PORT") 2>/dev/null; then
    echo "  ✅  $NAME — порт $PORT доступен"
  else
    echo "  ❌  $NAME — порт $PORT недоступен"
    ALL_OK=false
  fi
}

echo ""
echo "── Инфраструктура (Docker) ──────────────────"
check_port "PostgreSQL"          "localhost" "5432"
check_http "OpenSearch"          "http://localhost:9200"
check_http "OpenSearch Dashboards" "http://localhost:5601"
check_http "Maildev UI"          "http://localhost:1080"
check_port "Maildev SMTP"        "localhost" "1025"
check_port "Ollama"              "localhost" "11434"

echo ""
echo "── Java Services ────────────────────────────"
check_http "JavaMemoryService"   "http://localhost:8082/actuator/health" '"status":"UP"'
check_http "JavaMailAgent"       "http://localhost:8080/actuator/health" '"status":"UP"'
check_http "JavaRagService"      "http://localhost:8081/actuator/health"

echo ""
echo "── UI Endpoints ─────────────────────────────"
check_http "MemoryService /ui/today"   "http://localhost:8082/ui/today"
check_http "MailAgent /ui/status"      "http://localhost:8080/ui/status"

echo ""
echo "── PID файлы ────────────────────────────────"
for SERVICE in JavaMemoryService JavaMailAgent JavaRagService; do
  PID_FILE="logs/${SERVICE}.pid"
  if [[ -f "$PID_FILE" ]]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
      echo "  ✅  $SERVICE — PID $PID (запущен)"
    else
      echo "  ⚠️  $SERVICE — PID $PID (процесс не найден, стал PID устарел)"
    fi
  else
    echo "  —   $SERVICE — PID-файл отсутствует"
  fi
done

echo ""
echo "── Последние ошибки в логах ─────────────────"
for SERVICE in JavaMemoryService JavaMailAgent JavaRagService; do
  LOG="logs/${SERVICE}.log"
  if [[ -f "$LOG" ]]; then
    ERRORS=$(grep -i "ERROR\|Exception\|FAILED" "$LOG" | tail -3 || true)
    if [[ -n "$ERRORS" ]]; then
      echo "  ⚠️  $SERVICE (последние ошибки):"
      echo "$ERRORS" | sed 's/^/      /'
    fi
  fi
done

echo ""
echo "============================================="
if $ALL_OK; then
  echo "  ✅ Всё в порядке"
else
  echo "  ❌ Есть проблемы — проверь логи выше"
  echo ""
  echo "  Логи сервисов:"
  echo "    tail -50 logs/JavaMemoryService.log"
  echo "    tail -50 logs/JavaMailAgent.log"
  echo "    tail -50 logs/JavaRagService.log"
fi
echo "============================================="
