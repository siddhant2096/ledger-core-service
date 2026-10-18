package com.ledger.core.service.application;

import com.ledger.core.service.domain.account.Account;
import com.ledger.core.service.domain.event.EventEnvelope;
import com.ledger.core.service.domain.event.LedgerEvent;
import com.ledger.core.service.domain.money.CurrencyCode;
import com.ledger.core.service.domain.money.Money;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * The exact input event stream from the specification, in processing (stream)
 * order. Shared by the {@code Main} executable and the test suite so that
 * tests verify the same replay that produces the report.
 *
 * <p>Time model: the specification's "Day 1..6" are mapped to the business
 * window 2026-01-01..2026-01-06 (an arbitrary but fixed range — any six
 * consecutive dates produce identical results; documented in NUMBERS.md).
 * Every envelope carries an explicit 1-based {@code streamPosition} equal to
 * its specification number (E1 -> 1, ..., E10 -> 10). Note that E9 (position
 * 9, processing date 2026-01-06) precedes E10 (position 10, processing date
 * 2026-01-05): the stream order, not the processing date, is the processing
 * order.
 *
 * <p>E10 carries no explicit value date in the specification; the documented
 * assumption (see AMBIGUITIES.md) is value date = processing date
 * (2026-01-05) for every derived instalment posting.
 *
 * <p>The business zone is explicit ({@link #BUSINESS_ZONE}); it would govern
 * any Instant -> business-date derivation during ingestion. The scenario
 * inputs are already business dates, so no derivation occurs at runtime.
 */
public final class Scenario {

    public static final LocalDate WINDOW_START = LocalDate.of(2026, 1, 1);
    public static final LocalDate WINDOW_END = LocalDate.of(2026, 1, 6);
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("UTC");
    public static final int INSTALMENT_COUNT = 3;

    /**
     * Explicit per-currency overdraft fee schedule. AED 25.00 is the supplied
     * requirement. BHD 25.000 is an explicit, documented implementation choice
     * (same numeric value, account currency — AMBIGUITIES.md #15); it is
     * configuration, not a silent fallback, and a currency absent from this
     * map is charged NOTHING (the skip is recorded in the replay trace).
     */
    public static final Map<CurrencyCode, Money> OVERDRAFT_FEE_SCHEDULE = Map.of(
            CurrencyCode.AED, Money.of("25.00", CurrencyCode.AED),
            CurrencyCode.BHD, Money.of("25.000", CurrencyCode.BHD));

    private Scenario() {
    }

    /** Business date for the specification's "Day n" (1-based). */
    public static LocalDate day(int n) {
        return WINDOW_START.plusDays(n - 1L);
    }

    public static List<Account> accounts() {
        return List.of(
                Account.zeroBalance("ACC-001", CurrencyCode.AED),
                Account.zeroBalance("ACC-002", CurrencyCode.BHD));
    }

    public static List<EventEnvelope> events() {
        return List.of(
                new EventEnvelope("E1", 1, day(1), day(1),
                        new LedgerEvent.Credit("ACC-001", Money.of("1200.00", CurrencyCode.AED))),
                new EventEnvelope("E2", 2, day(1), day(1),
                        new LedgerEvent.Debit("ACC-001", Money.of("950.00", CurrencyCode.AED))),
                new EventEnvelope("E3", 3, day(2), day(2),
                        new LedgerEvent.AuthorizationRequest("ACC-001", "Auth-A",
                                Money.of("200.00", CurrencyCode.AED))),
                new EventEnvelope("E4", 4, day(3), day(3),
                        new LedgerEvent.Credit("ACC-001", Money.of("400.00", CurrencyCode.AED))),
                new EventEnvelope("E5", 5, day(4), day(4),
                        new LedgerEvent.Settlement("ACC-001", "Auth-A",
                                Money.of("185.00", CurrencyCode.AED))),
                new EventEnvelope("E6", 6, day(4), day(4),
                        new LedgerEvent.Settlement("ACC-001", "Auth-Z",
                                Money.of("180.00", CurrencyCode.AED))),
                new EventEnvelope("E7", 7, day(5), day(2),
                        new LedgerEvent.Debit("ACC-001", Money.of("620.00", CurrencyCode.AED))),
                new EventEnvelope("E8", 8, day(5), day(5),
                        new LedgerEvent.AuthorizationRequest("ACC-001", "Auth-B",
                                Money.of("90.00", CurrencyCode.AED))),
                new EventEnvelope("E9", 9, day(6), day(2),
                        new LedgerEvent.Reversal("ACC-001", "E7")),
                new EventEnvelope("E10", 10, day(5), day(5),
                        new LedgerEvent.InstalmentCredit("ACC-002",
                                Money.of("10.000", CurrencyCode.BHD), INSTALMENT_COUNT)));
    }

    public static LedgerEngine engine() {
        return new LedgerEngine(accounts(), WINDOW_START, WINDOW_END, BUSINESS_ZONE,
                OVERDRAFT_FEE_SCHEDULE);
    }

    public static ReplayResult replay() {
        return engine().replay(events());
    }
}
