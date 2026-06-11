package ru.andreyz.memoryservice.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.andreyz.memoryservice.dto.ContextDto;
import ru.andreyz.memoryservice.service.TaskService;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class McpContextToolTest {

    @Autowired
    private ContextTools contextTools;

    @Autowired
    private TaskService taskService;

    @Test
    void getContext_returnsTodayPlan() {
        taskService.createConfirmed(LocalDate.now(), "Контекстная задача",
                "HIGH", null, "AGENT", null);

        ContextDto context = contextTools.getContext();

        assertThat(context).isNotNull();
        assertThat(context.today()).isEqualTo(LocalDate.now());
        assertThat(context.todayPlan()).isNotNull();
    }

    @Test
    void getContext_doesNotIncludePendingTasks() {
        taskService.createPending("Pending задача", "Описание", "msg-001", "test@test.com", "NORMAL");

        ContextDto context = contextTools.getContext();

        // PENDING tasks must not appear in the context
        assertThat(context.todayPlan().tasks())
                .noneMatch(t -> "PENDING".equals(t.status()));
    }
}
