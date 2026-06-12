# RFC: common — Общий модуль LeaderOS

**Статус:** Draft
**Дата:** 2026-06-12
**Автор:** Андрей Зайцев
**Модуль:** `common`
**Запускать Claude Code из:** `Leader-Role-Framework/`

---

## Контекст системы
Перед работой читай `ARCHITECTURE.md`.

---

## 1. Проблема / Мотивация

Три сервиса — JavaMailAgent, JavaMemoryService, JavaRagService — независимо
реализуют одно и то же: вызов LLM через `claude --print` subprocess.

Дублирование:
- `ClaudeRunnerImpl` в JavaMailAgent
- `MockClaudeRunner` в JavaMailAgent
- `CaptureClassifierAgent` в JavaMemoryService
- `MockCaptureClassifierAgent` в JavaMemoryService

Проблемы:
- Хочется переключить LLM — нужно менять код в каждом сервисе отдельно
- Нет единого места для настройки таймаута, логирования, парсинга ответа
- Добавить Ollama или GigaChat — дублировать Spring AI интеграцию в каждом сервисе

**Решение:** вынести LLM-слой в отдельный Maven-модуль `common`.

---

## 2. Концепция

```
Каждый сервис строит промпт сам  →  передаёт строку в AgentClient  →  получает строку
                                            ↓
                              common выбирает реализацию по конфигу
```

Промпт-билдеры остаются в каждом сервисе — это предметная логика.
Вызов LLM — единственное место в `common`.

Переключение провайдера — одна строка в `application.yml`:
```yaml
agent:
  provider: claude   # claude | mock | ollama | gigachat
```

---

## 3. Место в архитектуре

```
Leader-Role-Framework/
├── common/                          ← новый модуль (plain JAR, не Boot app)
│   └── src/main/java/ru/andreyz/common/
│       ├── agent/
│       │   ├── AgentClient.java              ← интерфейс
│       │   ├── ClaudeProcessAgentClient.java ← claude --print subprocess
│       │   ├── MockAgentClient.java          ← детерминированный mock
│       │   ├── OllamaAgentClient.java        ← Spring AI → Ollama
│       │   └── GigaChatAgentClient.java      ← Spring AI → GigaChat
│       └── config/
│           └── AgentClientConfig.java        ← @ConditionalOnProperty
│
├── JavaMailAgent/      ← зависит от common
├── JavaMemoryService/  ← зависит от common
└── JavaRagService/     ← зависит от common (будущее)
```

---

## 4. Maven координаты

```xml
<groupId>ru.andreyz</groupId>
<artifactId>common</artifactId>
<version>1.0.0</version>
<packaging>jar</packaging>  <!-- НЕ fat-jar, НЕ Spring Boot app -->
```

---

## 5. `AgentClient` — интерфейс

```java
package ru.andreyz.common.agent;

/**
 * Единственный контракт для вызова LLM в LeaderOS.
 * Каждый сервис строит промпт сам, результат — сырая строка от модели.
 */
public interface AgentClient {

    /**
     * Отправить промпт в LLM, вернуть сырой текстовый ответ.
     * Парсинг JSON — ответственность вызывающего кода.
     *
     * @param prompt готовый промпт (результат работы PromptBuilder сервиса)
     * @return сырая строка ответа модели
     * @throws AgentException если LLM недоступен, таймаут или ошибка subprocess
     */
    String complete(String prompt) throws AgentException;
}
```

```java
package ru.andreyz.common.agent;

public class AgentException extends RuntimeException {
    public AgentException(String message) { super(message); }
    public AgentException(String message, Throwable cause) { super(message, cause); }
}
```

---

## 6. Реализации

### 6.1 `ClaudeProcessAgentClient` — `claude --print` subprocess

Активен при: `agent.provider=claude` (default).

```java
package ru.andreyz.common.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class ClaudeProcessAgentClient implements AgentClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeProcessAgentClient.class);

    private final int timeoutMinutes;

    public ClaudeProcessAgentClient(
            @Value("${agent.timeout-minutes:5}") int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }

    @PostConstruct
    public void init() {
        log.info("✅ AgentClient: claude --print subprocess (timeout={}m)", timeoutMinutes);
    }

    @Override
    public String complete(String prompt) throws AgentException {
        try {
            ProcessBuilder pb = new ProcessBuilder("claude", "--print");
            pb.redirectErrorStream(false);
            Process process = pb.start();

            try (var writer = new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(prompt);
            }

            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new AgentException("claude --print timed out after " + timeoutMinutes + "m");
            }

            String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            log.debug("Claude raw output ({} chars)", output.length());
            return output;

        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException("claude --print failed: " + e.getMessage(), e);
        }
    }
}
```

---

### 6.2 `MockAgentClient` — детерминированный mock для тестов

Активен при: `agent.provider=mock`.

```java
package ru.andreyz.common.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

public class MockAgentClient implements AgentClient {

    private static final Logger log = LoggerFactory.getLogger(MockAgentClient.class);

    /**
     * Optional fixed response. If blank, MockAgentClient classifies supported
     * mail/capture prompts with deterministic keyword-based rules.
     */
    private final String fixedResponse;

    public MockAgentClient(
            @Value("${agent.mock.response:}") String fixedResponse) {
        this.fixedResponse = fixedResponse;
    }

    @PostConstruct
    public void init() {
        log.warn("⚠️  AgentClient: MOCK — реальный LLM не вызывается");
        if (!fixedResponse.isBlank()) {
            log.warn("agent.mock.response = {}", fixedResponse);
        }
    }

    @Override
    public String complete(String prompt) {
        if (!fixedResponse.isBlank()) {
            log.debug("MockAgentClient returning fixed response");
            return fixedResponse;
        }
        // mail prompt -> AgentResponse JSON
        // capture prompt -> ClassifiedCapture JSON array
        return classifyPrompt(prompt);
    }
}
```

> **Важно:** `MockAgentClient` поддерживает два режима. Если задан
> `agent.mock.response`, возвращается фиксированная строка. Если значение пустое,
> mock классифицирует поддерживаемые prompt-ы по keyword-based правилам,
> перенесённым из бывших `MockClaudeRunner` и `MockCaptureClassifierAgent`.

---

### 6.3 `OllamaAgentClient` — Spring AI → Ollama

Активен при: `agent.provider=ollama`.

```java
package ru.andreyz.common.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

public class OllamaAgentClient implements AgentClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaAgentClient.class);

    private final ChatClient chatClient;

    public OllamaAgentClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostConstruct
    public void init() {
        log.info("✅ AgentClient: Ollama (Spring AI ChatClient)");
    }

    @Override
    public String complete(String prompt) throws AgentException {
        try {
            return chatClient.prompt(prompt).call().content();
        } catch (Exception e) {
            throw new AgentException("Ollama call failed: " + e.getMessage(), e);
        }
    }
}
```

Конфигурация в `application.yml`:
```yaml
agent:
  provider: ollama

spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: qwen2.5:7b       # или любая другая модель
        options:
          temperature: 0.0
          top-p: 1.0
```

---

### 6.4 `GigaChatAgentClient` — Spring AI → GigaChat

Активен при: `agent.provider=gigachat`.

```java
package ru.andreyz.common.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

public class GigaChatAgentClient implements AgentClient {

    private static final Logger log = LoggerFactory.getLogger(GigaChatAgentClient.class);

    private final ChatClient chatClient;

    public GigaChatAgentClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostConstruct
    public void init() {
        log.info("✅ AgentClient: GigaChat (Spring AI ChatClient)");
    }

    @Override
    public String complete(String prompt) throws AgentException {
        try {
            return chatClient.prompt(prompt).call().content();
        } catch (Exception e) {
            throw new AgentException("GigaChat call failed: " + e.getMessage(), e);
        }
    }
}
```

Конфигурация в `application.yml`:
```yaml
agent:
  provider: gigachat

spring:
  ai:
    gigachat:
      credentials: ${GIGACHAT_CREDENTIALS}
      scope: GIGACHAT_API_CORP   # или GIGACHAT_API_PERS
```

---

## 7. `AgentClientConfig` — фабрика бинов

```java
package ru.andreyz.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.andreyz.common.agent.*;

@Configuration
public class AgentClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentClientConfig.class);

    // ── claude --print (default) ────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "agent.provider", havingValue = "claude", matchIfMissing = true)
    public AgentClient claudeAgentClient(
            @Value("${agent.timeout-minutes:5}") int timeoutMinutes) {
        log.info("AgentClient provider: claude --print");
        return new ClaudeProcessAgentClient(timeoutMinutes);
    }

    // ── mock ────────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "agent.provider", havingValue = "mock")
    public AgentClient mockAgentClient(
            @Value("${agent.mock.response:{\"type\":\"NOISE\",\"note\":\"mock\"}}") String fixedResponse) {
        log.warn("AgentClient provider: MOCK");
        return new MockAgentClient(fixedResponse);
    }

    // ── ollama ──────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "agent.provider", havingValue = "ollama")
    public ChatClient ollamaChatClient(ChatClient.Builder builder) {
        return builder
            .defaultAdvisors(SimpleLoggerAdvisor.builder().order(4).build())
            .defaultOptions(OllamaOptions.builder()
                .temperature(0.0)
                .topP(1.0)
                .topK(20)
                .build())
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.provider", havingValue = "ollama")
    public AgentClient ollamaAgentClient(ChatClient chatClient) {
        log.info("AgentClient provider: ollama");
        return new OllamaAgentClient(chatClient);
    }

    // ── gigachat ────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "agent.provider", havingValue = "gigachat")
    public ChatClient gigaChatClient(ChatClient.Builder builder) {
        return builder
            .defaultAdvisors(SimpleLoggerAdvisor.builder().order(4).build())
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.provider", havingValue = "gigachat")
    public AgentClient gigaChatAgentClient(ChatClient chatClient) {
        log.info("AgentClient provider: gigachat");
        return new GigaChatAgentClient(chatClient);
    }
}
```

---

## 8. `common/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>ru.andreyz</groupId>
        <artifactId>leader-role-framework</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>common</artifactId>
    <packaging>jar</packaging>  <!-- plain JAR, не fat-jar -->

    <dependencies>
        <!-- Spring Context — для @Configuration, @Bean, @PostConstruct -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>

        <!-- Spring Boot autoconfigure — для @ConditionalOnProperty -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>

        <!-- Spring AI core — ChatClient, ChatClient.Builder -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-core</artifactId>
        </dependency>

        <!-- Ollama starter — опциональный, нужен только при provider=ollama -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-ollama</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- GigaChat starter — опциональный, нужен только при provider=gigachat -->
        <dependency>
            <groupId>chat.giga</groupId>
            <artifactId>spring-ai-starter-model-gigachat</artifactId>
            <version>1.0.6</version>
            <optional>true</optional>
        </dependency>

        <!-- Logging -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>

        <!-- Tests -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- plain jar — НЕ spring-boot-maven-plugin repackage -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 9. Корневой `pom.xml` — изменения

```xml
<!-- добавить common первым в <modules> -->
<modules>
    <module>common</module>          <!-- первым — остальные зависят от него -->
    <module>JavaMailAgent</module>
    <module>JavaMemoryService</module>
    <module>JavaRagService</module>
</modules>

<!-- добавить в <dependencyManagement> -->
<dependency>
    <groupId>ru.andreyz</groupId>
    <artifactId>common</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- добавить в <properties> для gigachat -->
<gigachat-spring-ai.version>1.0.6</gigachat-spring-ai.version>

<!-- добавить репозиторий gigachat (если нужен отдельный) -->
<repositories>
    <repository>
        <id>gigachat</id>
        <url>https://nexus.gigachat.ru/repository/maven-public/</url>
    </repository>
    <repository>
        <id>spring-milestones</id>
        <url>https://repo.spring.io/milestone</url>
    </repository>
</repositories>
```

---

## 10. Зависимость в каждом сервисе

Добавить в `pom.xml` JavaMailAgent, JavaMemoryService, JavaRagService:

```xml
<dependency>
    <groupId>ru.andreyz</groupId>
    <artifactId>common</artifactId>
</dependency>
```

---

## 11. Структура файлов

```
common/
├── pom.xml
└── src/
    ├── main/java/ru/andreyz/common/
    │   ├── agent/
    │   │   ├── AgentClient.java
    │   │   ├── AgentException.java
    │   │   ├── ClaudeProcessAgentClient.java
    │   │   ├── MockAgentClient.java
    │   │   ├── OllamaAgentClient.java
    │   │   └── GigaChatAgentClient.java
    │   └── config/
    │       └── AgentClientConfig.java
    └── test/java/ru/andreyz/common/
        ├── agent/
        │   ├── MockAgentClientTest.java
        │   └── ClaudeProcessAgentClientTest.java
        └── config/
            └── AgentClientConfigTest.java
```

---

## 12. Spring Boot autoconfigure регистрация

Чтобы `AgentClientConfig` автоматически подхватывался сервисами без явного
`@Import`, добавить файл:

```
common/src/main/resources/META-INF/spring/
└── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

Содержимое:
```
ru.andreyz.common.config.AgentClientConfig
```

---

## 13. Конфигурация по окружениям

### JavaMailAgent — application-local.yml
```yaml
agent:
  provider: mock
```

### JavaMailAgent — application-prod.yml
```yaml
agent:
  provider: claude
  timeout-minutes: 5
```

### JavaMemoryService — application-e2e.yml
```yaml
agent:
  provider: mock
  mock:
    response: '[{"type":"NOTE","title":"mock","body":"mock","priority":"NORMAL"}]'
```

### Ollama (любой сервис) — application-local.yml
```yaml
agent:
  provider: ollama

spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: qwen2.5:7b
        options:
          temperature: 0.0
```

### GigaChat (prod Сбер) — application-prod.yml
```yaml
agent:
  provider: gigachat

spring:
  ai:
    gigachat:
      credentials: ${GIGACHAT_CREDENTIALS}
      scope: GIGACHAT_API_CORP
```

---

## 14. Порядок реализации для Claude Code

1. Создать `common/pom.xml`
2. Обновить корневой `pom.xml` — добавить модуль + зависимость в BOM + репозитории
3. `AgentClient.java` + `AgentException.java`
4. `ClaudeProcessAgentClient.java`
5. `MockAgentClient.java`
6. `OllamaAgentClient.java`
7. `GigaChatAgentClient.java`
8. `AgentClientConfig.java`
9. `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
10. Тесты: `MockAgentClientTest`, `AgentClientConfigTest`
11. Убедиться что `mvn package -pl common` собирается
