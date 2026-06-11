package ru.andreyz.memoryservice.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.memoryservice.domain.PersonNameNote;

import java.util.List;

public interface PersonNameNoteRepository extends CrudRepository<PersonNameNote, Long> {
    List<PersonNameNote> findByPersonNameIgnoreCase(String personName);
}
