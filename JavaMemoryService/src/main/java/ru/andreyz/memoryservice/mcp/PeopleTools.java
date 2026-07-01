package ru.andreyz.memoryservice.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.domain.Person;
import ru.andreyz.memoryservice.dto.AgentProposalResponse;
import ru.andreyz.memoryservice.service.AgentIntakeProposalService;
import ru.andreyz.memoryservice.service.PeopleService;

import java.util.List;

@Component
public class PeopleTools {

    private final PeopleService peopleService;
    private final AgentIntakeProposalService agentIntakeProposalService;

    public PeopleTools(PeopleService peopleService, AgentIntakeProposalService agentIntakeProposalService) {
        this.peopleService = peopleService;
        this.agentIntakeProposalService = agentIntakeProposalService;
    }

    @Tool(description = "Create a person note proposal in Intake Gateway. This does not write directly to people notes.")
    public AgentProposalResponse proposePersonNote(
            @ToolParam(description = "Person ID") Long personId,
            @ToolParam(description = "Note text") String note,
            @ToolParam(description = "Comma-separated tags: trust,blocker,key-person", required = false) String tags,
            @ToolParam(description = "Optional run/session/source identifier", required = false) String sourceId) {
        return agentIntakeProposalService.createPersonNoteProposal(personId, note, tags, sourceId);
    }

    @Tool(description = "Search for a person by name to get their profile and recent notes")
    public List<Person> searchPeople(
            @ToolParam(description = "Name or part of name to search") String name) {
        return peopleService.search(name);
    }
}
