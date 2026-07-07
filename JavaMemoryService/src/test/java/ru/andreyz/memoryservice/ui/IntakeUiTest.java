package ru.andreyz.memoryservice.ui;

import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;
import org.springframework.ui.ConcurrentModel;
import ru.andreyz.memoryservice.dto.IntakeItemDto;
import ru.andreyz.memoryservice.service.IntakeService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntakeUiTest {

    @Test
    void intakePagePopulatesModel() {
        IntakeService intakeService = mock(IntakeService.class);
        IntakeViewController controller = new IntakeViewController(intakeService, new com.fasterxml.jackson.databind.ObjectMapper());
        when(intakeService.list("NEW", null, null)).thenReturn(List.of(new IntakeItemDto(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "MAIL",
                "msg-1",
                com.fasterxml.jackson.databind.node.TextNode.valueOf("email body"),
                "email body",
                "mock",
                "prompt body",
                com.fasterxml.jackson.databind.node.TextNode.valueOf("{\"route\":\"RAG\"}"),
                "{\"route\":\"RAG\"}",
                "RAG",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("title", "Release rule")
                        .put("body", "Use calendar"),
                null,
                null,
                "NEW",
                BigDecimal.valueOf(0.91),
                "mail-agent",
                null,
                Instant.now(),
                null,
                null,
                null,
                null
        )));

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.intake("NEW", null, null, model);

        assertThat(view).isEqualTo("intake");
        assertThat(model.getAttribute("activeStatus")).isEqualTo("NEW");
        assertThat(model.getAttribute("items").toString()).contains("Release rule").contains("prompt body");
    }

    @Test
    void intakePageNormalizesOriginalPayloadForDisplayOnly() {
        IntakeService intakeService = mock(IntakeService.class);
        IntakeViewController controller = new IntakeViewController(intakeService, new com.fasterxml.jackson.databind.ObjectMapper());
        when(intakeService.list("NEW", null, null)).thenReturn(List.of(new IntakeItemDto(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "MAIL",
                "msg-2",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("body", "line1\nline2\\nline3\r\\r\\m"),
                null,
                null,
                null,
                null,
                null,
                "TASK",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("title", "Task"),
                null,
                null,
                "NEW",
                null,
                "mail-agent",
                null,
                Instant.now(),
                null,
                null,
                null,
                null
        )));

        Model model = new ConcurrentModel();
        controller.intake("NEW", null, null, model);

        @SuppressWarnings("unchecked")
        List<IntakeViewController.IntakeCardView> items =
                (List<IntakeViewController.IntakeCardView>) model.getAttribute("items");
        assertThat(items)
                .extracting(IntakeViewController.IntakeCardView::sourcePayload, IntakeViewController.IntakeCardView::suggestedPayload)
                .containsExactly(tuple("{\n  \"body\" : \"line1 line2 line3\"\n}", "{\n  \"title\" : \"Task\"\n}"));
    }
}
