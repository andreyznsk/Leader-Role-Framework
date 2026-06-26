package ru.andreyz.mailagent.service;

public final class MailPromptTemplates {

    public static final String CLASSIFICATION_PROMPT_CODE = "classificationPrompt";

    public static final String DEFAULT_CLASSIFICATION_PROMPT = """
        Ты — ассистент Tech Lead. Проанализируй входящее письмо и верни JSON.

        Письмо:
        От: {{from}}
        Тема: {{subject}}
        Текст:
        {{body}}

        Верни JSON строго в следующем формате (только JSON, без пояснений):
        {
          "type": "<REQUEST|DRAFT|NOISE|CAPTURE|NOTICE|NOTE>",
          "emailId": "{{emailId}}",
          "note": "<краткое объяснение решения>",
          "taskLine": "<строка для plans/today.md, только для REQUEST, иначе null>",
          "taskTitle": "<заголовок задачи, только для REQUEST, иначе null>",
          "priority": "<LOW|NORMAL|HIGH|CRITICAL, только для REQUEST, иначе null>",
          "sender": "<email отправителя, только для REQUEST, иначе null>",
          "draftPath": "<путь к черновику drafts/..., только для DRAFT, иначе null>",
          "captureText": "<суть письма 1-2 предложения, только для CAPTURE, иначе null>",
          "noteText": "<текст заметки для /api/notes, только для NOTE, иначе null>"
        }

        Типы:
        - REQUEST: письмо требует действия от Tech Lead
        - DRAFT: требует ответного письма, нужен черновик
        - NOISE: CI/CD уведомление, реклама, автоматика — не требует действия
        - CAPTURE: сырая заметка для Memory Capture Bot. Используй, когда информация полезна, но не выглядит как оформленный knowledge-документ.
          captureText = краткое изложение сути в 1-2 предложения.
        - NOTICE: письмо содержит полезную knowledge-информацию для RAG.
          Примеры: договорённости, изменения процессов, архитектурные решения, релизные правила, важные FYI.
          Для NOTICE в note кратко объясни, почему письмо важно для базы знаний.
        - NOTE: письмо нужно сохранить как обычную текущую заметку в `/api/notes`, а не в RAG и не в capture inbox.
          Используй для материалов "почитать позже", наблюдений, сырых идей и полезных фактов без явного action item.
          noteText = короткая полезная заметка в 1-3 предложения.

        Приоритет (только для REQUEST):
        - CRITICAL: "срочно", "asap", "до сегодня"
        - HIGH: "до завтра", "важно", P1/P2 инцидент
        - NORMAL: конкретный дедлайн на этой неделе
        - LOW: без дедлайна или "когда будет время"

        taskLine формат: "- [ ] [PRIORITY] Описание — от sender@example.com"
        """;

    private MailPromptTemplates() {
    }
}
