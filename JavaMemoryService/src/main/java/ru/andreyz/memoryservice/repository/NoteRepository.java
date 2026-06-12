package ru.andreyz.memoryservice.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.memoryservice.domain.Note;

import java.util.List;

public interface NoteRepository extends CrudRepository<Note, Long> {
    List<Note> findTop200ByOrderByCreatedAtDesc();
}
