package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.andreyz.common.agent.AgentClient;
import ru.andreyz.common.agent.AgentException;
import ru.andreyz.memoryservice.domain.Capture;
import ru.andreyz.memoryservice.dto.ClassifiedCapture;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CaptureClassifierAgent {

    private static final Logger log = LoggerFactory.getLogger(CaptureClassifierAgent.class);

    private final AgentClient agentClient;
    private final ObjectMapper objectMapper;

    public CaptureClassifierAgent(AgentClient agentClient, ObjectMapper objectMapper) {
        this.agentClient = agentClient;
        this.objectMapper = objectMapper;
    }

    public List<ClassifiedCapture> classify(List<Capture> captures) {
        String prompt = buildPrompt(captures);
        return completeAndParse(prompt);
    }

    public List<ClassifiedCapture> classifyFiles(List<CaptureService.CaptureFile> files, String dayContext) {
        String prompt = buildFilePrompt(files, dayContext);
        return completeAndParse(prompt);
    }

    private List<ClassifiedCapture> completeAndParse(String prompt) {
        try {
            String output = agentClient.complete(prompt);
            log.debug("agent output:\n{}", output);
            return parseResponse(output);
        } catch (Exception e) {
            throw new AgentException("Capture classification failed: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(List<Capture> captures) {
        String notesList = captures.stream()
                .map(c -> "captureId=" + c.id() + ": " + c.rawText())
                .collect(Collectors.joining("\n"));

        return """
                Ты — ассистент техлида. Разбери заметки за день.
                Для каждой заметки верни JSON-объект в массиве:
                {
                  "captureId": <число>,
                  "type": "TASK|RISK|NOTE|PERSON_NOTE|KNOWLEDGE|JOURNAL|QUESTION",
                  "title": "...",
                  "body": "...",
                  "tags": "tag1,tag2",
                  "priority": "LOW|NORMAL|HIGH|CRITICAL"
                }
                Верни ТОЛЬКО JSON-массив, без пояснений и markdown-обёртки.
                Для PERSON_NOTE: title = имя человека, body = заметка о нём.
                Заметки:
                """ + notesList;
    }

    String buildFilePrompt(List<CaptureService.CaptureFile> files, String dayContext) {
        String notesJson = files.stream()
                .map(file -> "{\"file\":\"" + escapeJson(file.file()) + "\",\"text\":\"" + escapeJson(file.text()) + "\"}")
                .collect(Collectors.joining(",\n "));

        return """
                Классифицируй каждую заметку. Верни ТОЛЬКО JSON массив, без пояснений.

                Контекст дня (уже существуют, не дублируй):
                %s

                Типы классификации (выбери ОДИН наиболее подходящий):
                - TASK         — действие которое нужно выполнить (не дублируй существующие задачи)
                - RISK         — операционный риск или проблема (не дублируй существующие риски)
                - NOTE         — наблюдение, информация к сведению
                - QUESTION     — открытый вопрос, требующий ответа или исследования
                - PERSON_NOTE  — заметка о конкретном человеке (title = имя человека, body = заметка о нём)
                - KNOWLEDGE    — знание для базы знаний: архитектура, процессы, runbook, технические решения
                - JOURNAL      — запись дневника: итоги дня, наблюдения, ретроспектива

                Подсказки по выбору типа:
                - Начинается с "KNOWLEDGE:" или про архитектуру/процессы/деплой → KNOWLEDGE
                - Начинается с "JOURNAL:" или итоги/сегодня/завершили → JOURNAL
                - Начинается с "QUESTION:" или "как"/"почему"/"нужно разобраться" → QUESTION
                - Начинается с "PERSON_NOTE:" или про конкретного человека → PERSON_NOTE
                - Начинается с "RISK:" или угроза/только один знает/нет резервирования → RISK
                - Начинается с "TASK:" или нужно сделать/исправить/реализовать → TASK
                - Всё остальное → NOTE

                Заметки:
                [%s]

                Формат ответа:
                [
                  {"file": "10-32-00.md", "type": "TASK", "title": "...", "body": "...", "priority": "HIGH|NORMAL|LOW"},
                  {"file": "14-15-00.md", "type": "RISK", "title": "...", "body": "..."},
                  {"file": "16-40-00.md", "type": "NOTE", "title": "...", "body": "...", "tags": "risk,person"},
                  {"file": "18-00-00.md", "type": "KNOWLEDGE", "title": "...", "body": "..."},
                  {"file": "18-01-00.md", "type": "JOURNAL", "title": "...", "body": "..."},
                  {"file": "18-02-00.md", "type": "QUESTION", "title": "...", "body": "..."},
                  {"file": "18-03-00.md", "type": "PERSON_NOTE", "title": "Имя человека", "body": "..."}
                ]
                """.formatted(dayContext, notesJson);
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            return json.substring(1, json.length() - 1);
        } catch (IOException e) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    List<ClassifiedCapture> parseResponse(String output) throws IOException {
        String json = output.trim();
        // Strip markdown code block if present
        if (json.startsWith("```")) {
            json = json.replaceAll("(?s)```[a-z]*\\n?", "").replaceAll("```", "").trim();
        }
        // Find JSON array boundaries
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end < 0) {
            throw new IOException("No JSON array found in agent response: " + json);
        }
        json = json.substring(start, end + 1);
        return objectMapper.readValue(json, new TypeReference<>() {});
    }
}
