# CR-MEM-BUGFIX-001b: HTTP 201 для Incident/Risk/People/Note контроллеров

**Дата:** 2026-06-11  
**Статус:** Approved  
**Тип:** bugfix  
**Сервис:** JavaMemoryService  
**Источник:** TEST-REPORT-2026-06-11-memory Run2 / 06, 07, 08

---

## Проблема

CR-MEM-BUGFIX-001 был применён только к `TaskController`.  
`IncidentController`, `RiskController`, `PeopleController`, `PeopleNoteController`
по-прежнему возвращают `HTTP 200` вместо `201` при создании ресурсов.

**Затронутые endpoints:**
- `POST /api/incidents` → 200, ожидается 201
- `POST /api/risks` → 200, ожидается 201
- `POST /api/people` → 200, ожидается 201
- `POST /api/people/{id}/notes` → 200, ожидается 201

---

## Решение

В каждом контроллере заменить `ResponseEntity.ok(...)` на
`ResponseEntity.status(HttpStatus.CREATED).body(...)` во всех методах с `@PostMapping`.

---

## Изменения в коде

**Файлы:**
```
ru/andreyz/memoryservice/api/IncidentController.java
ru/andreyz/memoryservice/api/RiskController.java
ru/andreyz/memoryservice/api/PeopleController.java
```

```java
// В каждом @PostMapping методе — одинаковый паттерн:

// БЫЛО:
return ResponseEntity.ok(created);

// СТАЛО:
return ResponseEntity.status(HttpStatus.CREATED).body(created);
```

---

## Как проверить

```bash
# Incidents
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8082/api/incidents \
  -H "Content-Type: application/json" \
  -d '{"title":"Test","severity":"P2"}' 
# Ожидается: 201

# Risks
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8082/api/risks \
  -H "Content-Type: application/json" \
  -d '{"title":"Test","probability":"LOW","impact":"LOW"}'
# Ожидается: 201

# People
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8082/api/people \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Test Person"}'
# Ожидается: 201
```

**Сценарии:** `06_incidents` Step 1, `07_risks` Step 1, `08_people_and_notes` Step 1, 4, 5
