package ru.andreyz.ragservice.db;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface IndexedDocumentRepository extends CrudRepository<IndexedDocument, Long> {
    Optional<IndexedDocument> findByFilePath(String filePath);
    List<IndexedDocument> findAll();
}
