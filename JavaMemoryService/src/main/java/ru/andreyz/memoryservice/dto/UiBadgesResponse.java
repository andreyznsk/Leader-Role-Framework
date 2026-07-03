package ru.andreyz.memoryservice.dto;

import java.time.OffsetDateTime;

public record UiBadgesResponse(Counts counts, OffsetDateTime serverTime) {

    public record Counts(int newIntake, int pendingTasks) {}
}
