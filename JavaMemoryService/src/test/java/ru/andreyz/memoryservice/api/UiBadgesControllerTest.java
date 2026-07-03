package ru.andreyz.memoryservice.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.service.IntakeService;
import ru.andreyz.memoryservice.service.TaskService;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UiBadgesControllerTest {

    private TaskService taskService;
    private IntakeService intakeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        intakeService = mock(IntakeService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UiBadgesController(taskService, intakeService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void badges_returnsEnvelopeMatchingUnderlyingCounts() throws Exception {
        when(intakeService.countNew()).thenReturn(3);
        when(taskService.findPending()).thenReturn(List.of(pendingTask(1L), pendingTask(2L)));

        mockMvc.perform(get("/api/ui/badges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.newIntake").value(3))
                .andExpect(jsonPath("$.counts.pendingTasks").value(2))
                .andExpect(jsonPath("$.serverTime").exists());
    }

    @Test
    void badges_returnsZeroCountsWhenNothingPending() throws Exception {
        when(intakeService.countNew()).thenReturn(0);
        when(taskService.findPending()).thenReturn(List.of());

        mockMvc.perform(get("/api/ui/badges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.newIntake").value(0))
                .andExpect(jsonPath("$.counts.pendingTasks").value(0));
    }

    private Task pendingTask(Long id) {
        return new Task(id, null, "title", null, "PENDING", null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
