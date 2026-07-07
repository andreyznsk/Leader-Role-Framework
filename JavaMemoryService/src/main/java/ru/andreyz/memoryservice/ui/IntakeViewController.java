package ru.andreyz.memoryservice.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.andreyz.memoryservice.dto.IntakeItemDto;
import ru.andreyz.memoryservice.service.IntakeService;

import java.util.List;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/ui/intake")
public class IntakeViewController {
    private static final Pattern ESCAPED_CONTROL_PATTERN = Pattern.compile("\\\\[nrm]");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private final IntakeService intakeService;
    private final ObjectMapper objectMapper;

    public IntakeViewController(IntakeService intakeService, ObjectMapper objectMapper) {
        this.intakeService = intakeService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public String intake(@RequestParam(required = false, defaultValue = "NEW") String status,
                         @RequestParam(required = false) String sourceType,
                         @RequestParam(required = false) String suggestedRoute,
                         Model model) {
        List<IntakeCardView> items = intakeService.list(status, sourceType, suggestedRoute).stream()
                .map(this::toView)
                .toList();
        model.addAttribute("items", items);
        model.addAttribute("activeStatus", status == null || status.isBlank() ? "" : status.toUpperCase());
        model.addAttribute("activeSourceType", sourceType == null ? "" : sourceType.toUpperCase());
        model.addAttribute("activeSuggestedRoute", suggestedRoute == null ? "" : suggestedRoute.toUpperCase());
        model.addAttribute("routeOptions", List.of("RAG", "TASK", "TASK_LINK", "NOTE", "INCIDENT", "RISK", "PERSON", "NOISE"));
        model.addAttribute("sourceOptions", List.of("MAIL", "CAPTURE", "AGENT_MCP", "MANUAL"));
        model.addAttribute("statusOptions", List.of("NEW", "REVIEWING", "APPLIED", "REJECTED"));
        return "intake";
    }

    private IntakeCardView toView(IntakeItemDto item) {
        return new IntakeCardView(
                item.id().toString(),
                item.sourceType(),
                item.sourceId(),
                displayPayload(item.sourcePayload(), item.sourceText()),
                item.agentProvider(),
                item.agentPrompt(),
                pretty(item.agentResult(), item.agentResultText()),
                item.suggestedRoute(),
                pretty(item.suggestedPayload(), null),
                item.finalRoute(),
                pretty(item.finalPayload(), null),
                item.status(),
                item.confidence() != null ? item.confidence().toPlainString() : null,
                item.createdAt() != null ? item.createdAt().toString() : null,
                item.reviewedAt() != null ? item.reviewedAt().toString() : null,
                item.appliedAt() != null ? item.appliedAt().toString() : null,
                item.rejectReason()
        );
    }

    private String compact(JsonNode node, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return node.toString();
        }
    }

    private String displayPayload(JsonNode node, String fallback) {
        if (node == null || node.isNull()) {
            return normalizePayloadForDisplay(fallback);
        }
        JsonNode normalized = normalizeNodeForDisplay(node);
        if (normalized.isTextual()) {
            return normalized.textValue();
        }
        return pretty(normalized, fallback);
    }

    private String pretty(JsonNode node, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return node.toString();
        }
    }

    private String normalizePayloadForDisplay(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String normalized = value
                .replace("\r\n", " ")
                .replace('\r', ' ')
                .replace('\n', ' ');
        normalized = ESCAPED_CONTROL_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();
        return normalized;
    }

    private JsonNode normalizeNodeForDisplay(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(normalizePayloadForDisplay(node.textValue()));
        }
        if (node.isArray()) {
            ArrayNode arrayNode = objectMapper.createArrayNode();
            node.forEach(child -> arrayNode.add(normalizeNodeForDisplay(child)));
            return arrayNode;
        }
        if (node.isObject()) {
            ObjectNode objectNode = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> objectNode.set(entry.getKey(), normalizeNodeForDisplay(entry.getValue())));
            return objectNode;
        }
        return node;
    }

    public record IntakeCardView(
            String id,
            String sourceType,
            String sourceId,
            String sourcePayload,
            String agentProvider,
            String agentPrompt,
            String agentResult,
            String suggestedRoute,
            String suggestedPayload,
            String finalRoute,
            String finalPayload,
            String status,
            String confidence,
            String createdAt,
            String reviewedAt,
            String appliedAt,
            String rejectReason
    ) {}
}
