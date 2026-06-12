# CR-COMMON-001: Миграция сервисов на AgentClient из модуля common

**Дата:** 2026-06-12
**Статус:** Approved
**Сервис:** MAIL + MEM + ARCH (затрагивает все три сервиса и мастер-спеку)
**Зависимости:** RFC-common.md должен быть реализован первым

---

## Проблема / Мотивация

После создания модуля `common` с интерфейсом `AgentClient` нужно провести
рефакторинг существующих сервисов: убрать дублирование LLM-вызовов и подключить
единую точку входа.

---

## Изменения в JavaMailAgent

### Удалить
- `scheduler/ClaudeRunner.java` — интерфейс
- `scheduler/ClaudeRunnerImpl.java` — subprocess реализация
- `scheduler/MockClaudeRunner.java` — mock реализация

### Изменить: `MailAgentJob.java`
```java
// было:
private final ClaudeRunner claudeRunner;

// стало:
private final AgentClient agentClient;
private final ObjectMapper objectMapper;
```

```java
// было:
AgentResponse resp = claudeRunner.run(prompt);

// стало:
String raw = agentClient.complete(prompt);
AgentResponse resp = parseAgentResponse(raw);

private AgentResponse parseAgentResponse(String raw) throws IOException {
    String trimmed = raw.trim();
    // strip markdown если есть
    if (trimmed.startsWith("```")) {
        trimmed = trimmed.replaceAll("(?s)```[a-z]*\\n?", "").replaceAll("```", "").trim();
    }
    int start = trimmed.indexOf('{');
    int end   = trimmed.lastIndexOf('}');
    if (start < 0 || end <= start) {
        throw new IOException("No JSON object in agent response: " + trimmed);
    }
    return objectMapper.readValue(trimmed.substring(start, end + 1), AgentResponse.class);
}
```

### Изменить: `pom.xml` JavaMailAgent
```xml
<!-- добавить -->
<dependency>
    <groupId>ru.andreyz</groupId>
    <artifactId>common</artifactId>
</dependency>

<!-- удалить — теперь в common -->
<!-- mock.agent ConditionalOnProperty больше не нужен здесь -->
```

### Изменить: `application-local.yml`
```yaml
# было:
mock:
  agent: true

# стало:
agent:
  provider: mock
  mock:
    response: >
      {"type":"NOISE","emailId":"mock-id","note":"mock response",
       "taskLine":null,"taskTitle":null,"priority":null,"sender":null,"draftPath":null}
```

> **Примечание:** Логика классификации по ключевым словам из `MockClaudeRunner`
> переносится в отдельный `MailAgentMockClassifier` внутри JavaMailAgent.
> Он принимает тот же `AgentClient` но переопределяет поведение для E2E тестов.
> Это позволяет тестам работать детерминированно без фиксированного ответа.

### Добавить: `scheduler/MailAgentMockClassifier.java` (опционально для E2E)
```java
// Активен при agent.provider=mock — оборачивает MockAgentClient,
// добавляя логику классификации по ключевым словам из старого MockClaudeRunner.
// Нужен только пока E2E тесты полагаются на keyword-based классификацию.
// После перехода на реальный LLM — удалить.
```

---

## Изменения в JavaMemoryService

### Удалить
- `service/CaptureClassifierAgent.java` — удалить метод `runClaude()`,
  инкапсулировать только логику промпта и парсинга

### Изменить: `CaptureClassifierAgent.java`
```java
// было: @Service @ConditionalOnProperty(mock.capture-agent=false)
// стало: @Service — всегда активен, LLM-вызов делегирован

@Service
public class CaptureClassifierAgent {

    private final AgentClient agentClient;    // ← инжектируется из common
    private final ObjectMapper objectMapper;

    // buildFilePrompt() — остаётся без изменений
    // parseResponse()   — остаётся без изменений

    public List<ClassifiedCapture> classifyFiles(
            List<CaptureService.CaptureFile> files, String dayContext) {
        String prompt = buildFilePrompt(files, dayContext);
        try {
            String raw = agentClient.complete(prompt);  // ← вызов через common
            return parseResponse(raw);
        } catch (Exception e) {
            throw new AgentException("Capture classification failed: " + e.getMessage(), e);
        }
    }
}
```

### Удалить
- `service/MockCaptureClassifierAgent.java` — логику переносим

### Добавить: `service/MockCaptureClassifier.java`
```java
// Аналогично MailAgent: при agent.provider=mock активируется keyword-based
// классификатор. Зависит от AgentClient, но переопределяет classify()
// своей детерминированной логикой.
// @ConditionalOnProperty(name = "agent.provider", havingValue = "mock")
```

### Изменить: `application-e2e.yml`
```yaml
# было:
mock:
  capture-agent: true

# стало:
agent:
  provider: mock
```

### Изменить: `pom.xml` JavaMemoryService
```xml
<!-- добавить -->
<dependency>
    <groupId>ru.andreyz</groupId>
    <artifactId>common</artifactId>
</dependency>
```

---

## Изменения в JavaRagService

JavaRagService пока не использует агента напрямую — зависимость от `common`
добавить для будущего использования (например, генерация service-card через LLM).

```xml
<!-- pom.xml JavaRagService — добавить -->
<dependency>
    <groupId>ru.andreyz</groupId>
    <artifactId>common</artifactId>
</dependency>
```

---

## Итоговая карта замен

| Было | Стало | Где |
|------|-------|-----|
| `ClaudeRunner` интерфейс | `AgentClient` из common | JavaMailAgent |
| `ClaudeRunnerImpl` | `ClaudeProcessAgentClient` из common | JavaMailAgent |
| `MockClaudeRunner` | `MockAgentClient` из common + `MailAgentMockClassifier` | JavaMailAgent |
| `mock.agent=true` property | `agent.provider=mock` | application-local.yml |
| `CaptureClassifierAgent.runClaude()` | `agentClient.complete()` из common | JavaMemoryService |
| `MockCaptureClassifierAgent` | `MockAgentClient` из common + `MockCaptureClassifier` | JavaMemoryService |
| `mock.capture-agent=true` property | `agent.provider=mock` | application-e2e.yml |

---

## Порядок реализации

1. Убедиться что `common` собирается: `mvn package -pl common`
2. Добавить зависимость в каждый сервис
3. JavaMailAgent: рефактор `MailAgentJob` → inject `AgentClient`
4. JavaMailAgent: удалить `ClaudeRunner*`, создать `MailAgentMockClassifier`
5. JavaMailAgent: обновить конфиги
6. JavaMemoryService: рефактор `CaptureClassifierAgent` → inject `AgentClient`
7. JavaMemoryService: удалить `MockCaptureClassifierAgent`, создать `MockCaptureClassifier`
8. JavaMemoryService: обновить конфиги
9. Прогнать существующие E2E тесты — убедиться ничего не сломалось
10. Проверить что `agent.provider=ollama` поднимается без ошибок

---

## Как тестировать

```bash
# Сборка всего проекта
mvn package -q -DskipTests

# Запуск с Ollama
SPRING_PROFILES_ACTIVE=local \
  java -Dagent.provider=ollama \
       -Dspring.ai.ollama.chat.model=qwen2.5:7b \
       -jar JavaMailAgent/target/mail-agent.jar

# Проверка лога — должна появиться строка:
# ✅ AgentClient: Ollama (Spring AI ChatClient)

# E2E тесты с mock
SPRING_PROFILES_ACTIVE=local,e2e java -jar JavaMemoryService/target/memory-service.jar
# Лог: ⚠️  AgentClient: MOCK — реальный LLM не вызывается
```
