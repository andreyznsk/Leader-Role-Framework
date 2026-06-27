# CR-RAG-001: RAG Plugin Control API

**Дата:** 2026-06-22
**Статус:** Implemented
**Сервис:** RAG
**Зависимости:** CR-MEM-010 Universal Plugin Control UI

## Цель

Добавить в JavaRagService универсальный control API по тому же контракту, что и MailAgent.

RAG должен рассматриваться как подключаемый plugin, который может:

- сообщать свои настройки через descriptor;
- принимать изменения настроек от MemoryService;
- применять настройки в runtime;
- публиковать статус;
- публиковать audit применения настроек.

## API

### Получить descriptor

```http
GET /api/control/settings
```

Response:

```json
{
  "pluginCode": "rag",
  "pluginName": "RAG Service",
  "version": 1,
  "settings": {
    "enabled": {
      "value": "true",
      "type": "boolean",
      "label": "Enabled"
    },
    "schedulerEnabled": {
      "value": "true",
      "type": "boolean",
      "label": "Scheduler enabled"
    },
    "scanIntervalSeconds": {
      "value": "60",
      "type": "number",
      "label": "Scan interval seconds"
    },
    "ragInboxPath": {
      "value": "rag-inbox",
      "type": "string",
      "label": "RAG inbox path"
    },
    "embeddingModel": {
      "value": "bge-m3",
      "type": "string",
      "label": "Embedding model"
    },
    "topK": {
      "value": "10",
      "type": "number",
      "label": "Default top K"
    },
    "opensearchUrl": {
      "value": "http://localhost:9200",
      "type": "string",
      "label": "OpenSearch URL"
    },
    "validationEnabled": {
      "value": "true",
      "type": "boolean",
      "label": "Validation enabled"
    }
  }
}
```

### Применить настройки

```http
PUT /api/control/settings
```

### Получить статус

```http
GET /api/control/status
```

### Получить аудит

```http
GET /api/control/audit
```

## Минимальный набор настроек

- enabled
- schedulerEnabled
- scanIntervalSeconds
- ragInboxPath
- embeddingModel
- topK
- opensearchUrl
- validationEnabled

## Runtime поведение

```text
enabled=false
    ↓
индексация и scheduler остановлены

enabled=true
    ↓
индексация и scheduler активны
```

JVM процесс при этом продолжает работать.

Дополнительно:

- `embeddingModel`, `opensearchUrl`, `topK`, `validationEnabled` применяются в runtime без рестарта JVM;
- `schedulerEnabled=false` останавливает только background scheduler, не выключая весь сервис;
- live status для control plane в MemoryService проверяется по `/actuator/health`.

## Acceptance Criteria

1. Есть `GET /api/control/settings`.
2. Есть `PUT /api/control/settings`.
3. Есть `GET /api/control/status`.
4. Есть `GET /api/control/audit`.
5. Descriptor соответствует контракту MEM-010.
6. Настройки применяются без рестарта JVM.
7. Изменения логируются.
8. Добавлен E2E сценарий `JavaRagService/test_e2e/control_settings.md`.
9. Runtime использует обновленные `embeddingModel`, `opensearchUrl`, `topK`, `validationEnabled`.

## Future

После реализации RAG и Mail используют единый Plugin Control Protocol, который далее смогут использовать ChatAgent, CalendarAgent и другие сервисы LeaderOS.
