package ru.andreyz.memoryservice.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createAndGetTask() throws Exception {
        String today = LocalDate.now().toString();

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Test task","date":"%s","priority":"HIGH","source":"MANUAL"}
                                """.formatted(today)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Test task"))
                .andExpect(jsonPath("$.status").value("TODO"));

        mockMvc.perform(get("/api/tasks").param("date", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Test task')]").exists());
    }

    @Test
    void createPendingTask() throws Exception {
        String dueDate = LocalDate.now().plusDays(3).toString();

        mockMvc.perform(post("/api/tasks/pending")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Email task","emailId":"msg-123","sender":"test@test.com","priority":"NORMAL","dueDate":"%s"}
                """.formatted(dueDate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.source").value("EMAIL"))
                .andExpect(jsonPath("$.dueDate").value(dueDate));
    }

    @Test
    void getPendingTasks() throws Exception {
        mockMvc.perform(get("/api/tasks/pending"))
                .andExpect(status().isOk());
    }

    @Test
    void toggleDone_reopensDoneTaskAsTodo() throws Exception {
        String today = LocalDate.now().toString();

        String created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Toggle task","date":"%s","priority":"NORMAL","source":"MANUAL"}
                                """.formatted(today)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String taskId = created.replaceAll(".*\"id\":(\\d+).*", "$1");

        mockMvc.perform(post("/api/tasks/{id}/done", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        mockMvc.perform(post("/api/tasks/{id}/toggle-done", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TODO"));
    }
}
