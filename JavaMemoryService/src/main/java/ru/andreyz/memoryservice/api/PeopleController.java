package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.PeopleNote;
import ru.andreyz.memoryservice.domain.Person;
import ru.andreyz.memoryservice.service.PeopleService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/people")
public class PeopleController {

    private final PeopleService peopleService;

    public PeopleController(PeopleService peopleService) {
        this.peopleService = peopleService;
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
}
