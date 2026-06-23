# CR-RAG-001: RAG Plugin Control API

**Дата:** 2026-06-22
**Статус:** Draft
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
      "type": "boolean"
    },
    "schedulerEnabled": {
      "value": "true",
      "type": "boolean"
    },
    "scanIntervalSeconds": {
      "value": "60",
      "type": "number"
    },
    "embeddingModel": {
      "value": "bge-m3",
      "type": "string"
    },
    "topK": {
      "value": "10",
      "type": "number"
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
индексация и фоновые задачи остановлены

enabled=true
    ↓
индексация и фоновые задачи активны
```

JVM процесс при этом продолжает работать.

## Acceptance Criteria

1. Есть `GET /api/control/settings`.
2. Есть `PUT /api/control/settings`.
3. Есть `GET /api/control/status`.
4. Есть `GET /api/control/audit`.
5. Descriptor соответствует контракту MEM-010.
6. Настройки применяются без рестарта JVM.
7. Изменения логируются.
8. Добавлен E2E сценарий `JavaRagService/test_e2e/control_settings.md`.

## Future

После реализации RAG и Mail используют единый Plugin Control Protocol, который далее смогут использовать ChatAgent, CalendarAgent и другие сервисы LeaderOS.
