INSERT INTO mailagent.prompt_templates (code, template)
VALUES (
    'mailLinkingPrompt',
    $$Ты анализируешь новое входящее письмо и найденный контекст LeaderOS.
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
$$
)
ON CONFLICT (code) DO NOTHING;
