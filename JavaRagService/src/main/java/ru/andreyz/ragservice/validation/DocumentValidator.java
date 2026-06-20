package ru.andreyz.ragservice.validation;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentValidator {

    public ValidationResult validate(Path filePath) {
        try {
            return validate(Files.readString(filePath));
        } catch (IOException e) {
            return ValidationResult.error("Не удалось прочитать файл: " + e.getMessage());
        }
    }

    public ValidationResult validate(String content) {
        if (!content.startsWith("---")) {
            return ValidationResult.error("Отсутствует frontmatter (файл должен начинаться с ---)");
        }

        Map<String, String> frontmatter = parseFrontmatter(content);

        String typeRaw = frontmatter.get(DocField.TYPE.key());
        if (typeRaw == null || typeRaw.isBlank()) {
            return ValidationResult.error("Поле 'type' отсутствует в frontmatter. " +
                    "Допустимые значения: " + Arrays.toString(DocType.values()));
        }

        DocType docType;
        try {
            docType = DocType.valueOf(typeRaw.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return ValidationResult.error("Неизвестный тип документа: '" + typeRaw + "'. " +
                    "Допустимые: " + Arrays.toString(DocType.values()));
        }

        DocSchema schema = DocSchema.forType(docType);
        List<String> errors = new ArrayList<>();

        for (DocField field : schema.requiredFrontmatterFields()) {
            if (!frontmatter.containsKey(field.key()) || frontmatter.get(field.key()).isBlank()) {
                errors.add("Отсутствует обязательное поле frontmatter: '" + field.key() + "'");
            }
        }

        for (String section : schema.requiredSections()) {
            if (!content.contains(section)) {
                errors.add("Отсутствует обязательная секция: '" + section + "'");
            }
        }

        if (errors.isEmpty()) {
            return ValidationResult.ok(docType);
        }
        return ValidationResult.errors(docType, errors);
    }

    private Map<String, String> parseFrontmatter(String content) {
        int end = content.indexOf("---", 3);
        if (end == -1) return new LinkedHashMap<>();
        String fm = content.substring(3, end).trim();
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : fm.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                result.put(key, value);
            }
        }
        return result;
    }
}
