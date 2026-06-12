# JavaRagService E2E — конфигурация окружения
# Загрузить перед запуском: source JavaRagService/test_e2e/env.sh

export OPENSEARCH_URL="http://localhost:9200"
export OLLAMA_URL="http://localhost:11434"

export PGPASSWORD="rag_password"
export PGHOST="localhost"
export PGPORT="5432"
export PGUSER="rag_user"
export PGDATABASE="leader_framework"

echo "✅ JavaRagService E2E env loaded"
echo "   OpenSearch: $OPENSEARCH_URL"
echo "   Ollama:     $OLLAMA_URL"
echo "   PostgreSQL: $PGHOST:$PGPORT/$PGDATABASE (user: $PGUSER)"
