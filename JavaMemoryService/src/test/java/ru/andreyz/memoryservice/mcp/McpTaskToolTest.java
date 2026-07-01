package ru.andreyz.memoryservice.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.andreyz.memoryservice.dto.AgentProposalResponse;
import ru.andreyz.memoryservice.dto.IntakeApplyRequest;
import ru.andreyz.memoryservice.service.IntakeService;
import ru.andreyz.memoryservice.service.TaskService;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class McpTaskToolTest {

    @Autowired
    private TaskTools taskTools;

    @Autowired
    private IntakeService intakeService;

    @Autowired
    private TaskService taskService;

    @Test
    void proposeTask_createsAgentIntakeItem() {
        String today = LocalDate.now().toString();

        AgentProposalResponse created = taskTools.proposeTask("Провести 1-1", today, "HIGH", null, "run-task-1");
        var intakeItem = intakeService.get(created.intakeId());

        assertThat(created.status()).isEqualTo("NEW");
        assertThat(created.suggestedRoute()).isEqualTo("TASK");
        assertThat(intakeItem.sourceType()).isEqualTo("AGENT_MCP");
        assertThat(intakeItem.createdBy()).isEqualTo("agent-mcp");
        assertThat(intakeItem.suggestedRoute()).isEqualTo("TASK");
        assertThat(intakeItem.suggestedPayload().get("title").asText()).isEqualTo("Провести 1-1");
        assertThat(intakeItem.suggestedPayload().get("date").asText()).isEqualTo(today);
    }

    @Test
    void applyTaskProposal_createsOperationalTask() {
        String today = LocalDate.now().toString();
        AgentProposalResponse proposal = taskTools.proposeTask("Задача через intake", today, "HIGH", "Описание", "run-task-apply");

        intakeService.apply(proposal.intakeId(), new IntakeApplyRequest(null, null, "reviewer"));

        assertThat(taskService.findByDate(LocalDate.parse(today)))
                .anyMatch(task -> task.title().equals("Задача через intake") && "TODO".equals(task.status()));
    }
}
