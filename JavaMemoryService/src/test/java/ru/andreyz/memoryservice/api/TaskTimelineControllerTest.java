package ru.andreyz.memoryservice.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskTimelineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsTaskTimelineForLifecycleChanges() throws Exception {
        String today = LocalDate.now().toString();
        String created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Timeline task","date":"%s","priority":"NORMAL","source":"MANUAL"}
                                """.formatted(today)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String taskId = created.replaceAll(".*\"id\":(\\d+).*", "$1");

        mockMvc.perform(patch("/api/tasks/{id}/status", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_PROGRESS"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tasks/{id}/timeline/comment", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Need to coordinate release"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/{id}/timeline", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("COMMENT_ADDED"))
                .andExpect(jsonPath("$[0].newValue.text").value("Need to coordinate release"))
                .andExpect(jsonPath("$[1].eventType").value("STATUS_CHANGED"))
                .andExpect(jsonPath("$[1].oldValue.status").value("TODO"))
                .andExpect(jsonPath("$[1].newValue.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[2].eventType").value("TASK_CREATED"));
    }

    @Test
    void deleteEndpointArchivesTaskAndCreatesTimelineEvent() throws Exception {
        String today = LocalDate.now().toString();
        String created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Archive task","date":"%s","priority":"NORMAL","source":"MANUAL"}
                                """.formatted(today)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String taskId = created.replaceAll(".*\"id\":(\\d+).*", "$1");

        mockMvc.perform(post("/api/tasks/{id}/delete", taskId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/tasks/{id}/timeline", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("TASK_ARCHIVED"))
                .andExpect(jsonPath("$[0].newValue.status").value("ARCHIVED"))
                .andExpect(jsonPath("$[1].eventType").value("TASK_CREATED"));
    }
}
