package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.PeopleNote;
import ru.andreyz.memoryservice.domain.Person;
import ru.andreyz.memoryservice.repository.PeopleNoteRepository;
import ru.andreyz.memoryservice.repository.PersonRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class PeopleService {

    private final PersonRepository personRepository;
    private final PeopleNoteRepository noteRepository;

    public PeopleService(PersonRepository personRepository, PeopleNoteRepository noteRepository) {
        this.personRepository = personRepository;
        this.noteRepository = noteRepository;
    }

    public Person create(Person person) {
        Person toSave = new Person(null, person.fullName(), person.login(), person.email(),
                person.phone(), person.domain(), person.currentTask(),
                person.capacitySprint(), person.capacityMonth(), person.capacityQuarter(),
                person.notes(), Instant.now(), Instant.now());
        return personRepository.save(toSave);
    }

    public Person update(Long id, Person updated) {
        Person existing = personRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Person not found: " + id));
        Person toSave = new Person(existing.id(), updated.fullName(), updated.login(),
                updated.email(), updated.phone(), updated.domain(), updated.currentTask(),
                updated.capacitySprint(), updated.capacityMonth(), updated.capacityQuarter(),
                updated.notes(), existing.createdAt(), Instant.now());
        return personRepository.save(toSave);
    }

    public PeopleNote addNote(Long personId, String note, String tags) {
        personRepository.findById(personId)
                .orElseThrow(() -> new IllegalArgumentException("Person not found: " + personId));
        PeopleNote toSave = new PeopleNote(null, personId, note, tags, Instant.now());
        return noteRepository.save(toSave);
    }

    public List<PeopleNote> getNotes(Long personId) {
        return noteRepository.findByPersonIdOrderByCreatedAtDesc(personId);
    }

    public List<Person> findAll() {
        return (List<Person>) personRepository.findAll();
    }

    public List<Person> search(String name) {
        return personRepository.findByFullNameContainingIgnoreCase(name);
    }

    public Optional<Person> findById(Long id) {
        return personRepository.findById(id);
    }

    public void delete(Long id) {
        personRepository.deleteById(id);
    }
}
