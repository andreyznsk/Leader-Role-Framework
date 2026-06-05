# Architecture

Папка для архитектурных артефактов команды.

## Структура

```
architecture/
├── c4/          ← C4 диаграммы (Context, Container, Component)
├── adr/         ← Architecture Decision Records
├── diagrams/    ← Схемы сервисов, интеграций, потоков данных
└── services/    ← Карточки сервисов
```

## Как заполнять

### Автоматически (через агента)
```
Запусти суб-агент arch-analyst.
Прочитай Confluence space: [НАЗВАНИЕ].
Сохрани результаты в workspace/01_services/architecture/
```

### Вручную
Создавай файлы в нужной подпапке по шаблонам ниже.
