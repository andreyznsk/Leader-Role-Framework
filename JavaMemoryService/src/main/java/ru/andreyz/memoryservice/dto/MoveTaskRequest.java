package ru.andreyz.memoryservice.dto;

import java.time.LocalDate;

public record MoveTaskRequest(LocalDate toDate) {}
