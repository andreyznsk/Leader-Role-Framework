package ru.andreyz.memoryservice.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.Note;
import ru.andreyz.memoryservice.dto.CreateNoteRequest;
import ru.andreyz.memoryservice.service.NoteService;

import java.util.List;

@RestController
@RequestMapping("/api/notes")
public class NoteController {

    private final NoteService noteService;

    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    @PostMapping
    public ResponseEntity<Note> create(@RequestBody CreateNoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(noteService.create(request.text(), request.tags(), request.source()));
    }

    @GetMapping
    public ResponseEntity<List<Note>> list(@RequestParam(required = false) String tags,
                                           @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(noteService.list(tags, limit));
    }
}
