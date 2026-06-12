package ru.andreyz.ragservice.validation;

import java.util.List;

public enum DocSchema {

    SERVICE_CARD(DocType.SERVICE_CARD,
            List.of(DocField.TYPE, DocField.SERVICE, DocField.UPDATED, DocField.REVIEW_BY),
            List.of("## Назначение", "## Стек", "## Интеграции", "## Деплой")
    ),

    PROCESS(DocType.PROCESS,
            List.of(DocField.TYPE, DocField.UPDATED, DocField.REVIEW_BY),
            List.of("## Когда использовать", "## Шаги", "## Кто участвует", "## Escalation")
    ),

    GLOSSARY(DocType.GLOSSARY,
            List.of(DocField.TYPE, DocField.UPDATED),
            List.of("# Глоссарий")
    ),

    ADR(DocType.ADR,
            List.of(DocField.TYPE, DocField.UPDATED),
            List.of("## Статус", "## Контекст", "## Решение", "## Последствия")
    ),

    KNOWLEDGE(DocType.KNOWLEDGE,
            List.of(DocField.TYPE),
            List.of()
    );

    private final DocType docType;
    private final List<DocField> requiredFrontmatterFields;
    private final List<String> requiredSections;

    DocSchema(DocType docType, List<DocField> requiredFrontmatterFields, List<String> requiredSections) {
        this.docType = docType;
        this.requiredFrontmatterFields = requiredFrontmatterFields;
        this.requiredSections = requiredSections;
    }

    public DocType docType() { return docType; }
    public List<DocField> requiredFrontmatterFields() { return requiredFrontmatterFields; }
    public List<String> requiredSections() { return requiredSections; }

    public static DocSchema forType(DocType docType) {
        for (DocSchema schema : values()) {
            if (schema.docType == docType) return schema;
        }
        throw new IllegalArgumentException("No schema defined for DocType: " + docType);
    }
}
