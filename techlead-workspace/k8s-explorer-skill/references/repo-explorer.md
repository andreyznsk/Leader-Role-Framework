# Режим: Repo Explorer

Получает список сервисов из MD-файла неймспейса, извлекает имя репозитория
из Docker image, находит его в Bitbucket Server через MCP, проверяет CD конфиг,
сохраняет `repo-<namespace>.md`.

---

## Совместимый MCP для Bitbucket Server / Data Center

Используй один из этих MCP серверов (self-hosted Bitbucket):

| MCP сервер | Установка | Подходит |
|---|---|---|
| `garc33/bitbucket-server-mcp-server` | `npx @garc33/bitbucket-server-mcp-server` | ✅ рекомендован |
| `guenichone/atlassian-bitbucket-server-mcp` | `npm install + npm start` | ✅ альтернатива |

**Переменные окружения для запуска:**
```
BITBUCKET_URL=https://bitbucket.your-company.ru
BITBUCKET_TOKEN=your-personal-access-token
BITBUCKET_DEFAULT_PROJECT=EXPERTISE
```

**Инструменты которые предоставляет garc33 MCP:**

| Инструмент | Что делает |
|---|---|
| `list_projects` | Список всех проектов в Bitbucket |
| `list_repositories` | Список репо в проекте |
| `browse_repository` | Структура директорий репо |
| `get_file_content` | Содержимое файла (с пагинацией) |
| `search` | Поиск кода и файлов по репозиториям |
| `list_branches` | Ветки репо, включая default branch |
| `list_commits` | История коммитов с фильтрами |
| `list_pull_requests` | PR по состоянию/автору |
| `get_pull_request` | Детали конкретного PR |

---

## Шаг 1 — Парсинг image names из MD неймспейса

Из `<namespace>.md` извлечь все Docker images из секций сервисов.

**Формат image в вашем кластере:**
```
registry/ci1234/ci3456/universal-task:D-01.015.11@sha256:abc123def456
```

**Алгоритм парсинга:**
```
image = "registry/ci1234/ci3456/universal-task:D-01.015.11@sha256:abc123"

# Берём последний сегмент пути до двоеточия
repo_slug = image.split("/")[-1].split(":")[0]
# → "universal-task"

# Берём тег (версию сборки)
tag = image.split(":")[-2].split("/")[-1]  # между последним / и @
# → "D-01.015.11"

# Берём sha256 хэш (коммит или digest образа)
digest = image.split("sha256:")[-1][:12] if "sha256:" in image else None
# → "abc123def456"
```

Итог для каждого сервиса:
```
pod_name:  payment-api
repo_slug: universal-task
tag:       D-01.015.11
digest:    abc123def456
```

---

## Шаг 2 — Поиск репо в Bitbucket

### 2.1 Найти репо по slug
```
list_repositories --project "EXPERTISE"
```
Из списка найти репо где `slug == repo_slug` или `name == repo_slug`.

Если не найдено в EXPERTISE — попробовать поиск:
```
search --query "{repo_slug}" --type "file" --limit 5
```
Или перебрать другие проекты через `list_projects`.

Записать в MD:
- ✅ найдено: `projects/EXPERTISE/repos/universal-task`
- ❌ не найдено: указать что искали и где

### 2.2 Получить структуру репо
```
browse_repository --project "EXPERTISE" --repository "{repo_slug}"
```

Из списка файлов корня определить технологический стек:
- `pom.xml` → Java/Maven
- `build.gradle` → Java/Gradle
- `package.json` → Node.js
- `go.mod` → Go
- `requirements.txt` / `pyproject.toml` → Python
- `Dockerfile` → есть контейнеризация
- `Jenkinsfile` → CI через Jenkins
- `bitbucket-pipelines.yml` → CI через Bitbucket Pipelines

### 2.3 Прочитать ключевые файлы

**Jenkinsfile** (если есть):
```
get_file_content --project "EXPERTISE" --repository "{repo_slug}" --filePath "Jenkinsfile"
```
Извлечь: stages пайплайна, параметры деплоя, целевые окружения.

**Dockerfile** (если есть):
```
get_file_content --project "EXPERTISE" --repository "{repo_slug}" --filePath "Dockerfile"
```
Извлечь: базовый образ, порт, entrypoint.

**pom.xml или build.gradle** (первые 50 строк):
```
get_file_content --project "EXPERTISE" --repository "{repo_slug}" --filePath "pom.xml" --limit 50
```
Извлечь: artifactId, version, groupId.

### 2.4 Получить последние коммиты
```
list_commits --project "EXPERTISE" --repository "{repo_slug}" --limit 5
```
Извлечь: дату последнего коммита, автора, сообщение.

### 2.5 Получить ветки
```
list_branches --project "EXPERTISE" --repository "{repo_slug}"
```
Найти default branch (обычно `main` или `master`).

---

## Шаг 3 — Поиск CD конфига

CD хранится в одном общем репо (slug задаётся в config.json).

### 3.1 Найти конфиг для сервиса в CD репо
```
search --query "{repo_slug}" --type "file" --project "EXPERTISE" --repository "{CD_REPO_SLUG}"
```

Или просмотреть структуру CD репо:
```
browse_repository --project "EXPERTISE" --repository "{CD_REPO_SLUG}"
```

Типичные паттерны путей:
```
{repo_slug}/values.yaml
{repo_slug}/values-prod.yaml
namespaces/{namespace}/{repo_slug}/values.yaml
deploy/{repo_slug}/
helm/{repo_slug}/
```

### 3.2 Прочитать найденный values.yaml
```
get_file_content --project "EXPERTISE" --repository "{CD_REPO_SLUG}" --filePath "{path}/values.yaml"
```

Из values.yaml извлечь:
- `image.tag` или `imageTag` → версия которая должна быть в кластере
- `replicaCount` → реплики в CD vs реальные в кластере
- `namespace` → куда деплоится

---

## Шаг 4 — Сравнение: кластер vs репозиторий vs CD

| Проверка | Кластер (из NS.md) | CD values.yaml | Статус |
|---|---|---|---|
| Версия образа | `D-01.015.11` | `D-01.015.11` | ✅ / ❌ |
| Replicas | 2 | 2 | ✅ / ❌ |

**Сигналы проблем:**
- 🔴 Версия в CD не совпадает с кластером → ручное вмешательство или упавший деплой
- 🔴 Репо не найдено → образ из неизвестного источника
- 🟡 Нет Jenkinsfile → ручной CI
- 🟡 Нет CD конфига для сервиса → ручной деплой
- 🟡 Последний коммит старше 30 дней → репо заброшено или стабильно
- 🔵 Нет Dockerfile → нестандартная сборка

---

## Шаг 5 — Запись repo-MD файла

Сохрани `repo-<namespace>.md`:

```markdown
# Репозитории: <namespace>

> Исследован: <дата>
> Bitbucket: https://bitbucket.company.ru / project: EXPERTISE
> CD репо: <cd-repo-slug>

## Сводка

| Параметр | Значение |
|---|---|
| Сервисов исследовано | N |
| Репо найдено | N |
| Репо не найдено | N |
| Расхождений версий | N |

---

## Сервисы

### payment-api

| Параметр | Значение |
|---|---|
| Docker image | `registry/.../universal-task:D-01.015.11@sha256:abc123` |
| Repo slug | `universal-task` |
| Bitbucket URL | https://bitbucket.company.ru/projects/EXPERTISE/repos/universal-task |
| Стек | Java/Maven, Dockerfile ✅, Jenkinsfile ✅ |
| Последний коммит | `a1b2c3` — Иван Иванов — "fix: timeout handling" — 2 дня назад |
| Default branch | `main` |
| Версия в кластере | `D-01.015.11` |
| Версия в CD | `D-01.015.11` ✅ |
| Replicas: кластер / CD | 2 / 2 ✅ |
| CD конфиг | `cd-configs/ns-payments/universal-task/values.yaml` |

**Цепочка pod → repo → CD:**
```
pod: payment-api (ns-payments)
  └── image: universal-task:D-01.015.11
        └── repo: EXPERTISE/universal-task [bitbucket link]
              └── CD: cd-configs/.../values.yaml → tag: D-01.015.11 ✅
```

**Проблемы:** нет ✅

---

### auth-service

| Параметр | Значение |
|---|---|
| Docker image | `registry/.../auth-svc:D-01.010.00@sha256:xyz` |
| Repo slug | `auth-svc` |
| Bitbucket URL | ❌ не найдено в EXPERTISE |
| CD конфиг | ❌ не найдено |

**Проблемы:**
- 🔴 Репо `auth-svc` не найден в Bitbucket project EXPERTISE

---

## Проблемы

### 🔴 Критические
- `auth-service`: репо не найден в Bitbucket

### 🟡 Предупреждения
- `billing-worker`: версия в CD (D-01.014.00) ≠ кластер (D-01.015.11)

### 🔵 Информационно
- `cache-warmer`: последний коммит 47 дней назад
```

---

## Параметры — спросить один раз, сохранить в config.json

```json
{
  "BITBUCKET_BASE_URL": "https://bitbucket.company.ru",
  "BB_PROJECT_KEY": "EXPERTISE",
  "CD_REPO_SLUG": "cd-configs",
  "REPORTS_DIR": "/home/claude/k8s-reports/"
}
```

Сохранить в `<REPORTS_DIR>/config.json` после первого ввода.
При последующих запусках — читать из файла, не спрашивать снова.

---

## Fallback: если MCP недоступен

Записать в MD прямые URL для ручной проверки:
```markdown
> ⚠️ MCP Bitbucket недоступен. Проверь вручную:
> Репо: https://bitbucket.company.ru/projects/EXPERTISE/repos/{repo_slug}
> REST API: https://bitbucket.company.ru/rest/api/1.0/projects/EXPERTISE/repos/{repo_slug}
```
