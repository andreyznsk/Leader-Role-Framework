# Задача: диагностика MCP сервера в JavaMemoryService

## Контекст
Spring AI MCP сервер не регистрирует endpoints `/mcp/sse`.
Сервис запущен на `:8082`, REST API работает, но MCP paths отсутствуют в маппингах.

## Что нужно проверить

### 1. pom.xml — зависимости Spring AI

```bash
cat JavaMemoryService/pom.xml | grep -A3 -i "spring-ai\|mcp"
```

Ожидаем увидеть:
- `spring-ai-mcp-server-spring-boot-starter`
- BOM версию `spring-ai-bom`
- milestone репозиторий `https://repo.spring.io/milestone`

Если BOM версия `1.0.0` — заменить на `1.0.0-M6` и добавить репозиторий.

---

### 2. application.properties — конфигурация MCP

```bash
cat JavaMemoryService/src/main/resources/application.properties | grep -i "mcp\|ai\."
```

Должны быть строки:
```properties
spring.ai.mcp.server.enabled=true
spring.ai.mcp.server.name=java-memory-service
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.type=SYNC
spring.ai.mcp.server.sse-message-endpoint=/mcp/message
```

Если отсутствуют — добавить.

---

### 3. McpConfig.java — бин зарегистрирован

```bash
cat JavaMemoryService/src/main/java/ru/andreyz/memoryservice/mcp/McpConfig.java
```

Должен быть `@Configuration` класс с `ToolCallbackProvider` бином.
Если файл отсутствует — это причина, нужно создать.

---

### 4. Логи старта — ошибки инициализации

```bash
grep -i "mcp\|spring-ai\|tool\|error\|warn\|failed" JavaMemoryService/logs/memory-service.log | head -50
```

Ищем:
- `MCP server started` или подобное — значит поднялся
- `NoSuchBeanDefinitionException` — бин не найден
- `ClassNotFoundException` — зависимость не подтянулась
- `BeanCreationException` — ошибка инициализации

---

### 5. Проверить все доступные paths

```bash
curl -s http://localhost:8082/mcp/message \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'

curl -sv http://localhost:8082/sse 2>&1 | head -20
curl -sv http://localhost:8082/mcp 2>&1 | head -20
```

---

### 6. Зависимости в classpath

```bash
ls JavaMemoryService/target/dependency/ 2>/dev/null | grep -i "spring-ai\|mcp" || \
  find JavaMemoryService/target -name "*.jar" | xargs -I{} basename {} | grep -i "spring-ai\|mcp"
```

---

## По итогам диагностики

Составь отчёт в формате:

```
## Результат диагностики MCP — JavaMemoryService

### Найденные проблемы
1. ...
2. ...

### Исправления
1. Файл: ...
   Изменение: ...

### Статус после исправлений
- [ ] pom.xml исправлен
- [ ] application.properties дополнен
- [ ] McpConfig.java существует и корректен
- [ ] mvn package -q выполнен без ошибок
- [ ] curl /mcp/message возвращает tools/list
```

После исправлений — пересобрать и проверить:

```bash
cd JavaMemoryService && mvn package -q && cd ..
SPRING_PROFILES_ACTIVE=local java -jar JavaMemoryService/target/memory-service.jar &
sleep 10
curl -s http://localhost:8082/mcp/message \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

Ожидаемый результат — JSON с массивом `tools` содержащим `getContext`, `getTasks`, `createTask` и др.
