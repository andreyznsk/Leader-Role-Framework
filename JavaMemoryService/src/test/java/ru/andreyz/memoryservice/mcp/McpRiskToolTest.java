package ru.andreyz.memoryservice.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.andreyz.memoryservice.dto.AgentProposalResponse;
import ru.andreyz.memoryservice.dto.IntakeApplyRequest;
import ru.andreyz.memoryservice.service.IntakeService;
import ru.andreyz.memoryservice.service.RiskService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class McpRiskToolTest {

    @Autowired
    private RiskTools riskTools;

    @Autowired
    private IntakeService intakeService;

    @Autowired
    private RiskService riskService;

    @Test
    void proposeRisk_createsAgentIntakeItem() {
        AgentProposalResponse proposal = riskTools.proposeRisk(
                "Single deploy owner",
                "Only one person knows prod deploy flow",
                "MEDIUM",
                "HIGH",
                "Document the procedure",
                "run-risk-1"
        );

        var intakeItem = intakeService.get(proposal.intakeId());
        assertThat(intakeItem.sourceType()).isEqualTo("AGENT_MCP");
        assertThat(intakeItem.suggestedRoute()).isEqualTo("RISK");
        assertThat(intakeItem.suggestedPayload().get("title").asText()).isEqualTo("Single deploy owner");
        assertThat(intakeItem.suggestedPayload().get("mitigation").asText()).isEqualTo("Document the procedure");
    }

    @Test
    void applyRiskUpdateProposal_updatesRisk() {
        var existing = riskService.create("Stale backups", "Backups are not verified", "LOW", "HIGH");

        AgentProposalResponse proposal = riskTools.proposeRiskUpdate(
                existing.id(),
                "MITIGATED",
                "Weekly restore test",
                null,
                null,
                null,
                null,
                "run-risk-2"
        );

        intakeService.apply(proposal.intakeId(), new IntakeApplyRequest(null, null, "reviewer"));

        var updated = riskService.findById(existing.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo("MITIGATED");
        assertThat(updated.mitigation()).isEqualTo("Weekly restore test");
    }
}
