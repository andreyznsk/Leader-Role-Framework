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
        String status,
        String errorMessage
) {
    public static IndexedDocument indexed(String path, String hash, int chunks) {
        return new IndexedDocument(null, path, hash, LocalDateTime.now(), chunks, "indexed", null);
    }

    public static IndexedDocument invalid(String path, String hash, String error) {
        return new IndexedDocument(null, path, hash, LocalDateTime.now(), 0, "invalid", error);
    }

    public static IndexedDocument failed(String path, String hash, String error) {
        return new IndexedDocument(null, path, hash, LocalDateTime.now(), 0, "failed", error);
    }

    public IndexedDocument withUpdated(String fileHash, int chunkCount, String status) {
        return new IndexedDocument(id, filePath, fileHash, LocalDateTime.now(), chunkCount, status, null);
    }

    public IndexedDocument withUpdated(String fileHash, int chunkCount, String status, String errorMessage) {
        return new IndexedDocument(id, filePath, fileHash, LocalDateTime.now(), chunkCount, status, errorMessage);
    }
}
