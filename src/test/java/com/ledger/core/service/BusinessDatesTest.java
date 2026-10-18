package com.ledger.core.service;

import com.ledger.core.service.application.BusinessDates;
import com.ledger.core.service.application.LedgerEngine;
import com.ledger.core.service.application.Scenario;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Business dates are derived from instants ONLY through an explicit,
 * configured zone — never the JVM default zone (which would make replay
 * results depend on the machine running them).
 */
class BusinessDatesTest {

    @Test
    void sameInstantFallsOnDifferentBusinessDatesInDifferentZones() {
        // 2026-01-01T00:30:00Z is already 2026-01-01 in UTC but still
        // 2025-12-31 in America/New_York (UTC-5) and 2026-01-01 in Dubai (+4).
        Instant instant = Instant.parse("2026-01-01T00:30:00Z");
        assertEquals(LocalDate.of(2026, 1, 1),
                BusinessDates.businessDate(instant, ZoneId.of("UTC")));
        assertEquals(LocalDate.of(2025, 12, 31),
                BusinessDates.businessDate(instant, ZoneId.of("America/New_York")));
        assertEquals(LocalDate.of(2026, 1, 1),
                BusinessDates.businessDate(instant, ZoneId.of("Asia/Dubai")));
    }

    @Test
    void engineDerivesBusinessDatesWithItsConfiguredZone() {
        LedgerEngine engine = Scenario.engine();
        Instant instant = Instant.parse("2026-01-01T00:30:00Z");
        // Scenario.BUSINESS_ZONE is UTC.
        assertEquals(LocalDate.of(2026, 1, 1), engine.businessDate(instant));
    }

    @Test
    void datesBetweenIsInclusiveAndAscending() {
        assertEquals(List.of(
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 2),
                        LocalDate.of(2026, 1, 3)),
                BusinessDates.datesBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3)));
        assertEquals(List.of(),
                BusinessDates.datesBetween(LocalDate.of(2026, 1, 3), LocalDate.of(2026, 1, 1)));
    }
}
