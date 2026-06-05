---
name: k8s-explorer
description: >
  Исследует Kubernetes кластер и Bitbucket репозитории. Три режима работы:
  
  1. explore <namespace>   — исследует NS через MCP-Kubernetes, сохраняет <namespace>.md
  2. repos <namespace>     — по MD файлу NS находит репо в Bitbucket и CD конфиги, сохраняет repo-<namespace>.md
  3. summary <папка>       — читает все MD файлы, строит итоговый report.html

  Используй когда пользователь хочет:
  - разобраться что работает в кластере
  - найти какой репозиторий соответствует поду
  - понять связь pod → image → repo → CD
  - найти расхождения между кластером и CD конфигами
  - получить HTML отчёт по всему кластеру

compatibility:
  mcp_required:
    - mcp-kubernetes   (для explore режима)
    - mcp-bitbucket    (для repos режима)
  mcp_optional: []
---

# K8s Explorer Skill

## Три режима

| Команда | Что делает | Читать |
|---|---|---|
| `explore <ns>` | Исследует неймспейс в K8s | references/explore.md |
| `repos <ns>` | Находит репо и CD конфиги | references/repo-explorer.md |
| `summary <папка>` | Строит HTML отчёт | references/summary.md |

---

## Определение режима из запроса пользователя

**explore:** "исследуй ns-payments", "посмотри неймспейс X", "что в X?", "собери данные по X"

**repos:** "найди репо для ns-payments", "свяжи поды с репозиториями", "проверь CD конфиги",
"какой репо у сервиса X", "repos ns-payments"

**summary:** "собери отчёт", "summary по папке", "объедини все MD", "итоговый html"

**Полный цикл по одному NS** (если пользователь говорит "полностью исследуй X"):
1. explore X → сохраняет X.md
2. repos X   → читает X.md, сохраняет repo-X.md
3. Предлагает запустить summary когда все NS готовы

**Полный цикл по всем NS** ("исследуй все неймспейсы"):
- Последовательно: explore NS1 → repos NS1 → explore NS2 → repos NS2 → ...
- В конце автоматически предлагает summary

---

## Установка MCP для Bitbucket Server / DC

Официальный Atlassian Rovo MCP поддерживает только Bitbucket Cloud.
Для self-hosted Bitbucket Server / Data Center используй:

```json
{
  "mcpServers": {
    "bitbucket-server": {
      "command": "npx",
      "args": ["@garc33/bitbucket-server-mcp-server"],
      "env": {
        "BITBUCKET_URL": "https://bitbucket.your-company.ru",
        "BITBUCKET_TOKEN": "your-personal-access-token",
        "BITBUCKET_DEFAULT_PROJECT": "EXPERTISE"
      }
    }
  }
}
```

Personal Access Token создаётся в Bitbucket:
`Profile → Manage Account → Personal Access Tokens → Create`
Нужны права: `Repositories: Read`, `Projects: Read`.

---

## Рабочая папка

По умолчанию: `/home/claude/k8s-reports/`

Структура после полного прогона:
```
k8s-reports/
├── config.json          ← параметры BB и CD (создаётся при первом repos)
├── ns-payments.md       ← k8s данные
├── repo-ns-payments.md  ← bitbucket + CD данные
├── ns-auth.md
├── repo-ns-auth.md
├── ...
└── report.html          ← итоговый отчёт
```

---

## Прогресс вслух

На каждом шаге сообщай пользователю что происходит:
- "🔍 Собираю поды в ns-payments..."
- "📦 Парсю image names..."
- "🔗 Ищу репо universal-task в Bitbucket..."
- "📋 Проверяю CD конфиг..."
- "✅ ns-payments готов → сохранён в ns-payments.md"

---

## Важные принципы

- **Не останавливайся на ошибках** — если один сервис не нашёлся, пиши в MD и иди дальше
- **Параметры BB спроси один раз** — сохрани в config.json, не спрашивай повторно
- **Не читай весь код репо** — только метаданные, структуру файлов, Jenkinsfile, values.yaml
- **Фиксируй расхождения** — версия в CD vs версия в кластере это ключевая ценность
