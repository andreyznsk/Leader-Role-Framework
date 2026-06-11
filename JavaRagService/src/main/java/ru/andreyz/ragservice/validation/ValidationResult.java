package ru.andreyz.ragservice.validation;

import java.util.List;

public record ValidationResult(
        boolean valid,
        DocType docType,
        List<String> errors
) {
    public static ValidationResult ok(DocType docType) {
        return new ValidationResult(true, docType, List.of());
    }

    public static ValidationResult error(String message) {
        return new ValidationResult(false, null, List.of(message));
    }

    public static ValidationResult errors(List<String> errors) {
        return new ValidationResult(false, null, errors);
    }

    public String errorsAsString() {
        return String.join("; ", errors);
    }
}
