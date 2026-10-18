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
 * fee lifecycle: charge when negative, append-only compensation when the
 * corrected books no longer show an overdraft (E10 alone cannot prove any of
 * this: its BHD account never goes negative).
 *
 * <p>Stream (all AED, account A; Day n = 2026-01-0n):
 * <pre>
 *   X1  proc D1, value D1: CREDIT 100.00
 *   X2  proc D1, value D1: DEBIT  120.00   -> D1 = -20.00
 *   X3  proc D2, value D2: CREDIT 200.00
 *   X4  proc D4, value D1: CREDIT 100.00   (LATE, into ALREADY-CHARGED D1)
 *   X5  proc D5, value D3: DEBIT  300.00   (LATE, makes UNCHARGED D3 negative)
 *   X6  proc D5, value D4: CREDIT 300.00   (keeps D4+ non-negative)
 * </pre>
 *
 * <p>Derived expectations under the reconciliation policy:
 * <ul>
 *   <li>Evening D1 (runs when the processing date advances to D2): D1 =
 *       100 - 120 = -20.00 -> fee F1 = -25.00, valued D1, derived D1.</li>
 *   <li>Evenings D2/D3 (run when the date advances to D4, BEFORE X4 is
 *       applied): books without X4 give D1 = -20.00 -> F1 still supported,
 *       retained. D2 = 155.00, D3 = 155.00 -> no fees.</li>
 *   <li>Evening D4 (runs when the date advances to D5): X4 is now in the
 *       books; the corrected D1 = -20.00 + 100.00 = 80.00 is NOT overdrawn ->
 *       F1 is COMPENSATED: +25.00 valued D1, derived D4. The original F1 entry
 *       is retained untouched.</li>
 *   <li>Evenings D5/D6 (window-end boundary): X5 makes D3 = 280.00 - 300.00 =
 *       -20.00 -> fee F2 = -25.00, valued D3, derived D5 (D3 was examined on
 *       earlier evenings, found positive, never charged, and is now charged
 *       exactly once). D4 = -45.00 + 300.00 = 255.00 -> no fee.</li>
 *   <li>Final value-date projections: D1 80.00 (100-120-25+25+100), D2 280.00,
 *       D3 -45.00 (280-300-25), D4 255.00, D5 255.00. Interest: 80x.0004=.0320
 *       -> 0.03; 280 -> .1120 -> 0.11; D3 negative -> 0.00; 255 -> .1020 ->
 *       0.10 (x3); capitalized 0.44 -> D6 = 255.44.</li>
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
        assertEquals(aed("-20.00"), f2.preFeeBalance());
    }

    @Test
    void chargedFeeIsCompensatedWhenALateEventCorrectsItsDate() {
        // F1's original entry SURVIVES (append-only), and is neutralized by
        // exactly one FEE_REVERSAL entry valued on D1, derived on the D4
        // evening — the first boundary after X4 corrected D1.
        List<LedgerEntry> feeEntries = result.entries().stream()
                .filter(e -> e.kind() == EntryKind.FEE_OVERDRAFT).toList();
        assertEquals(2, feeEntries.size()); // F1 (D1) and F2 (D3) both retained
        List<LedgerEntry> reversals = result.entries().stream()
                .filter(e -> e.kind() == EntryKind.FEE_REVERSAL).toList();
        assertEquals(1, reversals.size());
        assertEquals(aed("25.00"), reversals.getFirst().amount());
        assertEquals(Scenario.day(1), reversals.getFirst().valueDate());
        assertEquals(Scenario.day(4), reversals.getFirst().derivedOnDate());
        assertEquals(1, result.feeCompensations().size());
        // D1's final projection is positive and the fee nets to zero.
        assertEquals(aed("80.00"), closing(1));
    }

    @Test
    void unchargedHistoricalDayIsAssessedExactlyOnce() {
        // D3 was examined on the D3/D4 evenings (positive -> never charged) and
        // is charged exactly once on the D5 evening after X5.
        assertEquals(1, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.FEE_OVERDRAFT
                        && e.valueDate().equals(Scenario.day(3))).count());
        assertEquals(aed("-45.00"), closing(3)); // 280 - 300 - 25
    }

    @Test
    void finalValueDateProjectionMatchesDerivation() {
        assertEquals(aed("80.00"), closing(1));    // 100 - 120 - 25 + 25 + 100
        assertEquals(aed("280.00"), closing(2));   // 80 + 200
        assertEquals(aed("-45.00"), closing(3));   // 280 - 300 - 25
        assertEquals(aed("255.00"), closing(4));   // -45 + 300
        assertEquals(aed("255.00"), closing(5));
        // Interest: 0.03 + 0.11 + 0.00 + 0.10 + 0.10 + 0.10 = 0.44,
        // capitalized once on D6.
        assertEquals(aed("255.44"), closing(6));
    }
}
