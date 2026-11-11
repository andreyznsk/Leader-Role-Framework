package ru.andreyz.memoryservice.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.dto.TaskRangeRequest;
import ru.andreyz.memoryservice.service.TaskRangeException;
import ru.andreyz.memoryservice.service.TaskService;

import java.time.LocalDate;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TaskRangeTest {
    @Autowired MockMvc mvc;
    @Autowired TaskService tasks;
    @Autowired ToolCallbackProvider memoryTools;

    private Task create(String dueDate, String status) {
        // Deliberately put tasks in a plan outside the range: filtering must use dueDate.
        return tasks.createConfirmed(LocalDate.of(2040, 1, 1), "range-test", "NORMAL", null,
                "MANUAL", null, LocalDate.parse(dueDate), status);
    }

    @Test
    void rangeUsesDueDateIncludesBoundariesAndFiltersStatus() throws Exception {
        Task first = create("2041-01-01", "TODO");
        Task last = create("2041-03-31", "DONE");
        create("2040-12-31", "TODO");
        create("2041-04-01", "DONE");
        assertThat(tasks.findByRange(LocalDate.parse("2041-01-01"), LocalDate.parse("2041-03-31"), null))
                .extracting(Task::id).containsExactly(first.id(), last.id());
        mvc.perform(post("/api/tasks/range").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2041-01-01\",\"to\":\"2041-03-31\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first.id()));
        mvc.perform(post("/api/tasks/range").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2041-01-01\",\"to\":\"2041-03-31\",\"status\":\"DONE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(last.id()));
        // Existing daily contract still selects by plan date.
        mvc.perform(get("/api/tasks").param("date", "2040-01-01").param("status", "DONE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void emptySingleDayAndMaximumRange() {
        LocalDate from = LocalDate.of(2042, 1, 1);
        assertThat(tasks.findByRange(from, from.plusDays(365), null)).isEmpty();
        Task task = create(from.toString(), "BLOCKED");
        assertThat(tasks.findByRange(from, from, "BLOCKED")).extracting(Task::id).containsExactly(task.id());
    }

    @ParameterizedTest
    @CsvSource({
            "2041-02-01,2041-01-01,TODO,invalid_date_range",
            "2041-01-01,2042-01-02,TODO,range_too_large",
            "2041-02-30,2041-03-01,TODO,invalid_date_format",
            "2041-1-01,2041-03-01,TODO,invalid_date_format",
            "2041-01-01,2041-13-01,TODO,invalid_date_format",
            "2041-01-01,2041-03-01,UNKNOWN,invalid_status",
            "2041-01-01,2041-03-01,'',invalid_status"
    })
    void validationReturnsStableCodes(String from, String to, String filter, String code) throws Exception {
        mvc.perform(post("/api/tasks/range").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"%s\",\"to\":\"%s\",\"status\":\"%s\"}".formatted(from, to, filter)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void missingDatesAreInvalid() throws Exception {
        for (String body : new String[]{"{}", "{\"from\":\"2041-01-01\"}", "{\"to\":\"2041-01-01\"}",
                "{\"from\":null,\"to\":\"2041-01-01\"}"}) {
            mvc.perform(post("/api/tasks/range").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_date_format"));
        }
        assertThatThrownBy(() -> tasks.findByRange(null, LocalDate.now(), null)).isInstanceOf(TaskRangeException.class);
        assertThatThrownBy(() -> TaskRangeRequest.parseDate("2026-11-11T00:00:00"))
                .isInstanceOf(TaskRangeException.class);
    }

    @Test
    void registeredMcpCallbackHasOptionalStatusAndExecutes() {
        Task task = create("2043-01-01", "DONE");
        var tool = Arrays.stream(memoryTools.getToolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equals("getTasksInRange"))
                .findFirst().orElseThrow();
        assertThat(tool.getToolDefinition().inputSchema()).contains("from", "to", "status");
        assertThat(tool.call("{\"from\":\"2043-01-01\",\"to\":\"2043-01-01\"}"))
                .contains("\"id\":" + task.id(), "DONE");
        var daily = Arrays.stream(memoryTools.getToolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equals("getTasks"))
                .findFirst().orElseThrow();
        assertThat(daily.call("{\"date\":\"2040-01-01\",\"status\":\"DONE\"}"))
                .contains("\"id\":" + task.id());
    }
}
