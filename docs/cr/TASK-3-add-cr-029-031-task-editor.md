# TASK-3: Добавить CR-MEM-029/030/031 (task editor, attachments, links)

**Дата:** 2026-07-02
**Контекст:** master @ 3604e0e (после PR #68)

> **Примечание:** исходно CR были черновиками под номерами 028/029/030, но
> CR-MEM-028 оказался уже занят (`2026-07-02_CR-MEM-028-live-badge-polling.md`,
> DONE). Коллизию разрешили ренумерацией на 029/030/031 — файлы и все
> внутренние ссылки (`Зависимости:`) обновлены.

## Шаги

1. Скопировать три файла в `docs/cr/`:
   - `2026-07-02_CR-MEM-029-toastui-task-editor.md`
   - `2026-07-02_CR-MEM-030-task-attachments.md`
   - `2026-07-02_CR-MEM-031-task-links.md`

2. Обновить `docs/cr/REGISTRY.md` — добавить в секцию MEM после CR-MEM-028:

```markdown
| CR-MEM-029        | [2026-07-02_CR-MEM-029-toastui-task-editor.md](2026-07-02_CR-MEM-029-toastui-task-editor.md) | Draft | 2026-07-02 |
| CR-MEM-030        | [2026-07-02_CR-MEM-030-task-attachments.md](2026-07-02_CR-MEM-030-task-attachments.md) | Draft | 2026-07-02 |
| CR-MEM-031        | [2026-07-02_CR-MEM-031-task-links.md](2026-07-02_CR-MEM-031-task-links.md) | Draft | 2026-07-02 |
```

3. Обновить строку следующего свободного номера:
   `_Следующий свободный номер в MEM серии: **CR-MEM-032**_`

4. Commit: `docs: add CR-MEM-029/030/031 (task editor, attachments, task links)`

## Порядок реализации (рекомендация)

`030 → 029 → 031`: сначала attachments (бэкенд, независим), затем редактор
(использует upload-хук из 030), затем связи (чистая фича поверх control-панели).

## Открытые вопросы

- Хотим ли индексировать текстовые вложения (pdf/md) в RAG? → потенциальный CR-RAG follow-up
- Бейдж связей на карточках /ui/today — включать в 031 или фаза 2?
