#!/usr/bin/env bash
# =============================================================================
# LeaderOS — Build All Services
# Запуск: ./test-runner/build.sh [--service JavaMemoryService]
# =============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

SERVICES=("JavaMemoryService" "JavaMailAgent" "JavaRagService")
TARGET_SERVICE=""

# --- аргументы ---
while [[ $# -gt 0 ]]; do
  case $1 in
    --service) TARGET_SERVICE="$2"; shift 2 ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

if [[ -n "$TARGET_SERVICE" ]]; then
  SERVICES=("$TARGET_SERVICE")
fi

echo "============================================="
echo "  LeaderOS Build — $(date '+%Y-%m-%d %H:%M:%S')"
echo "  Root: $ROOT_DIR"
echo "============================================="

FAILED=()

for SERVICE in "${SERVICES[@]}"; do
  if [[ ! -d "$SERVICE" ]]; then
    echo ""
    echo "⚠️  $SERVICE — директория не найдена, пропускаем"
    continue
  fi

  echo ""
  echo "🔨 Building $SERVICE..."

  BUILD_LOG="$ROOT_DIR/logs/build-${SERVICE}.log"
  mkdir -p "$ROOT_DIR/logs"

  if (cd "$SERVICE" && mvn package -q -DskipTests 2>&1) > "$BUILD_LOG" 2>&1; then
    JAR=$(find "$SERVICE/target" -name "*.jar" ! -name "*sources*" 2>/dev/null | head -1)
    echo "   ✅ OK — $JAR"
  else
    echo "   ❌ FAILED — см. $BUILD_LOG"
    FAILED+=("$SERVICE")
  fi
done

echo ""
echo "============================================="
if [[ ${#FAILED[@]} -eq 0 ]]; then
  echo "  ✅ Все сервисы собраны успешно"
else
  echo "  ❌ Сборка провалена: ${FAILED[*]}"
  echo "  Логи: logs/build-{service}.log"
  exit 1
fi
echo "============================================="
