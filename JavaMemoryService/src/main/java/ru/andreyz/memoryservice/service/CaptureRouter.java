package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.dto.ClassifiedCapture;
import ru.andreyz.memoryservice.dto.IntakeCreateRequest;
import ru.andreyz.memoryservice.dto.IntakeItemDto;

import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CaptureRouter {

    private final IntakeService intakeService;
    private final ObjectMapper objectMapper;

    public CaptureRouter(IntakeService intakeService, ObjectMapper objectMapper) {
        this.intakeService = intakeService;
        this.objectMapper = objectMapper;
    }

    public String route(ClassifiedCapture capture,
                        String sourceText,
                        String agentPrompt,
                        String agentResult,
                        String agentProvider) {
        String captureType = normalize(capture.type());
        String suggestedRoute = suggestedRoute(captureType);
        ObjectNode sourcePayload = JsonNodeFactory.instance.objectNode();
        if (capture.captureId() != null) {
            sourcePayload.put("captureId", capture.captureId());
        }
        if (capture.file() != null) {
            sourcePayload.put("file", capture.file());
        }
        if (sourceText != null) {
            sourcePayload.put("text", sourceText);
        }
        sourcePayload.put("classification", captureType);

        IntakeItemDto created = intakeService.create(new IntakeCreateRequest(
                "CAPTURE",
                capture.captureId() != null ? String.valueOf(capture.captureId()) : capture.file(),
                sourcePayload,
                agentProvider,
                agentPrompt,
                parseResult(agentResult, capture),
                suggestedRoute,
                suggestedPayload(captureType, capture),
                null,
                "capture-bot"
        ));
        return "intake/" + created.id();
    }

    private String suggestedRoute(String captureType) {
        return switch (captureType) {
            case "TASK" -> "TASK";
            case "RISK" -> "RISK";
            case "NOTE", "QUESTION", "JOURNAL" -> "NOTE";
            case "PERSON_NOTE" -> "PERSON";
            case "KNOWLEDGE", "NOTICE", "RAG" -> "RAG";
            default -> {
                log.warn("Unknown capture type for intake routing: {}", captureType);
                yield "NOISE";
            }
        };
    }

    private ObjectNode suggestedPayload(String captureType, ClassifiedCapture capture) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        switch (captureType) {
            case "TASK" -> {
                payload.put("title", capture.title());
                payload.put("description", capture.body());
                if (capture.priority() != null) {
                    payload.put("priority", capture.priority());
                }
            }
            case "RISK" -> {
                payload.put("title", capture.title());
                payload.put("description", capture.body());
                payload.put("probability", "MEDIUM");
                payload.put("impact", "MEDIUM");
            }
            case "NOTE" -> {
                payload.put("title", capture.title());
                payload.put("text", capture.body());
                if (capture.tags() != null) {
                    payload.put("tags", capture.tags());
                }
            }
            case "QUESTION" -> {
                payload.put("title", capture.title());
                payload.put("text", capture.body());
                payload.put("tags", withTag(capture.tags(), "question"));
            }
            case "PERSON_NOTE" -> {
                payload.put("personName", capture.title());
                payload.put("note", capture.body());
            }
            case "KNOWLEDGE", "NOTICE", "RAG" -> {
                payload.put("docType", "RAG");
                payload.put("title", capture.title());
                payload.put("body", capture.body());
            }
            case "JOURNAL" -> {
                payload.put("title", capture.title());
                payload.put("text", capture.body());
                payload.put("tags", withTag(capture.tags(), "journal"));
            }
            default -> payload.put("text", capture.body() != null ? capture.body() : capture.title());
        }
        payload.put("originalCaptureType", captureType);
        return payload;
    }

    private com.fasterxml.jackson.databind.JsonNode parseResult(String agentResult, ClassifiedCapture capture) {
        if (agentResult != null && !agentResult.isBlank()) {
            try {
                return objectMapper.readTree(agentResult);
            } catch (Exception ignored) {
            }
        }
        return objectMapper.valueToTree(capture);
    }

    private String withTag(String tags, String extraTag) {
        if (tags == null || tags.isBlank()) {
            return extraTag;
        }
        String normalized = tags.toLowerCase(Locale.ROOT);
        return normalized.contains(extraTag) ? tags : tags + "," + extraTag;
    }

    private String normalize(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }
}
