package ru.andreyz.memoryservice.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.andreyz.memoryservice.domain.UsageEventType;
import ru.andreyz.memoryservice.dto.UsageEventCommand;
import ru.andreyz.memoryservice.service.UsageEventService;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsageStatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsageEventService usageEventService;

    @Test
    void usageStats_returnsAggregates() throws Exception {
        usageEventService.record(new UsageEventCommand(
                UsageEventType.RAG_SEARCH,
                "test",
                "SUCCESS",
                "stats-test",
                null,
                null,
                null,
                null,
                Map.of("query", "stats")
        ));
        usageEventService.record(new UsageEventCommand(
                UsageEventType.RAG_RESULT_USED,
                "test",
                "SUCCESS",
                "stats-test",
                null,
                null,
                null,
                null,
                Map.of("query", "stats")
        ));

        mockMvc.perform(get("/api/stats/usage").param("period", "7d"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.period").value("7d"))
                .andExpect(jsonPath("$.ragSearches").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.savedMinutes").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.eventsBySource.test").exists());
    }

    @Test
    void statsUi_rendersDefaultPeriod() throws Exception {
        mockMvc.perform(get("/ui/stats"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Статистика")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Saved time")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("7 days")));
    }
}
