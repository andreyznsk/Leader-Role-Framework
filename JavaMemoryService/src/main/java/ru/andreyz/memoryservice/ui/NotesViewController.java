package ru.andreyz.memoryservice.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.andreyz.memoryservice.service.NoteService;

@Controller
@RequestMapping("/ui/notes")
public class NotesViewController {

    private final NoteService noteService;

    public NotesViewController(NoteService noteService) {
        this.noteService = noteService;
    }

    @GetMapping
    public String notes(@RequestParam(required = false) String tags,
                        @RequestParam(required = false) Integer limit,
                        Model model) {
        model.addAttribute("notes", noteService.list(tags, limit));
        model.addAttribute("tags", tags);
        model.addAttribute("limit", limit != null ? limit : 50);
        return "notes";
    }
}
