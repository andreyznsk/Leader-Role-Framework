# CR-ARCH-002: Обновление мастер-спеки и RFC сервисов — модуль common

**Дата:** 2026-06-12
**Статус:** Approved
**Сервис:** ARCH + MAIL + MEM + RAG (документация)
**Зависимости:** RFC-common.md, CR-COMMON-001

---

## Проблема / Мотивация

После создания модуля `common` и миграции сервисов (CR-COMMON-001) документация
расходится с реальностью. Нужно обновить:
- `ARCHITECTURE.md` — мастер-спека
- `RFC-JavaMailAgent.md`
- `RFC-memory-service.md`
- `RFC-rag-service.md`

---

## Изменения в ARCHITECTURE.md

### 1. Обзор системы — добавить `common` в схему

```
┌─────────────────────────────────────────────────────────────────┐
│                          LeaderOS                                │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    common :library                        │   │
│  │  AgentClient: claude | mock | ollama | gigachat           │   │
│  └──────────────────────────────────────────────────────────┘   │
│           ↑ использует              ↑ использует                 │
│  ┌─────────────────┐        ┌──────────────────────────────┐    │
│  │  JavaMailAgent  │──────→ │      JavaMemoryService       │    │
│  │  :8080          │        │      :8082                   │    │
│  └─────────────────┘        └──────────────────────────────┘    │
│                              ┌──────────────────────────────┐    │
│                              │      JavaRagService          │    │
│                              │      :8081                   │    │
│                              └──────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Раздел «Сервисы» — добавить новый подраздел `common`

```markdown
### common
**Тип:** plain JAR (не Spring Boot app, не fat-jar)
**RFC:** `common/RFC/RFC-common.md`
**Статус:** In Progress

**Роль:** Общая инфраструктура LeaderOS. Содержит единственный
внешний контракт — `AgentClient` — для вызова LLM из любого сервиса.

**Интерфейс:**
| Класс | Описание |
|-------|----------|
| `AgentClient` | интерфейс: `String complete(String prompt)` |
| `AgentException` | RuntimeException для ошибок LLM |
| `ClaudeProcessAgentClient` | реализация через `claude --print` subprocess |
| `MockAgentClient` | детерминированная реализация для тестов |
| `OllamaAgentClient` | Spring AI → Ollama |
| `GigaChatAgentClient` | Spring AI → GigaChat |
| `AgentClientConfig` | `@Configuration` — выбор реализации по `agent.provider` |

**Переключение провайдера:**
```yaml
agent:
  provider: claude   # claude | mock | ollama | gigachat
```
```

### 3. Раздел «Maven координаты» — добавить строку

```markdown
| common | `ru.andreyz.common` | `common` |
```

### 4. Раздел «RFC документы» — добавить строку

```markdown
| common | `common/RFC/RFC-common.md` | Draft |
```

### 5. Раздел «Claude-агент» — обновить описание

```markdown
## Claude-агент / LLM-агент

Каждый сервис вызывает LLM через `AgentClient` из модуля `common`.
Провайдер выбирается по `agent.provider` в `application.yml`.

Текущие реализации:
| Провайдер | Реализация | Когда использовать |
|-----------|------------|-------------------|
| `claude` | `claude --print` subprocess | prod (default) |
| `mock` | фиксированный ответ из конфига | local/e2e тесты |
| `ollama` | Spring AI → Ollama | local без Claude CLI |
| `gigachat` | Spring AI → GigaChat | корпоративный стенд |

Промпт-билдеры — в каждом сервисе отдельно (предметная логика).
```

### 6. Раздел «Что ещё планируется» — добавить

```markdown
- **common** — реализован, сервисы мигрированы (CR-COMMON-001)
```

### 7. Раздел «CR Workflow» — добавить новый префикс

```markdown
| `COMMON` | common модуль |
```

---

## Изменения в RFC-JavaMailAgent.md

### Раздел «Стек и зависимости» — заменить строки про агента

```markdown
| common | 1.0.0 | AgentClient — вызов LLM через единый интерфейс |
```

Удалить:
- Строки про `ClaudeRunner`, `MockClaudeRunner` как отдельные зависимости

### Раздел «Структура проекта» — обновить `scheduler/`

```
scheduler/
├── MailAgentJob.java            ← inject AgentClient (из common)
├── PromptBuilder.java           ← без изменений
├── MailAgentMockClassifier.java ← keyword-based классификатор для mock режима
└── ActionExecutor.java          ← без изменений
```

Удалить из структуры:
```
# Удалены:
# scheduler/ClaudeRunner.java
# scheduler/ClaudeRunnerImpl.java
# scheduler/MockClaudeRunner.java
```

### Раздел «Scheduler» — обновить описание вызова агента

```markdown
Вызов агента:
```java
String raw = agentClient.complete(promptBuilder.build(email));
AgentResponse resp = parseAgentResponse(raw);
```
`AgentClient` инжектируется из модуля `common`.
Провайдер выбирается через `agent.provider` в `application.yml`.
```

### Раздел «Конфиги» — обновить `application-local.yml`

```yaml
# Заменить:
# mock:
#   agent: true
# На:
agent:
  provider: mock
```

### Раздел «Порядок реализации» — пометить как устаревшее

```markdown
### Реализовано ✅ (обновлено CR-COMMON-001)
- `ClaudeRunnerImpl` → удалён, заменён `ClaudeProcessAgentClient` в common
- `MockClaudeRunner` → удалён, заменён `MockAgentClient` в common
  + `MailAgentMockClassifier` в JavaMailAgent
```

---

## Изменения в RFC-memory-service.md

### Раздел «Стек» — заменить

```markdown
| common | 1.0.0 | AgentClient — вызов LLM |
```

### Раздел «Структура проекта» — обновить `service/`

```
service/
├── CaptureClassifierAgent.java    ← inject AgentClient (из common)
├── MockCaptureClassifier.java     ← keyword-based для mock режима
│                                     @ConditionalOnProperty(agent.provider=mock)
├── CaptureProcessingService.java  ← без изменений
├── CaptureRouter.java             ← без изменений
└── CaptureScheduler.java          ← без изменений
```

Удалить из структуры:
```
# Удалены:
# service/MockCaptureClassifierAgent.java
```

### Раздел «Профили» — обновить `e2e`

```yaml
# application-e2e.yml — заменить:
# mock:
#   capture-agent: true
# На:
agent:
  provider: mock
  mock:
    response: >
      [{"type":"NOTE","title":"mock capture","body":"mock","priority":"NORMAL"}]
```

### Раздел «Capture Bot» — обновить описание классификации

```markdown
### Классификация (шаг 3)

`CaptureClassifierAgent` получает список файлов и dayContext,
строит промпт и вызывает `agentClient.complete(prompt)`.

`AgentClient` — из модуля `common`, провайдер: `agent.provider`.

При `agent.provider=mock` активируется `MockCaptureClassifier`,
который использует keyword-маркеры (`TASK:`, `RISK:`, etc.)
вместо реального LLM.
```

---

## Изменения в RFC-rag-service.md

Минимальные — JavaRagService пока не использует AgentClient напрямую.

### Раздел «Стек» — добавить

```markdown
| common | 1.0.0 | AgentClient (подготовка к будущей генерации service-card) |
```

### Раздел «Что планируется» — добавить

```markdown
- **AgentClient интеграция** — генерация структурированных service-card
  через LLM при индексации (провайдер из `agent.provider`)
```

---

## Что НЕ меняется

- Промпт-билдеры (`PromptBuilder.java`, `CaptureClassifierAgent.buildFilePrompt()`) — остаются в своих сервисах
- Логика парсинга ответа агента — остаётся в каждом сервисе (формат ответа предметный)
- Маршрутизация результатов (`ActionExecutor`, `CaptureRouter`) — без изменений
- E2E тесты — структура не меняется, только `mock.agent=true` → `agent.provider=mock`

---

## Порядок применения изменений

1. Создать `common/RFC/RFC-common.md` (скопировать из выходного файла)
2. Обновить `ARCHITECTURE.md` по разделам выше
3. Обновить `RFC-JavaMailAgent.md`
4. Обновить `RFC-memory-service.md`
5. Обновить `RFC-rag-service.md`
6. Создать симлинки: `common/ARCHITECTURE.md → ../ARCHITECTURE.md`
