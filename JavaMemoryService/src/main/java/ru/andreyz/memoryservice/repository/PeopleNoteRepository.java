package ru.andreyz.memoryservice.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.memoryservice.domain.PeopleNote;

import java.util.List;

public interface PeopleNoteRepository extends CrudRepository<PeopleNote, Long> {
    List<PeopleNote> findByPersonIdOrderByCreatedAtDesc(Long personId);
    List<PeopleNote> findTop10ByOrderByCreatedAtDesc();
}
