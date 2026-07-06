package ru.andreyz.memoryservice.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.andreyz.memoryservice.dto.IntakeItemDto;
import ru.andreyz.memoryservice.service.IntakeService;

import java.util.List;

@Controller
@RequestMapping("/ui/intake")
public class IntakeViewController {

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
                pretty(item.sourcePayload(), item.sourceText()),
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
