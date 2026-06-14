# CR-TEST-004: GitHub Pages Workflow Test

**Дата:** 2026-06-14  
**Статус:** Draft  
**Тип:** TEST  
**Сервис:** TEST / Documentation Workflow  
**Автор:** Андрей Зайцев / ChatGPT

---

## Проблема / Мотивация

Нужно проверить новый workflow хранения артефактов LeaderOS через GitHub:

1. Создавать настоящие `.md` файлы.
2. Хранить их в репозитории с историей изменений.
3. Получать постоянные HTTP-ссылки.
4. Добавлять ссылки в Google Calendar встречу.
5. В будущем публиковать эти документы через GitHub Pages.

Google Drive остаётся Source of Truth по текущей проектной инструкции, но текущий Drive connector не умеет надёжно создавать raw `.md` файлы в нужной папке. Поэтому GitHub используется как технический workaround для документов-as-code.

---

## Решение

Создать CR-файл в репозитории:

```text
Leader-Role-Framework/docs/cr/CR-TEST-004-github-pages-workflow-test.md
```

И HTML-версию для публикации через GitHub Pages:

```text
Leader-Role-Framework/docs/cr/CR-TEST-004-github-pages-workflow-test.html
```

---

## Ожидаемые ссылки

GitHub файл:

```text
https://github.com/andreyznsk/Leader-Role-Framework/blob/master/docs/cr/CR-TEST-004-github-pages-workflow-test.md
```

Raw Markdown:

```text
https://raw.githubusercontent.com/andreyznsk/Leader-Role-Framework/master/docs/cr/CR-TEST-004-github-pages-workflow-test.md
```

GitHub Pages HTML, если Pages включён для репозитория:

```text
https://andreyznsk.github.io/Leader-Role-Framework/cr/CR-TEST-004-github-pages-workflow-test.html
```

---

## Изменения в API

Нет.

---

## Изменения в схеме БД

Нет.

---

## Зависимости от других сервисов

- GitHub connector
- Google Calendar connector
- GitHub Pages, если нужна HTML-публикация

---

## Как тестировать

1. Открыть `.md` файл в GitHub.
2. Открыть raw Markdown ссылку.
3. Если GitHub Pages включён — открыть HTML ссылку.
4. Проверить, что календарная встреча содержит ссылки на GitHub и GitHub Pages.

---

## Результат теста

Статус будет зафиксирован после проверки ссылок пользователем.
