package ru.andreyz.memoryservice.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.andreyz.memoryservice.service.CaptureProcessingService;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaptureControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CaptureProcessingService processingService;

    @Test
    void postCapture_returnsCaptureIdAndSavedAt() throws Exception {
        mockMvc.perform(post("/api/capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Ivan doesn't know rollback", "source": "cli"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captureId").isNumber())
                .andExpect(jsonPath("$.savedAt").isString());
    }

    @Test
    void postCapture_withoutSource_defaultsToCli() throws Exception {
        mockMvc.perform(post("/api/capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "note without source"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captureId").isNumber());
    }

    @Test
    void getToday_returnsArray() throws Exception {
        // create one capture first
        mockMvc.perform(post("/api/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text": "today note", "source": "cli"}
                        """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/capture/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getToday_captureHasExpectedFields() throws Exception {
        mockMvc.perform(post("/api/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text": "field check note", "source": "web"}
                        """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/capture/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.rawText == 'field check note')].status").value("PENDING"))
                .andExpect(jsonPath("$[?(@.rawText == 'field check note')].source").value("web"));
    }

    @Test
    void getRecent_returnsArray() throws Exception {
        mockMvc.perform(get("/api/capture/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void postProcessToday_returnsTotalAndRouted() throws Exception {
        when(processingService.processToday())
                .thenReturn(new CaptureProcessingService.ProcessResult(3, 3));

        mockMvc.perform(post("/api/capture/process-today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.routed").value(3));
    }

    @Test
    void postProcessToday_noPending_returnsZeros() throws Exception {
        when(processingService.processToday())
                .thenReturn(new CaptureProcessingService.ProcessResult(0, 0));

        mockMvc.perform(post("/api/capture/process-today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.routed").value(0));
    }
}
