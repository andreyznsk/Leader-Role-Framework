package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Set;

@Table("task_links")
public record TaskLink(
        @Id Long id,
        Long fromTaskId,
        Long toTaskId,
        String linkType,
        Instant createdAt
) {
    public static final String RELATES_TO = "RELATES_TO";
    public static final String BLOCKS = "BLOCKS";
    public static final String DUPLICATES = "DUPLICATES";
    public static final String PARENT_OF = "PARENT_OF";

    public static final Set<String> VALID_TYPES = Set.of(RELATES_TO, BLOCKS, DUPLICATES, PARENT_OF);
}
