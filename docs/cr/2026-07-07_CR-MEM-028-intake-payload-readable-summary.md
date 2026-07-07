# 2026-07-07_CR-MEM-028: Intake Gateway readable payload and mail body summary

**Дата:** 2026-07-07  
**Статус:** Draft  
**Сервис:** MEM / JavaMemoryService  
**Тип:** enhancement + ui  
**Связанный Issue:** будет создан после публикации CR  

## Проблема / Мотивация

В разделе **Intake Gateway** неудобно читать карточку входящего сигнала:

1. В блоке **Original Payload** текст письма отображается с переносами строк и управляющими символами (`\n`, `\r`, возможные CR/LF последовательности). Из-за этого original payload визуально превращается в длинный «чатовый» текст, который тяжело быстро просматривать.
2. Блок **Original Payload** сейчас визуально больше, чем **Final Payload**. Это перегружает страницу review и заставляет пользователя много скроллить всю страницу.
3. При генерации **Final Payload** из письма нужно не просто переносить часть исходного body, а формировать всеобъемлющую суммаризацию задачи из письма: что требуется, от кого, контекст, сроки, ссылки/артефакты, ожидаемый результат.

## Цель

Сделать review в Intake Gateway компактным и читаемым:

- **Original Payload** — короткое окно того же размера, что и **Final Payload**, со внутренним скроллом.
- В **Original Payload** не должно быть визуального шума от `\n`, `\r`, CR/LF и похожих escape-последовательностей.
- **Final Payload** для mail-derived intake должен содержать нормальную summary-задачу по исходному body письма.

## Решение

### 1. UI: нормализация Original Payload только для отображения

В UI Intake Gateway добавить presentation-layer нормализацию для поля **Original Payload**:

- заменить реальные CR/LF (`\r`, `\n`, `\r\n`) на пробел;
- заменить escaped-последовательности (`\\r`, `\\n`, при наличии `\\m`) на пробел;
- схлопывать повторяющиеся пробелы до одного;
- не менять исходное значение в БД и API;
- сохранить возможность скопировать payload без визуального мусора.

Важно: это именно UI/display formatting. Raw payload в `memory.intake_items` должен остаться неизменным.

### 2. UI: одинаковый размер Original Payload и Final Payload

На странице `/ui/intake` для detail/review карточки:

- сделать **Original Payload** такого же размера, как **Final Payload**;
- добавить внутренний вертикальный scroll (`overflow-y: auto`);
- общий layout карточки не должен растягиваться на всю высоту original payload;
- если payload длинный, скроллится только содержимое блока, а не вся страница review.

Ориентир для MVP:

```css
.payload-box {
  max-height: <same-as-final-payload>;
  overflow-y: auto;
  white-space: pre-wrap; /* для final, если нужно */
}

.original-payload-box {
  white-space: normal;
  overflow-wrap: anywhere;
}
```

Точное имя CSS-классов выбрать по текущей реализации шаблона.

### 3. Backend/Prompt: Final Payload должен включать summary исходного body письма

Для intake items, созданных из Mail Agent (`sourceType = MAIL` или аналогичный признак), при подготовке/автозаполнении **Final Payload** добавить суммаризацию исходного `body` письма.

Final Payload должен включать:

- краткий заголовок задачи;
- кто инициатор / отправитель письма;
- что именно требуется сделать;
- контекст из письма;
- deadline / дату, если она есть в письме;
- ссылки, номера тикетов, названия систем, окружений, образов или артефактов, если они есть;
- ожидаемый результат / критерий готовности;
- исходную классификацию / suggestedRoute, если полезно для review.

Для маршрута `TASK` итоговое описание должно быть пригодно для создания полноценной задачи без необходимости читать всё письмо заново.

Пример структуры final payload для `TASK`:

```json
{
  "title": "Подготовить релиз <название>, если указано в письме",
  "description": "Суммаризация исходного письма: кто запросил, что требуется, контекст, сроки, ссылки, образы, ожидаемый результат.",
  "priority": "MEDIUM",
  "dueDate": "YYYY-MM-DD или null",
  "source": "mail",
  "sourceSummary": "Всеобъемлющая выжимка body письма для review в Intake Gateway"
}
```

Если текущий контракт final payload отличается — сохранить существующий контракт и добавить summary в подходящее поле (`description`, `text`, `sourceSummary`) без breaking changes.

## Изменения в API

Breaking changes не требуются.

Допустимые изменения:

- добавить поле `sourceSummary` в DTO final payload, если это не ломает существующие consumers;
- либо расширить уже существующее поле `description`/`text` более качественной summary;
- API original payload должен продолжать возвращать raw данные без UI-нормализации.

## Изменения в схеме БД

Не требуются.

Важно: не менять существующие Flyway migrations в `*/db/migration`.

## Зависимости

- JavaMemoryService `/api/intake`, `/ui/intake`;
- текущий `IntakeItem` / DTO / Thymeleaf-шаблон Intake Gateway;
- prompt/mapper, который формирует suggested/final payload из mail-derived intake.

## Как тестировать

### Unit / service-level

1. Проверить нормализацию display-текста:
   - input: `"строка1\\nстрока2\\rстрока3"`;
   - expected UI/display: `"строка1 строка2 строка3"`.
2. Проверить реальные CR/LF:
   - input содержит `\r\n`, `\n`, `\r`;
   - expected display без переносов.
3. Проверить, что raw original payload в API/БД не изменяется.

### UI smoke

1. Открыть `/ui/intake`.
2. Выбрать intake item с длинным original payload.
3. Убедиться:
   - Original Payload визуально того же размера, что Final Payload;
   - внутри Original Payload есть scroll;
   - страница review не растягивается из-за original payload;
   - `\n`, `\r`, `\r\n` и escaped-варианты не создают визуальные переносы.

### E2E сценарий

Создать новый сценарий:

`JavaMemoryService/test_e2e/26_intake_payload_readability_and_summary.md`

Проверить flow:

1. Создать intake item через `POST /api/intake` с `sourceType=MAIL`, `originalPayload`/`body`, где есть `\\n`, `\\r`, реальные CR/LF и длинный текст письма.
2. Открыть `/ui/intake` и проверить, что страница возвращает 200.
3. Проверить, что HTML содержит блоки Original Payload и Final Payload.
4. Проверить, что final payload содержит summary исходного body: ключевые слова из письма, expected action, deadline/ссылки при наличии.
5. Проверить, что API raw payload не потерял исходные escape-последовательности.

## Acceptance Criteria

- [ ] Original Payload в UI больше не отображает `\n`, `\r`, CR/LF как визуальный шум и переносы строк.
- [ ] Raw original payload в БД/API не мутируется.
- [ ] Original Payload имеет тот же визуальный размер, что Final Payload.
- [ ] Длинный Original Payload скроллится внутри своего блока.
- [ ] Final Payload для mail-derived intake содержит всеобъемлющую summary исходного body письма.
- [ ] Для `TASK` final payload можно применить как полноценную задачу без повторного чтения письма.
- [ ] Добавлен E2E сценарий для Intake Gateway payload readability + summary.
- [ ] Не изменены существующие Flyway migrations.

## После подтверждения пользователя

После проверки и подтверждения пользователем:

1. перевести этот CR в статус `DONE` / `Implemented`;
2. обновить `docs/cr/REGISTRY.md`;
3. обновить `ARCHITECTURE.md` и RFC JavaMemoryService, если контракт final payload будет расширен;
4. закрыть связанный GitHub Issue.
