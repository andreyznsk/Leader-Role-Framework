package ru.andreyz.ragservice.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentValidatorTest {

    private final DocumentValidator validator = new DocumentValidator();

    @Test
    void noFrontmatter_returnsError() {
        ValidationResult result = validator.validate("# Just a title\nSome content.");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorsAsString()).contains("frontmatter");
    }

    @Test
    void missingTypeField_returnsError() {
        String content = """
                ---
                updated: 2026-06-11
                ---
                # Doc
                """;

        ValidationResult result = validator.validate(content);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorsAsString()).contains("'type'");
    }

    @Test
    void unknownType_returnsError() {
        String content = """
                ---
                type: wiki
                updated: 2026-06-11
                ---
                # Doc
                """;

        ValidationResult result = validator.validate(content);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorsAsString()).contains("wiki");
    }

    @Test
    void serviceCard_missingRequiredField_returnsError() {
        String content = """
                ---
                type: service-card
                updated: 2026-06-11
                review_by: 2026-09-11
                ---
                # Service (SVC)
                ## Назначение
                desc
                ## Стек
                - Java
                ## Интеграции
                - none
                ## Деплой
                - k8s
                """;
        // 'service' field is missing

        ValidationResult result = validator.validate(content);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorsAsString()).contains("'service'");
    }

    @Test
    void serviceCard_missingSection_returnsError() {
        String content = """
                ---
                type: service-card
                service: SVC
                updated: 2026-06-11
                review_by: 2026-09-11
                ---
                # Service (SVC)
                ## Назначение
                desc
                ## Стек
                - Java
                ## Интеграции
                - none
                """;
        // '## Деплой' section is missing

        ValidationResult result = validator.validate(content);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorsAsString()).contains("## Деплой");
    }

    @Test
    void serviceCard_multipleMissingItems_allReported() {
        String content = """
                ---
                type: service-card
                updated: 2026-06-11
                ---
                # Service
                ## Назначение
                desc
                """;
        // missing: service, review_by, ## Стек, ## Интеграции, ## Деплой

        ValidationResult result = validator.validate(content);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSizeGreaterThan(1);
    }

    @Test
    void validServiceCard_returnsOk() {
        String content = """
                ---
                type: service-card
                service: TST
                updated: 2026-06-11
                review_by: 2026-09-11
                ---
                # TestService (TST)
                ## Назначение
                Test service.
                ## Стек
                - Java 21
                ## Интеграции
                - none
                ## Деплой
                - Kubernetes
                """;

        ValidationResult result = validator.validate(content);

        assertThat(result.valid()).isTrue();
        assertThat(result.docType()).isEqualTo(DocType.SERVICE_CARD);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validAdr_returnsOk() {
        String content = """
                ---
                type: adr
                updated: 2026-06-11
                ---
                # ADR-001
                ## Статус
                Accepted
                ## Контекст
                Background.
                ## Решение
                Decision.
                ## Последствия
                Consequences.
                """;

        ValidationResult result = validator.validate(content);

        assertThat(result.valid()).isTrue();
        assertThat(result.docType()).isEqualTo(DocType.ADR);
    }

    @Test
    void validGlossary_returnsOk() {
        String content = """
                ---
                type: glossary
                updated: 2026-06-11
                ---
                # Глоссарий
                - SVC: сервис
                """;

        ValidationResult result = validator.validate(content);

        assertThat(result.valid()).isTrue();
        assertThat(result.docType()).isEqualTo(DocType.GLOSSARY);
    }

    @Test
    void typeIsCaseInsensitive_serviceCard() {
        String content = """
                ---
                type: SERVICE-CARD
                service: TST
                updated: 2026-06-11
                review_by: 2026-09-11
                ---
                ## Назначение
                desc
                ## Стек
                - Java
                ## Интеграции
                - none
                ## Деплой
                - k8s
                """;

        ValidationResult result = validator.validate(content);

        assertThat(result.valid()).isTrue();
        assertThat(result.docType()).isEqualTo(DocType.SERVICE_CARD);
    }

    @Test
    void validNotice_returnsOk() {
        String content = """
                ---
                type: NOTICE
                source: mail
                updated: 2026-06-20
                review_by: 2026-09-20
                sender: architect@example.com
                subject: Новый порядок релизов
                ---
                # Новый порядок релизов
                ## Контекст
                Важное письмо по процессу релизов.
                ## Содержание
                Сначала release calendar, потом release notes.
                ## Возможное применение
                Использовать как reference.
                """;

        ValidationResult result = validator.validate(content);

        assertThat(result.valid()).isTrue();
        assertThat(result.docType()).isEqualTo(DocType.NOTICE);
    }

    @Test
    void noticeMissingSection_returnsErrorWithDocType() {
        String content = """
                ---
                type: NOTICE
                source: mail
                updated: 2026-06-20
                review_by: 2026-09-20
                ---
                # Новый порядок релизов
                ## Контекст
                Контекст.
                ## Содержание
                Содержание.
                """;

        ValidationResult result = validator.validate(content);

        assertThat(result.valid()).isFalse();
        assertThat(result.docType()).isEqualTo(DocType.NOTICE);
        assertThat(result.errorsAsString()).contains("## Возможное применение");
    }
}
