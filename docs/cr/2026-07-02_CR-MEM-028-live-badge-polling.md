# 2026-07-02_CR-MEM-028: Live-обновление счётчиков UI (polling) — фундамент для notifications bell

**Дата:** 2026-07-02
**Статус:** DONE
**Сервис:** MEM
**Зависимости:** нет (только JavaMemoryService; UI layout — общий для всех страниц)

---

## Проблема / Мотивация

Алерт-точки в сайдбаре ("ToDo" → `pendingCount`, "Intake Gateway" → `newIntakeCount`)
рендерятся сервером через `UiNavigationModelAdvice` только в момент загрузки страницы.

Если пользователь держит открытым `/ui/today`, а MailAgent или MCP-агент в это время
кладёт новый элемент в Intake (CR-MEM-024 flow) — пользователь этого не видит,
пока вручную не обновит страницу.

Нужен механизм live-обновления счётчиков. В перспективе (отдельный CR) — иконка
колокольчика справа сверху с лентой системных сообщений; этот CR закладывает
для неё контракт API.

---

## Решение

1. **Новый лёгкий endpoint** `GET /api/ui/badges` — возвращает счётчики одним JSON-конвертом.
2. **Общий JS-поллер** в `fragments/layout.html` — раз в 10 секунд запрашивает endpoint
   и обновляет алерт-точки в сайдбаре без перезагрузки страницы.
3. Формат ответа — расширяемый конверт: будущий CR колокольчика добавит поле `events`,
   не меняя существующий контракт.

Никаких изменений в БД и доменной логике: переиспользуем `IntakeService.countNew()`
и `TaskService.findPending()`.

---

## Изменения в API

| Метод | Путь | Описание |
|-------|------|----------|
| GET | /api/ui/badges | Счётчики для live-обновления UI |

Ответ `200 OK`:

```json
{
  "counts": {
    "newIntake": 3,
    "pendingTasks": 1
  },
  "serverTime": "2026-07-02T14:30:00+07:00"
}
```

Правила:

- Endpoint не требует параметров, не пишет ничего в БД, не логирует на INFO (только DEBUG) —
  иначе лог засорится записями каждые 10 секунд.
- `serverTime` — ISO-8601 с таймзоной; понадобится колокольчику для курсора `?after=`.

### Новый класс

`ru.andreyz.memoryservice.api.UiBadgesController`:

```java
@RestController
@RequestMapping("/api/ui")
public class UiBadgesController {

    private final TaskService taskService;
    private final IntakeService intakeService;

    // GET /api/ui/badges
    @GetMapping("/badges")
    public ResponseEntity<UiBadgesResponse> badges() {
        return ResponseEntity.ok(new UiBadgesResponse(
                new UiBadgesResponse.Counts(
                        intakeService.countNew(),
                        taskService.findPending().size()),
                OffsetDateTime.now()));
    }
}
```

DTO `UiBadgesResponse(Counts counts, OffsetDateTime serverTime)`,
`Counts(int newIntake, int pendingTasks)` — record'ы в пакете `dto`.

---

## Изменения в UI (fragments/layout.html)

1. Алерт-точкам в сайдбаре добавить стабильные id/data-атрибуты, чтобы JS мог их найти
   и создать, если сервер отрендерил страницу с нулевым счётчиком (сейчас точка
   в этом случае вообще отсутствует в DOM — `th:if`):

   - Intake: `data-badge="newIntake"`
   - ToDo: `data-badge="pendingTasks"`

   Проще всего: рендерить `<span class="los-nav-alert-dot" data-badge="...">` всегда,
   но со стилем `display:none` при нулевом значении — тогда JS только переключает
   видимость и текст, ничего не создаёт.

2. Общий скрипт поллинга в конец layout (внутри существующего `<script>` блока):

```javascript
(function () {
    const POLL_INTERVAL_MS = 10_000;
    const MAX_FAILURES = 3;
    let failures = 0;
    let timerId = null;

    function applyCounts(counts) {
        document.querySelectorAll('[data-badge]').forEach(el => {
            const value = counts[el.dataset.badge] ?? 0;
            el.textContent = value > 0 ? value : '';
            el.style.display = value > 0 ? '' : 'none';
        });
    }

    async function poll() {
        try {
            const resp = await fetch('/api/ui/badges', { cache: 'no-store' });
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            const data = await resp.json();
            applyCounts(data.counts || {});
            failures = 0;
            schedule(POLL_INTERVAL_MS);
        } catch (e) {
            failures++;
            // backoff: после MAX_FAILURES интервал ×3, не спамим консоль
            schedule(failures >= MAX_FAILURES ? POLL_INTERVAL_MS * 3 : POLL_INTERVAL_MS);
        }
    }

    function schedule(delay) {
        clearTimeout(timerId);
        timerId = setTimeout(poll, delay);
    }

    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
            clearTimeout(timerId);        // вкладка в фоне — не поллим
        } else {
            poll();                        // вернулись — обновить сразу
        }
    });

    if (!document.hidden) schedule(POLL_INTERVAL_MS);
})();
```

Поведение:

- вкладка в фоне → поллинг остановлен (Page Visibility API);
- возврат на вкладку → немедленный запрос;
- сервис недоступен → после 3 ошибок интервал увеличивается до 30 сек, ошибки не логируются в консоль.

---

## Изменения в схеме БД

Нет.

---

## Зависимости

- Нет новых зависимостей. Endpoint живёт в JavaMemoryService рядом с UI.
- Будущий CR "notifications bell" (иконка колокольчика справа сверху) расширит
  конверт `/api/ui/badges` полем `events` либо получит отдельный endpoint
  `GET /api/ui/notifications?after={serverTime}`. Возможный апгрейд с поллинга
  на SSE-push — тоже отдельным CR, контракт конверта это не блокирует.

## Явно вне скоупа этого CR

- Иконка колокольчика и лента системных сообщений.
- Хранение событий/уведомлений (таблица, retention).
- Browser Notifications API / звук.
- Live-обновление содержимого самих страниц (списка intake и т.п.) — только счётчики.

---

## Как тестировать

```bash
# 1. Endpoint отвечает и формат корректен
curl -s http://localhost:8082/api/ui/badges | jq
# Expected: {"counts":{"newIntake":N,"pendingTasks":M},"serverTime":"..."}

# 2. Счётчик реагирует на новый intake
curl -s -X POST http://localhost:8082/api/intake \
  -H 'Content-Type: application/json' \
  -d '{"sourceType":"MANUAL","title":"badge-poll-test","suggestedRoute":"TASK"}'
curl -s http://localhost:8082/api/ui/badges | jq '.counts.newIntake'
# Expected: значение увеличилось на 1

# 3. UI live-обновление (вручную):
#    открыть /ui/today, НЕ перезагружая страницу выполнить POST из шага 2,
#    подождать ≤10 сек → у "Intake Gateway" в сайдбаре появилась/увеличилась точка.

# 4. Backoff: остановить сервис при открытой странице →
#    консоль браузера без потока ошибок; после рестарта сервиса
#    счётчики восстанавливаются в течение ~30 сек.

# 5. Юнит: UiBadgesControllerTest — 200, структура конверта,
#    счётчики совпадают с IntakeService.countNew() / TaskService.findPending().size()
```

## После подтверждения пользователя перевести этот CR в Статус: DONE. и обновить реестр таблица `docs/cr/REGISTRY.md`
