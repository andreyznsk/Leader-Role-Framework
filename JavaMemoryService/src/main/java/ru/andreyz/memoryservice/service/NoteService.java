package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.andreyz.memoryservice.domain.Note;
import ru.andreyz.memoryservice.repository.NoteRepository;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class NoteService {

    private final NoteRepository noteRepository;

    public NoteService(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    public Note create(String title, String text, String tags, String source) {
        String normalizedTitle = normalizeTitle(title, text);
        String normalizedText = normalizeText(text);
        Note note = new Note(null, normalizedTitle, normalizedText, normalizeTags(tags),
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

    public void delete(Long id) {
        if (!noteRepository.existsById(id)) {
            throw new ResponseStatusException(NOT_FOUND, "Note not found: " + id);
        }
        noteRepository.deleteById(id);
    }

    private String normalizeTags(String tags) {
        Set<String> parsed = parseTags(tags);
        return parsed.isEmpty() ? null : String.join(",", parsed);
    }

    private String normalizeTitle(String title, String text) {
        String candidate = title != null && !title.isBlank() ? title.trim() : deriveTitle(text);
        if (candidate == null || candidate.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Note title is required");
        }
        if (candidate.length() > 200) {
            throw new ResponseStatusException(BAD_REQUEST, "Note title must be at most 200 chars");
        }
        return candidate;
    }

    private String normalizeText(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String deriveTitle(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String singleLine = text.trim().replace("\r", "").replace('\n', ' ').replaceAll("\\s+", " ");
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 200);
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
