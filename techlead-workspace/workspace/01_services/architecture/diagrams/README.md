# Схемы и диаграммы

Здесь хранятся схемы которые не вписываются в C4 модель.

## Типы диаграмм

| Папка/Файл | Что содержит |
|------------|-------------|
| `data-flows/` | Потоки данных между сервисами |
| `sequence/` | Sequence диаграммы для сложных сценариев |
| `deployment/` | Схемы деплоя (Kubernetes, окружения) |

## Шаблоны

### Sequence диаграмма (Mermaid)
```mermaid
sequenceDiagram
  participant U as Пользователь
  participant A as Service A
  participant B as Service B
  U->>A: POST /api/request
  A->>B: gRPC call
  B-->>A: response
  A-->>U: 200 OK
```

### Deployment диаграмма (Mermaid)
```mermaid
graph TD
  subgraph "Kubernetes Cluster"
    subgraph "Namespace: prod"
      P1[Pod: service-a x3]
      P2[Pod: service-b x2]
    end
    subgraph "Namespace: infra"
      DB[(PostgreSQL)]
      K([Kafka])
    end
  end
  LB[Load Balancer] --> P1
  P1 --> P2
  P2 --> DB
  P1 --> K
```
