package ru.andreyz.memoryservice.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.domain.Incident;
import ru.andreyz.memoryservice.service.IncidentService;

@Component
public class IncidentTools {

    private final IncidentService incidentService;

    public IncidentTools(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @Tool(description = "Create a new incident. Call only after explicit user confirmation.")
    public Incident createIncident(
            @ToolParam(description = "Incident title") String title,
            @ToolParam(description = "Severity: P1|P2|P3") String severity,
            @ToolParam(description = "Description", required = false) String description) {
        return incidentService.create(title, severity, description);
    }

    @Tool(description = "Resolve incident with root cause analysis. Call only after explicit user confirmation.")
    public Incident resolveIncident(
            @ToolParam(description = "Incident ID") Long id,
            @ToolParam(description = "Root cause") String rootCause,
            @ToolParam(description = "Action items to prevent recurrence") String actionItems) {
        return incidentService.resolve(id, rootCause, actionItems);
    }
}
