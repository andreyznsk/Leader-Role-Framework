package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Person;
import ru.andreyz.memoryservice.dto.AgentProposalResponse;
import ru.andreyz.memoryservice.dto.IntakeCreateRequest;
import ru.andreyz.memoryservice.dto.IntakeItemDto;

@Service
public class AgentIntakeProposalService {

    private static final String SOURCE_TYPE = "AGENT_MCP";
    private static final String CREATED_BY = "agent-mcp";
    private static final String REVIEW_URL = "/ui/intake";
    private static final String DEFAULT_AGENT_PROVIDER = "codex";
    private static final String PROPOSAL_MESSAGE = "Proposal created in Intake Gateway. Open /ui/intake to review and apply.";

    private final IntakeService intakeService;
    private final PeopleService peopleService;
    private final ObjectMapper objectMapper;
    private final String agentProvider;

    public AgentIntakeProposalService(IntakeService intakeService,
                                      PeopleService peopleService,
                                      ObjectMapper objectMapper,
                                      @Value("${app.mcp.agent-provider:" + DEFAULT_AGENT_PROVIDER + "}") String agentProvider) {
        this.intakeService = intakeService;
        this.peopleService = peopleService;
        this.objectMapper = objectMapper;
        this.agentProvider = agentProvider;
    }

    public AgentProposalResponse createTaskProposal(String title,
                                                    String date,
                                                    String priority,
                                                    String description,
                                                    String sourceId) {
        ObjectNode sourcePayload = objectMapper.createObjectNode()
                .put("tool", "proposeTask")
                .put("title", title)
                .put("date", date);
        putIfPresent(sourcePayload, "priority", priority);
        putIfPresent(sourcePayload, "description", description);

        ObjectNode suggestedPayload = objectMapper.createObjectNode()
                .put("title", title)
                .put("date", date);
        putIfPresent(suggestedPayload, "dueDate", date);
        putIfPresent(suggestedPayload, "priority", priority);
        putIfPresent(suggestedPayload, "description", description);

        return createProposal("TASK", sourceId, sourcePayload, suggestedPayload);
    }

    public AgentProposalResponse createRiskProposal(Long riskId,
                                                    String title,
                                                    String description,
                                                    String probability,
                                                    String impact,
                                                    String status,
                                                    String mitigation,
                                                    String sourceId) {
        ObjectNode sourcePayload = objectMapper.createObjectNode()
                .put("tool", "proposeRisk")
                .put("title", title);
        putIfPresent(sourcePayload, "description", description);
        putIfPresent(sourcePayload, "probability", probability);
        putIfPresent(sourcePayload, "impact", impact);
        putIfPresent(sourcePayload, "status", status);
        putIfPresent(sourcePayload, "mitigation", mitigation);
        putIfPresent(sourcePayload, "riskId", riskId);

        ObjectNode suggestedPayload = objectMapper.createObjectNode()
                .put("title", title);
        putIfPresent(suggestedPayload, "description", description);
        putIfPresent(suggestedPayload, "probability", probability);
        putIfPresent(suggestedPayload, "impact", impact);
        putIfPresent(suggestedPayload, "status", status);
        putIfPresent(suggestedPayload, "mitigation", mitigation);
        putIfPresent(suggestedPayload, "riskId", riskId);

        return createProposal("RISK", sourceId, sourcePayload, suggestedPayload);
    }

    public AgentProposalResponse createIncidentProposal(Long incidentId,
                                                        String title,
                                                        String severity,
                                                        String description,
                                                        String status,
                                                        String rootCause,
                                                        String actionItems,
                                                        String sourceId) {
        ObjectNode sourcePayload = objectMapper.createObjectNode()
                .put("tool", "proposeIncident")
                .put("title", title);
        putIfPresent(sourcePayload, "severity", severity);
        putIfPresent(sourcePayload, "description", description);
        putIfPresent(sourcePayload, "status", status);
        putIfPresent(sourcePayload, "rootCause", rootCause);
        putIfPresent(sourcePayload, "actionItems", actionItems);
        putIfPresent(sourcePayload, "incidentId", incidentId);

        ObjectNode suggestedPayload = objectMapper.createObjectNode()
                .put("title", title);
        putIfPresent(suggestedPayload, "severity", severity);
        putIfPresent(suggestedPayload, "description", description);
        putIfPresent(suggestedPayload, "status", status);
        putIfPresent(suggestedPayload, "rootCause", rootCause);
        putIfPresent(suggestedPayload, "actionItems", actionItems);
        putIfPresent(suggestedPayload, "incidentId", incidentId);

        return createProposal("INCIDENT", sourceId, sourcePayload, suggestedPayload);
    }

    public AgentProposalResponse createPersonNoteProposal(Long personId,
                                                          String note,
                                                          String tags,
                                                          String sourceId) {
        Person person = peopleService.findById(personId)
                .orElseThrow(() -> new IllegalArgumentException("Person not found: " + personId));

        ObjectNode sourcePayload = objectMapper.createObjectNode()
                .put("tool", "proposePersonNote")
                .put("personId", personId)
                .put("note", note);
        putIfPresent(sourcePayload, "tags", tags);

        ObjectNode suggestedPayload = objectMapper.createObjectNode()
                .put("personId", personId)
                .put("personName", person.fullName())
                .put("note", note);
        putIfPresent(suggestedPayload, "tags", tags);

        return createProposal("PERSON", sourceId, sourcePayload, suggestedPayload);
    }

    public AgentProposalResponse createTaskLinkProposal(Long fromTaskId,
                                                        Long toTaskId,
                                                        String linkType,
                                                        String reason,
                                                        String sourceId) {
        ObjectNode sourcePayload = objectMapper.createObjectNode()
                .put("tool", "proposeTaskLink")
                .put("fromTaskId", fromTaskId)
                .put("toTaskId", toTaskId)
                .put("linkType", linkType);
        putIfPresent(sourcePayload, "reason", reason);

        ObjectNode suggestedPayload = objectMapper.createObjectNode()
                .put("fromTaskId", fromTaskId)
                .put("toTaskId", toTaskId)
                .put("linkType", linkType);
        putIfPresent(suggestedPayload, "reason", reason);

        return createProposal("TASK_LINK", sourceId, sourcePayload, suggestedPayload);
    }

    private AgentProposalResponse createProposal(String route,
                                                 String sourceId,
                                                 ObjectNode sourcePayload,
                                                 ObjectNode suggestedPayload) {
        IntakeItemDto created = intakeService.create(new IntakeCreateRequest(
                SOURCE_TYPE,
                blankToNull(sourceId),
                sourcePayload,
                agentProvider,
                null,
                null,
                route,
                suggestedPayload,
                null,
                CREATED_BY
        ));
        return new AgentProposalResponse(created.id(), created.status(), created.suggestedRoute(), REVIEW_URL, PROPOSAL_MESSAGE);
    }

    private void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private void putIfPresent(ObjectNode node, String field, Long value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
