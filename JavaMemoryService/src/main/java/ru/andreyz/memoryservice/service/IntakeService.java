package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.IntakeItem;
import ru.andreyz.memoryservice.dto.IntakeApplyRequest;
import ru.andreyz.memoryservice.dto.IntakeCreateRequest;
import ru.andreyz.memoryservice.dto.IntakeItemDto;
import ru.andreyz.memoryservice.dto.IntakeRejectRequest;
import ru.andreyz.memoryservice.dto.IntakeUpdateRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Spliterators;
import java.util.UUID;
import java.util.stream.StreamSupport;

@Service
public class IntakeService {

    private final IntakeStore intakeStore;
    private final IntakeTargetApplier intakeTargetApplier;
    private final ObjectMapper objectMapper;

    public IntakeService(IntakeStore intakeStore,
                         IntakeTargetApplier intakeTargetApplier,
                         ObjectMapper objectMapper) {
        this.intakeStore = intakeStore;
        this.intakeTargetApplier = intakeTargetApplier;
        this.objectMapper = objectMapper;
    }

    public IntakeItemDto create(IntakeCreateRequest request) {
        Instant now = Instant.now();
        IntakeItem created = intakeStore.save(new IntakeItem(
                UUID.randomUUID(),
                normalizeRequired(request.sourceType(), "sourceType"),
                blankToNull(request.sourceId()),
                serializeJson(request.sourcePayload()),
                extractText(request.sourcePayload()),
                blankToNull(request.agentProvider()),
                blankToNull(request.agentPrompt()),
                serializeJson(request.agentResult()),
                extractText(request.agentResult()),
                normalizeOptional(request.suggestedRoute()),
                serializeJson(request.suggestedPayload()),
                null,
                null,
                "NEW",
                normalizeConfidence(request.confidence()),
                defaultCreatedBy(request.createdBy(), request.sourceType()),
                null,
                now,
                null,
                null,
                null,
                null
        ));
        return toDto(created);
    }

    public List<IntakeItemDto> list(String status, String sourceType, String suggestedRoute) {
        String normalizedStatus = normalizeOptional(status);
        String normalizedSourceType = normalizeOptional(sourceType);
        String normalizedSuggestedRoute = normalizeOptional(suggestedRoute);
        return streamAll()
                .filter(item -> normalizedStatus == null || normalizedStatus.equals(item.status()))
                .filter(item -> normalizedSourceType == null || normalizedSourceType.equals(item.sourceType()))
                .filter(item -> normalizedSuggestedRoute == null || normalizedSuggestedRoute.equals(item.suggestedRoute()))
                .sorted(Comparator.comparing(IntakeItem::createdAt).reversed())
                .map(this::toDto)
                .toList();
    }

    public int countNew() {
        return (int) streamAll()
                .filter(item -> "NEW".equals(item.status()))
                .count();
    }

    public IntakeItemDto get(UUID id) {
        return toDto(findRequired(id));
    }

    public IntakeItemDto update(UUID id, IntakeUpdateRequest request) {
        IntakeItem existing = findRequired(id);
        IntakeItem updated = intakeStore.save(new IntakeItem(
                existing.id(),
                existing.sourceType(),
                existing.sourceId(),
                existing.sourcePayloadJson(),
                existing.sourceText(),
                existing.agentProvider(),
                existing.agentPrompt(),
                existing.agentResultJson(),
                existing.agentResultText(),
                existing.suggestedRoute(),
                existing.suggestedPayloadJson(),
                normalizeOptional(request.finalRoute()) != null ? normalizeOptional(request.finalRoute()) : existing.finalRoute(),
                request.finalPayload() != null ? serializeJson(request.finalPayload()) : existing.finalPayloadJson(),
                transitionToReviewing(existing.status()),
                existing.confidence(),
                existing.createdBy(),
                defaultReviewedBy(request.reviewedBy()),
                existing.createdAt(),
                Instant.now(),
                existing.appliedAt(),
                existing.rejectedAt(),
                existing.rejectReason()
        ));
        return toDto(updated);
    }

    public IntakeItemDto apply(UUID id, IntakeApplyRequest request) {
        IntakeItem existing = findRequired(id);
        String finalRoute = normalizeOptional(request.finalRoute()) != null
                ? normalizeOptional(request.finalRoute())
                : fallbackRoute(existing);
        JsonNode finalPayload = request.finalPayload() != null
                ? normalizeJsonNode(request.finalPayload())
                : parseStoredJson(existing.finalPayloadJson(), existing.suggestedPayloadJson());
        intakeTargetApplier.apply(finalRoute, finalPayload, existing.id().toString());

        IntakeItem applied = intakeStore.save(new IntakeItem(
                existing.id(),
                existing.sourceType(),
                existing.sourceId(),
                existing.sourcePayloadJson(),
                existing.sourceText(),
                existing.agentProvider(),
                existing.agentPrompt(),
                existing.agentResultJson(),
                existing.agentResultText(),
                existing.suggestedRoute(),
                existing.suggestedPayloadJson(),
                finalRoute,
                serializeJson(finalPayload),
                "APPLIED",
                existing.confidence(),
                existing.createdBy(),
                defaultReviewedBy(request.reviewedBy()),
                existing.createdAt(),
                Instant.now(),
                Instant.now(),
                existing.rejectedAt(),
                existing.rejectReason()
        ));
        return toDto(applied);
    }

    public IntakeItemDto reject(UUID id, IntakeRejectRequest request) {
        IntakeItem existing = findRequired(id);
        IntakeItem rejected = intakeStore.save(new IntakeItem(
                existing.id(),
                existing.sourceType(),
                existing.sourceId(),
                existing.sourcePayloadJson(),
                existing.sourceText(),
                existing.agentProvider(),
                existing.agentPrompt(),
                existing.agentResultJson(),
                existing.agentResultText(),
                existing.suggestedRoute(),
                existing.suggestedPayloadJson(),
                existing.finalRoute(),
                existing.finalPayloadJson(),
                "REJECTED",
                existing.confidence(),
                existing.createdBy(),
                defaultReviewedBy(request.reviewedBy()),
                existing.createdAt(),
                Instant.now(),
                existing.appliedAt(),
                Instant.now(),
                blankToNull(request.reason())
        ));
        return toDto(rejected);
    }

    private java.util.stream.Stream<IntakeItem> streamAll() {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(intakeStore.findAll().iterator(), 0),
                false
        );
    }

    private IntakeItem findRequired(UUID id) {
        return intakeStore.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Intake item not found: " + id));
    }

    private IntakeItemDto toDto(IntakeItem item) {
        return new IntakeItemDto(
                item.id(),
                item.sourceType(),
                item.sourceId(),
                parseJson(item.sourcePayloadJson(), null),
                item.sourceText(),
                item.agentProvider(),
                item.agentPrompt(),
                parseJson(item.agentResultJson(), item.agentResultText()),
                item.agentResultText(),
                item.suggestedRoute(),
                parseJson(item.suggestedPayloadJson(), null),
                item.finalRoute(),
                parseJson(item.finalPayloadJson(), null),
                item.status(),
                item.confidence(),
                item.createdBy(),
                item.reviewedBy(),
                item.createdAt(),
                item.reviewedAt(),
                item.appliedAt(),
                item.rejectedAt(),
                item.rejectReason()
        );
    }

    private JsonNode parseJson(String json, String textFallback) {
        if (json != null && !json.isBlank()) {
            try {
                return objectMapper.readTree(json);
            } catch (JsonProcessingException ignored) {
            }
        }
        return textFallback != null ? TextNode.valueOf(textFallback) : null;
    }

    private JsonNode parseStoredJson(String primaryJson, String fallbackJson) {
        String candidate = primaryJson != null && !primaryJson.isBlank() ? primaryJson : fallbackJson;
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(candidate);
        }
    }

    private String serializeJson(JsonNode jsonNode) {
        JsonNode normalized = normalizeJsonNode(jsonNode);
        if (normalized == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize payload", e);
        }
    }

    private JsonNode normalizeJsonNode(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull()) {
            return null;
        }
        if (jsonNode.isTextual()) {
            String raw = jsonNode.textValue();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return objectMapper.readTree(raw);
            } catch (JsonProcessingException ignored) {
                return jsonNode;
            }
        }
        return jsonNode;
    }

    private String extractText(JsonNode jsonNode) {
        JsonNode normalized = normalizeJsonNode(jsonNode);
        if (normalized == null) {
            return null;
        }
        if (normalized.isTextual()) {
            return blankToNull(normalized.textValue());
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            return normalized.toString();
        }
    }

    private String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim().toUpperCase();
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BigDecimal normalizeConfidence(Double value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private String defaultCreatedBy(String createdBy, String sourceType) {
        String value = blankToNull(createdBy);
        return value != null ? value : normalizeRequired(sourceType, "sourceType").toLowerCase() + "-gateway";
    }

    private String defaultReviewedBy(String reviewedBy) {
        return blankToNull(reviewedBy) != null ? blankToNull(reviewedBy) : "ui";
    }

    private String transitionToReviewing(String status) {
        if ("APPLIED".equals(status) || "REJECTED".equals(status)) {
            return status;
        }
        return "REVIEWING";
    }

    private String fallbackRoute(IntakeItem existing) {
        String route = existing.finalRoute() != null ? existing.finalRoute() : existing.suggestedRoute();
        if (route == null || route.isBlank()) {
            throw new IllegalArgumentException("finalRoute is required");
        }
        return route;
    }
}
