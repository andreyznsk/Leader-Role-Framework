package ru.andreyz.memoryservice.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.dto.AgentProposalResponse;
import ru.andreyz.memoryservice.service.AgentIntakeProposalService;
import ru.andreyz.memoryservice.service.IncidentService;

@Component
public class IncidentTools {

    private final IncidentService incidentService;
    private final AgentIntakeProposalService agentIntakeProposalService;

    public IncidentTools(IncidentService incidentService, AgentIntakeProposalService agentIntakeProposalService) {
        this.incidentService = incidentService;
        this.agentIntakeProposalService = agentIntakeProposalService;
    }

    @Tool(description = "Create an incident proposal in Intake Gateway. This does not write directly to incidents.")
    public AgentProposalResponse proposeIncident(
            @ToolParam(description = "Incident title") String title,
            @ToolParam(description = "Severity: P1|P2|P3") String severity,
            @ToolParam(description = "Description", required = false) String description,
            @ToolParam(description = "Optional status", required = false) String status,
            @ToolParam(description = "Optional run/session/source identifier", required = false) String sourceId) {
        return agentIntakeProposalService.createIncidentProposal(
                null, title, severity, description, status, null, null, sourceId);
    }

    @Tool(description = "Create an incident resolution/update proposal in Intake Gateway for an existing incident.")
    public AgentProposalResponse proposeIncidentUpdate(
            @ToolParam(description = "Incident ID") Long id,
            @ToolParam(description = "Root cause", required = false) String rootCause,
            @ToolParam(description = "Action items to prevent recurrence", required = false) String actionItems,
            @ToolParam(description = "Optional updated title", required = false) String title,
            @ToolParam(description = "Optional updated severity: P1|P2|P3", required = false) String severity,
            @ToolParam(description = "Optional updated description", required = false) String description,
            @ToolParam(description = "Optional updated status", required = false) String status,
            @ToolParam(description = "Optional run/session/source identifier", required = false) String sourceId) {
        var existing = incidentService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Incident not found: " + id));
        return agentIntakeProposalService.createIncidentProposal(
                existing.id(),
                title != null ? title : existing.title(),
                severity != null ? severity : existing.severity(),
                description != null ? description : existing.description(),
                status != null ? status : existing.status(),
                rootCause != null ? rootCause : existing.rootCause(),
                actionItems != null ? actionItems : existing.actionItems(),
                sourceId
        );
    }
}
