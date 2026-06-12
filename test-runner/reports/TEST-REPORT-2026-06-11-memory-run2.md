# TEST-REPORT-2026-06-11-memory (Run 2 — новые сценарии 06/07/08)

**Запуск:** 2026-06-11 18:00 — 18:30 (local)
**Профиль:** local
**Инициатор:** ручной запуск — сценарии 06_incidents, 07_risks, 08_people_and_notes
**Сервис:** JavaMemoryService :8082

> Сценарии созданы в ходе этого прогона (до запуска не существовали).
> Перед написанием сценариев проведён зондирующий обход API (probe run).

---

## Summary

| Сценарий | PASS | FAIL | Примечание |
|----------|------|------|------------|
| 06_incidents | — | ❌ | HTTP 200 вместо 201; функционально всё работает |
| 07_risks | — | ❌ | HTTP 200 вместо 201 + PUT update → 500 (RiskService bug) |
| 08_people_and_notes | — | ❌ | HTTP 200 вместо 201 + Cyrillic query param → 400 |
| **Итого** | 0 | 3 | |

> **Примечание:** CR-MEM-BUGFIX-001 (HTTP 201) не применён к Incidents, Risks, People контроллерам — только к TaskController. Все три FAIL содержат HTTP 200 вместо 201 плюс дополнительные функциональные дефекты.

---

## 06_incidents — FAIL (HTTP code)

### Результаты по шагам

| Шаг | Expected | Actual | Результат |
|-----|----------|--------|-----------|
| Step 1 — POST /api/incidents | HTTP **201**, status OPEN, severity P1 | HTTP **200** ❌, OPEN ✅, P1 ✅ | ❌ HTTP code |
| Step 2 — инцидент в списке | count 1 | 1 ✅ | ✅ |
| Step 3 — статус OPEN, severity P1 | status OPEN, P1 | OPEN ✅, P1 ✅ | ✅ |
| Step 4 — resolve с rootCause | HTTP 200, RESOLVED, rootCause populated, resolvedAt не null | 200 ✅, RESOLVED ✅, rootCause ✅, resolvedAt ✅ | ✅ |
| Step 5 — RESOLVED в списке | status RESOLVED, resolvedAt не null | RESOLVED ✅, resolvedAt ✅ | ✅ |

**Вывод:** Инциденты функционально работают полностью — create → list → resolve с rootCause/actionItems.
Единственный дефект — HTTP 200 вместо 201 при создании (CR-MEM-BUGFIX-001 не применён к IncidentController).

**Обнаруженные ограничения:**
- `GET /api/incidents/{id}` → 405 (нет endpoint для чтения по ID)
- `DELETE /api/incidents/{id}` → 405 (нет удаления, данные накапливаются)

---

## 07_risks — FAIL (функциональный дефект)

### Результаты по шагам

| Шаг | Expected | Actual | Результат |
|-----|----------|--------|-----------|
| Step 1 — POST /api/risks | HTTP **201**, status OPEN, probability HIGH, impact HIGH | HTTP **200** ❌, OPEN ✅, HIGH ✅, HIGH ✅ | ❌ HTTP code |
| Step 2 — риск в списке | count 1 | 1 ✅ | ✅ |
| Step 3 — поля корректны | status OPEN, probability HIGH, impact HIGH | OPEN ✅, HIGH ✅, HIGH ✅ | ✅ |
| Step 4 — PUT /api/risks/{id} обновить статус | HTTP 200, status MITIGATED, mitigation populated | **HTTP 500** ❌ | ❌ FAIL |
| Step 5 — изменения сохранились | status MITIGATED, mitigation не null | status OPEN ❌, mitigation null ❌ | ❌ FAIL |

### CR-MEM-BUGFIX-004 — RiskService.update() выполняет INSERT вместо UPDATE

**Severity:** HIGH
**Endpoint:** `PUT /api/risks/{id}`
**Actual:** HTTP 500, `"message":"null value in column probability"`

Из лога:
```
Failed to execute InsertRoot{entity=Risk[id=null, ...]}
PSQLException: null value in column "probability" violates not-null constraint
at ru.andreyz.memoryservice.service.RiskService.update(RiskService.java:30)
```

**Причина:** `RiskService.update()` создаёт новый объект Risk без ID вместо загрузки существующего и обновления полей. Spring Data JDBC вызывает INSERT вместо UPDATE.

**Где фиксить:** `JavaMemoryService/src/main/java/.../service/RiskService.java`, line 30 — метод `update()`.

**Правильный паттерн:**
```java
// Было (InsertRoot → ошибка):
Risk updated = new Risk(...);
return riskRepository.save(updated);

// Должно быть (UpdateRoot):
Risk existing = riskRepository.findById(id).orElseThrow();
existing.setStatus(dto.getStatus());
existing.setMitigation(dto.getMitigation());
return riskRepository.save(existing);
```

**Обнаруженные ограничения:**
- `GET /api/risks/{id}` → 405
- `DELETE /api/risks/{id}` → 405
- `PATCH /api/risks/{id}/status` → 404 (нет endpoint)

---

## 08_people_and_notes — FAIL (HTTP code + Cyrillic bug)

### Результаты по шагам

| Шаг | Expected | Actual | Результат |
|-----|----------|--------|-----------|
| Step 1 — POST /api/people | HTTP **201**, fullName корректен | HTTP **200** ❌, fullName ✅ | ❌ HTTP code |
| Step 2 — человек в списке | count 1 | 1 ✅ | ✅ |
| Step 3 — поиск по имени `?name=Иванов` | count 1 | **HTTP 400** ❌ — Tomcat отклонил non-encoded Cyrillic в URL | ❌ FAIL |
| Step 4 — POST первой заметки | HTTP **201**, noteId не null, tags корректны | HTTP **200** ❌, noteId ✅, tags ✅ | ❌ HTTP code |
| Step 5 — POST второй заметки | HTTP **201**, tags blocker | HTTP **200** ❌, tags ✅ | ❌ HTTP code |
| Step 6 — обе заметки в списке | count 2, обе присутствуют | count 2 ✅, обе ✅ | ✅ |
| Step 7 — содержимое первой заметки | note text ✅, tags ✅, createdAt не null | note ✅, tags ✅, createdAt ✅ | ✅ |

### CR-MEM-BUGFIX-005 — GET /api/people?name={кириллица} → HTTP 400

**Severity:** MEDIUM
**Endpoint:** `GET /api/people?name=Иванов` (non-URL-encoded Cyrillic)
**Actual:** HTTP 400 "Bad Request" от Tomcat (до Spring)

**Воспроизведение:**
```bash
# FAIL — Tomcat не принимает не-encoded Cyrillic
curl "http://localhost:8082/api/people?name=Иванов"
# → HTTP 400

# PASS — с URL-encoding работает
curl "http://localhost:8082/api/people?name=%D0%98%D0%B2%D0%B0%D0%BD%D0%BE%D0%B2"
# → [{"id":4,"fullName":"E2E: Иванов Алексей",...}]
```

**Причина:** Tomcat по умолчанию отклоняет не-ASCII символы в query string без URL-encoding.

**Фикс в `application.yml`:**
```yaml
server:
  tomcat:
    uri-encoding: UTF-8
    relaxed-query-chars: "|{}[]\\"
```
или использовать `%encoded` значения на стороне клиента (Claude Code MCP клиент должен URL-encode параметры).

**Обнаруженные ограничения:**
- `GET /api/people/{id}` → 405 (нет endpoint по ID)
- `DELETE /api/people/{id}` → 405 (нет удаления)
- `GET /api/people/search?name=...` → 405 (endpoint не реализован, работает `?name=` param)

---

## Итоговая таблица всех дефектов по результатам двух прогонов

| CR | Статус | Severity | Описание |
|----|--------|----------|----------|
| CR-MEM-BUGFIX-001 | ✅ FIXED (Tasks) / ❌ НЕ применён к Incidents/Risks/People | LOW | POST возвращает 200 вместо 201 |
| CR-MEM-BUGFIX-002 | ✅ FIXED | MEDIUM | PATCH /api/tasks/{id}/status → 404 |
| CR-MEM-BUGFIX-003 | ❌ OPEN | HIGH | GET /api/tasks возвращает DELETED задачи |
| CR-MEM-BUGFIX-004 | ❌ NEW | HIGH | RiskService.update() делает INSERT вместо UPDATE → 500 |
| CR-MEM-BUGFIX-005 | ❌ NEW | MEDIUM | GET /api/people?name={кириллица} → 400 (Tomcat URI encoding) |

---

## Рекомендации по приоритету

1. **CR-MEM-BUGFIX-003** (HIGH) — DELETED задачи в плане дня — UX-критично, данные накапливаются
2. **CR-MEM-BUGFIX-004** (HIGH) — Risk update сломан, нет способа закрыть/митигировать риск через REST
3. **CR-MEM-BUGFIX-001** (LOW) — HTTP 201 для IncidentController, RiskController, PeopleController, NoteController
4. **CR-MEM-BUGFIX-005** (MEDIUM) — Tomcat URI encoding для кириллицы в query params
5. Добавить endpoints: `GET /api/incidents/{id}`, `GET /api/risks/{id}`, `GET /api/people/{id}`, `DELETE` для всех сущностей
