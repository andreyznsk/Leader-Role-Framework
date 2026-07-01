package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.andreyz.memoryservice.domain.IntakeItem;
import ru.andreyz.memoryservice.dto.IntakeApplyRequest;
import ru.andreyz.memoryservice.dto.IntakeCreateRequest;
import ru.andreyz.memoryservice.dto.IntakeRejectRequest;
import ru.andreyz.memoryservice.dto.IntakeUpdateRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntakeServiceTest {

    private IntakeStore intakeStore;
    private IntakeTargetApplier targetApplier;
    private IntakeService service;

    @BeforeEach
    void setUp() {
        intakeStore = mock(IntakeStore.class);
        targetApplier = mock(IntakeTargetApplier.class);
        service = new IntakeService(intakeStore, targetApplier, new ObjectMapper());
        when(intakeStore.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createStoresNewItem() {
        var created = service.create(new IntakeCreateRequest(
                "MANUAL",
                "manual-1",
                TextNode.valueOf("raw text"),
                "mock",
                "prompt",
                TextNode.valueOf("{\"route\":\"NOTE\"}"),
                "NOTE",
                JsonNodeFactory.instance.objectNode().put("title", "Ops note"),
                0.87,
                "tester"
        ));

        assertThat(created.status()).isEqualTo("NEW");
        assertThat(created.sourceType()).isEqualTo("MANUAL");
        assertThat(created.confidence()).isEqualByComparingTo(BigDecimal.valueOf(0.87).setScale(4));
    }

    @Test
    void updateApplyAndRejectChangeLifecycle() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        IntakeItem existing = new IntakeItem(
                id,
                "MANUAL",
                "manual-1",
                "\"raw text\"",
                "raw text",
                "mock",
                "prompt",
                "{\"route\":\"NOTE\"}",
                "{\"route\":\"NOTE\"}",
                "NOTE",
                "{\"title\":\"Ops note\"}",
                null,
                null,
                "NEW",
                BigDecimal.valueOf(0.87).setScale(4),
                "tester",
                null,
                Instant.now(),
                null,
                null,
                null,
                null
        );
        when(intakeStore.findById(id)).thenReturn(Optional.of(existing));
        when(intakeStore.findAll()).thenReturn(List.of(existing));
        when(targetApplier.apply("NOTE", JsonNodeFactory.instance.objectNode().put("title", "Updated note"), id.toString()))
                .thenReturn("notes/1");

        var updated = service.update(id, new IntakeUpdateRequest(
                "NOTE",
                JsonNodeFactory.instance.objectNode().put("title", "Updated note"),
                "reviewer"
        ));
        assertThat(updated.status()).isEqualTo("REVIEWING");

        var applied = service.apply(id, new IntakeApplyRequest(
                "NOTE",
                JsonNodeFactory.instance.objectNode().put("title", "Updated note"),
                "reviewer"
        ));
        assertThat(applied.status()).isEqualTo("APPLIED");

        var rejected = service.reject(id, new IntakeRejectRequest("noise", "reviewer"));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectReason()).isEqualTo("noise");

        verify(targetApplier).apply(any(), any(), any());
    }
}
