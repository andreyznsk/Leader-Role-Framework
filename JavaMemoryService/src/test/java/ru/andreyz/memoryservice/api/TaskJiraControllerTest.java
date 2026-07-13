package ru.andreyz.memoryservice.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.andreyz.memoryservice.support.JiraStubServer;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
class TaskJiraControllerTest {

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
    void contextExposesOnlyCurrentUserAsAssigneeOption() throws Exception {
        String today = LocalDate.now().toString();
        String person = mockMvc.perform(post("/api/people")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Ivan Petrov","login":"ivan.petrov","email":"ivan.petrov@example.com"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String personId = person.replaceAll(".*\"id\":(\\d+).*", "$1");

        String task = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Prepare Jira flow","date":"%s","priority":"HIGH","source":"MANUAL","assignedPersonId":%s}
                                """.formatted(today, personId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String taskId = task.replaceFirst("(?s).*?\"id\":(\\d+).*", "$1");

        mockMvc.perform(get("/api/tasks/{id}/jira/context", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integrationStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.defaultProject").value("ENG"))
                .andExpect(jsonPath("$.projects[0].key").value("ENG"))
                .andExpect(jsonPath("$.projects[0].issueTypes[0].name").value("Bug"))
                .andExpect(jsonPath("$.projects[0].assignableUsers[?(@.accountId == 'leader-account')]").exists())
                .andExpect(jsonPath("$.projects[0].assignableUsers.length()").value(1))
                .andExpect(jsonPath("$.currentUser.accountId").value("leader-account"))
                .andExpect(jsonPath("$.matchedAssignee").isEmpty());
    }

    @Test
    void createIssuePersistsAndSecondCallReturnsExistingLink() throws Exception {
        String today = LocalDate.now().toString();
        String task = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Create Jira ticket","date":"%s","priority":"NORMAL","source":"MANUAL"}
                                """.formatted(today)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String taskId = task.replaceAll(".*\"id\":(\\d+).*", "$1");

        mockMvc.perform(post("/api/tasks/{id}/jira/issues", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectKey":"ENG","issueTypeId":"3","summary":"Create Jira ticket","description":"Ready"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.issue.key").value("ENG-124"))
                .andExpect(jsonPath("$.issue.url").value("http://localhost:19997/browse/ENG-124"));

        mockMvc.perform(post("/api/tasks/{id}/jira/issues", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectKey":"ENG","issueTypeId":"3","summary":"Create Jira ticket","description":"Ready"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.alreadyLinked").value(true))
                .andExpect(jsonPath("$.issue.key").value("ENG-124"));
    }

    @Test
    void jiraErrorsAreSanitized() throws Exception {
        JiraStubServer.failCreate(401, """
                {"errorMessages":["Bearer very-secret-token is invalid"],"errors":{}}
                """);
        String today = LocalDate.now().toString();
        String task = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Broken Jira","date":"%s","priority":"NORMAL","source":"MANUAL"}
                                """.formatted(today)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String taskId = task.replaceAll(".*\"id\":(\\d+).*", "$1");

        MvcResult result = mockMvc.perform(post("/api/tasks/{id}/jira/issues", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectKey":"ENG","issueTypeId":"3","summary":"Broken Jira","description":"desc"}
                                """))
                .andExpect(status().isBadGateway())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getErrorMessage())
                .doesNotContain("very-secret-token");
    }
}
