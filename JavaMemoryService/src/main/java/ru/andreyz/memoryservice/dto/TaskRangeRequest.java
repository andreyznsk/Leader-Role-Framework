package ru.andreyz.memoryservice.dto;

import ru.andreyz.memoryservice.service.TaskRangeException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public record TaskRangeRequest(String from, String to, String status) {
    public static LocalDate parseDate(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new TaskRangeException("invalid_date_format", "Dates must use YYYY-MM-DD format");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new TaskRangeException("invalid_date_format", "Invalid calendar date: " + value);
        }
    }
}
