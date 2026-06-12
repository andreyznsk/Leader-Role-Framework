package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Capture;
import ru.andreyz.memoryservice.dto.ClassifiedCapture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class CaptureClassifierAgent {

    private static final Logger log = LoggerFactory.getLogger(CaptureClassifierAgent.class);

    private final ObjectMapper objectMapper;

    public CaptureClassifierAgent(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ClassifiedCapture> classify(List<Capture> captures) throws IOException, InterruptedException {
        String prompt = buildPrompt(captures);
        return runClaude(prompt);
    }

    public List<ClassifiedCapture> classifyFiles(List<CaptureService.CaptureFile> files, String dayContext)
            throws IOException, InterruptedException {
        String prompt = buildFilePrompt(files, dayContext);
        return runClaude(prompt);
    }

    private List<ClassifiedCapture> runClaude(String prompt) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("claude", "--print");
        pb.redirectErrorStream(false);
        Process process = pb.start();

        try (var stdin = process.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("claude --print timed out after 120s");
        }

        log.debug("claude output:\n{}", output);
        return parseResponse(output);
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

                Типы классификации:
                - TASK — действие которое нужно выполнить (не дублируй существующие задачи)
                - RISK — операционный риск или проблема (не дублируй существующие риски)
                - NOTE — наблюдение, информация к сведению

                Заметки:
                [%s]

                Формат ответа:
                [
                  {"file": "10-32-00.md", "type": "TASK", "title": "...", "body": "...", "priority": "HIGH|NORMAL|LOW"},
                  {"file": "14-15-00.md", "type": "RISK", "title": "...", "body": "..."},
                  {"file": "16-40-00.md", "type": "NOTE", "title": "...", "body": "...", "tags": "risk,person"}
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
