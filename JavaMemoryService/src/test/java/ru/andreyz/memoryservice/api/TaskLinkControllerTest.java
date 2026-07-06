package ru.andreyz.memoryservice.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskLinkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private long createTask(String title) throws Exception {
        String today = LocalDate.now().toString();
        String created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","date":"%s","priority":"NORMAL","source":"MANUAL"}
                                """.formatted(title, today)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void createLinkThenSeeMirroredOnOtherSideThenDelete() throws Exception {
        long taskA = createTask("Task A");
        long taskB = createTask("Task B");

        String created = mockMvc.perform(post("/api/tasks/{id}/links", taskA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toTaskId":%d,"linkType":"BLOCKS"}
                                """.formatted(taskB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("OUT"))
                .andExpect(jsonPath("$.linkType").value("BLOCKS"))
                .andExpect(jsonPath("$.relatedTaskId").value(taskB))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long linkId = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(get("/api/tasks/{id}/links", taskA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].direction").value("OUT"))
                .andExpect(jsonPath("$[0].linkType").value("BLOCKS"));

        mockMvc.perform(get("/api/tasks/{id}/links", taskB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].direction").value("IN"))
                .andExpect(jsonPath("$[0].linkType").value("BLOCKED_BY"))
                .andExpect(jsonPath("$[0].relatedTaskId").value(taskA));

        mockMvc.perform(delete("/api/tasks/{id}/links/{linkId}", taskA, linkId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/tasks/{id}/links", taskA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsDuplicateLink() throws Exception {
        long taskA = createTask("Task A dup");
        long taskB = createTask("Task B dup");

        mockMvc.perform(post("/api/tasks/{id}/links", taskA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toTaskId\":%d,\"linkType\":\"RELATES_TO\"}".formatted(taskB)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/tasks/{id}/links", taskA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toTaskId\":%d,\"linkType\":\"RELATES_TO\"}".formatted(taskB)))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsSelfLink() throws Exception {
        long taskA = createTask("Task A self");

        mockMvc.perform(post("/api/tasks/{id}/links", taskA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toTaskId\":%d,\"linkType\":\"RELATES_TO\"}".formatted(taskA)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownLinkType() throws Exception {
        long taskA = createTask("Task A badtype");
        long taskB = createTask("Task B badtype");

        mockMvc.perform(post("/api/tasks/{id}/links", taskA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toTaskId\":%d,\"linkType\":\"FOO\"}".formatted(taskB)))
                .andExpect(status().isBadRequest());
    }
}
