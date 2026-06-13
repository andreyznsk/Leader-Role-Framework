# CR-MAIL-BUGFIX-003: Email CAPTURE — captureText содержит mock-ответ вместо оригинала письма

**Компонент:** JavaMailAgent
**Серьёзность:** MEDIUM
**Обнаружено:** IT-08 (2026-06-13)
**Статус:** OPEN

---

## Симптом

После `process-now` capture с `source=email` остаётся в статусе PENDING.
Дополнительный побочный эффект: создаётся лишний PENDING task.

**Лог `process-now`:**
```
Capture 17-44-15.md → TASK (tasks/pending)
```
(ожидалось: → KNOWLEDGE / NOTE / routing по типу CAPTURE)

---

## Root Cause

`MailAgentJob` после классификации `CAPTURE` вызывает `ActionExecutor` с `captureText` из mock-ответа:

```json
{"type":"CAPTURE","captureText":"Mock capture: Ты — ассистент Tech Lead. Проанализируй входящее письмо и верни JSON."}
```

`ActionExecutor` отправляет этот `captureText` в `POST /api/capture`, который сохраняет его в файл `capture-inbox/YYYY-MM-DD/HH-MM-SS.md`. 

Файл содержит:
```
Mock capture: Ты — ассистент Tech Lead. Проанализируй входящее письмо и верни JSON.
```

При повторной классификации через `process-now` — MockAgentClient не находит маркеров типа (RISK/QUESTION/KNOWLEDGE/FYI/etc), возвращает тип **TASK** по умолчанию → capture не закрывается, task создаётся ненамеренно.

---

## Ожидаемое поведение

Файл в `capture-inbox/` должен содержать **исходный текст письма** (subject + body), чтобы при повторной классификации сохранялся корректный тип.

Либо: при `source=email` capture должен сразу обновляться в PROCESSED (без ожидания `process-now`), так как MailAgent уже выполнил классификацию.

---

## Fix Option A — Исправить ActionExecutor: сохранять оригинальный текст письма

В `ActionExecutor` для ветки CAPTURE:
```java
// было: captureText из ответа агента
String text = response.getCaptureText();

// должно быть: subject + body исходного письма
String text = email.getSubject() + "\n" + email.getBody();
```

## Fix Option B — Автоматически PROCESSED для source=email

В `CaptureService.saveFromEmail()`:
```java
capture.setStatus(CaptureStatus.PROCESSED);  // не PENDING
```
Файл в capture-inbox не создавать для email-captures — они уже обработаны агентом.

**Рекомендуется Option B** — email-capture уже прошёл классификацию в MailAgent, повторная классификация через process-now избыточна.
