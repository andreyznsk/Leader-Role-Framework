package ru.andreyz.mailagent.scheduler;

import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.Email;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class NoticeDocumentWriter {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_DATE;

    private final MailConfig.PathProperties pathProperties;

    public NoticeDocumentWriter(MailConfig.PathProperties pathProperties) {
        this.pathProperties = pathProperties;
    }

    public Path write(Email email, String note) throws IOException {
        LocalDate today = LocalDate.now();
        Path dir = Path.of(pathProperties.getRagInbox(), "mail", today.format(DAY_FORMAT));
        Files.createDirectories(dir);

        Path file = dir.resolve(ActionExecutor.sanitize(email.id()) + ".md");
        Files.writeString(file, buildMarkdown(email, note, today));
        return file;
    }

    private String buildMarkdown(Email email, String note, LocalDate today) {
        String subject = safe(email.subject(), "RAG document");
        String sender = safe(email.from(), "unknown");
        String summary = safe(note, "Письмо содержит полезную информацию для базы знаний техлида.");
        String normalizedContent = normalize(email.body());
        LocalDateTime receivedAt = email.receivedAt() != null ? email.receivedAt() : today.atStartOfDay();

        return """
                ---
                type: RAG
                source: mail
                updated: %s
                message_id: %s
                sender: %s
                subject: %s
                received_at: %s
                review_by: %s
                ---

                # %s

                ## Контекст

                %s

                ## Содержание

                %s

                ## Возможное применение

                Использовать как reference по теме письма: процессы, договорённости, архитектурные решения, риски и рабочие правила команды.
                """.formatted(
                today,
                yaml(email.id()),
                yaml(sender),
                yaml(subject),
                yaml(receivedAt.toString()),
                today.plusDays(90),
                subject,
                summary,
                normalizedContent
        );
    }

    private String normalize(String body) {
        if (body == null || body.isBlank()) {
            return "Содержимое письма отсутствует.";
        }
        String text = body.replace("\r", "")
                .replaceAll("(?m)^>.*$", "")
                .replaceAll("(?m)^(From|Sent|To|Subject):.*$", "")
                .replaceAll("(?m)^--\\s*$.*", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return text.isBlank() ? "Содержимое письма не удалось нормализовать." : text;
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String yaml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
