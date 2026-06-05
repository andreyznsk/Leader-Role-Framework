# 🧠 Tech Lead Agent Framework

Проект для входа в роль Tech Lead с помощью Claude Code.

## Быстрый старт

```bash
# 1. Установи Claude Code если ещё нет
npm install -g @anthropic-ai/claude-code

# 2. Перейди в папку проекта
cd techlead-workspace

# 3. Настрой токены в .mcp.json (замени YOUR_* на реальные значения)
# Получить токен Confluence/Jira: https://id.atlassian.com/manage-profile/security/api-tokens
# Получить GitHub токен: https://github.com/settings/tokens

# 4. Запусти
claude
```

## Структура проекта

```
techlead-workspace/
│
├── CLAUDE.md                    ← Главный промпт агента (не трогай)
├── .mcp.json                    ← MCP серверы (НАСТРОЙ ТОКЕНЫ)
│
├── .claude/
│   ├── settings.json            ← Permissions и настройки
│   ├── agents/                  ← Суб-агенты
│   │   ├── arch-analyst.md      ← Строит карту архитектуры
│   │   ├── risk-scanner.md      ← Ищет риски в Jira
│   │   ├── doc-writer.md        ← Создаёт документы
│   │   └── signal-filter.md     ← Отделяет сигнал от шума
│   └── commands/                ← Slash-команды
│       ├── standup.md           ← /standup
│       └── week-review.md       ← /week-review
│
├── skills/                      ← Скиллы (промпты под задачи)
│   ├── arch-mapper.md
│   ├── people-mapper.md
│   ├── release-prep.md
│   ├── incident-playbook.md
│   ├── daily-journal.md
│   └── risk-scan.md
│
└── workspace/                   ← Твой второй мозг (сюда агент пишет)
    ├── 00_people/               ← Stakeholder Map
    ├── 01_services/             ← Карта архитектуры
    ├── 02_processes/            ← Release flow, playbook
    ├── 03_incidents/            ← Постмортемы
    ├── 04_releases/             ← Release Notes, чеклисты
    ├── 05_questions/            ← Открытые вопросы
    ├── 06_decisions/            ← ADR
    ├── 07_risks/                ← Operational risks
    └── 08_daily_journal/        ← Дневник
```

## Команды агенту

### Понять архитектуру (Day 1)
```
Используй скилл из skills/arch-mapper.md.
Прочитай через Confluence MCP space: [НАЗВАНИЕ].
Построй карту сервисов команды.
```

### Найти риски (Day 1-2)
```
Запусти суб-агент risk-scanner.
Jira проект: [PROJECT_KEY].
Найди все инциденты за последние 90 дней.
```

### Stakeholder Map (Day 3)
```
Используй скилл из skills/people-mapper.md.
Я расскажу о людях в команде: [описание]
```

### Подготовить релиз
```
Используй скилл из skills/release-prep.md.
Задачи для релиза: [список]
```

### Дневник
```
Используй скилл из skills/daily-journal.md.
Вот мои наблюдения за сегодня: [описание дня]
```

### Slash-команды
```
/standup       ← подготовка к стендапу
/week-review   ← итоги недели
```

## Настройка MCP токенов

Открой `.mcp.json` и замени:

| Placeholder | Где взять |
|-------------|-----------|
| `YOUR_COMPANY.atlassian.net` | URL вашего Jira/Confluence |
| `YOUR_EMAIL@company.ru` | Твой email в Atlassian |
| `YOUR_CONFLUENCE_API_TOKEN` | https://id.atlassian.com/manage-profile/security/api-tokens |
| `YOUR_JIRA_API_TOKEN` | Тот же токен что и Confluence |
| `YOUR_GITHUB_TOKEN` | https://github.com/settings/tokens (repo, read:org) |

## Первая неделя по плану

| День | Задача агенту | Что получишь |
|------|--------------|--------------|
| Day 1 | arch-mapper + risk-scanner | Карта системы + топ-10 рисков |
| Day 2 | incident-playbook (интервью с TL) | Emergency Playbook |
| Day 3 | people-mapper (после встреч) | Stakeholder Map |
| Day 4 | signal-filter (список задач) | Noise vs Signal |
| Day 5 | release-prep | Готовые артефакты |
| Day 6-7 | /week-review | Рефлексия + план |
