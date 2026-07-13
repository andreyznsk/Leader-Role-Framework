package ru.andreyz.memoryservice.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.andreyz.memoryservice.support.JiraStubServer;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "jira.enabled=true",
        "jira.base-url=http://localhost:19997",
        "jira.token=test-token",
        "jira.default-project=ENG",
        "jira.allowed-projects=ENG,OPS",
        "jira.default-issue-type=Task"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskJiraUiTest {

    static {
        try {
            JiraStubServer.ensureStarted();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetStub() {
        JiraStubServer.reset();
    }

    @Test
    void todayAndEditPageRenderJiraActions() throws Exception {
        String today = LocalDate.now().toString();
        String task = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"UI Jira task","date":"%s","priority":"NORMAL","source":"MANUAL"}
                                """.formatted(today)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String taskId = task.replaceAll(".*\"id\":(\\d+).*", "$1");

        mockMvc.perform(get("/ui/today"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Создать задачу в Jira")))
                .andExpect(content().string(containsString("jiraIssueModal")));

        mockMvc.perform(get("/ui/tasks/{id}/edit", taskId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Создать задачу в Jira")))
                .andExpect(content().string(containsString("task-edit-jira-modal")));
    }
}
