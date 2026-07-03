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
    void createPendingTaskIsIdempotentByEmailId() throws Exception {
        String first = mockMvc.perform(post("/api/tasks/pending")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Email task","emailId":"msg-dup-123","sender":"test@test.com","priority":"NORMAL"}
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String second = mockMvc.perform(post("/api/tasks/pending")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Email task","emailId":"msg-dup-123","sender":"test@test.com","priority":"NORMAL"}
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String firstId = first.replaceAll(".*\"id\":(\\d+).*", "$1");
        String secondId = second.replaceAll(".*\"id\":(\\d+).*", "$1");
        org.junit.jupiter.api.Assertions.assertEquals(firstId, secondId);
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

    @Test
    void patchDate_updatesTaskDateAndDueDate() throws Exception {
        String initialDate = LocalDate.now().minusDays(3).toString();
        String targetDate = LocalDate.now().plusDays(1).toString();

        String created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Patch date task","date":"%s","priority":"NORMAL","source":"MANUAL"}
                                """.formatted(initialDate)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String taskId = created.replaceAll(".*\"id\":(\\d+).*", "$1");

        mockMvc.perform(patch("/api/tasks/{id}/date", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date":"%s"}
                                """.formatted(targetDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.parseLong(taskId)))
                .andExpect(jsonPath("$.date").value(targetDate))
                .andExpect(jsonPath("$.dueDate").value(targetDate));
    }

    @Test
    void moveOverdueToToday_movesOnlyActiveOverdueTasks() throws Exception {
        String overdueTodoDate = LocalDate.now().minusDays(10).toString();
        String overdueDoneDate = LocalDate.now().minusDays(9).toString();

        String overdueTodo = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"API overdue todo","date":"%s","priority":"HIGH","source":"MANUAL"}
                                """.formatted(overdueTodoDate)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String overdueTodoId = overdueTodo.replaceAll(".*\"id\":(\\d+).*", "$1");

        String overdueDone = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"API overdue done","date":"%s","priority":"NORMAL","source":"MANUAL"}
                                """.formatted(overdueDoneDate)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String overdueDoneId = overdueDone.replaceAll(".*\"id\":(\\d+).*", "$1");
        mockMvc.perform(post("/api/tasks/{id}/done", overdueDoneId))
                .andExpect(status().isOk());

        String today = LocalDate.now().toString();

        mockMvc.perform(post("/api/tasks/move-overdue-to-today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved").isNumber())
                .andExpect(jsonPath("$.today").value(today));

        mockMvc.perform(get("/api/tasks").param("date", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %s && @.dueDate == '%s')]".formatted(overdueTodoId, today)).exists())
                .andExpect(jsonPath("$[?(@.id == %s)]".formatted(overdueDoneId)).doesNotExist());
    }

    @Test
    void delegatedTaskRequiresAssignedPerson() throws Exception {
        String today = LocalDate.now().toString();

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Delegated without owner","date":"%s","status":"DELEGATED","source":"MANUAL"}
                                """.formatted(today)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void taskSupportsAssignedPersonLabelsAndLabelFiltering() throws Exception {
        String personJson = mockMvc.perform(post("/api/people")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Иван Иванов","login":"ivanov"}
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String personId = personJson.replaceAll(".*\"id\":(\\d+).*", "$1");

        String backendLabelJson = mockMvc.perform(post("/api/task-labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Backend","color":"#60a5fa"}
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String releaseLabelJson = mockMvc.perform(post("/api/task-labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Release"}
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String backendLabelId = backendLabelJson.replaceAll(".*\"id\":(\\d+).*", "$1");
        String releaseLabelId = releaseLabelJson.replaceAll(".*\"id\":(\\d+).*", "$1");

        String today = LocalDate.now().toString();
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Delegated release",
                                  "date":"%s",
                                  "status":"DELEGATED",
                                  "assignedPersonId":%s,
                                  "labelIds":[%s,%s],
                                  "source":"MANUAL"
                                }
                                """.formatted(today, personId, backendLabelId, releaseLabelId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DELEGATED"))
                .andExpect(jsonPath("$.assignedPersonId").value(Long.parseLong(personId)))
                .andExpect(jsonPath("$.assignedPerson.fullName").value("Иван Иванов"))
                .andExpect(jsonPath("$.labelIds[0]").isNumber())
                .andExpect(jsonPath("$.labels[?(@.name == 'Backend')]").exists())
                .andExpect(jsonPath("$.labels[?(@.name == 'Release')]").exists());

        mockMvc.perform(get("/api/tasks")
                        .param("date", today)
                        .param("labelId", backendLabelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Delegated release')]").exists());
    }
}
