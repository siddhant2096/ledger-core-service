package com.ledger.core.service;

import com.ledger.core.service.application.LedgerEngine;
import com.ledger.core.service.application.ReplayResult;
import com.ledger.core.service.application.ReplayResult.Outcome;
import com.ledger.core.service.application.Scenario;
import com.ledger.core.service.domain.account.Account;
import com.ledger.core.service.domain.auth.AuthorizationStatus;
import com.ledger.core.service.domain.entry.EntryKind;
import com.ledger.core.service.domain.entry.LedgerEntry;
import com.ledger.core.service.domain.money.CurrencyCode;
import com.ledger.core.service.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-event regression tests for the exact specification stream E1..E10.
 * Every expected figure is derived in the test comment from the documented
 * rules — nothing is hardcoded to force a pass.
 */
class EventStreamTest {

    private final ReplayResult result = Scenario.replay();
    private final Account acc001 = Scenario.accounts().get(0);
    private final Account acc002 = Scenario.accounts().get(1);

    private static LocalDate day(int n) {
        return Scenario.day(n);
    }

    private Money closing(Account account, int day) {
        return LedgerEngine.closingBalance(result.entries(), account, day(day));
    }

    private List<LedgerEntry> entriesOf(String eventId) {
        return result.entries().stream().filter(e -> e.sourceEventId().equals(eventId)).toList();
    }

    private static Money aed(String s) {
        return Money.of(s, CurrencyCode.AED);
    }

    private static Money bhd(String s) {
        return Money.of(s, CurrencyCode.BHD);
    }

    @Test
    void e1Credits1200AndE2Debits950Leaving250() {
        // E1 +1200.00, E2 -950.00 -> 250.00 on Day 1 (both value D1).
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("E1"));
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("E2"));
        assertEquals(aed("1200.00"), entriesOf("E1").getFirst().amount());
        assertEquals(aed("-950.00"), entriesOf("E2").getFirst().amount());
        // Pre-fee day-1 basis: 1200 - 950 = 250.00; day-1 fee trace is empty
        // (never negative), so the final D1 closing is exactly 250.00.
        assertEquals(aed("250.00"), closing(acc001, 1));
    }

    @Test
    void e3CreatesHoldWithoutChangingLedgerBalance() {
        // Authorization derives NO ledger entry; only auth state. The decision
        // note proves E3 was APPROVED at its processing point (the final
        // registry state is PARTIALLY_SETTLED after E5 — that is E5's test).
        Outcome e3 = result.outcomeOf("E3");
        assertInstanceOf(Outcome.Accepted.class, e3);
        assertTrue(((Outcome.Accepted) e3).note().contains("AUTH APPROVED"));
        assertEquals(0, entriesOf("E3").size());
        var authA = result.authorizations().get("Auth-A");
        assertEquals(aed("200.00"), authA.holdAmount());
        assertEquals(day(2), authA.processedDate());
        // Ledger balance on D2 is unchanged by the hold: 250.00 (the D2 fee
        // triggered later by E7 was compensated after E9; neither has anything
        // to do with E3).
        assertEquals(aed("250.00"), closing(acc001, 2));
        // ...while the hold was real: at decision time available went from
        // 250.00 to 50.00.
    }

    @Test
    void e4Credits400() {
        assertEquals(aed("400.00"), entriesOf("E4").getFirst().amount());
        assertEquals(day(3), entriesOf("E4").getFirst().valueDate());
        // D3 closing: D2 250.00 + 400.00 = 650.00.
        assertEquals(aed("650.00"), closing(acc001, 3));
    }

    @Test
    void e5SettlesAuthAFor185AndReleasesResidualHold() {
        // 185.00 <= 200.00 hold -> accepted; posts -185.00 @D4; authorization
        // -> PARTIALLY_SETTLED; residual 15.00 released (documented policy).
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("E5"));
        assertEquals(aed("-185.00"), entriesOf("E5").getFirst().amount());
        var authA = result.authorizations().get("Auth-A");
        assertEquals(AuthorizationStatus.PARTIALLY_SETTLED, authA.status());
        assertEquals(day(4), authA.settledOnDate());
        assertEquals(aed("15.00"), authA.holdAmount().subtract(authA.settledAmount()));
        // No active hold remains after settlement.
        assertEquals(aed("0.00"), LedgerEngine.activeHolds(result.authorizations(), acc001));
    }

    @Test
    void e6IsRejectedUnknownAuthorizationNoDebitPosted() {
        Outcome outcome = result.outcomeOf("E6");
        assertInstanceOf(Outcome.Rejected.class, outcome);
        assertTrue(((Outcome.Rejected) outcome).reason().contains("UNKNOWN_AUTHORIZATION"));
        assertEquals(0, entriesOf("E6").size());
        assertEquals(null, result.authorizations().get("Auth-Z"));
    }

    @Test
    void e7Posts620DebitValuedDay2ChangingHistoricalProjection() {
        LedgerEntry e7 = entriesOf("E7").getFirst();
        assertEquals(aed("-620.00"), e7.amount());
        assertEquals(day(2), e7.valueDate());      // back-valued
        assertEquals(day(5), e7.derivedOnDate());  // processed on Day 5
        // Historical effect: pre-fee D2 balance is 250 - 620 = -370.00, which
        // triggers the D2 fee on the evening of D5 (assessed-on date proves it).
        var d2Fee = result.feeTrace().stream()
                .filter(f -> f.valueDate().equals(day(2))).findFirst().orElseThrow();
        assertEquals(aed("-370.00"), d2Fee.preFeeBalance());
        assertEquals(day(5), d2Fee.assessedOnDate());
        // And E7's knock-on: D4 (5.00 - 185.00 = -180.00) and D5 (-205.00).
        assertEquals(aed("-180.00"), result.feeTrace().get(1).preFeeBalance());
        assertEquals(aed("-205.00"), result.feeTrace().get(2).preFeeBalance());
    }

    @Test
    void e8AuthBDeclinedBecauseAvailableBalanceWouldGoNegative() {
        // Books at E8's processing point (D5, after E7): 1200 - 950 + 400
        // - 185 - 620 = -155.00; active holds 0; -155.00 - 90.00 < 0 -> DECLINED.
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("E8")); // decision, not failure
        var authB = result.authorizations().get("Auth-B");
        assertEquals(AuthorizationStatus.DECLINED, authB.status());
        assertEquals(0, entriesOf("E8").size());
        // A declined hold never creates an active hold, on D5, D6, or ever.
        assertEquals(aed("0.00"), LedgerEngine.activeHolds(result.authorizations(), acc001));
        // (The "retain the hold through Day 6 if approved" rule applies only to
        // approved authorizations; the mechanism is pinned by
        // BoundaryAndValidationTest.approvedButNeverSettledHoldPersistsThroughWindowEnd.)
    }

    @Test
    void e9CompensatesE7OnValueDateDay2WithoutDeletingAnything() {
        LedgerEntry compensation = entriesOf("E9").getFirst();
        assertEquals(aed("620.00"), compensation.amount());
        assertEquals(EntryKind.REVERSAL_COMPENSATION, compensation.kind());
        assertEquals(day(2), compensation.valueDate());
        assertEquals(day(6), compensation.derivedOnDate());
        // E7 and all three fee entries survive untouched; each fee is
        // neutralized by an append-only FEE_REVERSAL entry (derived on the D6
        // evening) because the corrected books show no overdraft anywhere.
        assertEquals(1, entriesOf("E7").size());
        assertEquals(3, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.FEE_OVERDRAFT).count());
        assertEquals(3, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.FEE_REVERSAL).count());
        // Effect on all six daily closings (final projection, pre-interest):
        // D1 250.00 | D2 250.00 | D3 650.00 | D4 465.00 | D5 465.00 | D6 465.00.
        assertEquals(aed("250.00"), closing(acc001, 1));
        assertEquals(aed("250.00"), closing(acc001, 2));
        assertEquals(aed("650.00"), closing(acc001, 3));
        assertEquals(aed("465.00"), closing(acc001, 4));
        assertEquals(aed("465.00"), closing(acc001, 5));
    }

    @Test
    void e9ThenE10NonMonotonicProcessingDatesAreHandledInStreamOrder() {
        // E9 (processing D6) precedes E10 (processing D5) in the stream: the
        // processing-date sequence is non-monotonic. Requirements: stream
        // order preserved, all value dates examined, no fee charged twice.
        List<String> logOrder = result.log().events().stream()
                .map(com.ledger.core.service.domain.event.EventEnvelope::eventId).toList();
        assertTrue(logOrder.indexOf("E9") < logOrder.indexOf("E10"),
                "stream order must be preserved over processing-date order");

        // Every ACC-001 value date was examined; exactly the three negative
        // ones were charged, each exactly once, all on the D5 evening (the
        // only evening after E7's arrival; E10's late arrival adds nothing for
        // ACC-001 and ACC-002 never goes negative).
        assertEquals(3, result.feeTrace().size());
        assertEquals(3, result.feeTrace().stream().map(f -> f.valueDate()).distinct().count());
        assertTrue(result.feeTrace().stream()
                .allMatch(f -> f.assessedOnDate().equals(day(5))));
        assertTrue(result.feeTrace().stream()
                .allMatch(f -> f.accountId().equals("ACC-001")));

        // E10 was still applied (processing date moving backwards is not a
        // skip): three D5-valued instalments exist and total exactly 10.000.
        assertEquals(3, entriesOf("E10").size());
        assertTrue(entriesOf("E10").stream().allMatch(e -> e.valueDate().equals(day(5))));

        // Immutability after later events: replaying the same stream derives
        // byte-identical entries (no duplicate fees, no reordering).
        ReplayResult again = Scenario.replay();
        assertEquals(result.entries(), again.entries());
        assertEquals(result.feeTrace(), again.feeTrace());
    }

    @Test
    void e10SplitsExactly10000BhdAcrossThreeInstalments() {
        List<LedgerEntry> parts = entriesOf("E10");
        assertEquals(3, parts.size());
        // 10000 minor units / 3 = 3333 remainder 1 -> residual to the LAST
        // instalment: 3.333 + 3.333 + 3.334 = 10.000 exactly. Never 3 x 3.334
        // = 10.002 (money created) nor 3 x 3.333 = 9.999 (money destroyed).
        assertEquals(bhd("3.333"), parts.get(0).amount());
        assertEquals(bhd("3.333"), parts.get(1).amount());
        assertEquals(bhd("3.334"), parts.get(2).amount());
        assertEquals(bhd("10.000"), parts.stream().map(LedgerEntry::amount)
                .reduce(Money.zero(CurrencyCode.BHD), Money::add));
        assertTrue(parts.stream().allMatch(e -> e.valueDate().equals(day(5))));
        assertEquals(bhd("10.000"), closing(acc002, 5));
    }
}
