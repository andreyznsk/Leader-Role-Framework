package ru.andreyz.memoryservice.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.PeopleNote;
import ru.andreyz.memoryservice.domain.Person;
import ru.andreyz.memoryservice.service.PeopleService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/ui/people")
public class PeopleViewController {

    private final PeopleService peopleService;

    public PeopleViewController(PeopleService peopleService) {
        this.peopleService = peopleService;
    }

    @GetMapping
    public String people(@RequestParam(required = false) String search, Model model) {
        List<Person> people = search != null && !search.isBlank()
                ? peopleService.search(search)
                : peopleService.findAll();
        Map<Long, List<PeopleNote>> notesByPerson = people.stream()
                .collect(Collectors.toMap(Person::id, p -> peopleService.getNotes(p.id())));
        model.addAttribute("people", people);
        model.addAttribute("search", search);
        model.addAttribute("notesByPerson", notesByPerson);
        return "people";
    }

    @PostMapping("/add")
    public String add(@RequestParam String fullName,
                      @RequestParam(required = false) String login,
                      @RequestParam(required = false) String email,
                      @RequestParam(required = false) String domain) {
        Person p = new Person(null, fullName, login, email, null, domain, null,
                null, null, null, null, Instant.now(), Instant.now());
        peopleService.create(p);
        return "redirect:/ui/people";
    }

    @PostMapping("/{id}/note")
    public String addNote(@PathVariable Long id,
                          @RequestParam String note,
                          @RequestParam(required = false) String tags) {
        peopleService.addNote(id, note, tags);
        return "redirect:/ui/people";
    }

    @PutMapping("/{id}/edit")
    public String edit(@PathVariable Long id,
                       @RequestParam String fullName,
                       @RequestParam(required = false) String login,
                       @RequestParam(required = false) String email,
                       @RequestParam(required = false) String domain,
                       @RequestParam(required = false) String currentTask,
                       @RequestParam(required = false) String notes) {
        Person existing = peopleService.findById(id);
        Person updated = new Person(existing.id(), fullName, login, email,
                existing.phone(), domain, currentTask,
                existing.capacitySprint(), existing.capacityMonth(), existing.capacityQuarter(),
                notes, existing.createdAt(), Instant.now());
        peopleService.update(id, updated);
        return "redirect:/ui/people";
    }
}
