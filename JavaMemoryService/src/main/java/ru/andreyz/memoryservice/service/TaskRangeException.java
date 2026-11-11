package ru.andreyz.memoryservice.service;

public class TaskRangeException extends IllegalArgumentException {
    private final String code;

    public TaskRangeException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
