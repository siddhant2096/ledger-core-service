package com.ledger.core.service;

import com.ledger.core.service.application.LedgerEngine;
import com.ledger.core.service.application.ReplayResult;
import com.ledger.core.service.application.ReplayResult.FeeAssessment;
import com.ledger.core.service.application.ReplayResult.Outcome;
import com.ledger.core.service.application.Scenario;
import com.ledger.core.service.domain.account.Account;
import com.ledger.core.service.domain.entry.EntryKind;
import com.ledger.core.service.domain.entry.LedgerEntry;
import com.ledger.core.service.domain.event.EventEnvelope;
import com.ledger.core.service.domain.event.LedgerEvent;
import com.ledger.core.service.domain.money.CurrencyCode;
import com.ledger.core.service.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Focused regression test for late back-valued events interacting with the
 * fee lifecycle (E10 alone cannot prove this: its BHD account never goes
 * negative).
 *
 * <p>Stream (all AED, account A; Day n = 2026-01-0n):
 * <pre>
 *   X1  proc D1, value D1: CREDIT 100.00
 *   X2  proc D1, value D1: DEBIT  120.00   -> D1 = -20.00
 *   X3  proc D2, value D2: CREDIT 200.00   -> D2 = -45.00 + 200.00 = 155.00
 *   X4  proc D4, value D1: CREDIT 100.00   (LATE, into ALREADY-ASSESSED D1)
 *   X5  proc D5, value D3: DEBIT  300.00   (LATE, makes UNASSESSED D3 negative)
 *   X6  proc D5, value D4: CREDIT 300.00   (keeps D4+ non-negative)
 * </pre>
 *
 * <p>Derived expectations:
 * <ul>
 *   <li>Evening D1: D1 = 100.00 - 120.00 = -20.00 -> fee F1 = -25.00, valued
 *       D1, derived D1. D1 becomes -45.00.</li>
 *   <li>Evenings D2/D3 (run when the processing date advances to D4): D2 =
 *       155.00, D3 = 155.00 -> no fees.</li>
 *   <li>Evening D4 (run when the date advances to D5): X4 is now in the books,
 *       so D1 = 55.00, D2 = 255.00, D3 = 255.00, D4 = 255.00 -> no NEW fees;
 *       F1 is already assessed and is NOT reconsidered, refunded, or
 *       duplicated, even though D1's reconstructed balance is now
 *       POSITIVE.</li>
 *   <li>Evening D5 (window-end boundary): X5 makes D3 = 255.00 - 300.00 =
 *       -45.00 -> fee F2 = -25.00, valued D3, derived D5 (D3 was examined on
 *       earlier evenings, found positive, left unmarked, and is now
 *       reconsidered exactly once). D4 = -45 - 25 + 300 = 230.00 -> no fee.</li>
 *   <li>Final value-date projections: D1 55.00, D2 255.00, D3 -70.00,
 *       D4 230.00, D5 230.00. Interest: 55 x .0004 = .0220 -> 0.02;
 *       255 -> 0.1020 -> 0.10; D3 negative -> 0.00; 230 -> 0.0920 -> 0.09
 *       (x3) = capitalized 0.39 on D6 -> D6 = 230.39.</li>
 * </ul>
 */
class LateEventFeeTest {

    private static final Account AED = Account.zeroBalance("A", CurrencyCode.AED);
    private final AtomicLong position = new AtomicLong(0);

    private EventEnvelope env(String id, int processingDay, int valueDay, LedgerEvent event) {
        return new EventEnvelope(id, position.incrementAndGet(),
                Scenario.day(processingDay), Scenario.day(valueDay), event);
    }

    private static Money aed(String s) {
        return Money.of(s, CurrencyCode.AED);
    }

    private final ReplayResult result = new LedgerEngine(List.of(AED),
            Scenario.WINDOW_START, Scenario.WINDOW_END, Scenario.BUSINESS_ZONE,
            Scenario.OVERDRAFT_FEE_SCHEDULE).replay(List.of(
            env("X1", 1, 1, new LedgerEvent.Credit("A", aed("100.00"))),
            env("X2", 1, 1, new LedgerEvent.Debit("A", aed("120.00"))),
            env("X3", 2, 2, new LedgerEvent.Credit("A", aed("200.00"))),
            env("X4", 4, 1, new LedgerEvent.Credit("A", aed("100.00"))),
            env("X5", 5, 3, new LedgerEvent.Debit("A", aed("300.00"))),
            env("X6", 5, 4, new LedgerEvent.Credit("A", aed("300.00")))));

    private Money closing(int day) {
        return LedgerEngine.closingBalance(result.entries(), AED, Scenario.day(day));
    }

    @Test
    void streamOrderIsPreservedAndAllEventsAccepted() {
        assertEquals(List.of("X1", "X2", "X3", "X4", "X5", "X6"),
                result.log().events().stream().map(EventEnvelope::eventId).toList());
        for (String id : List.of("X1", "X2", "X3", "X4", "X5", "X6")) {
            assertInstanceOf(Outcome.Accepted.class, result.outcomeOf(id), id);
        }
    }

    @Test
    void lateEventsPostAtTheirValueDatesWithTheirRealProcessingDates() {
        LedgerEntry x4 = result.entries().stream()
                .filter(e -> e.sourceEventId().equals("X4")).findFirst().orElseThrow();
        assertEquals(aed("100.00"), x4.amount());
        assertEquals(Scenario.day(1), x4.valueDate());      // back-valued to D1
        assertEquals(Scenario.day(4), x4.derivedOnDate());  // processed on D4

        LedgerEntry x5 = result.entries().stream()
                .filter(e -> e.sourceEventId().equals("X5")).findFirst().orElseThrow();
        assertEquals(aed("-300.00"), x5.amount());
        assertEquals(Scenario.day(3), x5.valueDate());
        assertEquals(Scenario.day(5), x5.derivedOnDate());
    }

    @Test
    void feeAssessmentDatesAndValueDatesAreExactlyAsScheduled() {
        assertEquals(2, result.feeTrace().size());
        FeeAssessment f1 = result.feeTrace().get(0);
        assertEquals(Scenario.day(1), f1.valueDate());        // fee belongs to D1
        assertEquals(Scenario.day(1), f1.assessedOnDate());   // discovered on D1 evening
        assertEquals(aed("-20.00"), f1.preFeeBalance());
        assertEquals(aed("25.00"), f1.feeAmount());
        FeeAssessment f2 = result.feeTrace().get(1);
        assertEquals(Scenario.day(3), f2.valueDate());        // fee belongs to D3
        assertEquals(Scenario.day(5), f2.assessedOnDate());   // discovered only on D5 evening
        assertEquals(aed("-45.00"), f2.preFeeBalance());
    }

    @Test
    void alreadyAssessedFeeSurvivesALateEventThatMakesItsDatePositive() {
        // X4 turns D1's reconstructed balance positive (55.00), yet F1 remains:
        // exactly one fee entry per charged date, no refund, no duplicate.
        List<LedgerEntry> feeEntries = result.entries().stream()
                .filter(e -> e.kind() == EntryKind.FEE_OVERDRAFT).toList();
        assertEquals(2, feeEntries.size());
        assertEquals(1, feeEntries.stream()
                .filter(e -> e.valueDate().equals(Scenario.day(1))).count());
        // D1's final projection is POSITIVE while carrying the fee — the fee
        // was triggered by the books as known on the D1 evening, not by the
        // final reconstructed balance.
        assertEquals(aed("55.00"), closing(1));
    }

    @Test
    void unassessedHistoricalDayIsReconsideredExactlyOnce() {
        // D3 was examined on the D3/D4 evenings (positive -> unmarked) and is
        // reconsidered on the D5 evening after X5: charged exactly once.
        assertEquals(1, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.FEE_OVERDRAFT
                        && e.valueDate().equals(Scenario.day(3))).count());
        assertEquals(aed("-70.00"), closing(3)); // 255 - 300 - 25
    }

    @Test
    void finalValueDateProjectionMatchesDerivation() {
        assertEquals(aed("55.00"), closing(1));    // 100 - 120 - 25 + 100
        assertEquals(aed("255.00"), closing(2));   // 55 + 200
        assertEquals(aed("-70.00"), closing(3));   // 255 - 300 - 25
        assertEquals(aed("230.00"), closing(4));   // -70 + 300
        assertEquals(aed("230.00"), closing(5));
        // Interest: 0.02 + 0.10 + 0.00 + 0.09 + 0.09 + 0.09 = 0.39, capitalized
        // once on D6.
        assertEquals(aed("230.39"), closing(6));
    }
}
