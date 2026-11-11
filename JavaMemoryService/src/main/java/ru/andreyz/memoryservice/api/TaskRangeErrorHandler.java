package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.andreyz.memoryservice.service.TaskRangeException;

@RestControllerAdvice
public class TaskRangeErrorHandler {
    @ExceptionHandler(TaskRangeException.class)
    public ResponseEntity<RangeError> handle(TaskRangeException exception) {
        return ResponseEntity.badRequest().body(new RangeError(exception.code(), exception.getMessage()));
    }

    public record RangeError(String code, String message) {}
}
