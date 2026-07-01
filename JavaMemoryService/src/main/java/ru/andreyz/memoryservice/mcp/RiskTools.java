package ru.andreyz.memoryservice.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.dto.AgentProposalResponse;
import ru.andreyz.memoryservice.service.AgentIntakeProposalService;
import ru.andreyz.memoryservice.service.RiskService;

@Component
public class RiskTools {

    private final RiskService riskService;
    private final AgentIntakeProposalService agentIntakeProposalService;

    public RiskTools(RiskService riskService, AgentIntakeProposalService agentIntakeProposalService) {
        this.riskService = riskService;
        this.agentIntakeProposalService = agentIntakeProposalService;
    }

    @Tool(description = "Create a risk proposal in Intake Gateway. This does not write directly to the risk register.")
    public AgentProposalResponse proposeRisk(
            @ToolParam(description = "Risk title") String title,
            @ToolParam(description = "Risk description", required = false) String description,
            @ToolParam(description = "Probability: LOW|MEDIUM|HIGH", required = false) String probability,
            @ToolParam(description = "Impact: LOW|MEDIUM|HIGH", required = false) String impact,
            @ToolParam(description = "Optional mitigation plan", required = false) String mitigation,
            @ToolParam(description = "Optional run/session/source identifier", required = false) String sourceId) {
        return agentIntakeProposalService.createRiskProposal(
                null, title, description, probability, impact, null, mitigation, sourceId);
    }

    @Tool(description = "Create a risk update proposal in Intake Gateway for an existing risk.")
    public AgentProposalResponse proposeRiskUpdate(
            @ToolParam(description = "Risk ID") Long id,
            @ToolParam(description = "New status: OPEN|MITIGATED|ACCEPTED|CLOSED", required = false) String status,
            @ToolParam(description = "Mitigation plan", required = false) String mitigation,
            @ToolParam(description = "Optional updated title", required = false) String title,
            @ToolParam(description = "Optional updated description", required = false) String description,
            @ToolParam(description = "Optional updated probability: LOW|MEDIUM|HIGH", required = false) String probability,
            @ToolParam(description = "Optional updated impact: LOW|MEDIUM|HIGH", required = false) String impact,
            @ToolParam(description = "Optional run/session/source identifier", required = false) String sourceId) {
        var existing = riskService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Risk not found: " + id));
        return agentIntakeProposalService.createRiskProposal(
                existing.id(),
                title != null ? title : existing.title(),
                description != null ? description : existing.description(),
                probability != null ? probability : existing.probability(),
                impact != null ? impact : existing.impact(),
                status != null ? status : existing.status(),
                mitigation != null ? mitigation : existing.mitigation(),
                sourceId
        );
    }
}
