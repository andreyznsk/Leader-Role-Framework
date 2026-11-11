package ru.andreyz.memoryservice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.andreyz.memoryservice.dto.TaskRangeRequest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class TaskRangeValidationTest {
    private final TaskService service = new TaskService(null, null, null, null, null, null);
    private final LocalDate from = LocalDate.of(2026, 1, 1);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"2026-1-01", "2026-02-29", "2026-04-31", "2026-13-01", " 2026-01-01", "2026-01-01T00:00:00"})
    void rejectsInvalidDates(String value) {
        assertCode(() -> TaskRangeRequest.parseDate(value), "invalid_date_format");
    }

    @Test
    void acceptsLeapDay() {
        assertThat(TaskRangeRequest.parseDate("2028-02-29")).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void validatesBeforeQueryingRepository() {
        assertCode(() -> service.findByRange(null, from, null), "invalid_date_format");
        assertCode(() -> service.findByRange(from, null, null), "invalid_date_format");
        assertCode(() -> service.findByRange(from, from.minusDays(1), null), "invalid_date_range");
        assertCode(() -> service.findByRange(from, from.plusDays(366), null), "range_too_large");
        for (String status : new String[]{"", "done", "UNKNOWN", " DONE "}) {
            assertCode(() -> service.findByRange(from, from, status), "invalid_status");
        }
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(TaskRangeException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
