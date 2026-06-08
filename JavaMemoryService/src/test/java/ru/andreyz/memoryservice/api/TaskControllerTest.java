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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Test task"))
                .andExpect(jsonPath("$.status").value("TODO"));

        mockMvc.perform(get("/api/tasks").param("date", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Test task')]").exists());
    }

    @Test
    void createPendingTask() throws Exception {
        mockMvc.perform(post("/api/tasks/pending")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Email task","emailId":"msg-123","sender":"test@test.com","priority":"NORMAL"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.source").value("EMAIL"));
    }

    @Test
    void getPendingTasks() throws Exception {
        mockMvc.perform(get("/api/tasks/pending"))
                .andExpect(status().isOk());
    }
}
