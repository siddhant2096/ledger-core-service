package com.ledger.core.service;

import com.ledger.core.service.application.LedgerEngine;
import com.ledger.core.service.application.ReplayResult;
import com.ledger.core.service.application.ReplayResult.Outcome;
import com.ledger.core.service.application.Scenario;
import com.ledger.core.service.domain.account.Account;
import com.ledger.core.service.domain.auth.Authorization;
import com.ledger.core.service.domain.auth.AuthorizationStatus;
import com.ledger.core.service.domain.entry.EntryKind;
import com.ledger.core.service.domain.entry.LedgerEntry;
import com.ledger.core.service.domain.event.EventEnvelope;
import com.ledger.core.service.domain.event.LedgerEvent;
import com.ledger.core.service.domain.money.CurrencyCode;
import com.ledger.core.service.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary cases and validation policies, each on a minimal hand-built stream
 * so the expected values follow directly from the inputs. Dates are built on
 * the shared {@link Scenario} window (Day n = 2026-01-0n).
 */
class BoundaryAndValidationTest {

    private static final Account AED_ACCOUNT = Account.zeroBalance("A", CurrencyCode.AED);
    private static final Account BHD_ACCOUNT = Account.zeroBalance("B", CurrencyCode.BHD);

    private final AtomicLong position = new AtomicLong(0);

    private EventEnvelope env(String id, int processingDay, int valueDay, LedgerEvent event) {
        return new EventEnvelope(id, position.incrementAndGet(),
                Scenario.day(processingDay), Scenario.day(valueDay), event);
    }

    private ReplayResult run(List<Account> accounts, List<EventEnvelope> events) {
        return new LedgerEngine(accounts, Scenario.WINDOW_START, Scenario.WINDOW_END,
                Scenario.BUSINESS_ZONE, Scenario.OVERDRAFT_FEE_SCHEDULE).replay(events);
    }

    @Test
    void zeroBalanceEarnsNoInterestAndPaysNoFee() {
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of());
        for (int day = 1; day <= 6; day++) {
            assertEquals(Money.zero(CurrencyCode.AED),
                    LedgerEngine.closingBalance(result.entries(), AED_ACCOUNT, Scenario.day(day)));
        }
        assertTrue(result.feeTrace().isEmpty());
        assertTrue(result.entries().isEmpty()); // zero total interest -> no capitalization entry
    }

    @Test
    void negativeBalanceEarnsNoInterest() {
        // -10.00 from D1; every date closes negative, so exactly one fee per
        // date, six in total (fees carry forward: D1 -10, D2 -35, ...).
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Debit("A", Money.of("10.00", CurrencyCode.AED)))));
        assertEquals(6, result.feeTrace().size());
        assertEquals(Money.of("10.00", CurrencyCode.AED).negate(),
                result.feeTrace().getFirst().preFeeBalance());
        // No duplicate fee for any account-date even though every date is
        // re-examined on subsequent evenings.
        assertEquals(6, result.feeTrace().stream().map(f -> f.valueDate()).distinct().count());
        assertTrue(result.interestTrace().stream().allMatch(a -> a.accrual().isZero()));
        long feeDays = result.entries().stream()
                .filter(e -> e.kind() == EntryKind.FEE_OVERDRAFT).count();
        assertEquals(6, feeDays);
    }

    @Test
    void exactZeroAvailableBalanceIsApproved() {
        // Ledger 100.00, hold 100.00 -> available after hold == 0.00, and the
        // rule is "at least zero", so the authorization must be APPROVED.
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Credit("A", Money.of("100.00", CurrencyCode.AED))),
                env("X2", 2, 2, new LedgerEvent.AuthorizationRequest("A", "Auth-X",
                        Money.of("100.00", CurrencyCode.AED)))));
        assertEquals(AuthorizationStatus.APPROVED, result.authorizations().get("Auth-X").status());
    }

    @Test
    void oneFilsBelowTheHoldIsDeclined() {
        // Ledger 99.99, hold 100.00 -> available after hold = -0.01 < 0.
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Credit("A", Money.of("99.99", CurrencyCode.AED))),
                env("X2", 2, 2, new LedgerEvent.AuthorizationRequest("A", "Auth-X",
                        Money.of("100.00", CurrencyCode.AED)))));
        assertEquals(AuthorizationStatus.DECLINED, result.authorizations().get("Auth-X").status());
    }

    @Test
    void crossCurrencyEventIsRejectedNotPosted() {
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Credit("A", Money.of("5.000", CurrencyCode.BHD)))));
        Outcome outcome = result.outcomeOf("X1");
        assertInstanceOf(Outcome.Rejected.class, outcome);
        assertTrue(((Outcome.Rejected) outcome).reason().contains("CURRENCY_MISMATCH"));
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void bhdAccountIsChargedConfiguredFeeOnlyWhenScheduleAuthorizesIt() {
        // Mechanism test: IF the business configures a BHD fee, a negative BHD
        // account is charged in BHD (never AED — no FX table exists). The
        // DEFAULT scenario schedule deliberately contains only AED 25.00.
        ReplayResult result = new LedgerEngine(List.of(BHD_ACCOUNT),
                Scenario.WINDOW_START, Scenario.WINDOW_END, Scenario.BUSINESS_ZONE,
                java.util.Map.of(CurrencyCode.AED, Money.of("25.00", CurrencyCode.AED),
                        CurrencyCode.BHD, Money.of("25.000", CurrencyCode.BHD)))
                .replay(List.of(env("X1", 1, 1,
                        new LedgerEvent.Debit("B", Money.of("1.000", CurrencyCode.BHD)))));
        assertEquals(Money.of("25.000", CurrencyCode.BHD), result.feeTrace().getFirst().feeAmount());
        assertEquals(CurrencyCode.BHD, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.FEE_OVERDRAFT)
                .findFirst().orElseThrow().amount().currency());
    }

    @Test
    void bhdAccountIsChargedNothingUnderTheDefaultAedOnlySchedule() {
        // Default schedule = AED 25.00 only (the supplied requirement). A
        // negative BHD account must NOT be charged (no silent cross-currency
        // fee, no invented BHD fee) and the skip must be visible in the trace.
        ReplayResult result = run(List.of(BHD_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Debit("B", Money.of("1.000", CurrencyCode.BHD)))));
        assertEquals(0, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.FEE_OVERDRAFT).count());
        assertTrue(result.feeTrace().isEmpty());
        assertEquals(6, result.skippedFees().size()); // D1..D6 all negative, one skip each
        assertEquals("NO_FEE_CONFIGURED_FOR_CURRENCY BHD", result.skippedFees().getFirst().reason());
        assertEquals(Scenario.day(1), result.skippedFees().getFirst().valueDate());
        assertEquals(Money.of("-1.000", CurrencyCode.BHD),
                result.skippedFees().getFirst().preFeeBalance());
        // Exactly one skip per account-date: the idempotency key also covers skips.
        assertEquals(6, result.skippedFees().stream()
                .map(s -> s.valueDate()).distinct().count());
    }

    @Test
    void approvedButNeverSettledHoldPersistsThroughWindowEnd() {
        // The mechanism behind "Auth-B retains its hold through Day 6 IF
        // approved": an APPROVED, never-settled hold is still active at the
        // window end, reducing available balance but never the ledger balance.
        // (In the real stream Auth-B is DECLINED, so this synthetic stream
        // proves the persistence rule directly.)
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Credit("A", Money.of("500.00", CurrencyCode.AED))),
                env("X2", 2, 2, new LedgerEvent.AuthorizationRequest("A", "Auth-X",
                        Money.of("90.00", CurrencyCode.AED)))));
        Authorization auth = result.authorizations().get("Auth-X");
        assertEquals(AuthorizationStatus.APPROVED, auth.status());
        assertEquals(null, auth.settledOnDate());
        // Still active after the full window (Day 6 evening has passed).
        assertEquals(Money.of("90.00", CurrencyCode.AED),
                LedgerEngine.activeHolds(result.authorizations(), AED_ACCOUNT));
        // Ledger balance is hold-free: 500.00 on D1-D5 (plus interest on D6).
        assertEquals(Money.of("500.00", CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED_ACCOUNT, Scenario.day(5)));
        // No ledger entry was ever derived from the authorization.
        assertEquals(0, result.entries().stream()
                .filter(e -> e.sourceEventId().equals("X2")).count());
        // Interest was computed on the ledger balance (holds don't reduce it):
        // 6 days x round(500.00 x 0.0004) = 6 x 0.20 = 1.20 capitalized.
        assertEquals(Money.of("501.20", CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED_ACCOUNT, Scenario.day(6)));
    }

    @Test
    void excessSettlementIsRejectedAndHoldSurvives() {
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Credit("A", Money.of("500.00", CurrencyCode.AED))),
                env("X2", 2, 2, new LedgerEvent.AuthorizationRequest("A", "Auth-X",
                        Money.of("100.00", CurrencyCode.AED))),
                env("X3", 3, 3, new LedgerEvent.Settlement("A", "Auth-X",
                        Money.of("100.01", CurrencyCode.AED)))));
        Outcome outcome = result.outcomeOf("X3");
        assertInstanceOf(Outcome.Rejected.class, outcome);
        assertTrue(((Outcome.Rejected) outcome).reason().contains("SETTLEMENT_EXCEEDS_AUTHORIZED_AMOUNT"));
        // The hold must NOT be consumed by an invalid settlement.
        assertEquals(AuthorizationStatus.APPROVED, result.authorizations().get("Auth-X").status());
        assertEquals(Money.of("100.00", CurrencyCode.AED),
                LedgerEngine.activeHolds(result.authorizations(), AED_ACCOUNT));
    }

    @Test
    void secondSettlementOfSameAuthorizationIsRejected() {
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Credit("A", Money.of("500.00", CurrencyCode.AED))),
                env("X2", 2, 2, new LedgerEvent.AuthorizationRequest("A", "Auth-X",
                        Money.of("100.00", CurrencyCode.AED))),
                env("X3", 3, 3, new LedgerEvent.Settlement("A", "Auth-X",
                        Money.of("40.00", CurrencyCode.AED))),
                env("X4", 4, 4, new LedgerEvent.Settlement("A", "Auth-X",
                        Money.of("40.00", CurrencyCode.AED)))));
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("X3"));
        Outcome second = result.outcomeOf("X4");
        assertInstanceOf(Outcome.Rejected.class, second);
        assertTrue(((Outcome.Rejected) second).reason().contains("AUTHORIZATION_NOT_SETTLEABLE"));
        // Exactly one settlement posting exists: double consumption prevented.
        assertEquals(1, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.POSTING && e.amount().isNegative()).count());
        assertEquals(AuthorizationStatus.PARTIALLY_SETTLED,
                result.authorizations().get("Auth-X").status());
    }

    @Test
    void duplicateReversalIsRejected() {
        // Credit first so the account never goes negative (keeps fees out of
        // this test's way; fee persistence after reversal is covered elsewhere).
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X0", 1, 1, new LedgerEvent.Credit("A", Money.of("100.00", CurrencyCode.AED))),
                env("X1", 1, 1, new LedgerEvent.Debit("A", Money.of("10.00", CurrencyCode.AED))),
                env("X2", 2, 1, new LedgerEvent.Reversal("A", "X1")),
                env("X3", 3, 1, new LedgerEvent.Reversal("A", "X1"))));
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("X2"));
        Outcome duplicate = result.outcomeOf("X3");
        assertInstanceOf(Outcome.Rejected.class, duplicate);
        assertTrue(((Outcome.Rejected) duplicate).reason().contains("DUPLICATE_REVERSAL"));
        // 100 - 10 + 10 = 100.00; the duplicate reversal added nothing.
        assertEquals(Money.of("100.00", CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED_ACCOUNT, Scenario.day(5)));
        assertEquals(1, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.REVERSAL_COMPENSATION).count());
        assertTrue(result.feeTrace().isEmpty());
    }

    @Test
    void reversalOfUnknownTargetIsRejected() {
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Reversal("A", "NOPE"))));
        Outcome outcome = result.outcomeOf("X1");
        assertInstanceOf(Outcome.Rejected.class, outcome);
        assertTrue(((Outcome.Rejected) outcome).reason().contains("REVERSAL_TARGET_UNKNOWN"));
    }

    @Test
    void negativeAndZeroAmountsAreRejected() {
        // A negative Credit would be a backdoor debit; a zero Debit is noise.
        // Direction must come from the event type, never from a smuggled sign.
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Credit("A", Money.of("-50.00", CurrencyCode.AED))),
                env("X2", 1, 1, new LedgerEvent.Debit("A", Money.of("0.00", CurrencyCode.AED))),
                env("X3", 1, 1, new LedgerEvent.Debit("A", Money.of("-5.00", CurrencyCode.AED)))));
        for (String id : List.of("X1", "X2", "X3")) {
            Outcome outcome = result.outcomeOf(id);
            assertInstanceOf(Outcome.Rejected.class, outcome, id);
            assertTrue(((Outcome.Rejected) outcome).reason().contains("NON_POSITIVE_AMOUNT"), id);
        }
        assertTrue(result.entries().isEmpty());
        assertEquals(Money.zero(CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED_ACCOUNT, Scenario.day(6)));
    }

    @Test
    void zeroTotalInstalmentCreditIsRejected() {
        ReplayResult result = run(List.of(BHD_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.InstalmentCredit("B",
                        Money.of("0.000", CurrencyCode.BHD), 3))));
        Outcome outcome = result.outcomeOf("X1");
        assertInstanceOf(Outcome.Rejected.class, outcome);
        assertTrue(((Outcome.Rejected) outcome).reason().contains("NON_POSITIVE_INSTALMENT_TOTAL"));
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void unknownAccountIsRejected() {
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Credit("GHOST", Money.of("1.00", CurrencyCode.AED)))));
        assertInstanceOf(Outcome.Rejected.class, result.outcomeOf("X1"));
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void lateEventIsReflectedInFeeAssessmentOnlyAfterItIsProcessed() {
        // Debit valued D1 processed D3: D1-D2 are fee-free when assessed on
        // their evenings; the D1 fee appears only on the evening of D3, and
        // the carried-forward negative balance then triggers D2 and D3 fees in
        // the same assessment run.
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Credit("A", Money.of("100.00", CurrencyCode.AED))),
                env("X2", 3, 1, new LedgerEvent.Debit("A", Money.of("300.00", CurrencyCode.AED)))));
        // All six dates end negative, so six fees in total; D1-D3 are assessed
        // together on the evening of D3, D4-D6 on their own evenings.
        assertEquals(6, result.feeTrace().size());
        assertEquals(Scenario.day(3), result.feeTrace().getFirst().assessedOnDate());
        assertEquals(Scenario.day(1), result.feeTrace().getFirst().valueDate());
        assertEquals(Money.of("-200.00", CurrencyCode.AED), result.feeTrace().getFirst().preFeeBalance());
        // D2 pre-fee: -200 - 25 = -225.00; D3: -250.00.
        assertEquals(Money.of("-225.00", CurrencyCode.AED), result.feeTrace().get(1).preFeeBalance());
        assertEquals(Money.of("-250.00", CurrencyCode.AED), result.feeTrace().get(2).preFeeBalance());
        // D4-D6 are assessed on their own processing dates.
        assertEquals(Scenario.day(4), result.feeTrace().get(3).assessedOnDate());
        assertEquals(Scenario.day(6), result.feeTrace().get(5).assessedOnDate());
    }

    @Test
    void outOfOrderProcessingDateIsAppliedInStreamOrder() {
        // Mirrors the E9-before-E10 situation: an event whose processing date
        // is EARLIER than one already seen must still be applied in stream
        // order, and its effects are picked up by the next evening assessment.
        // X2 (processing D5, value D5) arrives AFTER X3 (processing D6).
        ReplayResult result = run(List.of(AED_ACCOUNT, BHD_ACCOUNT), List.of(
                env("X1", 5, 2, new LedgerEvent.Debit("A", Money.of("10.00", CurrencyCode.AED))),
                env("X3", 6, 6, new LedgerEvent.Credit("A", Money.of("1.00", CurrencyCode.AED))),
                env("X2", 5, 5, new LedgerEvent.Credit("B", Money.of("2.000", CurrencyCode.BHD)))));
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("X2"));
        assertEquals(List.of("X1", "X3", "X2"),
                result.log().events().stream().map(EventEnvelope::eventId).toList());
        // A's D2-valued debit makes D2 negative; the fee lands with value date
        // D2 and is assessed on the evening of D5 (when X1 was processed).
        // The negativity then carries forward: D3..D6 each earn one fee too
        // (D2 -10 -> fee; D3 -35 -> fee; D4 -60; D5 -85; D6 -109+1... all < 0),
        // so 5 fees in total, the first being the back-valued one.
        assertEquals(5, result.feeTrace().size());
        assertEquals(Scenario.day(2), result.feeTrace().getFirst().valueDate());
        assertEquals(Scenario.day(5), result.feeTrace().getFirst().assessedOnDate());
        // B's back-ordered credit posts fine: closing on D5 includes it.
        assertEquals(Money.of("2.000", CurrencyCode.BHD),
                LedgerEngine.closingBalance(result.entries(), BHD_ACCOUNT, Scenario.day(5)));
    }

    @Test
    void backwardProcessingDateEventIsCaughtAtWindowEndWithoutDuplicates() {
        // X1 processed D6 (the maximum date), then X2 processed D2 (moves
        // backwards) valued D1 — a debit that makes D1 negative. The evenings
        // for D1..D5 ran BEFORE X2 existed, so only the window-end evening can
        // catch the D1 overdraft: no date must be skipped and none assessed
        // twice.
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 6, 6, new LedgerEvent.Credit("A", Money.of("5.00", CurrencyCode.AED))),
                env("X2", 2, 1, new LedgerEvent.Debit("A", Money.of("50.00", CurrencyCode.AED)))));
        assertEquals(Scenario.day(1), result.feeTrace().getFirst().valueDate());
        assertEquals(Scenario.day(6), result.feeTrace().getFirst().assessedOnDate());
        assertEquals(Money.of("-50.00", CurrencyCode.AED),
                result.feeTrace().getFirst().preFeeBalance());
        // The negativity carries forward (fees participate in later bases):
        // D1 -50 -> fee; D2 -75 -> fee; D3 -100; D4 -125; D5 -150; D6 -170+5
        // = -165 -> fee. All six dates are negative, so exactly 6 fees, one
        // per account-date, all assessed on the D6 evening.
        assertEquals(6, result.feeTrace().size());
        assertEquals(6, result.feeTrace().stream().map(f -> f.valueDate()).distinct().count());
        assertTrue(result.feeTrace().stream()
                .allMatch(f -> f.assessedOnDate().equals(Scenario.day(6))));
        // And a second replay derives byte-identical entries (idempotency).
        ReplayResult again = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 6, 6, new LedgerEvent.Credit("A", Money.of("5.00", CurrencyCode.AED))),
                env("X2", 2, 1, new LedgerEvent.Debit("A", Money.of("50.00", CurrencyCode.AED)))));
        assertEquals(result.entries(), again.entries());
        assertEquals(result.feeTrace(), again.feeTrace());
    }

    @Test
    void eventDatesOutsideTheWindowAreRejected() {
        // A posting valued or processed outside the configured window would be
        // invisible to fee assessment and interest — a silent inconsistency.
        // The engine rejects loudly instead (documented in AMBIGUITIES.md).
        LocalDate before = Scenario.WINDOW_START.minusDays(1);
        LocalDate after = Scenario.WINDOW_END.plusDays(1);
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                new EventEnvelope("X1", 1, before, Scenario.day(1),
                        new LedgerEvent.Credit("A", Money.of("1.00", CurrencyCode.AED))),
                new EventEnvelope("X2", 2, Scenario.day(1), after,
                        new LedgerEvent.Credit("A", Money.of("1.00", CurrencyCode.AED))),
                new EventEnvelope("X3", 3, after, Scenario.day(1),
                        new LedgerEvent.Credit("A", Money.of("1.00", CurrencyCode.AED)))));
        for (String id : List.of("X1", "X2", "X3")) {
            Outcome outcome = result.outcomeOf(id);
            assertInstanceOf(Outcome.Rejected.class, outcome, id);
            assertTrue(((Outcome.Rejected) outcome).reason().contains("EVENT_DATE_OUT_OF_HORIZON"), id);
        }
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void duplicateEventIdIsRejectedAndKeepsBothOutcomes() {
        // Idempotent ingestion: the second event with the same id is rejected;
        // the first occurrence's posting stands; no double-posting.
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Credit("A", Money.of("100.00", CurrencyCode.AED))),
                env("X1", 2, 2, new LedgerEvent.Credit("A", Money.of("100.00", CurrencyCode.AED)))));
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("X1"));
        // The duplicate's own outcome is retrievable by stream position.
        Outcome dup = result.outcomeAt(2);
        assertInstanceOf(Outcome.Rejected.class, dup);
        assertTrue(((Outcome.Rejected) dup).reason().contains("DUPLICATE_EVENT_ID"));
        // Only ONE 100.00 posting exists.
        assertEquals(1, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.POSTING).count());
        assertEquals(Money.of("100.00", CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED_ACCOUNT, Scenario.day(5)));
    }

    @Test
    void duplicateEventIdCannotAffectEntriesAuthFeesOrInterest() {
        // The duplicate carries a DIFFERENT event type (an authorization
        // request) under an already-used id: it must be rejected as a duplicate
        // and must not create an authorization, post entries, or change
        // fee/interest derivation. The first occurrence stands.
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Credit("A", Money.of("100.00", CurrencyCode.AED))),
                env("X1", 2, 2, new LedgerEvent.AuthorizationRequest("A", "Auth-X",
                        Money.of("50.00", CurrencyCode.AED)))));
        Outcome dup = result.outcomeAt(2);
        assertInstanceOf(Outcome.Rejected.class, dup);
        assertTrue(((Outcome.Rejected) dup).reason().contains("DUPLICATE_EVENT_ID"));
        // No authorization created by the duplicate.
        assertTrue(result.authorizations().isEmpty());
        // Exactly one posting (the first credit); interest is the only other
        // entry and reflects the single 100.00 credit: 6 x round(100 x 0.0004)
        // = 6 x 0.04 = 0.24, capitalized once.
        assertEquals(2, result.entries().size());
        assertTrue(result.feeTrace().isEmpty());
        assertEquals(Money.of("100.24", CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED_ACCOUNT, Scenario.day(6)));
    }

    @Test
    void validationOrderIsDeterministic() {
        // Documented order (AMBIGUITIES.md #27): duplicate id -> horizon ->
        // account -> currency -> business rules. An event violating two rules
        // gets the FIRST applicable reason.
        LocalDate outside = Scenario.WINDOW_END.plusDays(1);
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Credit("A", Money.of("100.00", CurrencyCode.AED))),
                // duplicate id AND wrong currency: duplicate wins
                env("X1", 2, 2, new LedgerEvent.Credit("A", Money.of("5.000", CurrencyCode.BHD))),
                // out-of-horizon AND unknown account: horizon wins
                new EventEnvelope("X3", position.incrementAndGet(), outside, outside,
                        new LedgerEvent.Credit("GHOST", Money.of("1.00", CurrencyCode.AED)))));
        assertTrue(((Outcome.Rejected) result.outcomeAt(2)).reason().contains("DUPLICATE_EVENT_ID"));
        assertTrue(((Outcome.Rejected) result.outcomeAt(3)).reason()
                .contains("EVENT_DATE_OUT_OF_HORIZON"));
    }

    @Test
    void settlementReversalCompensatesPostingButAuthorizationStaysTerminal() {
        // Reversing a SETTLEMENT posting is a financial compensation only:
        // under the one-capture policy (#6) a settled authorization is
        // terminal, so the released hold is NOT resurrected and no further
        // settlement is possible (documented in AMBIGUITIES.md #21).
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Credit("A", Money.of("500.00", CurrencyCode.AED))),
                env("X2", 2, 2, new LedgerEvent.AuthorizationRequest("A", "Auth-X",
                        Money.of("100.00", CurrencyCode.AED))),
                env("X3", 3, 3, new LedgerEvent.Settlement("A", "Auth-X",
                        Money.of("40.00", CurrencyCode.AED))),
                env("X4", 4, 4, new LedgerEvent.Reversal("A", "X3")),
                env("X5", 5, 5, new LedgerEvent.Settlement("A", "Auth-X",
                        Money.of("40.00", CurrencyCode.AED)))));
        // The settlement reversal is accepted and posts +40.00 valued D4.
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("X4"));
        LedgerEntry compensation = result.entries().stream()
                .filter(e -> e.kind() == EntryKind.REVERSAL_COMPENSATION).findFirst().orElseThrow();
        assertEquals(Money.of("40.00", CurrencyCode.AED), compensation.amount());
        assertEquals(Scenario.day(4), compensation.valueDate());
        // Authorization remains terminal; hold is not resurrected.
        Authorization auth = result.authorizations().get("Auth-X");
        assertEquals(AuthorizationStatus.PARTIALLY_SETTLED, auth.status());
        assertEquals(Money.zero(CurrencyCode.AED),
                LedgerEngine.activeHolds(result.authorizations(), AED_ACCOUNT));
        // A new settlement after the reversal is still rejected.
        Outcome x5 = result.outcomeOf("X5");
        assertInstanceOf(Outcome.Rejected.class, x5);
        assertTrue(((Outcome.Rejected) x5).reason().contains("AUTHORIZATION_NOT_SETTLEABLE"));
        // Funds: 500 - 40 + 40 = 500.00 on D4/D5 (before D6 interest).
        assertEquals(Money.of("500.00", CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED_ACCOUNT, Scenario.day(4)));
    }

    @Test
    void rejectedEventStillConsumesItsEventId() {
        // Documented policy (AMBIGUITIES.md #25): an event id is consumed on
        // first SIGHT, even when the event is rejected — redelivering the id
        // with different content is a conflict, not a fresh event.
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                env("X1", 1, 1, new LedgerEvent.Credit("A", Money.of("5.000", CurrencyCode.BHD))),
                env("X1", 2, 2, new LedgerEvent.Credit("A", Money.of("50.00", CurrencyCode.AED)))));
        assertTrue(((Outcome.Rejected) result.outcomeAt(1)).reason().contains("CURRENCY_MISMATCH"));
        Outcome second = result.outcomeAt(2);
        assertInstanceOf(Outcome.Rejected.class, second);
        assertTrue(((Outcome.Rejected) second).reason().contains("DUPLICATE_EVENT_ID"));
        // Neither occurrence posted anything.
        assertTrue(result.entries().isEmpty());
        assertEquals(Money.zero(CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED_ACCOUNT, Scenario.day(6)));
    }

    @Test
    void outOfOrderStreamPositionsAreRejected() {
        // Positions make the stream order explicit; a sequence that
        // contradicts the list order (2 before 1) is a data-contract
        // violation, not a reason to sort. The engine must NOT reorder.
        List<EventEnvelope> stream = List.of(
                new EventEnvelope("X1", 2, Scenario.day(1), Scenario.day(1),
                        new LedgerEvent.Credit("A", Money.of("1.00", CurrencyCode.AED))),
                new EventEnvelope("X2", 1, Scenario.day(1), Scenario.day(1),
                        new LedgerEvent.Credit("A", Money.of("1.00", CurrencyCode.AED))));
        LedgerEngine engine = new LedgerEngine(List.of(AED_ACCOUNT),
                Scenario.WINDOW_START, Scenario.WINDOW_END, Scenario.BUSINESS_ZONE,
                Scenario.OVERDRAFT_FEE_SCHEDULE);
        assertThrows(IllegalArgumentException.class, () -> engine.replay(stream));
    }

    @Test
    void emptyStreamProducesNoEntriesNoFeesNoInterestEntries() {
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of());
        assertTrue(result.entries().isEmpty());
        assertTrue(result.feeTrace().isEmpty());
        assertTrue(result.feeCompensations().isEmpty());
        assertTrue(result.skippedFees().isEmpty());
        assertTrue(result.log().events().isEmpty());
        assertTrue(result.outcomes().isEmpty());
        // The interest trace still records one (zero) accrual row per date —
        // deterministic shape — but no capitalization entry is derived.
        assertEquals(6, result.interestTrace().size());
        assertTrue(result.interestTrace().stream().allMatch(a -> a.accrual().isZero()));
    }

    @Test
    void streamWithOnlyOutOfWindowEventsDerivesNothing() {
        LocalDate outside = Scenario.WINDOW_END.plusDays(1);
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                new EventEnvelope("X0", 1, outside, outside,
                        new LedgerEvent.Credit("A", Money.of("100.00", CurrencyCode.AED)))));
        assertInstanceOf(Outcome.Rejected.class, result.outcomeAt(1));
        assertEquals(1, result.log().size());
        assertTrue(result.entries().isEmpty());
        assertTrue(result.feeTrace().isEmpty());
    }

    @Test
    void defaultFeeScheduleContainsExactlyTheSpecifiedAedFee() {
        assertEquals(1, Scenario.OVERDRAFT_FEE_SCHEDULE.size());
        assertEquals(Money.of("25.00", CurrencyCode.AED),
                Scenario.OVERDRAFT_FEE_SCHEDULE.get(CurrencyCode.AED));
    }

    @Test
    void duplicateStreamPositionIsADataContractViolation() {
        List<EventEnvelope> stream = List.of(
                new EventEnvelope("X1", 7, Scenario.day(1), Scenario.day(1),
                        new LedgerEvent.Credit("A", Money.of("1.00", CurrencyCode.AED))),
                new EventEnvelope("X2", 7, Scenario.day(1), Scenario.day(1),
                        new LedgerEvent.Credit("A", Money.of("1.00", CurrencyCode.AED))));
        LedgerEngine engine = new LedgerEngine(List.of(AED_ACCOUNT),
                Scenario.WINDOW_START, Scenario.WINDOW_END, Scenario.BUSINESS_ZONE,
                Scenario.OVERDRAFT_FEE_SCHEDULE);
        assertThrows(IllegalArgumentException.class, () -> engine.replay(stream));
    }
}
