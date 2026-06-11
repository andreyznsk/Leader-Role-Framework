package ru.andreyz.memoryservice.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.domain.PeopleNote;
import ru.andreyz.memoryservice.domain.Person;
import ru.andreyz.memoryservice.service.PeopleService;

import java.util.List;

@Component
public class PeopleTools {

    private final PeopleService peopleService;

    public PeopleTools(PeopleService peopleService) {
        this.peopleService = peopleService;
    }

    @Tool(description = "Add a chronological note about a team member or stakeholder (e.g. after 1-1, meeting observation)")
    public PeopleNote addPeopleNote(
            @ToolParam(description = "Person ID") Long personId,
            @ToolParam(description = "Note text") String note,
            @ToolParam(description = "Comma-separated tags: trust,blocker,key-person", required = false) String tags) {
        return peopleService.addNote(personId, note, tags);
    }

    @Tool(description = "Search for a person by name to get their profile and recent notes")
    public List<Person> searchPeople(
            @ToolParam(description = "Name or part of name to search") String name) {
        return peopleService.search(name);
    }
}
