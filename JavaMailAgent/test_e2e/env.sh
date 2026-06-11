# JavaMailAgent E2E — конфигурация окружения
#
# Установить адреса Maildev под своё окружение.
# Загрузить перед запуском сценариев:
#   source JavaMailAgent/test_e2e/env.sh
#
# Или передать Claude Code:
#   "Перед запуском сценариев выполни: source JavaMailAgent/test_e2e/env.sh"

# Maildev UI + API
export MAILDEV_URL="http://172.80.2.1:18080"

# Maildev SMTP
export MAILDEV_SMTP="172.80.2.1:1025"

# PostgreSQL для проверки processed_emails
export PGPASSWORD="mailagent_password"
export PGHOST="172.80.2.1"
export PGPORT="5432"
export PGUSER="mailagent_user"
export PGDATABASE="leader_framework"

echo "✅ JavaMailAgent E2E env loaded"
echo "   MAILDEV_URL:  $MAILDEV_URL"
echo "   MAILDEV_SMTP: $MAILDEV_SMTP"
echo "   PostgreSQL:   $PGHOST:$PGPORT/$PGDATABASE"
