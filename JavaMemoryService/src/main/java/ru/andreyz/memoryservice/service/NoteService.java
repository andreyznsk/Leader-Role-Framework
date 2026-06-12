package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Note;
import ru.andreyz.memoryservice.repository.NoteRepository;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class NoteService {

    private final NoteRepository noteRepository;

    public NoteService(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    public Note create(String text, String tags, String source) {
        Note note = new Note(null, text, normalizeTags(tags),
                source != null ? source : "agent", Instant.now());
        return noteRepository.save(note);
    }

    public List<Note> list(String tags, Integer limit) {
        int effectiveLimit = limit != null ? Math.max(1, Math.min(limit, 200)) : 50;
        Set<String> requestedTags = parseTags(tags);

        return noteRepository.findTop200ByOrderByCreatedAtDesc().stream()
                .filter(note -> requestedTags.isEmpty() || matchesAnyTag(note.tags(), requestedTags))
                .limit(effectiveLimit)
                .toList();
    }

    private String normalizeTags(String tags) {
        Set<String> parsed = parseTags(tags);
        return parsed.isEmpty() ? null : String.join(",", parsed);
    }

    private Set<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private boolean matchesAnyTag(String noteTags, Set<String> requestedTags) {
        Set<String> actual = parseTags(noteTags);
        return actual.stream().anyMatch(requestedTags::contains);
    }
}
