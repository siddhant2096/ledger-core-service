package com.ledger.core.service.application;

import com.ledger.core.service.domain.account.Account;
import com.ledger.core.service.domain.event.EventEnvelope;
import com.ledger.core.service.domain.event.LedgerEvent;
import com.ledger.core.service.domain.money.CurrencyCode;
import com.ledger.core.service.domain.money.Money;

import java.util.List;

/**
 * The exact input event stream from the specification, in processing order.
 * Shared by the {@code Main} executable and the test suite so that tests
 * verify the same replay that produces the report.
 *
 * <p>E10 carries no explicit value date in the specification; the documented
 * assumption (see AMBIGUITIES.md) is value date = processing day (Day 5) for
 * every derived instalment posting.
 */
public final class Scenario {

    public static final int HORIZON_DAYS = 6;
    public static final int INSTALMENT_COUNT = 3;

    private Scenario() {
    }

    public static List<Account> accounts() {
        return List.of(
                Account.zeroBalance("ACC-001", CurrencyCode.AED),
                Account.zeroBalance("ACC-002", CurrencyCode.BHD));
    }

    public static List<EventEnvelope> events() {
        return List.of(
                new EventEnvelope("E1", 1, 1,
                        new LedgerEvent.Credit("ACC-001", Money.of("1200.00", CurrencyCode.AED))),
                new EventEnvelope("E2", 1, 1,
                        new LedgerEvent.Debit("ACC-001", Money.of("950.00", CurrencyCode.AED))),
                new EventEnvelope("E3", 2, 2,
                        new LedgerEvent.AuthorizationRequest("ACC-001", "Auth-A",
                                Money.of("200.00", CurrencyCode.AED))),
                new EventEnvelope("E4", 3, 3,
                        new LedgerEvent.Credit("ACC-001", Money.of("400.00", CurrencyCode.AED))),
                new EventEnvelope("E5", 4, 4,
                        new LedgerEvent.Settlement("ACC-001", "Auth-A",
                                Money.of("185.00", CurrencyCode.AED))),
                new EventEnvelope("E6", 4, 4,
                        new LedgerEvent.Settlement("ACC-001", "Auth-Z",
                                Money.of("180.00", CurrencyCode.AED))),
                new EventEnvelope("E7", 5, 2,
                        new LedgerEvent.Debit("ACC-001", Money.of("620.00", CurrencyCode.AED))),
                new EventEnvelope("E8", 5, 5,
                        new LedgerEvent.AuthorizationRequest("ACC-001", "Auth-B",
                                Money.of("90.00", CurrencyCode.AED))),
                new EventEnvelope("E9", 6, 2,
                        new LedgerEvent.Reversal("ACC-001", "E7")),
                new EventEnvelope("E10", 5, 5,
                        new LedgerEvent.InstalmentCredit("ACC-002",
                                Money.of("10.000", CurrencyCode.BHD), INSTALMENT_COUNT)));
    }

    public static ReplayResult replay() {
        return new LedgerEngine(accounts(), HORIZON_DAYS).replay(events());
    }
}
