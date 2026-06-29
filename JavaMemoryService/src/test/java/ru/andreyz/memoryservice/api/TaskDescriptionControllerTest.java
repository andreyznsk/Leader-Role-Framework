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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskDescriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void jsonRoundTripAndExportWork() throws Exception {
        String today = LocalDate.now().toString();
        String created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Export task","date":"%s","priority":"HIGH","source":"MANUAL"}
                                """.formatted(today)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String taskId = created.replaceAll(".*\"id\":(\\d+).*", "$1");

        mockMvc.perform(put("/api/tasks/{id}/description", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentMd":"## Context\\nNeeds QA approval"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.contentMd").value("## Context\nNeeds QA approval"))
                .andExpect(jsonPath("$.contentHash").isString());

        mockMvc.perform(get("/api/tasks/{id}/description", taskId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentMd").value("## Context\nNeeds QA approval"));

        mockMvc.perform(get("/api/tasks/{id}/description/export-md", taskId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("TASK-" + taskId + ".md")))
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Needs QA approval")));
    }

    @Test
    void plainTextCompatibilityStillWorks() throws Exception {
        String today = LocalDate.now().toString();
        String created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Legacy desc","date":"%s","priority":"NORMAL","source":"MANUAL"}
                                """.formatted(today)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String taskId = created.replaceAll(".*\"id\":(\\d+).*", "$1");

        mockMvc.perform(put("/api/tasks/{id}/description", taskId)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("legacy body"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/{id}/description", taskId)
                        .accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isOk())
                .andExpect(content().string("legacy body"));
    }
}
