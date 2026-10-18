package com.ledger.core.service;

import com.ledger.core.service.application.LedgerEngine;
import com.ledger.core.service.application.ReplayResult;
import com.ledger.core.service.application.ReplayResult.DailyAccrual;
import com.ledger.core.service.application.ReplayResult.FeeAssessment;
import com.ledger.core.service.application.ReplayResult.Outcome;
import com.ledger.core.service.application.Scenario;
import com.ledger.core.service.domain.account.Account;
import com.ledger.core.service.domain.auth.Authorization;
import com.ledger.core.service.domain.auth.AuthorizationStatus;
import com.ledger.core.service.domain.entry.EntryKind;
import com.ledger.core.service.domain.entry.LedgerEntry;
import com.ledger.core.service.domain.event.EventEnvelope;
import com.ledger.core.service.domain.money.CurrencyCode;
import com.ledger.core.service.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests over the exact specification stream (E1..E10).
 *
 * <p>Expected numbers are DERIVED, not invented; the derivations (also in
 * NUMBERS.md and REJECTED.md):
 *
 * <p>ACC-001 (AED) effective entries: E1 +1200.00@D1, E2 -950.00@D1,
 * E5 -185.00@D4 (Auth-A settlement), E7 -620.00@D2 (processed D5),
 * E9 +620.00@D2 (reversal compensation, processed D6), fees -25.00 valued on
 * D2/D4/D5 (assessed on the evening of D5), interest +0.93@D6. Specification
 * "Day n" maps to business date 2026-01-0n (see {@link Scenario#day}).
 *
 * <p>Final value-date closings (post-fee, interest only on D6):
 * D1 250.00, D2 225.00, D3 625.00, D4 415.00, D5 390.00, D6 390.93.
 *
 * <p>ACC-002 (BHD): E10 instalments 3.333 + 3.333 + 3.334 = 10.000@D5;
 * accruals 0.004 (D5) + 0.004 (D6) = 0.008 capitalized; final 10.008.
 */
class ReplayTest {

    private final ReplayResult result = Scenario.replay();
    private final Account acc001 = Scenario.accounts().get(0);
    private final Account acc002 = Scenario.accounts().get(1);

    private static LocalDate day(int n) {
        return Scenario.day(n);
    }

    private Money closing(Account account, int day) {
        return LedgerEngine.closingBalance(result.entries(), account, day(day));
    }

    private Money aed(String amount) {
        return Money.of(amount, CurrencyCode.AED);
    }

    private Money bhd(String amount) {
        return Money.of(amount, CurrencyCode.BHD);
    }

    @Test
    void initialBalancesAreZeroAtCurrencyPrecision() {
        assertEquals(Money.zero(CurrencyCode.AED), acc001.openingBalance());
        assertEquals(Money.zero(CurrencyCode.BHD), acc002.openingBalance());
        assertEquals("0.00", acc001.openingBalance().amount().toPlainString());
        assertEquals("0.000", acc002.openingBalance().amount().toPlainString());
    }

    @Test
    void allTenEventsAreInTheLogInStreamOrder() {
        assertEquals(List.of("E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8", "E9", "E10"),
                result.log().events().stream().map(EventEnvelope::eventId).toList());
        // Stream positions are explicit, unique, and match specification order.
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L),
                result.log().events().stream().map(EventEnvelope::streamPosition).toList());
    }

    @Test
    void onlyE6IsRejected() {
        assertInstanceOf(Outcome.Rejected.class, result.outcomeOf("E6"));
        for (String id : List.of("E1", "E2", "E3", "E4", "E5", "E7", "E8", "E9", "E10")) {
            assertInstanceOf(Outcome.Accepted.class, result.outcomeOf(id), id + " must be accepted");
        }
        // E8 is ACCEPTED as an event even though the authorization is DECLINED;
        // a decline is a decision, not a validation failure.
        Authorization authB = result.authorizations().get("Auth-B");
        assertEquals(AuthorizationStatus.DECLINED, authB.status());
    }

    @Test
    void finalDailyClosingsMatchHandDerivation() {
        assertEquals(aed("250.00"), closing(acc001, 1));   // 1200 - 950
        assertEquals(aed("225.00"), closing(acc001, 2));   // 250 - 620 + 620 - 25 fee
        assertEquals(aed("625.00"), closing(acc001, 3));   // 225 + 400
        assertEquals(aed("415.00"), closing(acc001, 4));   // 625 - 185 - 25 fee
        assertEquals(aed("390.00"), closing(acc001, 5));   // 415 - 25 fee
        assertEquals(aed("390.93"), closing(acc001, 6));   // 390 + 0.93 interest
        assertEquals(bhd("0.000"), closing(acc002, 4));
        assertEquals(bhd("10.000"), closing(acc002, 5));   // 3.333 + 3.333 + 3.334
        assertEquals(bhd("10.008"), closing(acc002, 6));   // + 0.008 interest
    }

    @Test
    void e7RewritesDay2HistoryWithoutTouchingTheStream() {
        // Without E7/E9 the D2 closing would be 250.00. The replayed D2 closing
        // is 225.00 because E7 (-620@D2) and E9 (+620@D2) and the D2 fee
        // (-25@D2) are all part of the complete stream. E7 itself remains,
        // unmutated, with processing date D5 and value date D2.
        LedgerEntry e7Entry = result.entries().stream()
                .filter(e -> e.sourceEventId().equals("E7")).findFirst().orElseThrow();
        assertEquals(aed("-620.00"), e7Entry.amount());
        assertEquals(day(2), e7Entry.valueDate());
        assertEquals(day(5), e7Entry.derivedOnDate());
    }

    @Test
    void e9CompensatesE7ExactlyWithoutDeletingIt() {
        LedgerEntry compensation = result.entries().stream()
                .filter(e -> e.sourceEventId().equals("E9")).findFirst().orElseThrow();
        assertEquals(aed("620.00"), compensation.amount());
        assertEquals(EntryKind.REVERSAL_COMPENSATION, compensation.kind());
        assertEquals(day(2), compensation.valueDate());   // lands on E7's value date
        assertEquals(day(6), compensation.derivedOnDate());
        // Originals intact: E7's debit and E9's compensation both exist.
        assertEquals(1, result.entries().stream().filter(e -> e.sourceEventId().equals("E7")).count());
        assertEquals(1, result.entries().stream().filter(e -> e.sourceEventId().equals("E9")).count());
    }

    @Test
    void authZSettlementIsRejectedAndPostsNothing() {
        Outcome outcome = result.outcomeOf("E6");
        assertInstanceOf(Outcome.Rejected.class, outcome);
        assertTrue(((Outcome.Rejected) outcome).reason().contains("UNKNOWN_AUTHORIZATION"));
        // No ledger entry, no authorization state created.
        assertEquals(0, result.entries().stream().filter(e -> e.sourceEventId().equals("E6")).count());
        assertEquals(null, result.authorizations().get("Auth-Z"));
        // Funds did not leave the account: D4 closing reflects only the
        // Auth-A settlement (625 - 185 - 25 fee = 415.00).
        assertEquals(aed("415.00"), closing(acc001, 4));
    }

    @Test
    void authASettlesPartiallyAndReleasesTheHold() {
        Authorization authA = result.authorizations().get("Auth-A");
        assertEquals(AuthorizationStatus.PARTIALLY_SETTLED, authA.status());
        assertEquals(aed("185.00"), authA.settledAmount());
        assertEquals(aed("200.00"), authA.holdAmount());
        assertEquals(day(4), authA.settledOnDate());
        // Settlement posted -185.00 with value date D4.
        LedgerEntry settlement = result.entries().stream()
                .filter(e -> e.sourceEventId().equals("E5")).findFirst().orElseThrow();
        assertEquals(aed("-185.00"), settlement.amount());
        assertEquals(day(4), settlement.valueDate());
        // Hold fully released at settlement: no active holds remain.
        assertEquals(aed("0.00"), LedgerEngine.activeHolds(result.authorizations(), acc001));
    }

    @Test
    void authBIsDeclinedBecauseTheAccountWasOverdrawnAtProcessingTime() {
        // When E8 is processed (D5, after E7), the known ledger balance is
        // 1200 - 950 + 400 - 185 - 620 = -155.00; -155.00 - 90.00 < 0, so the
        // hold must be declined. It creates no active hold and no ledger entry.
        Authorization authB = result.authorizations().get("Auth-B");
        assertEquals(AuthorizationStatus.DECLINED, authB.status());
        assertTrue(((Outcome.Accepted) result.outcomeOf("E8")).note().contains("DECLINED"));
        assertEquals(0, result.entries().stream().filter(e -> e.sourceEventId().equals("E8")).count());
    }

    @Test
    void exactlyOneFeePerNegativeAccountDayWithAssessedDayValueDate() {
        List<FeeAssessment> fees = result.feeTrace();
        assertEquals(3, fees.size());
        // All three fees were derived on the evening of D5, when E7 made
        // D2, D4 and D5 negative. D3 stayed positive: -370 -25 +400 = 5.00.
        assertEquals(day(2), fees.get(0).valueDate());
        assertEquals(aed("-370.00"), fees.get(0).preFeeBalance());
        assertEquals(day(4), fees.get(1).valueDate());
        assertEquals(aed("-180.00"), fees.get(1).preFeeBalance()); // 5 - 185
        assertEquals(day(5), fees.get(2).valueDate());
        assertEquals(aed("-205.00"), fees.get(2).preFeeBalance()); // -180 - 25
        for (FeeAssessment fee : fees) {
            assertEquals(day(5), fee.assessedOnDate());
            assertEquals(aed("25.00"), fee.feeAmount());
        }
        // Fee entries carry value date == assessed date (the date that was negative).
        List<LedgerEntry> feeEntries = result.entries().stream()
                .filter(e -> e.kind() == EntryKind.FEE_OVERDRAFT).toList();
        assertEquals(3, feeEntries.size());
        assertEquals(List.of(day(2), day(4), day(5)),
                feeEntries.stream().map(LedgerEntry::valueDate).toList());
        assertEquals(List.of(day(5), day(5), day(5)),
                feeEntries.stream().map(LedgerEntry::derivedOnDate).toList());
    }

    @Test
    void e9DoesNotRemoveAssessedFees() {
        // Chosen policy: fee entries are immutable once derived. E9 restores
        // the pre-fee D2 balance to +250.00, yet the D2 fee stays, so the
        // final D2 closing is 225.00, NOT the pre-E7 250.00.
        assertEquals(aed("225.00"), closing(acc001, 2));
        assertEquals(3, result.feeTrace().size());
    }

    @Test
    void interestAccruesOnPositivePostFeeBalancesOnly() {
        List<DailyAccrual> accruals = result.interestTrace().stream()
                .filter(a -> a.accountId().equals("ACC-001")).toList();
        // Bases are the post-fee closings: 250, 225, 625, 415, 390, 390.
        // x 0.0004, HALF_UP to 2dp: 0.10, 0.09, 0.25, 0.17 (0.1660), 0.16 (0.1560), 0.16.
        assertEquals(aed("0.10"), accruals.get(0).accrual());
        assertEquals(aed("0.09"), accruals.get(1).accrual());
        assertEquals(aed("0.25"), accruals.get(2).accrual());
        assertEquals(aed("0.17"), accruals.get(3).accrual());
        assertEquals(aed("0.16"), accruals.get(4).accrual());
        assertEquals(aed("0.16"), accruals.get(5).accrual());
        assertEquals(day(1), accruals.get(0).date());
        assertEquals(day(6), accruals.get(5).date());
        // ACC-002: zero balance accrues nothing on D1-D4.
        List<DailyAccrual> bhdAccruals = result.interestTrace().stream()
                .filter(a -> a.accountId().equals("ACC-002")).toList();
        for (int i = 0; i < 4; i++) {
            assertEquals(bhd("0.000"), bhdAccruals.get(i).accrual());
        }
        assertEquals(bhd("0.004"), bhdAccruals.get(4).accrual()); // 10.000 x 0.0004
        assertEquals(bhd("0.004"), bhdAccruals.get(5).accrual());
    }

    @Test
    void capitalizedInterestEqualsSumOfRoundedDailyAccrualsExactly() {
        // ACC-001: 0.10+0.09+0.25+0.17+0.16+0.16 = 0.93.
        // Note: rounding the aggregate (2295.00 x 0.0004 = 0.918 -> 0.92) would
        // give a DIFFERENT answer; the policy is sum-of-rounded-dailies, so no
        // remainder ever exists to discard.
        LedgerEntry interest001 = result.entries().stream()
                .filter(e -> e.kind() == EntryKind.INTEREST_CAPITALIZATION
                        && e.accountId().equals("ACC-001")).findFirst().orElseThrow();
        assertEquals(aed("0.93"), interest001.amount());
        assertEquals(day(6), interest001.valueDate());

        LedgerEntry interest002 = result.entries().stream()
                .filter(e -> e.kind() == EntryKind.INTEREST_CAPITALIZATION
                        && e.accountId().equals("ACC-002")).findFirst().orElseThrow();
        assertEquals(bhd("0.008"), interest002.amount());

        for (Account account : Scenario.accounts()) {
            Money sumOfDailies = result.interestTrace().stream()
                    .filter(a -> a.accountId().equals(account.id()))
                    .map(DailyAccrual::accrual)
                    .reduce(Money.zero(account.currency()), Money::add);
            Money capitalized = result.entries().stream()
                    .filter(e -> e.kind() == EntryKind.INTEREST_CAPITALIZATION
                            && e.accountId().equals(account.id()))
                    .map(LedgerEntry::amount)
                    .reduce(Money.zero(account.currency()), Money::add);
            assertEquals(sumOfDailies, capitalized, account.id());
        }
    }

    @Test
    void bhdInstalmentsConserveExactlyTenBhd() {
        List<LedgerEntry> instalments = result.entries().stream()
                .filter(e -> e.sourceEventId().equals("E10")).toList();
        assertEquals(3, instalments.size());
        assertEquals(bhd("3.333"), instalments.get(0).amount());
        assertEquals(bhd("3.333"), instalments.get(1).amount());
        assertEquals(bhd("3.334"), instalments.get(2).amount());
        Money total = instalments.stream().map(LedgerEntry::amount)
                .reduce(Money.zero(CurrencyCode.BHD), Money::add);
        assertEquals(bhd("10.000"), total);
        // All three instalments carry the same value date (D5), the documented
        // assumption for E10's missing value date.
        assertTrue(instalments.stream().allMatch(e -> e.valueDate().equals(day(5))));
    }

    @Test
    void replayIsDeterministic() {
        ReplayResult again = Scenario.replay();
        assertEquals(result.entries(), again.entries());
        assertEquals(result.feeTrace(), again.feeTrace());
        assertEquals(result.interestTrace(), again.interestTrace());
        assertEquals(result.authorizations(), again.authorizations());
        assertEquals(result.outcomes(), again.outcomes());
    }

    @Test
    void eventLogIsAppendOnly() {
        List<EventEnvelope> view = result.log().events();
        EventEnvelope extra = Scenario.events().getFirst();
        assertThrows(UnsupportedOperationException.class, () -> view.add(extra));
        assertThrows(UnsupportedOperationException.class, () -> view.removeFirst());
        assertEquals(10, view.size());
    }
}
