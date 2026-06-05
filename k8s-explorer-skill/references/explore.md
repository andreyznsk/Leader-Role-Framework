# Режим: Explore

Исследование одного неймспейса через MCP-Kubernetes. Результат — MD-файл.

---

## Шаг 1 — Подключение и базовый обзор

Используй MCP-инструменты для следующих команд (точные имена инструментов
зависят от твоего MCP-сервера — обычно это `kubectl_get`, `kubectl_describe`,
`kubectl_logs` или единый инструмент `run_kubectl_command`):

```
kubectl get pods -n <namespace> -o wide
kubectl get deployments -n <namespace> -o wide
kubectl get services -n <namespace>
kubectl get ingress -n <namespace>
kubectl get configmaps -n <namespace>
kubectl get events -n <namespace> --sort-by='.lastTimestamp'
kubectl top pods -n <namespace>
```

Если `kubectl top` недоступен (metrics-server не установлен) — пропусти и отметь в MD.

---

## Шаг 2 — Детали по каждому поду

Для каждого пода из списка:

```
kubectl describe pod <pod-name> -n <namespace>
```

Из вывода извлечь:
- **Image** — имя Docker образа (подсказка о репозитории)
- **Env vars** — переменные окружения: URL других сервисов, Kafka, БД, S3
- **Restart count** — количество рестартов
- **Last State** — причина последнего падения (OOMKilled, Error, etc.)
- **Requests/Limits** — resource limits
- **Readiness/Liveness probes** — есть ли

---

## Шаг 3 — Построение карты зависимостей

Из env vars ищи паттерны зависимостей:

| Паттерн в env var | Тип зависимости |
|---|---|
| `*_URL`, `*_HOST`, `*_ADDR` | HTTP/gRPC вызов к другому сервису |
| `KAFKA_*`, `*_BROKER*` | Kafka producer или consumer |
| `*_DB_*`, `POSTGRES_*`, `MYSQL_*` | База данных |
| `REDIS_*`, `*_CACHE_*` | Кэш |
| `S3_*`, `*_BUCKET*` | Объектное хранилище |
| `*_SECRET*`, `*_TOKEN*` | Внешняя аутентификация |

Для каждого сервиса составь:
- `calls` — что вызывает (по env vars)
- `exposes` — какие порты/эндпоинты открывает (из kubectl get svc)

---

## Шаг 4 — Анализ проблем

Пройди по данным и выяви проблемы по категориям:

### Критические (🔴)
- Рестартов > 5 у любого пода
- Статус `CrashLoopBackOff` или `OOMKilled`
- Под в статусе `Pending` больше нескольких минут
- Под без реплик (replicas: 1) для бизнес-сервиса

### Предупреждения (🟡)
- Рестартов 1–5
- Нет readiness probe
- Нет resource limits
- ConfigMap пустой или отсутствует
- События с типом `Warning` в последние 24 часа

### Информационно (🔵)
- Образ использует тег `latest`
- Нет ingress (сервис не доступен извне — это может быть ок)
- Одна реплика для infra-сервиса

---

## Шаг 5 — Классификация сервисов

Для каждого деплоймента определи тип:

- **business** — бизнес-логика (payment-service, order-api, user-service, etc.)
- **infra** — инфраструктура (postgres, redis, kafka, zookeeper, elasticsearch)
- **gateway** — API gateway, nginx, istio ingress
- **worker** — фоновые задачи (consumer, worker, scheduler, cron)
- **unknown** — непонятно

Признаки infra: image содержит `postgres`, `redis`, `kafka`, `rabbit`, `elastic`, `mongo`, `clickhouse`, `nginx`, `envoy`.

---

## Шаг 6 — Запись MD-файла

Сохрани в файл `<namespace>.md`:

```markdown
# Неймспейс: <namespace>

> Исследован: <дата и время>

## Обзор

| Параметр | Значение |
|---|---|
| Подов всего | N |
| Деплойментов | N |
| Сервисов | N |
| Ingress | N |
| Проблем критических | N |
| Проблем предупреждений | N |

## Сервисы

### <service-name> [тип: business/infra/worker/gateway]

| Параметр | Значение |
|---|---|
| Image | ... |
| Реплики | ... |
| Рестартов | ... |
| CPU request/limit | ... |
| Memory request/limit | ... |

**Зависимости (из env vars):**
- calls: [список сервисов которые вызывает]
- kafka topics: [список если есть]
- databases: [список если есть]

**Проблемы:**
- 🔴 ... (если есть)
- 🟡 ... (если есть)

---

## Карта вызовов

```
service-a → service-b (HTTP)
service-a → postgres (DB)
service-b → kafka (producer)
service-c ← kafka (consumer)
```

## Проблемы и риски

### 🔴 Критические

- ...

### 🟡 Предупреждения

- ...

### 🔵 Информационно

- ...

## События (последние Warning)

| Время | Объект | Причина | Сообщение |
|---|---|---|---|
| ... | ... | ... | ... |

## Сырые данные

<details>
<summary>kubectl get pods -o wide</summary>

```
<вывод команды>
```

</details>

<details>
<summary>kubectl get events</summary>

```
<вывод команды>
```

</details>
```

---

## Если MCP недоступен или команда упала

Запиши в MD:

```markdown
> ⚠️ Команда `kubectl <...>` вернула ошибку: <текст ошибки>
```

И продолжай со следующей командой.
