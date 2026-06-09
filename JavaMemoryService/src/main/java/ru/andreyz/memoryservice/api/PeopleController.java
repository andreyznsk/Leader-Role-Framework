package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.PeopleNote;
import ru.andreyz.memoryservice.domain.Person;
import ru.andreyz.memoryservice.domain.PersonNameNote;
import ru.andreyz.memoryservice.repository.PersonNameNoteRepository;
import ru.andreyz.memoryservice.service.PeopleService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/people")
public class PeopleController {

    private final PeopleService peopleService;
    private final PersonNameNoteRepository personNameNoteRepository;

    public PeopleController(PeopleService peopleService,
                            PersonNameNoteRepository personNameNoteRepository) {
        this.peopleService = peopleService;
        this.personNameNoteRepository = personNameNoteRepository;
    }

    @GetMapping
    public ResponseEntity<List<Person>> getPeople(@RequestParam(required = false) String name) {
        List<Person> people = name != null
                ? peopleService.search(name)
                : peopleService.findAll();
        return ResponseEntity.ok(people);
    }

    @PostMapping
    public ResponseEntity<Person> create(@RequestBody Person person) {
        return ResponseEntity.ok(peopleService.create(person));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Person> update(@PathVariable Long id, @RequestBody Person person) {
        return ResponseEntity.ok(peopleService.update(id, person));
    }

    @GetMapping("/{id}/notes")
    public ResponseEntity<List<PeopleNote>> getNotes(@PathVariable Long id) {
        return ResponseEntity.ok(peopleService.getNotes(id));
    }

    @PostMapping("/{id}/notes")
    public ResponseEntity<PeopleNote> addNote(@PathVariable Long id,
                                              @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(
                peopleService.addNote(id, body.get("note"), body.get("tags")));
    }

    @PostMapping("/name/{name}/notes")
    public ResponseEntity<PersonNameNote> addNoteByName(@PathVariable("name") String name,
                                                        @RequestBody Map<String, String> body) {
        PersonNameNote note = new PersonNameNote(null, name, body.get("note"), Instant.now());
        return ResponseEntity.ok(personNameNoteRepository.save(note));
    }

    @GetMapping("/name/{name}/notes")
    public ResponseEntity<List<PersonNameNote>> getNotesByName(@PathVariable("name") String name) {
        return ResponseEntity.ok(personNameNoteRepository.findByPersonNameIgnoreCase(name));
    }
}
