package ru.andreyz.mailagent.model;

public record MailConnectionTestResult(
        boolean success,
        String message,
        String target
) {
}
