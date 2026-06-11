# TEST-REPORT-2026-06-11-memory (Run 5 — сценарии 06/07/08)

**Запуск:** 2026-06-11 18:42 — 18:44 (local)
**Профиль:** local
**Инициатор:** ручной запуск после пересборки
**Сервис:** JavaMemoryService :8082 (свежая сборка, PID 241615)

---

## Summary

| Сценарий | Шагов | PASS | FAIL | Итог |
|----------|-------|------|------|------|
| 06_incidents | 5 | 4 | 1 | ❌ FAIL |
| 07_risks | 5 | 4 | 1 | ❌ FAIL |
| 08_people_and_notes | 7 | 5 | 2 | ❌ FAIL |
| **Итого** | **17** | **13** | **4** | |

> Прогресс по сравнению с Run 4: было 10/17 PASS → стало **13/17 PASS**.

---

## Статус фиксов

| CR | Было | Стало |
|----|------|-------|
| CR-MEM-BUGFIX-001 (HTTP 201) | ❌ OPEN (Incident/Risk/People/Note) | ✅ **FIXED** — все возвращают 201 |
| CR-MEM-BUGFIX-004 (RiskService.update) | ❌ OPEN → 500 | ✅ **FIXED** — PUT /api/risks/{id} возвращает 200, MITIGATED |
| DATA-ISOLATION (нет DELETE) | ❌ OPEN | ❌ OPEN — не исправлен |

---

## 06_incidents — FAIL (data isolation)

| Шаг | Expected | Actual | Результат |
|-----|----------|--------|-----------|
| Step 1 — POST /api/incidents | HTTP **201**, OPEN, P1 | **201** ✅, OPEN ✅, P1 ✅ | ✅ PASS |
| Step 2 — count в списке | **1** | **4** (накопление за 4 прогона без delete) | ❌ FAIL |
| Step 3 — {status, severity} по id | OPEN, P1 | OPEN ✅, P1 ✅ | ✅ PASS |
| Step 4 — resolve → RESOLVED | HTTP 200, RESOLVED, rootCause, resolvedAt | 200 ✅, RESOLVED ✅, rootCause ✅, resolvedAt ✅ | ✅ PASS |
| Step 5 — RESOLVED в списке | RESOLVED, resolvedAt не null | RESOLVED ✅, resolvedAt ✅ | ✅ PASS |

**Единственный FAIL:** Step 2 — отсутствие DELETE endpoint приводит к накоплению данных.

---

## 07_risks — FAIL (data isolation)

| Шаг | Expected | Actual | Результат |
|-----|----------|--------|-----------|
| Step 1 — POST /api/risks | HTTP **201**, OPEN, HIGH, HIGH | **201** ✅, OPEN ✅, HIGH ✅, HIGH ✅ | ✅ PASS |
| Step 2 — count в списке | **1** | **4** (накопление за 4 прогона) | ❌ FAIL |
| Step 3 — {status, probability, impact} по id | OPEN, HIGH, HIGH | OPEN ✅, HIGH ✅, HIGH ✅ | ✅ PASS |
| Step 4 — PUT → MITIGATED | HTTP 200, MITIGATED, mitigation | **200** ✅, **MITIGATED** ✅, mitigation ✅ | ✅ PASS |
| Step 5 — изменения сохранились | MITIGATED, mitigation не null | MITIGATED ✅, mitigation ✅ | ✅ PASS |

**Единственный FAIL:** Step 2 — data isolation. Функционально сценарий полностью работает.

---

## 08_people_and_notes — FAIL (data isolation)

| Шаг | Expected | Actual | Результат |
|-----|----------|--------|-----------|
| Step 1 — POST /api/people | HTTP **201**, fullName | **201** ✅, fullName ✅ | ✅ PASS |
| Step 2 — count в списке | **1** | **4** (4 записи "Иванов Алексей") | ❌ FAIL |
| Step 3 — поиск URL-encoded | **1** | **4** (те же 4 записи по имени) | ❌ FAIL |
| Step 4 — POST первой заметки | HTTP **201**, noteId, trust,key-person | **201** ✅, noteId ✅, tags ✅ | ✅ PASS |
| Step 5 — POST второй заметки | HTTP **201**, blocker | **201** ✅, blocker ✅ | ✅ PASS |
| Step 6 — обе заметки в списке | count 2, обе | count 2 ✅, обе ✅ | ✅ PASS |
| Step 7 — содержимое заметки | note, tags, createdAt | note ✅, tags ✅, createdAt ✅ | ✅ PASS |

**FAIL:** Steps 2 и 3 — data isolation. Функционально весь сценарий работает корректно.

---

## Единственный открытый дефект

### DATA-ISOLATION — нет DELETE-эндпоинтов

**Severity:** MEDIUM
**Влияние:** Steps 2 (все сценарии) и Step 3 (08) дают count N вместо 1, где N = количество прогонов.

| Endpoint | Статус |
|----------|--------|
| DELETE /api/incidents/{id} | 405 |
| DELETE /api/risks/{id} | 405 |
| DELETE /api/people/{id} | 405 |

**Фикс:** добавить soft-delete (установка статуса DELETED) для каждой сущности и исключить DELETED из GET-списков.

---

## Эволюция результатов по прогонам

| Метрика | Run 2 | Run 3 | Run 4 | **Run 5** |
|---------|-------|-------|-------|-----------|
| PASS шагов | 9/17 | 10/17 | 10/17 | **13/17** |
| HTTP 201 (Incident/Risk/People) | ❌ | ❌ | ❌ | ✅ |
| Risk PUT update | ❌ 500 | ❌ 500 | ❌ 500 | ✅ 200 |
| Data isolation (count==1) | ❌ | ❌ | ❌ | ❌ |
