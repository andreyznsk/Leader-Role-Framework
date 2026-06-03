# Режим: Summary

Читает все `.md` файлы из папки (каждый — отчёт по одному неймспейсу),
объединяет их в один `report.html`.

---

## Входные данные

```
<папка>/
├── ns-payments.md
├── ns-notifications.md
├── ns-auth.md
└── ... (до 8 файлов)
```

Прочитай все `.md` файлы из папки. Если папка пустая — сообщи пользователю.

---

## Структура HTML-отчёта

Генерируй самодостаточный HTML (один файл, без внешних зависимостей).
Используй встроенный CSS и JS.

### Секции отчёта:

1. **Шапка** — дата генерации, количество неймспейсов, суммарная статистика
2. **Dashboard** — карточки по каждому неймспейсу с цветовым статусом
3. **Карта зависимостей** — граф между неймспейсами (Mermaid или SVG)
4. **Сводная таблица проблем** — все критические и предупреждения по всем NS
5. **Детали по каждому NS** — разворачиваемые секции с содержимым MD

---

## Алгоритм генерации

### 1. Парсинг MD файлов

Из каждого MD извлечь:
- Имя неймспейса (из заголовка `# Неймспейс: <name>`)
- Обзорные цифры (из таблицы Обзор)
- Список сервисов и их типы
- Карту вызовов (секция "Карта вызовов")
- Все проблемы (🔴 🟡 🔵)
- Список предупреждающих событий

### 2. Агрегация

Посчитать по всему кластеру:
- Итого подов, деплойментов, сервисов
- Итого критических проблем
- Итого предупреждений
- Неймспейсы без проблем / с проблемами

### 3. Межнеймспейсные зависимости

Сопоставь `calls` из разных неймспейсов — если service-a в ns-payments
вызывает `user-service`, а user-service живёт в ns-auth — это межнеймспейсная
связь. Отобрази её на общей карте.

---

## HTML шаблон

```html
<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="UTF-8">
  <title>K8s Cluster Report</title>
  <style>
    /* --- Reset & Base --- */
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
           font-size: 14px; color: #1a1a1a; background: #f5f5f5; }

    /* --- Layout --- */
    .container { max-width: 1200px; margin: 0 auto; padding: 24px; }
    header { background: #1a1a2e; color: white; padding: 24px 32px;
             border-radius: 12px; margin-bottom: 24px; }
    header h1 { font-size: 22px; font-weight: 500; }
    header .meta { font-size: 12px; color: #aaa; margin-top: 4px; }

    /* --- Stat cards --- */
    .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px,1fr));
             gap: 12px; margin-bottom: 24px; }
    .stat { background: white; border-radius: 10px; padding: 16px;
            border: 1px solid #e8e8e8; }
    .stat .num { font-size: 28px; font-weight: 500; }
    .stat .lbl { font-size: 12px; color: #666; margin-top: 4px; }
    .stat.danger .num { color: #c0392b; }
    .stat.warn .num { color: #e67e22; }
    .stat.ok .num { color: #27ae60; }

    /* --- NS Cards grid --- */
    .ns-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px,1fr));
               gap: 12px; margin-bottom: 24px; }
    .ns-card { background: white; border-radius: 10px; padding: 16px;
               border: 1px solid #e8e8e8; border-left: 4px solid #ccc; }
    .ns-card.has-critical { border-left-color: #c0392b; }
    .ns-card.has-warning { border-left-color: #e67e22; }
    .ns-card.ok { border-left-color: #27ae60; }
    .ns-card h3 { font-size: 14px; font-weight: 500; margin-bottom: 8px; }
    .ns-card .badges { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 8px; }
    .badge { font-size: 11px; padding: 2px 8px; border-radius: 20px; }
    .badge.crit { background: #fdecea; color: #c0392b; }
    .badge.warn { background: #fef5e7; color: #e67e22; }
    .badge.info { background: #eaf4fb; color: #2471a3; }
    .badge.biz { background: #eaf4fb; color: #1a5276; }
    .badge.infra { background: #f0f0f0; color: #555; }

    /* --- Problems table --- */
    .section { background: white; border-radius: 10px; padding: 20px;
               border: 1px solid #e8e8e8; margin-bottom: 20px; }
    .section h2 { font-size: 16px; font-weight: 500; margin-bottom: 16px;
                  padding-bottom: 8px; border-bottom: 1px solid #eee; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th { text-align: left; padding: 8px 12px; background: #f8f8f8;
         font-weight: 500; color: #555; border-bottom: 1px solid #eee; }
    td { padding: 8px 12px; border-bottom: 1px solid #f0f0f0; vertical-align: top; }
    tr:last-child td { border-bottom: none; }
    tr:hover td { background: #fafafa; }

    /* --- Dependency diagram --- */
    .mermaid-wrap { background: #fafafa; border-radius: 8px; padding: 16px;
                    overflow-x: auto; }

    /* --- Details / NS drill-down --- */
    details { border: 1px solid #eee; border-radius: 8px;
              margin-bottom: 8px; overflow: hidden; }
    summary { padding: 12px 16px; cursor: pointer; font-weight: 500;
              font-size: 13px; background: #f8f8f8; user-select: none; }
    summary:hover { background: #f0f0f0; }
    .detail-body { padding: 16px; }
    .detail-body pre { background: #f4f4f4; padding: 12px; border-radius: 6px;
                       font-size: 12px; overflow-x: auto; white-space: pre-wrap; }

    /* --- Mermaid --- */
    .mermaid { font-size: 13px; }
  </style>
</head>
<body>
<div class="container">

  <header>
    <h1>K8s Cluster Report</h1>
    <div class="meta">Сгенерирован: {{DATE}} · Неймспейсов: {{NS_COUNT}}</div>
  </header>

  <!-- Суммарные метрики -->
  <div class="stats">
    <div class="stat"><div class="num">{{TOTAL_PODS}}</div><div class="lbl">Подов всего</div></div>
    <div class="stat"><div class="num">{{TOTAL_SERVICES}}</div><div class="lbl">Сервисов</div></div>
    <div class="stat danger"><div class="num">{{TOTAL_CRITICAL}}</div><div class="lbl">Критических проблем</div></div>
    <div class="stat warn"><div class="num">{{TOTAL_WARN}}</div><div class="lbl">Предупреждений</div></div>
    <div class="stat ok"><div class="num">{{NS_OK}}</div><div class="lbl">NS без проблем</div></div>
  </div>

  <!-- Карточки неймспейсов -->
  <div class="section">
    <h2>Неймспейсы</h2>
    <div class="ns-grid">
      {{NS_CARDS}}
    </div>
  </div>

  <!-- Сводная таблица проблем -->
  <div class="section">
    <h2>🔴 Критические проблемы</h2>
    <table>
      <thead><tr><th>Неймспейс</th><th>Сервис</th><th>Проблема</th></tr></thead>
      <tbody>{{CRITICAL_ROWS}}</tbody>
    </table>
  </div>

  <div class="section">
    <h2>🟡 Предупреждения</h2>
    <table>
      <thead><tr><th>Неймспейс</th><th>Сервис</th><th>Проблема</th></tr></thead>
      <tbody>{{WARN_ROWS}}</tbody>
    </table>
  </div>

  <!-- Карта зависимостей (Mermaid) -->
  <div class="section">
    <h2>Карта зависимостей между сервисами</h2>
    <div class="mermaid-wrap">
      <pre class="mermaid">
{{MERMAID_GRAPH}}
      </pre>
    </div>
  </div>

  <!-- Детали по каждому NS -->
  <div class="section">
    <h2>Детали по неймспейсам</h2>
    {{NS_DETAILS}}
  </div>

</div>

<script type="module">
  import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.esm.min.mjs';
  mermaid.initialize({ startOnLoad: true, theme: 'neutral' });
</script>
</body>
</html>
```

---

## Генерация Mermaid графа

Из карт вызовов всех MD файлов построй:

```
graph TD
  subgraph ns-payments
    payment-api
    billing-worker
  end
  subgraph ns-auth
    user-service
    auth-api
  end
  payment-api -->|HTTP| user-service
  billing-worker -->|Kafka| notification-service
```

Правила:
- Каждый неймспейс = subgraph
- Стрелки берёшь из секции "Карта вызовов" каждого MD
- Для межнеймспейсных связей — стрелка пересекает subgraph
- Infra-сервисы (postgres, redis, kafka) — отдельный subgraph `infra`

---

## Заглушки если данных нет

Если какая-то секция в MD отсутствует или пустая — не пропускай,
пиши в HTML: `<em>Данные не собраны</em>`

Если проблем нет — пиши: `<em>Проблем не обнаружено ✓</em>`

---

## Сохранение

Сохрани файл как `report.html` в ту же папку где лежат MD файлы.
После сохранения — сообщи пользователю путь к файлу и предложи его открыть.
