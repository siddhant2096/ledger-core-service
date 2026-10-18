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
 *   It asserts that the Day-2 (2026-01-02) closing balance of ACC-001 is
 *   IMMUTABLE once its processing date has passed — i.e. that replaying only
 *   the events known by end of Day 2 and replaying the complete stream give
 *   the same Day-2 balance. That invariant is false by design: E7 (DEBIT
 *   620.00, value date Day 2) is processed on Day 5 and E9 (its reversal) on
 *   Day 6, and the Day-2 overdraft fee of 25.00 is derived on the evening of
 *   Day 5. Under the documented projection convention (end-of-window
 *   value-date projection), the final Day-2 closing is 225.00, not 250.00.
 *
 * WHICH ASSUMPTION / INVARIANT IT EXPOSES:
 *   The naive assumption "historical daily balances are frozen once their
 *   processing date ends". The ledger's real invariant is the opposite:
 *   historical VALUE-DATE projections must reflect late-arriving events,
 *   while the event log itself stays immutable. Anyone bolting a snapshot-
 *   based cache onto this engine without invalidating historical value dates
 *   would break correctness.
 *
 * WHAT A NAIVE IMPLEMENTATION MIGHT INCORRECTLY CONCLUDE:
 *   A snapshot-per-processing-date implementation would conclude Day 2 =
 *   250.00 forever, would never assess the Day-2 fee, and would misprice
 *   Day-3+ interest (bases 650.00/465.00/... instead of 625.00/415.00/...).
 *
 * STATUS:
 *   Intentional demonstration. It is excluded from the normal suite via the
 *   JUnit tag "intentional-failure" (see pom.xml / README.md). Run it with:
 *     mvn test -Dsurefire.groups=intentional-failure -Dsurefire.excludedGroups=
 */
@Tag("intentional-failure")
class IntentionalFailureTest {

    @Test
    void historicalBalancesAreFrozenAfterTheirProcessingDay_naiveAssumption() {
        Account acc001 = Scenario.accounts().getFirst();

        // "End of Day 2" view: only E1, E2, E3 processed. Day-2 closing 250.00.
        List<EventEnvelope> knownByEndOfDay2 = Scenario.events().stream()
                .filter(e -> !e.processingDate().isAfter(Scenario.day(2)))
                .toList();
        ReplayResult earlyView = new LedgerEngine(Scenario.accounts(), Scenario.WINDOW_START,
                Scenario.WINDOW_END, Scenario.BUSINESS_ZONE, Scenario.OVERDRAFT_FEE_SCHEDULE)
                .replay(knownByEndOfDay2);
        Money day2AsKnownThen = LedgerEngine.closingBalance(earlyView.entries(), acc001,
                Scenario.day(2));
        assertEquals(Money.of("250.00", CurrencyCode.AED), day2AsKnownThen); // sanity: this holds

        // Complete-stream view: Day-2 closing is 225.00 (E7 -620, E9 +620,
        // FEE -25 all valued on Day 2). The assertion below is the naive one
        // and FAILS — that failure is the demonstration.
        ReplayResult fullView = Scenario.replay();
        Money day2Final = LedgerEngine.closingBalance(fullView.entries(), acc001, Scenario.day(2));
        assertEquals(day2AsKnownThen, day2Final,
                "INTENTIONAL FAILURE: late value-dated events (E7/E9) and the derived day-2 fee "
                        + "rewrite the Day-2 closing from " + day2AsKnownThen + " to " + day2Final
                        + ". Historical value-date projections are NOT frozen at processing time.");
    }
}
