package ru.andreyz.memoryservice.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.domain.Risk;
import ru.andreyz.memoryservice.service.RiskService;

@Component
public class RiskTools {

    private final RiskService riskService;

    public RiskTools(RiskService riskService) {
        this.riskService = riskService;
    }

    @Tool(description = "Add a new risk to the risk register. Call only after explicit user confirmation.")
    public Risk addRisk(
            @ToolParam(description = "Risk title") String title,
            @ToolParam(description = "Risk description", required = false) String description,
            @ToolParam(description = "Probability: LOW|MEDIUM|HIGH", required = false) String probability,
            @ToolParam(description = "Impact: LOW|MEDIUM|HIGH", required = false) String impact) {
        return riskService.create(title, description, probability, impact);
    }

    @Tool(description = "Update risk status or mitigation plan. Call only after explicit user confirmation.")
    public Risk updateRisk(
            @ToolParam(description = "Risk ID") Long id,
            @ToolParam(description = "New status: OPEN|MITIGATED|ACCEPTED|CLOSED", required = false) String status,
            @ToolParam(description = "Mitigation plan", required = false) String mitigation) {
        Risk existing = riskService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Risk not found: " + id));
        Risk updated = new Risk(existing.id(), existing.title(), existing.description(),
                existing.probability(), existing.impact(),
                status != null ? status : existing.status(),
                mitigation != null ? mitigation : existing.mitigation(),
                existing.createdAt(), java.time.Instant.now());
        return riskService.update(id, updated);
    }
}
