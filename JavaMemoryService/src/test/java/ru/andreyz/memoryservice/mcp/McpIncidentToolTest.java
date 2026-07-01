package ru.andreyz.memoryservice.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.andreyz.memoryservice.dto.AgentProposalResponse;
import ru.andreyz.memoryservice.dto.IntakeApplyRequest;
import ru.andreyz.memoryservice.service.IncidentService;
import ru.andreyz.memoryservice.service.IntakeService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class McpIncidentToolTest {

    @Autowired
    private IncidentTools incidentTools;

    @Autowired
    private IntakeService intakeService;

    @Autowired
    private IncidentService incidentService;

    @Test
    void proposeIncident_createsAgentIntakeItem() {
        AgentProposalResponse proposal = incidentTools.proposeIncident(
                "DB connection pool exhausted", "P1", "All connections busy", null, "run-incident-1");
        var intakeItem = intakeService.get(proposal.intakeId());

        assertThat(intakeItem.sourceType()).isEqualTo("AGENT_MCP");
        assertThat(intakeItem.suggestedRoute()).isEqualTo("INCIDENT");
        assertThat(intakeItem.suggestedPayload().get("title").asText()).isEqualTo("DB connection pool exhausted");
        assertThat(intakeItem.suggestedPayload().get("severity").asText()).isEqualTo("P1");
    }

    @Test
    void applyIncidentUpdateProposal_updatesIncident() {
        var incident = incidentService.create("Slow queries", "P2", null);

        AgentProposalResponse proposal = incidentTools.proposeIncidentUpdate(
                incident.id(),
                "Missing index on orders.created_at",
                "Add index, monitor query time",
                null,
                null,
                null,
                "RESOLVED",
                "run-incident-2"
        );
        intakeService.apply(proposal.intakeId(), new IntakeApplyRequest(null, null, "reviewer"));

        var resolved = incidentService.findById(incident.id()).orElseThrow();
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.rootCause()).contains("Missing index");
    }
}
