package ru.andreyz.memoryservice.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.andreyz.memoryservice.dto.IntakeCreateRequest;
import ru.andreyz.memoryservice.dto.IntakeItemDto;
import ru.andreyz.memoryservice.service.IntakeService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IntakeControllerTest {

    private IntakeService intakeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        intakeService = mock(IntakeService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new IntakeController(intakeService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
                .build();
    }

    @Test
    void createDelegatesToService() throws Exception {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(intakeService.create(any())).thenReturn(dto(id, "NEW"));

        mockMvc.perform(post("/api/intake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "MANUAL",
                                  "sourcePayload": "raw text",
                                  "suggestedRoute": "NOTE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("NEW"));

        ArgumentCaptor<IntakeCreateRequest> captor = ArgumentCaptor.forClass(IntakeCreateRequest.class);
        verify(intakeService).create(captor.capture());
    }

    @Test
    void listReturnsItems() throws Exception {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(intakeService.list("NEW", "MAIL", "RAG")).thenReturn(List.of(dto(id, "NEW")));

        mockMvc.perform(get("/api/intake")
                        .param("status", "NEW")
                        .param("sourceType", "MAIL")
                        .param("suggestedRoute", "RAG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()));
    }

    @Test
    void updateApplyAndRejectDelegateToService() throws Exception {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(intakeService.update(eq(id), any())).thenReturn(dto(id, "REVIEWING"));
        when(intakeService.apply(eq(id), any())).thenReturn(dto(id, "APPLIED"));
        when(intakeService.reject(eq(id), any())).thenReturn(dto(id, "REJECTED"));

        mockMvc.perform(put("/api/intake/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"finalRoute":"NOTE","finalPayload":{"title":"Updated note"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWING"));

        mockMvc.perform(post("/api/intake/{id}/apply", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"finalRoute":"NOTE","finalPayload":{"title":"Updated note"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        mockMvc.perform(post("/api/intake/{id}/reject", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"noise"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    private IntakeItemDto dto(UUID id, String status) {
        return new IntakeItemDto(
                id,
                "MANUAL",
                "manual-1",
                null,
                "raw text",
                "mock",
                "prompt",
                null,
                "{\"route\":\"NOTE\"}",
                "NOTE",
                null,
                "NOTE",
                null,
                status,
                BigDecimal.valueOf(0.87),
                "manual",
                "ui",
                Instant.now(),
                Instant.now(),
                null,
                null,
                null
        );
    }
}
