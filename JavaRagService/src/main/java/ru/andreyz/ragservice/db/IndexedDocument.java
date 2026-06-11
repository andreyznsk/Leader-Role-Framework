package ru.andreyz.ragservice.db;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("indexed_documents")
public record IndexedDocument(
        @Id Long id,
        String filePath,
        String fileHash,
        LocalDateTime indexedAt,
        Integer chunkCount,
        String status
) {
    public static IndexedDocument create(String filePath, String fileHash, int chunkCount) {
        return new IndexedDocument(null, filePath, fileHash, LocalDateTime.now(), chunkCount, "indexed");
    }

    public IndexedDocument withUpdated(String fileHash, int chunkCount, String status) {
        return new IndexedDocument(id, filePath, fileHash, LocalDateTime.now(), chunkCount, status);
    }
}
