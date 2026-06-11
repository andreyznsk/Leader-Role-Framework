# CR-MEM-BUGFIX-004: RiskService.update() делает INSERT вместо UPDATE → HTTP 500

**Дата:** 2026-06-11  
**Статус:** Approved  
**Тип:** bugfix  
**Сервис:** JavaMemoryService  
**Severity:** HIGH  
**Источник:** TEST-REPORT-2026-06-11-memory Run2 / 07_risks / Step 4

---

## Проблема

`PUT /api/risks/{id}` возвращает HTTP 500.

**Лог ошибки:**
```
Failed to execute InsertRoot{entity=Risk[id=null, ...]}
PSQLException: null value in column "probability" violates not-null constraint
at RiskService.update(RiskService.java:30)
```

**Причина:** `RiskService.update()` создаёт новый объект `Risk` с `id=null`.
Spring Data JDBC видит `id=null` → выполняет `INSERT` вместо `UPDATE`.
PostgreSQL отклоняет INSERT — `probability` NOT NULL, но в новом объекте оно `null`.

Это классическая ловушка Spring Data JDBC с immutable records:
`save()` делает INSERT если `id == null`, UPDATE если `id != null`.

---

## Решение

В `RiskService.update()` — загрузить существующий риск, создать новый record
с нужными изменёнными полями, сохранить с тем же `id`.

---

## Изменения в коде

**Файл:** `JavaMemoryService/src/main/java/ru/andreyz/memoryservice/service/RiskService.java`

```java
// БЫЛО (сломанный вариант — id=null → INSERT → 500):
public Risk update(Long id, UpdateRiskRequest dto) {
    Risk updated = new Risk(
        null,              // ← id=null! Spring Data JDBC делает INSERT
        dto.title(),
        dto.description(),
        dto.probability(),
        dto.impact(),
        dto.status(),
        dto.mitigation(),
        null,              // ← createdAt=null, нарушает NOT NULL
        Instant.now()
    );
    return riskRepository.save(updated);
}

// СТАЛО (правильный вариант — загрузить → обновить нужные поля → save с id):
public Risk update(Long id, UpdateRiskRequest dto) {
    Risk existing = riskRepository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("Risk not found: " + id));

    Risk updated = new Risk(
        existing.id(),          // ← id сохранён → Spring Data JDBC делает UPDATE
        dto.title() != null ? dto.title() : existing.title(),
        dto.description() != null ? dto.description() : existing.description(),
        dto.probability() != null ? dto.probability() : existing.probability(),
        dto.impact() != null ? dto.impact() : existing.impact(),
        dto.status() != null ? dto.status() : existing.status(),
        dto.mitigation() != null ? dto.mitigation() : existing.mitigation(),
        existing.createdAt(),   // ← сохранить оригинальный createdAt
        Instant.now()           // ← обновить updatedAt
    );
    return riskRepository.save(updated);
}
```

---

## Проверить аналогичный паттерн в других сервисах

Та же ошибка может быть в:
- `IncidentService.update()` — проверить что `id` передаётся в новый record
- `PeopleService.update()` — аналогично
- `TaskService.edit()` — аналогично

**Правило для Spring Data JDBC + records:**  
При обновлении **всегда** копировать `id` и `createdAt` из существующей записи.

---

## Как проверить

```bash
# Создать риск
RISK_ID=$(curl -s -X POST http://localhost:8082/api/risks \
  -H "Content-Type: application/json" \
  -d '{"title":"Test risk","probability":"HIGH","impact":"HIGH"}' \
  | jq -r '.id')

# Обновить — должен вернуть 200 с обновлёнными полями
curl -s -X PUT "http://localhost:8082/api/risks/$RISK_ID" \
  -H "Content-Type: application/json" \
  -d '{"title":"Test risk","probability":"HIGH","impact":"HIGH","status":"MITIGATED","mitigation":"Задокументировано в Confluence"}'
# Ожидается: HTTP 200, "status":"MITIGATED", "mitigation" заполнен
```

**Сценарий:** `07_risks.md` Step 4, Step 5
