package com.ledger.core.service;

import com.ledger.core.service.application.LedgerEngine;
import com.ledger.core.service.application.ReplayResult;
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
 * Reversal validation and state-transition correctness.
 *
 * <p>Regression context: the engine must validate a reversal target fully and
 * post the compensation BEFORE marking the target id as reversed. A failed
 * reversal must leave no trace — otherwise a rejection of "reverse X1" before
 * X1 exists would poison X1 and block its legitimate reversal after X1
 * arrives.
 */
class ReversalTest {

    private static final Account AED = Account.zeroBalance("A", CurrencyCode.AED);
    private static final Account BHD = Account.zeroBalance("B", CurrencyCode.BHD);

    private final AtomicLong position = new AtomicLong(0);

    private EventEnvelope env(String id, int processingDay, int valueDay, LedgerEvent event) {
        return new EventEnvelope(id, position.incrementAndGet(),
                Scenario.day(processingDay), Scenario.day(valueDay), event);
    }

    private ReplayResult run(List<Account> accounts, List<EventEnvelope> events) {
        return new LedgerEngine(accounts, Scenario.WINDOW_START, Scenario.WINDOW_END,
                Scenario.BUSINESS_ZONE, Scenario.OVERDRAFT_FEE_SCHEDULE).replay(events);
    }

    private EventEnvelope credit(String id, int day, String account, String amount, CurrencyCode c) {
        return env(id, day, day, new LedgerEvent.Credit(account, Money.of(amount, c)));
    }

    private EventEnvelope debit(String id, int day, String account, String amount) {
        return env(id, day, day, new LedgerEvent.Debit(account, Money.of(amount, CurrencyCode.AED)));
    }

    private EventEnvelope reversal(String id, int day, String account, String targetId) {
        return env(id, day, day, new LedgerEvent.Reversal(account, targetId));
    }

    private static String reason(ReplayResult result, String eventId) {
        return ((Outcome.Rejected) result.outcomeOf(eventId)).reason();
    }

    @Test
    void failedReversalOfUnknownTargetDoesNotPoisonTheTargetId() {
        // D1: reversal of X1 — X1 does not exist yet -> rejected.
        // D2: X1 arrives as a real debit -> accepted.
        // D3: reversal of X1 -> MUST be accepted. The D1 rejection must not
        // have marked X1 as reversed (the pre-fix bug did exactly that).
        ReplayResult result = run(List.of(AED), List.of(
                reversal("R1", 1, "A", "X1"),
                credit("X0", 2, "A", "100.00", CurrencyCode.AED),
                debit("X1", 2, "A", "40.00"),
                reversal("R2", 3, "A", "X1")));

        assertTrue(reason(result, "R1").contains("REVERSAL_TARGET_UNKNOWN"));
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("X1"));
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("R2"),
                "a failed reversal must not block a later valid reversal of the same id");

        // Exactly one compensation, and the balance proves the net:
        // +100 - 40 + 40 = 100.00 (checked on D5, before interest capitalization).
        assertEquals(1, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.REVERSAL_COMPENSATION).count());
        assertEquals(Money.of("100.00", CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED, Scenario.day(5)));
    }

    @Test
    void rejectedReversalMutatesNothing() {
        ReplayResult result = run(List.of(AED), List.of(
                credit("X0", 1, "A", "100.00", CurrencyCode.AED),
                reversal("R1", 2, "A", "NOPE")));
        long postingEntries = result.entries().stream()
                .filter(e -> e.kind() == EntryKind.POSTING).count();
        assertEquals(1, postingEntries); // only the credit
        assertEquals(Money.of("100.00", CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED, Scenario.day(5)));
    }

    @Test
    void duplicateReversalIsRejectedAndOnlyOneCompensationExists() {
        ReplayResult result = run(List.of(AED), List.of(
                credit("X0", 1, "A", "100.00", CurrencyCode.AED),
                debit("X1", 1, "A", "10.00"),
                reversal("R1", 2, "A", "X1"),
                reversal("R2", 3, "A", "X1")));
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("R1"));
        assertTrue(reason(result, "R2").contains("DUPLICATE_REVERSAL"));
        assertEquals(1, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.REVERSAL_COMPENSATION).count());
        assertEquals(Money.of("100.00", CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED, Scenario.day(5)));
    }

    @Test
    void crossAccountReversalIsRejected() {
        // X1 debits account A; R1 on account B tries to reverse it.
        ReplayResult result = run(List.of(AED, BHD), List.of(
                credit("X0", 1, "A", "100.00", CurrencyCode.AED),
                debit("X1", 1, "A", "10.00"),
                reversal("R1", 2, "B", "X1")));
        assertTrue(reason(result, "R1").contains("REVERSAL_ACCOUNT_MISMATCH"));
        // Account A keeps its debit; no compensation was posted anywhere.
        assertEquals(0, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.REVERSAL_COMPENSATION).count());
        assertEquals(Money.of("90.00", CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED, Scenario.day(5)));
    }

    @Test
    void reversingAnAuthorizationOrRejectedEventFindsNoEntries() {
        // Authorization events derive no ledger entries; rejected events
        // derive none either. Both land on REVERSAL_TARGET_UNKNOWN — consistent
        // with "rejected means it did not happen, accounting-wise".
        ReplayResult result = run(List.of(AED), List.of(
                credit("X0", 1, "A", "100.00", CurrencyCode.AED),
                env("X1", 2, 2, new LedgerEvent.AuthorizationRequest("A", "Auth-X",
                        Money.of("10.00", CurrencyCode.AED))),
                reversal("R1", 3, "A", "X1"),
                reversal("R2", 3, "A", "GHOST")));
        assertTrue(reason(result, "R1").contains("REVERSAL_TARGET_UNKNOWN"));
        assertTrue(reason(result, "R2").contains("REVERSAL_TARGET_UNKNOWN"));
    }

    @Test
    void reversingDerivedFeeEntriesIsUnsupported() {
        // The fee entry's source id is "FEE-A-<date>" (derived on the evening
        // of D1). Attempting to reverse that id must be rejected: fees are
        // corrected by explicit business events, not raw reversal.
        String feeSourceId = "FEE-A-" + Scenario.day(1);
        ReplayResult result = run(List.of(AED), List.of(
                debit("X1", 1, "A", "10.00"),
                reversal("R1", 2, "A", feeSourceId)));
        assertTrue(reason(result, "R1").contains("REVERSAL_UNSUPPORTED_TARGET"));
    }

    @Test
    void reversingAFeeCompensationEntryIsUnsupported() {
        // A FEE_REVERSAL entry's deterministic source id is
        // "FEE-REVERSAL-<account>-<date>". It is a derived compensation, not a
        // POSTING, so reversing it is rejected — compensations cannot be
        // reversed through the ordinary reversal event.
        // Stream: debit -> fee D1; back-valued credit -> fee compensated on D2 evening.
        String feeReversalSourceId = "FEE-REVERSAL-A-" + Scenario.day(1);
        ReplayResult result = run(List.of(AED), List.of(
                debit("X1", 1, "A", "10.00"),
                env("X2", 2, 1, new LedgerEvent.Credit("A", Money.of("100.00", CurrencyCode.AED))),
                reversal("R1", 3, "A", feeReversalSourceId)));
        assertTrue(reason(result, "R1").contains("REVERSAL_UNSUPPORTED_TARGET"));
        // The compensation entry is intact and still singular.
        assertEquals(1, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.FEE_REVERSAL).count());
    }

    @Test
    void reversingInterestIsUnreachableAndHarmless() {
        // Interest capitalization entries are derived only AFTER the last
        // event is processed, so no in-window event can target them: the
        // deterministic id "INTEREST-<account>-<windowEnd>" resolves to
        // REVERSAL_TARGET_UNKNOWN and derives nothing.
        String interestId = "INTEREST-A-" + Scenario.WINDOW_END;
        ReplayResult result = run(List.of(AED), List.of(
                credit("X1", 1, "A", "100.00", CurrencyCode.AED),
                reversal("R1", 2, "A", interestId)));
        assertTrue(reason(result, "R1").contains("REVERSAL_TARGET_UNKNOWN"));
        assertEquals(1, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.INTEREST_CAPITALIZATION).count());
        assertEquals(Money.of("100.24", CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED, Scenario.day(6)));
    }

    @Test
    void reversalOfAReversalIsRejected() {
        // R1's compensation entry is kind REVERSAL_COMPENSATION, which is not
        // a reversible POSTING — so re-reversing R1 is unsupported.
        ReplayResult result = run(List.of(AED), List.of(
                credit("X0", 1, "A", "100.00", CurrencyCode.AED),
                debit("X1", 1, "A", "10.00"),
                reversal("R1", 2, "A", "X1"),
                reversal("R2", 3, "A", "R1")));
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("R1"));
        assertTrue(reason(result, "R2").contains("REVERSAL_UNSUPPORTED_TARGET"));
        assertEquals(1, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.REVERSAL_COMPENSATION).count());
    }

    @Test
    void multiEntryInstalmentCreditIsReversedInFullAsOneCompensation() {
        // BHD 10.000 in 3 instalments (3.333 + 3.333 + 3.334) on D1, reversed
        // on D2 (value date D2). Compensation must equal the SUM of the parts
        // — 10.000 exactly — as a single entry.
        ReplayResult result = run(List.of(BHD), List.of(
                env("X1", 1, 1, new LedgerEvent.InstalmentCredit("B",
                        Money.of("10.000", CurrencyCode.BHD), 3)),
                reversal("R1", 2, "B", "X1")));
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("R1"));

        LedgerEntry compensation = result.entries().stream()
                .filter(e -> e.kind() == EntryKind.REVERSAL_COMPENSATION)
                .findFirst().orElseThrow();
        assertEquals(Money.of("-10.000", CurrencyCode.BHD), compensation.amount());
        assertEquals(Scenario.day(2), compensation.valueDate());

        // Originals intact: 3 instalment postings remain; D1 closing still
        // 10.000; from D2 the net is zero (no fees — never negative).
        assertEquals(3, result.entries().stream()
                .filter(e -> e.sourceEventId().equals("X1")).count());
        assertEquals(Money.of("10.000", CurrencyCode.BHD),
                LedgerEngine.closingBalance(result.entries(), BHD, Scenario.day(1)));
        assertEquals(Money.zero(CurrencyCode.BHD),
                LedgerEngine.closingBalance(result.entries(), BHD, Scenario.day(5)));
        assertTrue(result.feeTrace().isEmpty());
    }
}
