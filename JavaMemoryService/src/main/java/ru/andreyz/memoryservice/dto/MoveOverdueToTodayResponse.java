package ru.andreyz.memoryservice.dto;

import java.time.LocalDate;

public record MoveOverdueToTodayResponse(
        int moved,
        LocalDate today
) {}
