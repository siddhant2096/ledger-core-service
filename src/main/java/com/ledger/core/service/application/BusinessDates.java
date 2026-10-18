package com.ledger.core.service.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Date utilities for the ledger's business calendar.
 *
 * <p>The single sanctioned way to turn an {@link Instant} into a business
 * {@link LocalDate} is {@link #businessDate(Instant, ZoneId)} with the
 * engine's configured zone — never the JVM default zone, which would make
 * replay results machine-dependent. (The same instant can fall on different
 * calendar dates in different zones; see {@code BusinessDatesTest}.)
 */
public final class BusinessDates {

    private BusinessDates() {
    }

    /** The business date of an instant in the given explicit zone. */
    public static LocalDate businessDate(Instant instant, ZoneId zone) {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(zone, "zone");
        return instant.atZone(zone).toLocalDate();
    }

    /** All dates from {@code startInclusive} to {@code endInclusive}, ascending. */
    public static List<LocalDate> datesBetween(LocalDate startInclusive, LocalDate endInclusive) {
        if (endInclusive.isBefore(startInclusive)) {
            return List.of();
        }
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = startInclusive; !d.isAfter(endInclusive); d = d.plusDays(1)) {
            dates.add(d);
        }
        return dates;
    }
}
