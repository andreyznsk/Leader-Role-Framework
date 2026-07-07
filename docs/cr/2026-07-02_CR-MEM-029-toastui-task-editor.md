# CR-MEM-029: WYSIWYG-редактор описания задачи (Toast UI Editor)

**Дата:** 2026-07-02
**Статус:** Implemented
**Сервис:** JavaMemoryService
**Зависимости:** CR-MEM-023 (task-edit control panel), CR-MEM-015 (tsvector search), CR-MEM-030 (attachments — для paste-to-upload изображений)

## Проблема / Мотивация
Описание задачи редактируется через голый `<textarea>` в `task-edit.html`. Нет форматирования (заголовки, списки, таблицы, code blocks), превью ограничено самодельным извлечением URL. При этом бэкенд уже хранит Markdown (`task_descriptions.content_md`, V14) — потенциал не используется.

## Решение
Заменить textarea на **Toast UI Editor** в режиме WYSIWYG:
- Toast UI редактирует визуально, но сериализуется в Markdown → `content_md` остаётся source of truth, API `PUT /api/tasks/{id}/description` не меняется.
- tsvector-индексация (CR-MEM-015) и экспорт `/description/export-md` продолжают работать без изменений.
- **Только WYSIWYG-режим**: переключатель Markdown/WYSIWYG отключён (`hideModeSwitch: true`), `initialEditType: 'wysiwyg'`. Markdown-режим редактора пользователю не доступен — это единственный способ редактирования в UI (сырой Markdown по-прежнему доступен через `export-md`).
- **Ассеты self-hosted**: `toastui-editor-all.min.js` + `toastui-editor.min.css` кладём в `src/main/resources/static/vendor/toastui/`. Никаких CDN во время выполнения (см. мотивацию CR-MEM-008 offline fallback) — файлы скачаны один раз из официального CDN NHN на этапе разработки и закоммичены как vendor-ассеты (npm-сборка `@toast-ui/editor` для browser-скрипта не подходит: там prosemirror-* вынесены как внешние зависимости и `toastui.Editor` не инициализируется).
- Тёмная тема: подключить `toastui-editor-dark.min.css`. Общая тема UI всегда тёмная (`data-bs-theme="dark"` захардкожен во всех шаблонах, переключателя тем в проекте нет) — синхронизация не требуется.
- Хук `addImageBlobHook`: вставка/drag-drop картинки → upload через API вложений (CR-MEM-030) → в Markdown вставляется `![](/api/tasks/{id}/attachments/{aid}/content)`.
- Сохранение: кнопка Save шлёт `editor.getMarkdown()` в существующий PUT endpoint (`text/plain` body).

## Изменения в API
Нет. Используются существующие endpoints `GET/PUT /api/tasks/{id}/description`.

## Изменения в схеме БД
Нет.

## Изменения в UI
| Файл | Изменение |
|------|-----------|
| `templates/task-edit.html` | textarea → `<div id="editor">` + init Toast UI (initialEditType: wysiwyg, hideModeSwitch: true — единственный режим) |
| `static/vendor/toastui/` | новые ассеты (js, css, dark css) |
| `static/style.css` | стилевые override под JetBrains Mono / тёмную тему |

## Зависимости от других сервисов
Нет.

## Как тестировать
```
1. Открыть /ui/tasks/{id}/edit — редактор загружается без сети (DevTools offline)
2. Набрать текст с заголовком, списком, таблицей, code block → Save
3. GET /api/tasks/{id}/description (Accept: text/plain) → корректный Markdown
4. Перезагрузить страницу → контент восстановлен в WYSIWYG
5. Глобальный поиск (/ui/search) находит текст из отформатированного описания
6. export-md выгружает валидный .md
```
