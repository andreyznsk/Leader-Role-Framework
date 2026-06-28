package ru.andreyz.memoryservice.search.provider;

import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.domain.Person;
import ru.andreyz.memoryservice.repository.PeopleNoteRepository;
import ru.andreyz.memoryservice.repository.PersonRepository;
import ru.andreyz.memoryservice.search.SearchLayer;
import ru.andreyz.memoryservice.search.SearchProvider;
import ru.andreyz.memoryservice.search.SearchResultItem;

import java.util.ArrayList;
import java.util.List;

@Component
public class PeopleSearchProvider implements SearchProvider {

    private final PersonRepository personRepository;
    private final PeopleNoteRepository peopleNoteRepository;

    public PeopleSearchProvider(PersonRepository personRepository, PeopleNoteRepository peopleNoteRepository) {
        this.personRepository = personRepository;
        this.peopleNoteRepository = peopleNoteRepository;
    }

    @Override
    public SearchLayer layer() {
        return SearchLayer.PEOPLE;
    }

    @Override
    public List<SearchResultItem> search(String query, int limit) {
        String q = query.toLowerCase();
        var results = new ArrayList<SearchResultItem>();

        personRepository.findAll().forEach(person -> {
            double score = scorePerson(q, person);
            if (score > 0) {
                String snippet = buildSnippet(person);
                results.add(new SearchResultItem(
                        SearchLayer.PEOPLE,
                        person.fullName(),
                        snippet,
                        "/ui/people",
                        String.valueOf(person.id()),
                        "PERSON",
                        score,
                        person.updatedAt()
                ));
            }
        });

        peopleNoteRepository.findAll().forEach(note -> {
            if (note.note() != null && note.note().toLowerCase().contains(q)) {
                results.add(new SearchResultItem(
                        SearchLayer.PEOPLE,
                        "Note for person #" + note.personId(),
                        note.note(),
                        "/ui/people",
                        String.valueOf(note.id()),
                        "PEOPLE_NOTE",
                        0.45,
                        note.createdAt()
                ));
            }
        });

        return results.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .toList();
    }

    private double scorePerson(String query, Person person) {
        if (person.fullName() != null && person.fullName().toLowerCase().contains(query)) return 0.90;
        if (person.domain() != null && person.domain().toLowerCase().contains(query)) return 0.60;
        if (person.notes() != null && person.notes().toLowerCase().contains(query)) return 0.50;
        if (person.currentTask() != null && person.currentTask().toLowerCase().contains(query)) return 0.45;
        return 0.0;
    }

    private String buildSnippet(Person person) {
        var parts = new ArrayList<String>();
        if (person.domain() != null) parts.add(person.domain());
        if (person.currentTask() != null) parts.add(person.currentTask());
        if (person.notes() != null) parts.add(person.notes());
        return String.join(" · ", parts);
    }
}
