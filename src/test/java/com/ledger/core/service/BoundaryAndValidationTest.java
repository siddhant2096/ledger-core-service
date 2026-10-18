package com.ledger.core.service;

import com.ledger.core.service.application.LedgerEngine;
import com.ledger.core.service.application.ReplayResult;
import com.ledger.core.service.application.ReplayResult.Outcome;
import com.ledger.core.service.domain.account.Account;
import com.ledger.core.service.domain.auth.AuthorizationStatus;
import com.ledger.core.service.domain.entry.EntryKind;
import com.ledger.core.service.domain.event.EventEnvelope;
import com.ledger.core.service.domain.event.LedgerEvent;
import com.ledger.core.service.domain.money.CurrencyCode;
import com.ledger.core.service.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary cases and validation policies, each on a minimal hand-built stream
 * so the expected values follow directly from the inputs.
 */
class BoundaryAndValidationTest {

    private static final Account AED_ACCOUNT = Account.zeroBalance("A", CurrencyCode.AED);
    private static final Account BHD_ACCOUNT = Account.zeroBalance("B", CurrencyCode.BHD);

    private ReplayResult run(List<Account> accounts, List<EventEnvelope> events) {
        return new LedgerEngine(accounts, 6).replay(events);
    }

    @Test
    void zeroBalanceEarnsNoInterestAndPaysNoFee() {
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of());
        for (int day = 1; day <= 6; day++) {
            assertEquals(Money.zero(CurrencyCode.AED),
                    LedgerEngine.closingBalance(result.entries(), AED_ACCOUNT, day));
        }
        assertTrue(result.feeTrace().isEmpty());
        assertTrue(result.entries().isEmpty()); // zero total interest -> no capitalization entry
    }

    @Test
    void negativeBalanceEarnsNoInterest() {
        // -10.00 from day 1; fee assessed day 1 makes it -35.00; both negative.
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                new EventEnvelope("X1", 1, 1,
                        new LedgerEvent.Debit("A", Money.of("10.00", CurrencyCode.AED)))));
        // Every day closes negative, so exactly one fee per day, six in total:
        // day 1: -10.00 -> fee; day 2: -35.00 -> fee; ... (fees carry forward).
        assertEquals(6, result.feeTrace().size());
        assertEquals(Money.of("10.00", CurrencyCode.AED).negate(),
                result.feeTrace().getFirst().preFeeBalance());
        // No duplicate fee for any account-day even though every day is
        // re-examined on subsequent evenings.
        assertEquals(6, result.feeTrace().stream().map(f -> f.valueDay()).distinct().count());
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
                new EventEnvelope("X1", 1, 1,
                        new LedgerEvent.Credit("A", Money.of("100.00", CurrencyCode.AED))),
                new EventEnvelope("X2", 2, 2,
                        new LedgerEvent.AuthorizationRequest("A", "Auth-X",
                                Money.of("100.00", CurrencyCode.AED)))));
        assertEquals(AuthorizationStatus.APPROVED, result.authorizations().get("Auth-X").status());
    }

    @Test
    void oneFilsBelowTheHoldIsDeclined() {
        // Ledger 99.99, hold 100.00 -> available after hold = -0.01 < 0.
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                new EventEnvelope("X1", 1, 1,
                        new LedgerEvent.Credit("A", Money.of("99.99", CurrencyCode.AED))),
                new EventEnvelope("X2", 2, 2,
                        new LedgerEvent.AuthorizationRequest("A", "Auth-X",
                                Money.of("100.00", CurrencyCode.AED)))));
        assertEquals(AuthorizationStatus.DECLINED, result.authorizations().get("Auth-X").status());
    }

    @Test
    void crossCurrencyEventIsRejectedNotPosted() {
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                new EventEnvelope("X1", 1, 1,
                        new LedgerEvent.Credit("A", Money.of("5.000", CurrencyCode.BHD)))));
        Outcome outcome = result.outcomeOf("X1");
        assertInstanceOf(Outcome.Rejected.class, outcome);
        assertTrue(((Outcome.Rejected) outcome).reason().contains("CURRENCY_MISMATCH"));
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void bhdAccountIsChargedBhdFeeNeverAed() {
        // Policy: the overdraft fee is denominated in the account's own
        // currency (no FX table exists). A negative BHD account pays BHD 25.000.
        ReplayResult result = run(List.of(BHD_ACCOUNT), List.of(
                new EventEnvelope("X1", 1, 1,
                        new LedgerEvent.Debit("B", Money.of("1.000", CurrencyCode.BHD)))));
        assertEquals(Money.of("25.000", CurrencyCode.BHD), result.feeTrace().getFirst().feeAmount());
        assertEquals(CurrencyCode.BHD, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.FEE_OVERDRAFT)
                .findFirst().orElseThrow().amount().currency());
    }

    @Test
    void excessSettlementIsRejectedAndHoldSurvives() {
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                new EventEnvelope("X1", 1, 1,
                        new LedgerEvent.Credit("A", Money.of("500.00", CurrencyCode.AED))),
                new EventEnvelope("X2", 2, 2,
                        new LedgerEvent.AuthorizationRequest("A", "Auth-X",
                                Money.of("100.00", CurrencyCode.AED))),
                new EventEnvelope("X3", 3, 3,
                        new LedgerEvent.Settlement("A", "Auth-X",
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
                new EventEnvelope("X1", 1, 1,
                        new LedgerEvent.Credit("A", Money.of("500.00", CurrencyCode.AED))),
                new EventEnvelope("X2", 2, 2,
                        new LedgerEvent.AuthorizationRequest("A", "Auth-X",
                                Money.of("100.00", CurrencyCode.AED))),
                new EventEnvelope("X3", 3, 3,
                        new LedgerEvent.Settlement("A", "Auth-X",
                                Money.of("40.00", CurrencyCode.AED))),
                new EventEnvelope("X4", 4, 4,
                        new LedgerEvent.Settlement("A", "Auth-X",
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
                new EventEnvelope("X0", 1, 1,
                        new LedgerEvent.Credit("A", Money.of("100.00", CurrencyCode.AED))),
                new EventEnvelope("X1", 1, 1,
                        new LedgerEvent.Debit("A", Money.of("10.00", CurrencyCode.AED))),
                new EventEnvelope("X2", 2, 1,
                        new LedgerEvent.Reversal("A", "X1")),
                new EventEnvelope("X3", 3, 1,
                        new LedgerEvent.Reversal("A", "X1"))));
        assertInstanceOf(Outcome.Accepted.class, result.outcomeOf("X2"));
        Outcome duplicate = result.outcomeOf("X3");
        assertInstanceOf(Outcome.Rejected.class, duplicate);
        assertTrue(((Outcome.Rejected) duplicate).reason().contains("DUPLICATE_REVERSAL"));
        // 100 - 10 + 10 = 100.00; the duplicate reversal added nothing.
        assertEquals(Money.of("100.00", CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED_ACCOUNT, 5));
        assertEquals(1, result.entries().stream()
                .filter(e -> e.kind() == EntryKind.REVERSAL_COMPENSATION).count());
        assertTrue(result.feeTrace().isEmpty());
    }

    @Test
    void reversalOfUnknownTargetIsRejected() {
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                new EventEnvelope("X1", 1, 1,
                        new LedgerEvent.Reversal("A", "NOPE"))));
        Outcome outcome = result.outcomeOf("X1");
        assertInstanceOf(Outcome.Rejected.class, outcome);
        assertTrue(((Outcome.Rejected) outcome).reason().contains("REVERSAL_TARGET_UNKNOWN"));
    }

    @Test
    void negativeAndZeroAmountsAreRejected() {
        // A negative Credit would be a backdoor debit; a zero Debit is noise.
        // Direction must come from the event type, never from a smuggled sign.
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                new EventEnvelope("X1", 1, 1,
                        new LedgerEvent.Credit("A", Money.of("-50.00", CurrencyCode.AED))),
                new EventEnvelope("X2", 1, 1,
                        new LedgerEvent.Debit("A", Money.of("0.00", CurrencyCode.AED))),
                new EventEnvelope("X3", 1, 1,
                        new LedgerEvent.Debit("A", Money.of("-5.00", CurrencyCode.AED)))));
        for (String id : List.of("X1", "X2", "X3")) {
            Outcome outcome = result.outcomeOf(id);
            assertInstanceOf(Outcome.Rejected.class, outcome, id);
            assertTrue(((Outcome.Rejected) outcome).reason().contains("NON_POSITIVE_AMOUNT"), id);
        }
        assertTrue(result.entries().isEmpty());
        assertEquals(Money.zero(CurrencyCode.AED),
                LedgerEngine.closingBalance(result.entries(), AED_ACCOUNT, 6));
    }

    @Test
    void zeroTotalInstalmentCreditIsRejected() {
        ReplayResult result = run(List.of(BHD_ACCOUNT), List.of(
                new EventEnvelope("X1", 1, 1,
                        new LedgerEvent.InstalmentCredit("B", Money.of("0.000", CurrencyCode.BHD), 3))));
        Outcome outcome = result.outcomeOf("X1");
        assertInstanceOf(Outcome.Rejected.class, outcome);
        assertTrue(((Outcome.Rejected) outcome).reason().contains("NON_POSITIVE_INSTALMENT_TOTAL"));
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void unknownAccountIsRejected() {
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                new EventEnvelope("X1", 1, 1,
                        new LedgerEvent.Credit("GHOST", Money.of("1.00", CurrencyCode.AED)))));
        assertInstanceOf(Outcome.Rejected.class, result.outcomeOf("X1"));
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void lateEventIsReflectedInFeeAssessmentOnlyAfterItIsProcessed() {
        // Debit valued day 1 processed day 3: days 1-2 are fee-free when
        // assessed on days 1-2; the day-1 fee appears only at end of day 3,
        // and the carried-forward negative balance then triggers day-2 and
        // day-3 fees in the same assessment run.
        ReplayResult result = run(List.of(AED_ACCOUNT), List.of(
                new EventEnvelope("X1", 1, 1,
                        new LedgerEvent.Credit("A", Money.of("100.00", CurrencyCode.AED))),
                new EventEnvelope("X2", 3, 1,
                        new LedgerEvent.Debit("A", Money.of("300.00", CurrencyCode.AED)))));
        // All six days end negative, so six fees in total; days 1-3 are
        // assessed together at end of day 3, days 4-6 on their own evenings.
        assertEquals(6, result.feeTrace().size());
        assertEquals(3, result.feeTrace().getFirst().assessedOnDay());
        assertEquals(1, result.feeTrace().getFirst().valueDay());
        assertEquals(Money.of("-200.00", CurrencyCode.AED), result.feeTrace().getFirst().preFeeBalance());
        // Day 2 pre-fee: -200 - 25 = -225.00; day 3: -250.00.
        assertEquals(Money.of("-225.00", CurrencyCode.AED), result.feeTrace().get(1).preFeeBalance());
        assertEquals(Money.of("-250.00", CurrencyCode.AED), result.feeTrace().get(2).preFeeBalance());
        // Days 4-6 are assessed on their own processing days.
        assertEquals(4, result.feeTrace().get(3).assessedOnDay());
        assertEquals(6, result.feeTrace().get(5).assessedOnDay());
    }
}
