package ru.andreyz.mailagent.scheduler;

import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.model.Email;

@Component
public class PromptBuilder {

    public String build(Email email) {
        return """
            Ты — ассистент Tech Lead. Проанализируй входящее письмо и верни JSON.

            Письмо:
            От: %s
            Тема: %s
            Текст:
            %s

            Верни JSON строго в следующем формате (только JSON, без пояснений):
            {
              "type": "<REQUEST|DRAFT|NOISE>",
              "emailId": "%s",
              "note": "<краткое объяснение решения>",
              "taskLine": "<строка для plans/today.md, только для REQUEST, иначе null>",
              "taskTitle": "<заголовок задачи, только для REQUEST, иначе null>",
              "priority": "<LOW|NORMAL|HIGH|CRITICAL, только для REQUEST, иначе null>",
              "sender": "<email отправителя, только для REQUEST, иначе null>",
              "draftPath": "<путь к черновику drafts/..., только для DRAFT, иначе null>"
            }

            Типы:
            - REQUEST: письмо требует действия от Tech Lead
            - DRAFT: требует ответного письма, нужен черновик
            - NOISE: CI/CD уведомление, реклама, автоматика — не требует действия

            Приоритет (только для REQUEST):
            - CRITICAL: "срочно", "asap", "до сегодня"
            - HIGH: "до завтра", "важно", P1/P2 инцидент
            - NORMAL: конкретный дедлайн на этой неделе
            - LOW: без дедлайна или "когда будет время"

            taskLine формат: "- [ ] [PRIORITY] Описание — от sender@example.com"
            """.formatted(email.from(), email.subject(), email.body(), email.id());
    }
}
