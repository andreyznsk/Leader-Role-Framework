# CR-MEM-BUGFIX-005: GET /api/people?name={кириллица} → HTTP 400

**Дата:** 2026-06-11  
**Статус:** Approved  
**Тип:** bugfix  
**Сервис:** JavaMemoryService  
**Severity:** MEDIUM  
**Источник:** TEST-REPORT-2026-06-11-memory Run2 / 08_people_and_notes / Step 3

---

## Проблема

```bash
curl "http://localhost:8082/api/people?name=Иванов"
# → HTTP 400 Bad Request (Tomcat отклоняет до Spring)
```

Tomcat по умолчанию запрещает не-ASCII символы в query string без URL-encoding.  
URL-encoded вариант работает корректно:
```bash
curl "http://localhost:8082/api/people?name=%D0%98%D0%B2%D0%B0%D0%BD%D0%BE%D0%B2"
# → HTTP 200, [{...}]
```

**Практическая проблема:** Claude-агент при вызове MCP tool `searchPeople("Иванов")`
генерирует прямой вызов с кириллицей — он упадёт с 400.
Пользователь в UI вводит `?name=Иванов` — тоже 400.

---

## Решение

Настроить Tomcat принимать UTF-8 символы в URI/query string.

---

## Изменения в конфигурации

**Файл:** `JavaMemoryService/src/main/resources/application.properties`

```properties
# Разрешить не-ASCII символы в URL (кириллица в query params)
server.tomcat.relaxed-query-chars=|,{,},[,]
server.tomcat.uri-encoding=UTF-8
```

**Дополнительно** — убедиться что Spring MVC также корректно декодирует:

```properties
spring.web.resources.add-mappings=false
server.servlet.encoding.charset=UTF-8
server.servlet.encoding.enabled=true
server.servlet.encoding.force=true
```

---

## Альтернативное решение на стороне клиента

Если конфигурация Tomcat не помогает (зависит от версии) — URL-encode в сценариях:

```bash
# Обновить сценарий 08_people_and_notes.md Step 3:
NAME_ENCODED=$(python3 -c "import urllib.parse; print(urllib.parse.quote('E2E Иванов'))")
curl -s "http://localhost:8082/api/people?name=$NAME_ENCODED"
```

---

## Обновить сценарий 08_people_and_notes.md

**Step 3** — заменить прямой curl с кириллицей на URL-encoded:

```bash
# БЫЛО:
curl -s "http://localhost:8082/api/people?name=E2E Иванов"

# СТАЛО:
NAME_ENCODED=$(python3 -c "import urllib.parse; print(urllib.parse.quote('E2E Иванов'))")
curl -s "http://localhost:8082/api/people?name=$NAME_ENCODED" \
  | jq '[.[] | select(.login == "e2e.ivanov")] | length'
```

---

## Как проверить

```bash
# После применения фикса:
curl -s -o /dev/null -w "%{http_code}" \
  "http://localhost:8082/api/people?name=Иванов"
# Ожидается: 200 (не 400)
```

**Сценарий:** `08_people_and_notes.md` Step 3
