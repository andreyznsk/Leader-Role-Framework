#!/usr/bin/env bash
# LeaderOS E2E Integration — переменные окружения
# Загрузить перед запуском сценариев:
#   source e2e-integration/env.sh

export MA_URL="http://localhost:8080"
export MS_URL="http://localhost:8082"
export RAG_URL="http://localhost:8081"
export MAILDEV_URL="http://172.80.2.1:18080"
export MAILDEV_SMTP="172.80.2.1:1025"

export PGHOST="172.80.2.1"
export PGPORT="5432"
export PGUSER="mailagent_user"
export PGPASSWORD="mailagent_password"
export PGDATABASE="leader_framework"

echo "✅ e2e-integration env loaded"
echo "   MA_URL:       $MA_URL"
echo "   MS_URL:       $MS_URL"
echo "   RAG_URL:      $RAG_URL"
echo "   MAILDEV_URL:  $MAILDEV_URL"
echo "   MAILDEV_SMTP: $MAILDEV_SMTP"
echo "   PostgreSQL:   $PGHOST:$PGPORT/$PGDATABASE"
