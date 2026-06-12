# TEST-REPORT-2026-06-11-memory (Run 4 — сценарии 06/07/08)

**Запуск:** 2026-06-11 18:33 — 18:34 (local)
**Профиль:** local
**Инициатор:** ручной запуск
**Сервис:** JavaMemoryService :8082

---

## Summary

| Сценарий | Шагов | PASS | FAIL | Итог |
|----------|-------|------|------|------|
| 06_incidents | 5 | 3 | 2 | ❌ FAIL |
| 07_risks | 5 | 3 | 2 | ❌ FAIL |
| 08_people_and_notes | 7 | 4 | 3 | ❌ FAIL |
| **Итого** | **17** | **10** | **7** | |

**Статус открытых багов (без изменений с Run 3):**

| CR | Severity | Статус |
|----|----------|--------|
| CR-MEM-BUGFIX-001 | LOW | ❌ OPEN — HTTP 200 вместо 201 (Incident/Risk/People/Note) |
| CR-MEM-BUGFIX-004 | HIGH | ❌ OPEN — RiskService.update() → INSERT вместо UPDATE |
| DATA-ISOLATION | MEDIUM | ❌ OPEN — нет DELETE, данные накапливаются между прогонами |

---

## 06_incidents — FAIL

| Шаг | Expected | Actual | Результат |
|-----|----------|--------|-----------|
| Step 1 — POST /api/incidents | HTTP **201**, OPEN, P1 | HTTP **200**, OPEN ✅, P1 ✅ | ❌ HTTP code |
| Step 2 — count в списке | 1 | **3** (накопление: 3 прогона без delete) | ❌ data isolation |
| Step 3 — {status, severity} по id | OPEN, P1 | OPEN ✅, P1 ✅ | ✅ |
| Step 4 — resolve → RESOLVED | HTTP 200, RESOLVED, rootCause, resolvedAt | 200 ✅, RESOLVED ✅, rootCause ✅, resolvedAt ✅ | ✅ |
| Step 5 — статус в списке | RESOLVED, resolvedAt не null | RESOLVED ✅, resolvedAt ✅ | ✅ |

---

## 07_risks — FAIL

| Шаг | Expected | Actual | Результат |
|-----|----------|--------|-----------|
| Step 1 — POST /api/risks | HTTP **201**, OPEN, HIGH, HIGH | HTTP **200**, OPEN ✅, HIGH ✅, HIGH ✅ | ❌ HTTP code |
| Step 2 — count в списке | 1 | **3** (накопление: 3 прогона) | ❌ data isolation |
| Step 3 — {status, probability, impact} по id | OPEN, HIGH, HIGH | OPEN ✅, HIGH ✅, HIGH ✅ | ✅ |
| Step 4 — PUT → MITIGATED | HTTP 200, MITIGATED, mitigation | **HTTP 500** | ❌ CR-MEM-BUGFIX-004 |
| Step 5 — проверка изменений | MITIGATED, mitigation не null | OPEN ❌, null ❌ | ❌ |

---

## 08_people_and_notes — FAIL

| Шаг | Expected | Actual | Результат |
|-----|----------|--------|-----------|
| Step 1 — POST /api/people | HTTP **201**, fullName | HTTP **200**, fullName ✅ | ❌ HTTP code |
| Step 2 — count в списке | 1 | **3** (накопление: 3 прогона) | ❌ data isolation |
| Step 3 — поиск URL-encoded | 1 | **3** (те же 3 записи найдены) | ❌ data isolation |
| Step 4 — POST первой заметки | HTTP **201**, noteId, trust,key-person | HTTP **200**, noteId ✅, tags ✅ | ❌ HTTP code |
| Step 5 — POST второй заметки | HTTP **201**, blocker | HTTP **200**, blocker ✅ | ❌ HTTP code |
| Step 6 — обе заметки в списке | count 2, обе | count 2 ✅, обе ✅ | ✅ |
| Step 7 — содержимое заметки | note, tags, createdAt | note ✅, tags ✅, createdAt ✅ | ✅ |

---

## Изменения между прогонами

| Прогон | 06 Step2 | 07 Step2 | 08 Step2/3 |
|--------|----------|----------|------------|
| Run 2 (первый) | 1 | 1 | 1 |
| Run 3 | 2 | 2 | 2 |
| Run 4 (этот) | **3** | **3** | **3** |

Счётчик растёт на +1 с каждым запуском — подтверждает отсутствие cleanup.

---

## Приоритетный список для следующего багфикс-цикла

1. **CR-MEM-BUGFIX-004** (HIGH) — `RiskService.update()` — один метод, одна строка. Без этого обновление рисков заблокировано полностью.
2. **DATA-ISOLATION** (MEDIUM) — добавить `DELETE` (soft-delete) для Incident, Risk, People. Без этого сценарии 06/07/08 никогда не дадут `count == 1` при повторных прогонах.
3. **CR-MEM-BUGFIX-001** (LOW) — `ResponseEntity.status(201)` в оставшихся контроллерах.
