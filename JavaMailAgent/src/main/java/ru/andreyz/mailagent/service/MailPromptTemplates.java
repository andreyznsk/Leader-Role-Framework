package ru.andreyz.mailagent.service;

public final class MailPromptTemplates {

    public static final String CLASSIFICATION_PROMPT_CODE = "classificationPrompt";
    public static final String LINKING_PROMPT_CODE = "mailLinkingPrompt";

    public static final String DEFAULT_CLASSIFICATION_PROMPT = """
        Ты — ассистент Tech Lead. Проанализируй входящее письмо и верни JSON.

        Письмо:
        От: {{from}}
        Кому: {{recipients}}
        Тема: {{subject}}
        Message-Id: {{messageId}}
        Conversation-Id: {{conversationId}}
        In-Reply-To: {{inReplyTo}}
        Текст:
        {{body}}

        Верни JSON строго в следующем формате (только JSON, без пояснений):
        {
          "type": "<REQUEST|DRAFT|NOISE|CAPTURE|RAG|NOTE>",
          "emailId": "{{emailId}}",
          "note": "<краткое объяснение решения>",
          "taskLine": "<строка для plans/today.md, только для REQUEST, иначе null>",
          "taskTitle": "<заголовок задачи, только для REQUEST, иначе null>",
          "priority": "<LOW|NORMAL|HIGH|CRITICAL, только для REQUEST, иначе null>",
          "sender": "<email отправителя, только для REQUEST, иначе null>",
          "draftPath": "<путь к черновику drafts/..., только для DRAFT, иначе null>",
          "captureText": "<суть письма 1-2 предложения, только для CAPTURE, иначе null>",
          "noteTitle": "<короткий заголовок заметки для /api/notes, только для NOTE, иначе null>",
          "noteText": "<тело заметки для /api/notes, только для NOTE, иначе null>"
        }

        Типы:
        - REQUEST: письмо требует действия от Tech Lead
        - DRAFT: требует ответного письма, нужен черновик
        - NOISE: CI/CD уведомление, реклама, автоматика — не требует действия
        - CAPTURE: сырая заметка для Memory Capture Bot. Используй, когда информация полезна, но не выглядит как оформленный knowledge-документ.
          captureText = краткое изложение сути в 1-2 предложения.
        - RAG: письмо содержит полезную knowledge-информацию для RAG.
          Примеры: договорённости, изменения процессов, архитектурные решения, релизные правила, важные FYI.
          Для RAG в note кратко объясни, почему письмо важно для базы знаний.
        - NOTE: письмо нужно сохранить как обычную текущую заметку в `/api/notes`, а не в RAG и не в capture inbox.
          Используй для материалов "почитать позже", наблюдений, сырых идей и полезных фактов без явного action item.
        
        Для NOTE:
          - noteTitle = короткий предметный заголовок до 200 символов
          - noteText = опциональное тело заметки в 1-3 предложения
            
        ## Важное уточнение: RAG vs NOTE
        
            **NOTE** (не RAG) для следующих категорий писем:
            - Корпоративные анонсы-рассылки (Дни донора, тимбилдинги, HR-рассылки, benefits, офисные анонсы, массовые оповещения без action item)
            - Информация полезна для личного контекста, но НЕ является knowledge-документом (не процесс, не архитектура, не релизное правило)

            **RAG** — только для информации с ценностью долгоживущего knowledge:
            - Договорённости и решения команды
            - Изменения процессов и архитектуры
            - Релизные правила и runbook
            - Важные FYI, влияющие на работу Tech Lead

        Приоритет (только для REQUEST):
        - CRITICAL: "срочно", "asap", "до сегодня"
        - HIGH: "до завтра", "важно", P1/P2 инцидент
        - NORMAL: конкретный дедлайн на этой неделе
        - LOW: без дедлайна или "когда будет время"

        taskLine формат: "- [ ] [PRIORITY] Описание — от sender@example.com"
        """;

    public static final String DEFAULT_LINKING_PROMPT = """
        Ты анализируешь новое входящее письмо и найденный контекст LeaderOS.
        Определи, это новая задача или продолжение существующей.

        Верни только JSON:
        {
          "decision": "<NEW_TASK|LINK_TO_TASK|UPDATE_TASK|IGNORE|REQUEST_CONFIRMATION>",
          "confidence": <0.0-1.0>,
          "targetTaskId": <id или null>,
          "title": "<предлагаемый title или null>",
          "summary": "<краткое решение или null>",
          "reason": "<почему принято это решение>",
          "proposedDescriptionAppend": "<что добавить в описание задачи, только для UPDATE_TASK, иначе null>",
          "matchedSources": ["TASK-42", "NOTICE-5"]
        }

        Правила:
        - Если письмо выглядит как ответ/продолжение по уже существующей задаче, выбирай LINK_TO_TASK.
        - Если в письме есть новый дедлайн, договоренность, риск или существенное обновление по существующей задаче, выбирай UPDATE_TASK.
        - Если это новый action item, выбирай NEW_TASK.
        - Если действий не требуется, выбирай IGNORE.
        - Если контекст неоднозначен, выбирай REQUEST_CONFIRMATION.

        Письмо и контекст:
        {{context}}
        """;

    private MailPromptTemplates() {
    }
}
