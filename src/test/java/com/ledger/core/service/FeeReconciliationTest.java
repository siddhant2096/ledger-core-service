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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for fee reconciliation under non-monotonic processing
 * dates, backward-moving clocks, compensation cascades, and out-of-window
 * events interleaved with valid ones. Every expectation is derived inline
 * from the documented policy (AMBIGUITIES.md #1, #10, #11, #20, #24, #28).
 */
class FeeReconciliationTest {

    private static final Account AED = Account.zeroBalance("A", CurrencyCode.AED);

    private static Money aed(String s) {
        return Money.of(s, CurrencyCode.AED);
    }

    private static EventEnvelope env(long position, String id, int processingDay, int valueDay,
                                     LedgerEvent event) {
        return new EventEnvelope(id, position, Scenario.day(processingDay), Scenario.day(valueDay),
                event);
    }

    private ReplayResult run(List<EventEnvelope> events) {
        return new LedgerEngine(List.of(AED), Scenario.WINDOW_START, Scenario.WINDOW_END,
                Scenario.BUSINESS_ZONE, Scenario.OVERDRAFT_FEE_SCHEDULE).replay(events);
    }

    private static List<LedgerEntry> entriesOfKind(ReplayResult result, EntryKind kind) {
        return result.entries().stream().filter(e -> e.kind() == kind).toList();
    }

    private static Money closing(ReplayResult result, int day) {
        return LedgerEngine.closingBalance(result.entries(), AED, Scenario.day(day));
    }

    // ---------------------------------------------------------------
    // Test A: an earlier fee becomes unjustified; its compensation
    // changes later dates' balances and their fee justifications.
    //
    // X1 -30@D1, X2 -5@D2, X3 +50@D1 (proc D4, LATE correction).
    // Charges:   F1@D1 (preFee -30.00, derivedOn D1),
    //            F2@D2 (preFee -60.00 = -30-25-5, derivedOn D2),
    //            F3@D3 (preFee -85.00, derivedOn D3).
    // D4 evening (X3 now in the books): D1 = 20.00, D2 = 15.00, D3 = 15.00
    // -> NO date is overdrawn -> all three fees compensated (derivedOn D4).
    // Note the consistency requirement: D2's corrected base (15.00) must be
    // computed WITHOUT F1's about-to-be-compensated fee; an algorithm that
    // leaked it would see -10.00 and wrongly keep F2.
    // ---------------------------------------------------------------
    private static List<EventEnvelope> streamA() {
        return List.of(
                env(1, "X1", 1, 1, new LedgerEvent.Debit("A", aed("30.00"))),
                env(2, "X2", 2, 2, new LedgerEvent.Debit("A", aed("5.00"))),
                env(3, "X3", 4, 1, new LedgerEvent.Credit("A", aed("50.00"))));
    }

    @Test
    void compensationOfEarlierFeeFlowsIntoLaterDatesAndCompensatesThemToo() {
        ReplayResult result = run(streamA());

        List<LedgerEntry> fees = entriesOfKind(result, EntryKind.FEE_OVERDRAFT);
        assertEquals(3, fees.size());
        assertEquals(Scenario.day(1), fees.get(0).valueDate());
        assertEquals(Scenario.day(1), fees.get(0).derivedOnDate());
        assertEquals(aed("-60.00"),
                result.feeTrace().stream().filter(f -> f.valueDate().equals(Scenario.day(2)))
                        .findFirst().orElseThrow().preFeeBalance());
        assertEquals(aed("-85.00"),
                result.feeTrace().stream().filter(f -> f.valueDate().equals(Scenario.day(3)))
                        .findFirst().orElseThrow().preFeeBalance());

        // All three fees compensated on the D4 evening; originals retained.
        List<LedgerEntry> compensations = entriesOfKind(result, EntryKind.FEE_REVERSAL);
        assertEquals(3, compensations.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(fees.get(i).valueDate(), compensations.get(i).valueDate());
            assertEquals(aed("25.00"), compensations.get(i).amount());
            assertEquals(Scenario.day(4), compensations.get(i).derivedOnDate());
        }

        // Finals: D1 20.00 (fee nets to zero), D2..D5 15.00, D6 15.06
        // (interest 0.01 x 6 = 0.06).
        assertEquals(aed("20.00"), closing(result, 1));
        assertEquals(aed("15.00"), closing(result, 2));
        assertEquals(aed("15.00"), closing(result, 5));
        assertEquals(aed("15.06"), closing(result, 6));
    }

    // ---------------------------------------------------------------
    // Test B: a compensated account-date is NEVER re-charged (the
    // documented one-lifecycle-per-date policy, AMBIGUITIES.md #28),
    // while genuinely new negative dates still get charged.
    //
    // Stream A + X4 -80@D1 (proc D5). After compensation D1 = 20.00;
    // X4 makes D1 = -60.00 again. D1, D2, D3 stay at exactly one charge
    // and one compensation each; the negativity cascades to genuinely new
    // dates D4 (preFee -65.00, derivedOn D5), D5 (preFee -90.00, derivedOn
    // D5), D6 (preFee -115.00, derivedOn D6).
    // ---------------------------------------------------------------
    @Test
    void compensatedDateIsNeverRechargedButNewNegativeDatesAreCharged() {
        List<EventEnvelope> stream = new java.util.ArrayList<>(streamA());
        stream.add(env(4, "X4", 5, 1, new LedgerEvent.Debit("A", aed("80.00"))));
        ReplayResult result = run(stream);

        List<LedgerEntry> fees = entriesOfKind(result, EntryKind.FEE_OVERDRAFT);
        assertEquals(6, fees.size());
        // D1, D2, D3: exactly one charge each, despite ending negative.
        for (int day : List.of(1, 2, 3)) {
            assertEquals(1, fees.stream()
                    .filter(e -> e.valueDate().equals(Scenario.day(day))).count(),
                    "D" + day + " must never be re-charged after compensation");
        }
        // New charges for D4/D5/D6 with the correct discovery dates and bases.
        List<FeeAssessment> trace = result.feeTrace();
        FeeAssessment d4 = trace.stream().filter(f -> f.valueDate().equals(Scenario.day(4)))
                .findFirst().orElseThrow();
        assertEquals(aed("-65.00"), d4.preFeeBalance());
        assertEquals(Scenario.day(5), d4.assessedOnDate());
        FeeAssessment d6 = trace.stream().filter(f -> f.valueDate().equals(Scenario.day(6)))
                .findFirst().orElseThrow();
        assertEquals(aed("-115.00"), d6.preFeeBalance());
        assertEquals(Scenario.day(6), d6.assessedOnDate());

        assertEquals(3, entriesOfKind(result, EntryKind.FEE_REVERSAL).size());
        assertEquals(aed("-60.00"), closing(result, 1));
        assertEquals(aed("-65.00"), closing(result, 2));
        assertEquals(aed("-65.00"), closing(result, 3));
        assertEquals(aed("-90.00"), closing(result, 4));
        assertEquals(aed("-115.00"), closing(result, 5));
        assertEquals(aed("-140.00"), closing(result, 6));
        // Everything negative -> no interest capitalization at all.
        assertEquals(0, entriesOfKind(result, EntryKind.INTEREST_CAPITALIZATION).size());
    }

    // ---------------------------------------------------------------
    // Test C: regression for the boundary-poisoning defect. An
    // out-of-window event (processing D7 > window end) arrives FIRST.
    // It must be logged and rejected with exactly one outcome, and must
    // NOT advance the processing clock — otherwise the final
    // reconciliation would start past the window end and the valid late
    // event below would never be fee-assessed (0 fees on the old code).
    // ---------------------------------------------------------------
    @Test
    void outOfWindowEventDoesNotAdvanceBoundariesOrSuppressReconciliation() {
        EventEnvelope rogue = new EventEnvelope("X0", 1,
                Scenario.WINDOW_END.plusDays(1), Scenario.WINDOW_END.plusDays(1),
                new LedgerEvent.Credit("A", aed("100.00")));
        EventEnvelope valid = env(2, "X1", 1, 1, new LedgerEvent.Debit("A", aed("10.00")));
        ReplayResult result = run(List.of(rogue, valid));

        // Logged in stream order, exactly one outcome each.
        assertEquals(List.of("X0", "X1"),
                result.log().events().stream().map(EventEnvelope::eventId).toList());
        Outcome rogueOutcome = result.outcomeAt(1);
        assertInstanceOf(Outcome.Rejected.class, rogueOutcome);
        assertTrue(((Outcome.Rejected) rogueOutcome).reason().contains("EVENT_DATE_OUT_OF_HORIZON"));
        assertInstanceOf(Outcome.Accepted.class, result.outcomeAt(2));

        // The reconciliation ran for the valid event's overdraft: D1 = -10,
        // carried forward -> one fee per date D1..D6, six in total. On the
        // defective code this was 0.
        assertEquals(6, entriesOfKind(result, EntryKind.FEE_OVERDRAFT).size());
        assertEquals(6, result.feeTrace().stream().map(FeeAssessment::valueDate).distinct().count());
        // The rogue event derived nothing.
        assertEquals(0, result.entries().stream()
                .filter(e -> e.sourceEventId().equals("X0")).count());
    }

    // ---------------------------------------------------------------
    // Test D: forward -> backward -> forward processing dates.
    //
    // X1 +100@D2 (proc D2), X2 -30@D4 (proc D4), X3 -160@D2 (proc D3,
    // BACKWARD — creates a historical overdraft on D2 whose evening has
    // already passed), X4 +300@D6 (proc D6).
    // X3's overdraft is reconciled at the next boundary (the D4 evening,
    // reached when X4 advances the clock): fees F1@D2 (preFee -60.00),
    // F2@D3 (preFee -85.00), F3@D4 (preFee -140.00), all derivedOn D4;
    // F4@D5 (preFee -165.00, derivedOn D5). D6 = 110.00 positive -> no fee,
    // interest 110 x 0.0004 = 0.0440 -> 0.04 capitalized once.
    // ---------------------------------------------------------------
    @Test
    void forwardBackwardForwardProcessingDatesReconcileAtNextBoundary() {
        ReplayResult result = run(List.of(
                env(1, "X1", 2, 2, new LedgerEvent.Credit("A", aed("100.00"))),
                env(2, "X2", 4, 4, new LedgerEvent.Debit("A", aed("30.00"))),
                env(3, "X3", 3, 2, new LedgerEvent.Debit("A", aed("160.00"))),
                env(4, "X4", 6, 6, new LedgerEvent.Credit("A", aed("300.00")))));

        // Stream order preserved despite the backward processing date.
        assertEquals(List.of("X1", "X2", "X3", "X4"),
                result.log().events().stream().map(EventEnvelope::eventId).toList());

        List<LedgerEntry> fees = entriesOfKind(result, EntryKind.FEE_OVERDRAFT);
        assertEquals(4, fees.size());
        assertEquals(List.of(Scenario.day(2), Scenario.day(3), Scenario.day(4), Scenario.day(5)),
                fees.stream().map(LedgerEntry::valueDate).toList());
        assertEquals(List.of(Scenario.day(4), Scenario.day(4), Scenario.day(4), Scenario.day(5)),
                fees.stream().map(LedgerEntry::derivedOnDate).toList());
        assertEquals(aed("-60.00"), result.feeTrace().get(0).preFeeBalance());
        assertEquals(aed("-85.00"), result.feeTrace().get(1).preFeeBalance());
        assertEquals(aed("-140.00"), result.feeTrace().get(2).preFeeBalance());
        assertEquals(aed("-165.00"), result.feeTrace().get(3).preFeeBalance());
        assertEquals(0, entriesOfKind(result, EntryKind.FEE_REVERSAL).size());

        assertEquals(aed("0.00"), closing(result, 1));
        assertEquals(aed("-85.00"), closing(result, 2));
        assertEquals(aed("-110.00"), closing(result, 3));
        assertEquals(aed("-165.00"), closing(result, 4));
        assertEquals(aed("-190.00"), closing(result, 5));
        // D6: -190 + 300 = 110.00, plus exactly one capitalization of 0.04.
        assertEquals(1, entriesOfKind(result, EntryKind.INTEREST_CAPITALIZATION).size());
        assertEquals(aed("110.04"), closing(result, 6));
    }

    // ---------------------------------------------------------------
    // Test E: a fee that stays justified is never compensated, and a
    // fee-bearing replay (including compensations) is deterministic.
    // ---------------------------------------------------------------
    @Test
    void justifiedFeesAreNeverCompensated() {
        ReplayResult result = run(List.of(
                env(1, "X1", 1, 1, new LedgerEvent.Debit("A", aed("10.00")))));
        assertEquals(6, entriesOfKind(result, EntryKind.FEE_OVERDRAFT).size());
        assertEquals(0, entriesOfKind(result, EntryKind.FEE_REVERSAL).size());
        assertEquals(0, result.feeCompensations().size());
    }

    @Test
    void replayWithCompensationsIsByteDeterministic() {
        ReplayResult first = run(streamA());
        ReplayResult second = run(streamA());
        assertEquals(first.entries(), second.entries());
        assertEquals(first.feeTrace(), second.feeTrace());
        assertEquals(first.feeCompensations(), second.feeCompensations());
        assertEquals(first.interestTrace(), second.interestTrace());
        assertEquals(first.outcomes(), second.outcomes());
        // Entry sequence numbers are part of equality: identical ordered
        // entries prove deterministic emission order.
        assertEquals(Scenario.day(1), first.entries().stream()
                .filter(e -> e.kind() == EntryKind.FEE_OVERDRAFT).findFirst().orElseThrow()
                .valueDate());
    }
}
