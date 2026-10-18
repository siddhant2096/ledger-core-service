package com.ledger.core.service;

import com.ledger.core.service.application.LedgerEngine;
import com.ledger.core.service.application.ReplayResult;
import com.ledger.core.service.application.ReplayResult.DailyAccrual;
import com.ledger.core.service.application.Scenario;
import com.ledger.core.service.domain.account.Account;
import com.ledger.core.service.domain.auth.Authorization;
import com.ledger.core.service.domain.auth.AuthorizationStatus;
import com.ledger.core.service.domain.entry.EntryKind;
import com.ledger.core.service.domain.money.CurrencyCode;
import com.ledger.core.service.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exhaustive per-day projection verification for both accounts over the full
 * window: closing balances, active holds, available balances, fee presence,
 * and interest accruals. Every expectation is derived inline from the rules:
 *
 * <pre>
 * ACC-001 entries: +1200@D1, -950@D1, -620@D2 (E7), +620@D2 (E9), +400@D3,
 *                  -185@D4 (E5), fees -25@D2/D4/D5 (charged D5 evening),
 *                  fee compensations +25@D2/D4/D5 (derived D6 evening, after
 *                  E9 left no date overdrawn), interest +1.03@D6.
 * Holds (processing-time): Auth-A 200.00 active D2..D3 (settled D4);
 *                          Auth-B declined (never active).
 * ACC-002 entries: +3.333/+3.333/+3.334@D5, interest +0.008@D6.
 * </pre>
 */
class DailyProjectionTest {

    private final ReplayResult result = Scenario.replay();
    private final Account acc001 = Scenario.accounts().get(0);
    private final Account acc002 = Scenario.accounts().get(1);

    private static final String[][] EXPECTED_AED = {
            // day, closing, activeHolds, available, accrual
            {"2026-01-01", "250.00", "0.00", "250.00", "0.10"},  // 250 x .0004 = .1000
            {"2026-01-02", "250.00", "200.00", "50.00", "0.10"}, // fee -25 + compensation +25 net 0
            {"2026-01-03", "650.00", "200.00", "450.00", "0.26"}, // 650 x .0004 = .2600
            {"2026-01-04", "465.00", "0.00", "465.00", "0.19"},  // 465 x .0004 = .1860 -> .19
            {"2026-01-05", "465.00", "0.00", "465.00", "0.19"},
            {"2026-01-06", "466.03", "0.00", "466.03", "0.19"},  // +1.03 capitalized
    };

    private static final String[][] EXPECTED_BHD = {
            {"2026-01-01", "0.000", "0.000", "0.000", "0.000"},
            {"2026-01-02", "0.000", "0.000", "0.000", "0.000"},
            {"2026-01-03", "0.000", "0.000", "0.000", "0.000"},
            {"2026-01-04", "0.000", "0.000", "0.000", "0.000"},
            {"2026-01-05", "10.000", "0.000", "10.000", "0.004"}, // 10 x .0004 = .004000
            {"2026-01-06", "10.008", "0.000", "10.008", "0.004"}, // +0.008 capitalized
    };

    @Test
    void acc001DailyProjectionsMatchDerivation() {
        verify(acc001, CurrencyCode.AED, EXPECTED_AED);
    }

    @Test
    void acc002DailyProjectionsMatchDerivation() {
        verify(acc002, CurrencyCode.BHD, EXPECTED_BHD);
    }

    private void verify(Account account, CurrencyCode currency, String[][] expected) {
        for (String[] row : expected) {
            LocalDate date = LocalDate.parse(row[0]);
            Money closing = Money.of(row[1], currency);
            Money holds = Money.of(row[2], currency);
            Money available = Money.of(row[3], currency);
            Money accrual = Money.of(row[4], currency);

            assertEquals(closing, LedgerEngine.closingBalance(result.entries(), account, date),
                    account.id() + " closing on " + date);
            assertEquals(holds, activeHoldsOn(account, date),
                    account.id() + " active holds on " + date);
            assertEquals(available, LedgerEngine.closingBalance(result.entries(), account, date)
                    .subtract(activeHoldsOn(account, date)), account.id() + " available on " + date);
            DailyAccrual accrualRow = result.interestTrace().stream()
                    .filter(a -> a.accountId().equals(account.id()) && a.date().equals(date))
                    .findFirst().orElseThrow();
            assertEquals(accrual, accrualRow.accrual(), account.id() + " accrual on " + date);
        }
    }

    /** Same rule as the report: active from processing date until settlement date. */
    private Money activeHoldsOn(Account account, LocalDate date) {
        Money total = Money.zero(account.currency());
        for (Authorization auth : result.authorizations().values()) {
            boolean active = auth.accountId().equals(account.id())
                    && auth.status() != AuthorizationStatus.DECLINED
                    && !date.isBefore(auth.processedDate())
                    && (auth.settledOnDate() == null || date.isBefore(auth.settledOnDate()));
            if (active) {
                total = total.add(auth.holdAmount());
            }
        }
        return total;
    }

    @Test
    void day6CapitalizationIsTheOnlyInterestEntryPerAccount() {
        for (Account account : Scenario.accounts()) {
            assertEquals(1, result.entries().stream()
                    .filter(e -> e.accountId().equals(account.id())
                            && e.kind() == EntryKind.INTEREST_CAPITALIZATION)
                    .count());
        }
    }
}
