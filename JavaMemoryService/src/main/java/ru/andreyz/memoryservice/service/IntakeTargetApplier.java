package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Incident;
import ru.andreyz.memoryservice.domain.PersonNameNote;
import ru.andreyz.memoryservice.domain.Risk;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.dto.TaskLinkResponse;
import ru.andreyz.memoryservice.repository.PersonNameNoteRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class IntakeTargetApplier {

    private final TaskService taskService;
    private final RiskService riskService;
    private final IncidentService incidentService;
    private final NoteService noteService;
    private final PersonNameNoteRepository personNameNoteRepository;
    private final TaskLinkService taskLinkService;
    private final Path ragInboxDir;

    public IntakeTargetApplier(TaskService taskService,
                               RiskService riskService,
                               IncidentService incidentService,
                               NoteService noteService,
                               PersonNameNoteRepository personNameNoteRepository,
                               TaskLinkService taskLinkService,
                               @Value("${app.rag.inbox-dir:../JavaRagService/rag-inbox}") String ragInboxDir) {
        this.taskService = taskService;
        this.riskService = riskService;
        this.incidentService = incidentService;
        this.noteService = noteService;
        this.personNameNoteRepository = personNameNoteRepository;
        this.taskLinkService = taskLinkService;
        this.ragInboxDir = Path.of(ragInboxDir);
    }

    public String apply(String route, JsonNode payload, String sourceRef) {
        String normalizedRoute = normalizeRequired(route, "route");
        JsonNode safePayload = payload != null ? payload : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        return switch (normalizedRoute) {
            case "TASK" -> applyTask(safePayload);
            case "TASK_LINK" -> applyTaskLink(safePayload);
            case "NOTE" -> applyNote(safePayload);
            case "RISK" -> applyRisk(safePayload);
            case "INCIDENT" -> applyIncident(safePayload);
            case "PERSON" -> applyPerson(safePayload);
            case "RAG" -> applyRag(safePayload, sourceRef);
            case "NOISE" -> "noise";
            default -> throw new IllegalArgumentException("Unsupported intake route: " + route);
        };
    }

    private String applyTaskLink(JsonNode payload) {
        Long fromTaskId = requiredLong(payload, "fromTaskId");
        Long toTaskId = requiredLong(payload, "toTaskId");
        String linkType = requiredText(payload, "linkType");
        TaskLinkResponse created = taskLinkService.create(fromTaskId, toTaskId, linkType);
        return "task_links/" + created.id();
    }

    private String applyTask(JsonNode payload) {
        String title = requiredText(payload, "title");
        String description = optionalText(payload, "description", "body", "summary", "text");
        String sourceId = optionalText(payload, "emailId", "sourceId");
        String sender = optionalText(payload, "sender", "sourceSender");
        String priority = optionalText(payload, "priority");
        String dateValue = optionalText(payload, "date", "dueDate");
        Task task;
        if (dateValue != null) {
            LocalDate date = LocalDate.parse(dateValue);
            String status = normalizeTaskStatus(optionalText(payload, "status"));
            task = taskService.createConfirmed(
                    date,
                    title,
                    priority,
                    description,
                    "intake-gateway",
                    sourceId,
                    date,
                    status
            );
        } else {
            task = taskService.createPending(
                    title,
                    description,
                    sourceId,
                    sender,
                    priority,
                    null,
                    "intake-gateway"
            );
        }
        return "tasks/" + task.id();
    }

    private String applyNote(JsonNode payload) {
        var note = noteService.create(
                optionalText(payload, "title"),
                optionalText(payload, "text", "body", "description", "summary"),
                optionalText(payload, "tags"),
                "intake"
        );
        return "notes/" + note.id();
    }

    private String applyRisk(JsonNode payload) {
        JsonNode riskId = payload.get("riskId");
        if (riskId != null && riskId.canConvertToLong()) {
            Risk updated = riskService.update(riskId.longValue(), new Risk(
                    null,
                    requiredText(payload, "title"),
                    optionalText(payload, "description", "body", "summary"),
                    optionalText(payload, "probability"),
                    optionalText(payload, "impact"),
                    optionalText(payload, "status"),
                    optionalText(payload, "mitigation"),
                    null,
                    null
            ));
            return "risks/" + updated.id();
        }
        Risk created = riskService.create(
                requiredText(payload, "title"),
                optionalText(payload, "description", "body", "summary"),
                optionalText(payload, "probability"),
                optionalText(payload, "impact")
        );
        return "risks/" + created.id();
    }

    private String applyIncident(JsonNode payload) {
        JsonNode incidentId = payload.get("incidentId");
        if (incidentId != null && incidentId.canConvertToLong()) {
            Incident updated = incidentService.update(incidentId.longValue(), new Incident(
                    null,
                    requiredText(payload, "title"),
                    optionalText(payload, "severity", "priority"),
                    optionalText(payload, "status"),
                    optionalText(payload, "description", "body", "summary"),
                    optionalText(payload, "rootCause"),
                    optionalText(payload, "actionItems"),
                    null,
                    null,
                    null
            ));
            return "incidents/" + updated.id();
        }
        Incident created = incidentService.create(
                requiredText(payload, "title"),
                defaultIncidentSeverity(optionalText(payload, "severity", "priority")),
                optionalText(payload, "description", "body", "summary")
        );
        return "incidents/" + created.id();
    }

    private String applyPerson(JsonNode payload) {
        String personName = requiredText(payload, "personName", "title", "name");
        String noteText = requiredText(payload, "note", "description", "body", "summary", "text");
        PersonNameNote saved = personNameNoteRepository.save(new PersonNameNote(null, personName, noteText, Instant.now()));
        return "person_notes/" + saved.id();
    }

    private String defaultIncidentSeverity(String severity) {
        return severity != null && !severity.isBlank() ? severity : "MEDIUM";
    }

    private String normalizeTaskStatus(String status) {
        if (status == null || status.isBlank() || "PENDING".equalsIgnoreCase(status)) {
            return "TODO";
        }
        return status.trim().toUpperCase();
    }

    private String applyRag(JsonNode payload, String sourceRef) {
        try {
            String docType = normalizeDocType(optionalText(payload, "docType", "type"));
            String title = requiredText(payload, "title");
            String body = optionalText(payload, "body", "description", "summary", "text");
            String filename = buildKnowledgeFileName(docType, sourceRef);
            Path targetDir = ragInboxDir.resolve("intake");
            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(filename);
            Files.writeString(targetFile, buildKnowledgeContent(docType, title, body, payload),
                    StandardOpenOption.CREATE_NEW);
            return "rag-inbox/intake/" + filename;
        } catch (IOException e) {
            log.error("Failed to write intake RAG document {}: {}", sourceRef, e.getMessage(), e);
            throw new IllegalStateException("Failed to write intake RAG document", e);
        }
    }

    private String buildKnowledgeFileName(String docType, String sourceRef) {
        String safeRef = sourceRef == null || sourceRef.isBlank()
                ? String.valueOf(System.currentTimeMillis())
                : sourceRef.replaceAll("[^a-zA-Z0-9._-]", "_");
        return LocalDate.now() + "-" + docType.toLowerCase() + "-" + safeRef + ".md";
    }

    private String buildKnowledgeContent(String docType, String title, String body, JsonNode payload) {
        Map<String, String> frontmatter = new LinkedHashMap<>();
        frontmatter.put("type", docType);
        frontmatter.put("updated", LocalDate.now().toString());
        copyIfPresent(frontmatter, "subject", optionalText(payload, "subject"));
        copyIfPresent(frontmatter, "sender", optionalText(payload, "sender"));
        copyIfPresent(frontmatter, "received_at", optionalText(payload, "receivedAt"));

        StringBuilder builder = new StringBuilder("---\n");
        frontmatter.forEach((key, value) -> builder.append(key).append(": ").append(value).append('\n'));
        builder.append("---\n\n# ").append(title).append("\n\n");
        if (body != null && !body.isBlank()) {
            builder.append(body.strip()).append('\n');
        }
        return builder.toString();
    }

    private void copyIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String normalizeDocType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "RAG";
        }
        return switch (raw.trim().toUpperCase()) {
            case "NOTICE", "KNOWLEDGE", "RAG" -> "RAG";
            default -> raw.trim().toUpperCase();
        };
    }

    private Long requiredLong(JsonNode payload, String fieldName) {
        JsonNode field = payload.get(fieldName);
        if (field == null || field.isNull() || !field.canConvertToLong()) {
            throw new IllegalArgumentException("Missing required payload field: " + fieldName);
        }
        return field.longValue();
    }

    private String requiredText(JsonNode payload, String... fieldNames) {
        String value = optionalText(payload, fieldNames);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required payload field: " + String.join("/", fieldNames));
        }
        return value;
    }

    private String optionalText(JsonNode payload, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode field = payload.get(fieldName);
            if (field == null || field.isNull()) {
                continue;
            }
            if (field.isTextual()) {
                String value = field.textValue();
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
                continue;
            }
            String value = field.toString();
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim().toUpperCase();
    }
}
