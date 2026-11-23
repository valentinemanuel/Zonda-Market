package com.zondamarket.shared.time;

import com.zondamarket.shared.errors.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;

public record ReportingPeriod(LocalDate from, LocalDate to) {
    public static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final int MAX_DAYS = 90;

    public static ReportingPeriod parse(String from, String to, Clock clock, String errorCode) {
        if (from == null && to == null) {
            var today = LocalDate.now(clock.withZone(ZONE));
            return new ReportingPeriod(today.minusDays(29), today);
        }
        if (from == null || to == null || !from.matches("\\d{4}-\\d{2}-\\d{2}") || !to.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw invalidRange(errorCode);
        }
        try {
            var start = LocalDate.parse(from);
            var end = LocalDate.parse(to);
            if (start.getYear() == 0 || end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
                throw invalidRange(errorCode);
            }
            return new ReportingPeriod(start, end);
        } catch (DateTimeParseException exception) {
            throw invalidRange(errorCode);
        }
    }

    public Instant start() { return from.atStartOfDay(ZONE).toInstant(); }
    public Instant end() { return to.plusDays(1).atStartOfDay(ZONE).toInstant(); }

    private static ApiProblemException invalidRange(String code) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, code,
                "Elegí un período de hasta 90 días corridos, desde la fecha menor hasta la mayor.");
    }
}
