# CR-MAIL-BUGFIX-001: MockAgentClient не классифицирует DRAFT по "ответное письмо"

**Дата:** 2026-06-13
**Статус:** Draft
**Тип:** BugFix
**Сервис:** common / JavaMailAgent (local профиль)
**Источник:** `test-runner/reports/TEST-REPORT-2026-06-13.md`, сценарий `JavaMailAgent/test_e2e/06_multiple_emails.md`

---

## Проблема

При отправке письма с текстом "ответное письмо" в теле агент возвращает тип `REQUEST`, а не `DRAFT`.

Ожидаемое поведение: любое письмо, содержащее "ответн" или "черновик" (регистронезависимо), должно классифицироваться как `DRAFT`.

**Воспроизведение:**
```bash
# Отправить письмо с телом "ответное письмо"
curl -s --max-time 10 --url "smtp://172.80.2.1:1025" \
  --mail-from "user@example.com" \
  --mail-rcpt "inbox@example.com" \
  --upload-file - <<'EOF'
From: user@example.com
To: inbox@example.com
Subject: Re: задача

ответное письмо

EOF

# Дождаться poll-цикла (до 90с), проверить классификацию в processed_emails:
docker exec leader-postgres psql -U mail_user -d leader_framework \
  -c "SELECT email_id, type FROM processed_emails ORDER BY id DESC LIMIT 5;"
```

**Ожидается:** `type = 'DRAFT'`
**Фактически:** `type = 'REQUEST'`

---

## Локализация

**Файл:** `common/src/main/java/ru/andreyz/common/agent/MockAgentClient.java`

**Метод `detectMailType()` (строки 150-165):**
```java
private MailType detectMailType(String emailSection) {
    String upper = emailSection.toUpperCase(Locale.ROOT);
    if (upper.contains("ОТВЕТН") || upper.contains("ЧЕРНОВИК")) {
        return MailType.DRAFT;  // ← логика верная
    }
    ...
    return MailType.REQUEST;  // ← default
}
```

**Метод `extractEmailSection()` (строки 145-148):**
```java
private String extractEmailSection(String prompt) {
    int idx = prompt.indexOf("Верни JSON");
    return idx >= 0 ? prompt.substring(0, idx) : prompt;
}
```

**Гипотеза:** `PromptBuilder` формирует prompt, где тело письма располагается **после** строки "Верни JSON" (или это слово отсутствует в prompt). В результате `extractEmailSection` возвращает только header-часть без тела — и `ОТВЕТН` не найдено.

---

## Как проверить гипотезу

```bash
# Добавить временный лог в MockAgentClient.completeMailPrompt():
log.debug("emailSection length={}, contains ОТВЕТН={}", 
    emailSection.length(), 
    emailSection.toUpperCase().contains("ОТВЕТН"));
```

Затем включить DEBUG логирование для `ru.andreyz.common.agent` и наблюдать вывод при обработке письма с "ответное письмо".

---

## Предлагаемый фикс

**Вариант A (консервативный):** проверять оба — section ДО "Верни JSON" и section ПОСЛЕ.
Изменить `extractEmailSection` чтобы возвращать полный prompt если позиция `emailSection` не содержит ключевых слов письма.

**Вариант B (точечный):** проверять `ОТВЕТН` не только в `emailSection`, но и в полном prompt:
```java
private MailType detectMailType(String emailSection) {
    String upper = emailSection.toUpperCase(Locale.ROOT);
    if (upper.contains("ОТВЕТН") || upper.contains("ЧЕРНОВИК")) {
        return MailType.DRAFT;
    }
    // Добавить: проверка тела письма если subject не дал результата
    ...
}
```

**Вариант C (радикальный):** убрать `extractEmailSection` — проверять весь prompt на MailType.

---

## Scope

В scope:
- `common/src/main/java/ru/andreyz/common/agent/MockAgentClient.java` — `extractEmailSection` или `detectMailType`
- юнит-тест в `common/src/test/java/ru/andreyz/common/agent/MockAgentClientTest.java`

Не в scope:
- `PromptBuilder.java` — структура реального prompt к Claude не меняется
- production классификация (только mock)

---

## Acceptance Criteria

```bash
# После фикса:
# 1. Отправить письмо с телом "ответное письмо"
# 2. Дождаться poll-цикла
# 3. Проверить:
docker exec leader-postgres psql -U mail_user -d leader_framework \
  -c "SELECT email_id, type FROM processed_emails WHERE type='DRAFT' ORDER BY id DESC LIMIT 3;"
# Ожидается: строка с type='DRAFT'

# 4. Прогнать сценарий:
# JavaMailAgent/test_e2e/06_multiple_emails.md — Step 7: PASS
```

---

## Связанные CRs

- CR-BUGFIX-001 (BUG-003) — общий CR по mock classifier из прошлого прогона
