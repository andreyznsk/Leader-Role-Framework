# TEST-REPORT-2026-06-11-memory (Run 3 — сценарии 06/07/08)

**Запуск:** 2026-06-11 18:15 — 18:25 (local)
**Профиль:** local
**Инициатор:** ручной запуск
**Сервис:** JavaMemoryService :8082

---

## Summary

| Сценарий | PASS | FAIL | Примечание |
|----------|------|------|------------|
| 06_incidents | — | ❌ | 2 дефекта: HTTP 200, data isolation |
| 07_risks | — | ❌ | 4 дефекта: HTTP 200, data isolation, PUT update 500 |
| 08_people_and_notes | — | ❌ | 3 дефекта: HTTP 200, data isolation, Cyrillic search count |
| **Итого** | **0** | **3** | |

---

## 06_incidents — FAIL

| Шаг | Expected | Actual | Результат |
|-----|----------|--------|-----------|
| Step 1 — POST /api/incidents | HTTP **201**, OPEN, P1 | HTTP **200** ❌, OPEN ✅, P1 ✅ | ❌ |
| Step 2 — инцидент в списке | count **1** | **2** ❌ (накопились данные из предыдущих прогонов) | ❌ |
| Step 3 — статус OPEN, severity P1 | OPEN, P1 | OPEN ✅, P1 ✅ | ✅ |
| Step 4 — resolve с rootCause | HTTP 200, RESOLVED, rootCause, resolvedAt | 200 ✅, RESOLVED ✅, rootCause ✅, resolvedAt ✅ | ✅ |
| Step 5 — RESOLVED в списке | RESOLVED, resolvedAt не null | RESOLVED ✅, resolvedAt ✅ | ✅ |

**Дефекты:**
- **CR-MEM-BUGFIX-001**: `POST /api/incidents` → HTTP 200 (ожидается 201). Фикс не применён к IncidentController.
- **DATA-ISOLATION**: нет endpoint `DELETE /api/incidents/{id}` → данные из предыдущих прогонов накапливаются. Step 2 ожидает `1`, получает `2`. Сценарий нестабилен при повторных запусках.

**Функционально:** resolve flow работает полностью — создание, просмотр, resolve с rootCause/actionItems/resolvedAt. ✅

---

## 07_risks — FAIL

| Шаг | Expected | Actual | Результат |
|-----|----------|--------|-----------|
| Step 1 — POST /api/risks | HTTP **201**, OPEN, HIGH, HIGH | HTTP **200** ❌, OPEN ✅, HIGH ✅, HIGH ✅ | ❌ |
| Step 2 — риск в списке | count **1** | **2** ❌ (накопились данные) | ❌ |
| Step 3 — поля корректны | OPEN, HIGH, HIGH | OPEN ✅, HIGH ✅, HIGH ✅ | ✅ |
| Step 4 — PUT /api/risks/{id} → MITIGATED | HTTP 200, MITIGATED, mitigation | **HTTP 500** ❌ | ❌ |
| Step 5 — изменения сохранились | MITIGATED, mitigation не null | OPEN ❌, null ❌ | ❌ |

**Дефекты:**
- **CR-MEM-BUGFIX-001**: HTTP 200 вместо 201.
- **DATA-ISOLATION**: нет `DELETE` → count 2 вместо 1 в Step 2.
- **CR-MEM-BUGFIX-004** (подтверждён): `PUT /api/risks/{id}` → HTTP 500. `RiskService.update()` делает `InsertRoot` вместо `UpdateRoot`. Лог: `Failed to execute InsertRoot — null value in column "probability"`. Обновление статуса риска через REST невозможно.

**Функционально:** создание и чтение работают. Обновление сломано. ❌

---

## 08_people_and_notes — FAIL

| Шаг | Expected | Actual | Результат |
|-----|----------|--------|-----------|
| Step 1 — POST /api/people | HTTP **201**, fullName корректен | HTTP **200** ❌, fullName ✅ | ❌ |
| Step 2 — человек в списке | count **1** | **2** ❌ (накопились данные) | ❌ |
| Step 3 — поиск URL-encoded `%D0%98%D0%B2%D0%B0%D0%BD%D0%BE%D0%B2` | count **1** | **2** ❌ (две записи "Иванов") | ❌ |
| Step 4 — POST первой заметки | HTTP **201**, noteId, trust,key-person | HTTP **200** ❌, noteId ✅, tags ✅ | ❌ |
| Step 5 — POST второй заметки | HTTP **201**, blocker | HTTP **200** ❌, blocker ✅ | ❌ |
| Step 6 — обе заметки в списке | count 2, обе | count 2 ✅, обе ✅ | ✅ |
| Step 7 — содержимое заметки | note text, tags, createdAt | note ✅, tags ✅, createdAt ✅ | ✅ |

**Дефекты:**
- **CR-MEM-BUGFIX-001**: HTTP 200 вместо 201 — для `POST /api/people` и `POST /api/people/{id}/notes`.
- **DATA-ISOLATION**: нет `DELETE /api/people/{id}` → Step 2 даёт count 2. Тот же person создаётся заново при каждом прогоне.
- **CR-MEM-BUGFIX-005** (частично исправлен сценарием): Step 3 теперь использует URL-encoding через Python (`%D0%98%D0%B2%D0%B0%D0%BD%D0%BE%D0%B2`), запрос проходит. Но count 2 вместо 1 — из-за двух записей "Иванов Алексей" в БД от разных прогонов.

**Функционально:** создание person (с `fullName`), добавление заметок, чтение заметок по `personId` — всё работает. Поиск по имени работает при URL-encoded запросе. ✅

---

## Сводная таблица дефектов

| CR | Severity | Статус | Описание |
|----|----------|--------|----------|
| CR-MEM-BUGFIX-001 | LOW | ❌ OPEN (Tasks ✅, остальные ❌) | POST возвращает 200 вместо 201 — не применён к Incident/Risk/People/Note контроллерам |
| CR-MEM-BUGFIX-003 | HIGH | ❌ OPEN | GET /api/tasks возвращает DELETED задачи |
| CR-MEM-BUGFIX-004 | HIGH | ❌ OPEN | RiskService.update() делает INSERT вместо UPDATE → 500 |
| CR-MEM-BUGFIX-005 | MEDIUM | ⚠️ WORKAROUND | Cyrillic в query param: сценарий обновлён под URL-encoding, сервис не исправлен |
| DATA-ISOLATION | MEDIUM | ❌ OPEN | Нет DELETE-эндпоинтов для Incident/Risk/People → тесты нестабильны при повторных запусках |

---

## Рекомендации

**Быстрые фиксы (1–2 строки кода):**

1. **CR-MEM-BUGFIX-001** — заменить `ResponseEntity.ok()` на `ResponseEntity.status(201).body()` в IncidentController, RiskController, PeopleController, NoteController.

2. **CR-MEM-BUGFIX-004** — в `RiskService.update()` загружать существующий объект из репозитория и обновлять поля, а не создавать новый:
```java
Risk existing = riskRepository.findById(id).orElseThrow();
existing.setStatus(dto.getStatus());
existing.setMitigation(dto.getMitigation());
return riskRepository.save(existing);
```

**Средние фиксы:**

3. **DATA-ISOLATION** — добавить `DELETE /api/incidents/{id}`, `DELETE /api/risks/{id}`, `DELETE /api/people/{id}` (soft delete через статус DELETED). Без этого E2E тесты накапливают мусор и не дают воспроизводимых результатов.

4. **CR-MEM-BUGFIX-005** — добавить в `application.yml`:
```yaml
server:
  tomcat:
    uri-encoding: UTF-8
```
