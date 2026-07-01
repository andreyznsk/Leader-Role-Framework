package ru.andreyz.memoryservice.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.andreyz.memoryservice.domain.Person;
import ru.andreyz.memoryservice.dto.AgentProposalResponse;
import ru.andreyz.memoryservice.repository.PersonNameNoteRepository;
import ru.andreyz.memoryservice.service.IntakeService;
import ru.andreyz.memoryservice.service.PeopleService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class McpPeopleToolTest {

    @Autowired
    private PeopleTools peopleTools;

    @Autowired
    private PeopleService peopleService;

    @Autowired
    private IntakeService intakeService;

    @Autowired
    private PersonNameNoteRepository personNameNoteRepository;

    @Test
    void proposePersonNote_createsAgentIntakeItemWithResolvedName() {
        Person person = peopleService.create(new Person(
                null,
                "Иван Петров",
                "ipetrov",
                "ivan@example.com",
                null,
                "backend",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        AgentProposalResponse proposal = peopleTools.proposePersonNote(
                person.id(),
                "Нужен регулярный sync по релизам",
                "trust,key-person",
                "run-person-1"
        );

        var intakeItem = intakeService.get(proposal.intakeId());
        assertThat(intakeItem.suggestedRoute()).isEqualTo("PERSON");
        assertThat(intakeItem.suggestedPayload().get("personName").asText()).isEqualTo("Иван Петров");
        assertThat(personNameNoteRepository.findByPersonNameIgnoreCase("Иван Петров")).isEmpty();
    }
}
