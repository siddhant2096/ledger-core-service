package com.ledger.core.service;

import com.ledger.core.service.application.LedgerEngine;
import com.ledger.core.service.application.ReplayResult;
import com.ledger.core.service.application.Scenario;
import com.ledger.core.service.domain.account.Account;
import com.ledger.core.service.domain.event.EventEnvelope;
import com.ledger.core.service.domain.money.CurrencyCode;
import com.ledger.core.service.domain.money.Money;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * =====================================================================
 *  DELIBERATELY FAILING TEST — INTENTIONAL DEMONSTRATION, NOT A DEFECT
 * =====================================================================
 *
 * WHY THIS TEST FAILS:
 *   It asserts that an overdraft fee, once assessed, keeps its financial
 *   effect forever — i.e. that the Day-2 (2026-01-02) net fee effect as of
 *   the Day-5 evening (−25.00) equals the final one. That invariant is false
 *   under the documented fee-RECONCILIATION policy: E9 (processed Day 6,
 *   valued Day 2) corrects the books, and the Day-6 evening reconciliation
 *   appends an append-only FEE_REVERSAL compensation (+25.00 valued Day 2),
 *   so the final net fee effect on Day 2 is 0.00.
 *
 * WHICH ASSUMPTION / INVARIANT IT EXPOSES:
 *   The naive assumption "a derived fee entry, once posted, always keeps its
 *   financial effect". The ledger's real invariant is subtler: entries are
 *   immutable, but their NET effect can be neutralized by later compensation
 *   entries. A projection cache keyed by value date that never accounts for
 *   late compensation entries would silently keep the −25.00 fee effect.
 *
 * WHAT A NAIVE IMPLEMENTATION MIGHT INCORRECTLY CONCLUDE:
 *   That the Day-2 fee assessed on the Day-5 evening keeps Day 2 at −25.00 of
 *   fees permanently — reporting stale cumulative fees (75.00 instead of net
 *   0.00) and stale balances after E9.
 *
 * STATUS:
 *   Intentional demonstration. It is excluded from the normal suite via the
 *   JUnit tag "intentional-failure" (see pom.xml / README.md). Run it with:
 *     mvn test -Dsurefire.groups=intentional-failure -Dsurefire.excludedGroups=
 *
 * (History: this test originally demonstrated that late value-dated events
 * rewrite historical projections — 250.00 vs 225.00. Under the phase-8
 * fee-compensation policy the final Day-2 closing is again 250.00, so the
 * demonstration was re-aimed at fee-effect permanence, which is where a naive
 * implementation would now break. A mid-edit mistake during that re-aiming —
 * asserting the mid-window Day-2 closing as 225.00 when E7's own −620.00
 * makes it −395.00 — was caught by the test itself failing on the sanity
 * line with "expected 225.00 but was -395.00"; the fee-effect formulation
 * above is the precise one.)
 */
@Tag("intentional-failure")
class IntentionalFailureTest {

    @Test
    void assessedFeesRemainEffectiveForever_naiveAssumption() {
        Account acc001 = Scenario.accounts().getFirst();

        // Mid-window view: the stream WITHOUT E9 (i.e. as known through the
        // Day-5 evening). The Day-2 fee's net effect on Day 2 is -25.00.
        List<EventEnvelope> withoutE9 = Scenario.events().stream()
                .filter(e -> !e.eventId().equals("E9"))
                .toList();
        ReplayResult midWindowView = new LedgerEngine(Scenario.accounts(), Scenario.WINDOW_START,
                Scenario.WINDOW_END, Scenario.BUSINESS_ZONE, Scenario.OVERDRAFT_FEE_SCHEDULE)
                .replay(withoutE9);
        Money day2FeeEffectMidWindow = netFeeEffectValuedOn(midWindowView, acc001, Scenario.day(2));
        assertEquals(Money.of("-25.00", CurrencyCode.AED), day2FeeEffectMidWindow); // sanity

        // Complete-stream view: E9's compensation of E7 leaves no date
        // overdrawn, so the Day-6 evening reconciliation appends a
        // FEE_REVERSAL (+25.00 valued Day 2). The net Day-2 fee effect is
        // 0.00, NOT -25.00. The assertion below is the naive one and FAILS —
        // that failure is the demonstration.
        ReplayResult fullView = Scenario.replay();
        Money day2FeeEffectFinal = netFeeEffectValuedOn(fullView, acc001, Scenario.day(2));
        assertEquals(day2FeeEffectMidWindow, day2FeeEffectFinal,
                "INTENTIONAL FAILURE: fee entries are immutable but not permanently effective — "
                        + "the Day-6 reconciliation appended a FEE_REVERSAL, moving Day 2's net "
                        + "fee effect from " + day2FeeEffectMidWindow + " to " + day2FeeEffectFinal
                        + ". Derived-entry EFFECTS can be compensated by later entries.");
    }

    /** Net amount of fee and fee-reversal entries valued on the given date. */
    private static Money netFeeEffectValuedOn(ReplayResult result, Account account,
                                              java.time.LocalDate date) {
        Money total = Money.zero(account.currency());
        for (var entry : result.entries()) {
            boolean feeKind = entry.kind() == com.ledger.core.service.domain.entry.EntryKind.FEE_OVERDRAFT
                    || entry.kind() == com.ledger.core.service.domain.entry.EntryKind.FEE_REVERSAL;
            if (feeKind && entry.accountId().equals(account.id())
                    && entry.valueDate().equals(date)) {
                total = total.add(entry.amount());
            }
        }
        return total;
    }
}
