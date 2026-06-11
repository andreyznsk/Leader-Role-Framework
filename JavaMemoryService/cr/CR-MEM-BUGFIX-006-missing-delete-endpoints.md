# CR-MEM-BUGFIX-006: Нет DELETE endpoints для Incident/Risk/People → тесты нестабильны

**Дата:** 2026-06-11  
**Статус:** Approved  
**Тип:** bugfix  
**Сервис:** JavaMemoryService  
**Severity:** MEDIUM  
**Источник:** TEST-REPORT-2026-06-11-memory Run3 / все сценарии

---

## Проблема

Отсутствуют DELETE-endpoints для `Incident`, `Risk`, `People`.
При повторных прогонах E2E тестов данные накапливаются в H2/PostgreSQL.
Сценарии не могут почистить за собой → count-проверки дают непредсказуемые результаты.

**Конкретно:**
```
POST /api/incidents  → создаёт запись
DELETE /api/incidents/{id}  → 405 Method Not Allowed
→ при следующем прогоне в БД уже 2 инцидента → Step 2 ожидает 1, получает 2
```

Аналогично для `Risk` и `People` (с каскадным удалением `people_notes`).

**Не нужен** DELETE для `Task` — там уже есть `POST /api/tasks/{id}/delete` (soft delete → DELETED).

---

## Решение

Добавить DELETE-endpoints (soft delete через статус) для Incident, Risk и People.  
Плюс добавить GET по ID для возможности читать отдельный ресурс.

---

## Изменения в коде

### IncidentController.java

```java
// Добавить:
@GetMapping("/{id}")
public ResponseEntity<Incident> getById(@PathVariable Long id) {
    return incidentService.findById(id)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
}

@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable Long id) {
    incidentService.delete(id);
    return ResponseEntity.noContent().build();
}
```

### IncidentService.java

```java
public Optional<Incident> findById(Long id) {
    return incidentRepository.findById(id);
}

public void delete(Long id) {
    // Soft delete — статус CLOSED
    Incident existing = incidentRepository.findById(id).orElseThrow();
    Incident closed = new Incident(
        existing.id(), existing.title(), existing.severity(),
        "CLOSED",  // ← soft delete через статус
        existing.description(), existing.rootCause(), existing.actionItems(),
        existing.startedAt(), Instant.now(), existing.createdAt()
    );
    incidentRepository.save(closed);
}
```

### RiskController.java

```java
@GetMapping("/{id}")
public ResponseEntity<Risk> getById(@PathVariable Long id) {
    return riskService.findById(id)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
}

@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable Long id) {
    riskService.delete(id);
    return ResponseEntity.noContent().build();
}
```

### RiskService.java

```java
public void delete(Long id) {
    Risk existing = riskRepository.findById(id).orElseThrow();
    Risk closed = new Risk(
        existing.id(), existing.title(), existing.description(),
        existing.probability(), existing.impact(),
        "CLOSED",  // ← soft delete
        existing.mitigation(), existing.createdAt(), Instant.now()
    );
    riskRepository.save(closed);
}
```

### PeopleController.java

```java
@GetMapping("/{id}")
public ResponseEntity<Person> getById(@PathVariable Long id) {
    return peopleService.findById(id)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
}

@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable Long id) {
    peopleService.delete(id);   // каскадно удаляет people_notes (FK ON DELETE CASCADE)
    return ResponseEntity.noContent().build();
}
```

### PeopleService.java

```java
public void delete(Long id) {
    // Hard delete — notes удаляются каскадно через FK
    personRepository.deleteById(id);
}
```

---

## Статусы для soft delete

| Сущность | Статус soft delete | Фильтруется из GET /api/... |
|----------|-------------------|----------------------------|
| Incident | `CLOSED` | да — `?status=OPEN` уже работает |
| Risk | `CLOSED` | да — `?status=OPEN` уже работает |
| People | hard delete | каскадно удаляет notes |

---

## Как проверить

```bash
# Incident
INC_ID=$(curl -s -X POST http://localhost:8082/api/incidents \
  -H "Content-Type: application/json" \
  -d '{"title":"Test","severity":"P3"}' | jq -r '.id')

curl -s -o /dev/null -w "%{http_code}" \
  -X DELETE "http://localhost:8082/api/incidents/$INC_ID"
# Ожидается: 204

curl -s "http://localhost:8082/api/incidents?status=OPEN" \
  | jq '[.[] | select(.id == '$INC_ID')] | length'
# Ожидается: 0

# People (hard delete)
PERSON_ID=$(curl -s -X POST http://localhost:8082/api/people \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Delete Test"}' | jq -r '.id')

curl -s -o /dev/null -w "%{http_code}" \
  -X DELETE "http://localhost:8082/api/people/$PERSON_ID"
# Ожидается: 204
```
