# Playwright for JavaMemoryService

Этот слой нужен для реального browser-driven UI тестирования `JavaMemoryService`.

## Где лежит

- config: `JavaMemoryService/test_e2e/playwright.config.js`
- tests: `JavaMemoryService/test_e2e/tests/*.spec.js`

## Установка

```bash
cd JavaMemoryService/test_e2e
npm install
npx playwright install chromium
```

## Запуск

Сервис должен быть уже поднят.

По умолчанию используется:

```bash
http://127.0.0.1:8082
```

Если сервис поднят на другом порту:

```bash
PLAYWRIGHT_BASE_URL=http://127.0.0.1:8090 npm test
```

Локальные режимы:

```bash
npm test
npm run test:headed
npm run test:ui
npm run codegen -- http://127.0.0.1:8082/ui/today
```

## Что уже покрыто

1. Переход в редактор задачи по клику на название.
2. Сдвиг дедлайна кнопкой `Завтра`.
