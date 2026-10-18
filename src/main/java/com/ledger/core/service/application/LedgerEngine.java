package com.ledger.core.service.application;

import com.ledger.core.service.application.ReplayResult.DailyAccrual;
import com.ledger.core.service.application.ReplayResult.FeeAssessment;
import com.ledger.core.service.application.ReplayResult.Outcome;
import com.ledger.core.service.domain.account.Account;
import com.ledger.core.service.domain.auth.Authorization;
import com.ledger.core.service.domain.auth.AuthorizationStatus;
import com.ledger.core.service.domain.entry.EntryKind;
import com.ledger.core.service.domain.entry.LedgerEntry;
import com.ledger.core.service.domain.event.EventEnvelope;
import com.ledger.core.service.domain.event.LedgerEvent;
import com.ledger.core.service.domain.ledger.EventLog;
import com.ledger.core.service.domain.money.CurrencyMismatchException;
import com.ledger.core.service.domain.money.Money;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, in-memory, event-sourced ledger engine.
 *
 * <p>Processing model:
 * <ol>
 *   <li>Source events are applied strictly in stream order, grouped by
 *       processing day (same-day order = stream order).</li>
 *   <li>Balances are always derived from ledger entries filtered by
 *       <em>value day</em>, so a late event (e.g. processed day 5, value day 2)
 *       rewrites historical projections without mutating any event.</li>
 *   <li>At the end of every processing day D, overdraft fees are assessed for
 *       every value day v &lt;= D whose pre-fee closing balance is negative and
 *       that has not been assessed before (idempotency key: account + value
 *       day). Fee entries are immutable once derived; later reversals never
 *       remove them.</li>
 *   <li>At the end of the final horizon day, interest is capitalized: each
 *       day's accrual is the post-fee closing balance x {@link #DAILY_RATE},
 *       rounded HALF_UP to the account currency's scale (positive balances
 *       only), and the capitalized credit is exactly the sum of the rounded
 *       daily accruals.</li>
 * </ol>
 *
 * Replaying the same stream always produces byte-identical entries and reports.
 */
public final class LedgerEngine {

    /** Business constant: overdraft fee, 25 in the ACCOUNT's own currency. See NUMBERS.md. */
    public static final String OVERDRAFT_FEE_AMOUNT = "25";

    /** Business constant: daily interest rate 0.04% = 0.0004. See NUMBERS.md. */
    public static final String DAILY_RATE = "0.0004";

    private final List<Account> accounts;
    private final int horizonDays;

    public LedgerEngine(List<Account> accounts, int horizonDays) {
        // LinkedHashMap semantics via list copy: iteration order = registration
        // order, which is what keeps the replay output deterministic.
        this.accounts = List.copyOf(accounts);
        if (horizonDays < 1) {
            throw new IllegalArgumentException("horizonDays must be >= 1");
        }
        this.horizonDays = horizonDays;
    }

    public ReplayResult replay(List<EventEnvelope> stream) {
        EventLog log = new EventLog();
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        List<LedgerEntry> entries = new ArrayList<>();
        Map<String, Authorization> authorizations = new LinkedHashMap<>();
        List<FeeAssessment> feeTrace = new ArrayList<>();
        List<DailyAccrual> interestTrace = new ArrayList<>();
        Set<String> assessedFeeDays = new HashSet<>(); // "accountId|valueDay"
        Set<String> reversedEventIds = new HashSet<>();
        long[] sequence = {0};

        // Events are applied strictly in stream order (the specification's
        // "precise processing order"). Processing days are expected to be
        // non-decreasing; whenever the processing day advances, the previous
        // evening's fee assessment runs first (assessment covers ALL value
        // days up to that day, so skipped empty days need no work). An event
        // whose processing day is EARLIER than one already seen (E10 follows
        // E9 in the stream: day 5 after day 6) is still applied in stream
        // order and is picked up by the next assessment run — documented in
        // AMBIGUITIES.md.
        int lastProcessedDay = 0;
        for (EventEnvelope envelope : stream) {
            if (envelope.processingDay() > lastProcessedDay) {
                // Evenings of every day that has fully completed: from the day
                // we were last on (or day 1) up to the day before the new one.
                for (int evening = Math.max(1, lastProcessedDay); evening < envelope.processingDay(); evening++) {
                    assessFees(evening, entries, feeTrace, assessedFeeDays, sequence);
                }
                lastProcessedDay = envelope.processingDay();
            }
            log.append(envelope);
            outcomes.put(envelope.eventId(), processEvent(
                    envelope, entries, authorizations, reversedEventIds, sequence));
        }
        // Final evening(s): assess everything up to the horizon, then
        // capitalize interest as the last derived entries of the window.
        for (int evening = Math.max(1, lastProcessedDay); evening <= horizonDays; evening++) {
            assessFees(evening, entries, feeTrace, assessedFeeDays, sequence);
        }
        capitalizeInterest(entries, interestTrace, sequence);

        return new ReplayResult(accounts, log, outcomes, entries, authorizations, feeTrace, interestTrace);
    }

    // ------------------------------------------------------------------
    // Event handling (business validation -> derived entries)
    // ------------------------------------------------------------------

    private Outcome processEvent(EventEnvelope envelope,
                                 List<LedgerEntry> entries,
                                 Map<String, Authorization> authorizations,
                                 Set<String> reversedEventIds,
                                 long[] sequence) {
        LedgerEvent event = envelope.event();
        Account account = findAccount(event.accountId());
        if (account == null) {
            return new Outcome.Rejected("UNKNOWN_ACCOUNT: " + event.accountId());
        }
        return switch (event) {
            case LedgerEvent.Credit e -> postFinancial(envelope, account, e.amount(), EntryKind.POSTING,
                    "Credit", entries, sequence);
            case LedgerEvent.Debit e -> postFinancial(envelope, account, e.amount().negate(), EntryKind.POSTING,
                    "Debit", entries, sequence);
            case LedgerEvent.InstalmentCredit e -> postInstalments(envelope, account, e, entries, sequence);
            case LedgerEvent.AuthorizationRequest e -> decideAuthorization(envelope, account, e, entries,
                    authorizations);
            case LedgerEvent.Settlement e -> settle(envelope, account, e, entries, authorizations, sequence);
            case LedgerEvent.Reversal e -> reverse(envelope, account, e, entries, reversedEventIds, sequence);
        };
    }

    private Outcome postFinancial(EventEnvelope envelope, Account account, Money signedAmount,
                                  EntryKind kind, String description,
                                  List<LedgerEntry> entries, long[] sequence) {
        Outcome currencyCheck = checkCurrency(envelope, account, signedAmount);
        if (currencyCheck != null) {
            return currencyCheck;
        }
        if (kind == EntryKind.POSTING && signedAmount.isZero()) {
            return new Outcome.Rejected("ZERO_AMOUNT: posting amount must be non-zero");
        }
        appendEntry(entries, sequence, envelope.eventId(), account.id(), signedAmount,
                envelope.valueDay(), envelope.processingDay(), kind, description);
        return new Outcome.Accepted(description + " posted");
    }

    private Outcome postInstalments(EventEnvelope envelope, Account account,
                                    LedgerEvent.InstalmentCredit e,
                                    List<LedgerEntry> entries, long[] sequence) {
        Outcome currencyCheck = checkCurrency(envelope, account, e.totalAmount());
        if (currencyCheck != null) {
            return currencyCheck;
        }
        List<Money> parts = InstalmentAllocator.allocate(e.totalAmount(), e.instalmentCount());
        for (int i = 0; i < parts.size(); i++) {
            appendEntry(entries, sequence, envelope.eventId(), account.id(), parts.get(i),
                    envelope.valueDay(), envelope.processingDay(), EntryKind.INSTALMENT_POSTING,
                    "Instalment " + (i + 1) + "/" + parts.size() + " of " + e.totalAmount());
        }
        return new Outcome.Accepted("Instalment credit posted in " + parts.size() + " parts: " + parts);
    }

    private Outcome decideAuthorization(EventEnvelope envelope, Account account,
                                        LedgerEvent.AuthorizationRequest e,
                                        List<LedgerEntry> entries,
                                        Map<String, Authorization> authorizations) {
        Outcome currencyCheck = checkCurrency(envelope, account, e.holdAmount());
        if (currencyCheck != null) {
            return currencyCheck;
        }
        if (authorizations.containsKey(e.authorizationId())) {
            return new Outcome.Rejected("DUPLICATE_AUTHORIZATION_ID: " + e.authorizationId());
        }
        if (!e.holdAmount().isPositive()) {
            return new Outcome.Rejected("NON_POSITIVE_HOLD: " + e.holdAmount());
        }
        // Authorization decisions use the ledger balance KNOWN AT PROCESSING
        // TIME: entries derived so far (any source value day up to today, plus
        // fee entries already derived), i.e. the current books. See
        // AMBIGUITIES.md — late value-dated events processed earlier today DO
        // count, because they are already known facts.
        Money ledgerBalance = closingBalance(entries, account, envelope.processingDay());
        Money activeHolds = activeHolds(authorizations, account);
        Money available = ledgerBalance.subtract(activeHolds);
        boolean approved = !available.subtract(e.holdAmount()).isNegative();

        AuthorizationStatus status = approved ? AuthorizationStatus.APPROVED : AuthorizationStatus.DECLINED;
        authorizations.put(e.authorizationId(),
                Authorization.decided(e.authorizationId(), account.id(), e.holdAmount(),
                        envelope.processingDay(), status));
        String note = "AUTH " + status + ": ledger=" + ledgerBalance
                + " activeHolds=" + activeHolds + " requested=" + e.holdAmount();
        return new Outcome.Accepted(note);
    }

    private Outcome settle(EventEnvelope envelope, Account account,
                           LedgerEvent.Settlement e,
                           List<LedgerEntry> entries,
                           Map<String, Authorization> authorizations,
                           long[] sequence) {
        Outcome currencyCheck = checkCurrency(envelope, account, e.amount());
        if (currencyCheck != null) {
            return currencyCheck;
        }
        // Settlement validity derives from the AUTHORIZATION, never from the
        // account balance: the hold guaranteed the funds at approval time.
        Authorization auth = authorizations.get(e.authorizationId());
        if (auth == null) {
            // Unknown authorization: rejected, recorded, no ledger entry, and
            // crucially no authorization state is created or consumed.
            return new Outcome.Rejected("UNKNOWN_AUTHORIZATION: no authorization "
                    + e.authorizationId() + " exists for account " + account.id());
        }
        if (!auth.accountId().equals(account.id())) {
            return new Outcome.Rejected("AUTHORIZATION_ACCOUNT_MISMATCH: " + e.authorizationId()
                    + " belongs to " + auth.accountId());
        }
        if (!auth.status().isSettleable()) {
            return new Outcome.Rejected("AUTHORIZATION_NOT_SETTLEABLE: " + e.authorizationId()
                    + " is " + auth.status());
        }
        if (!e.amount().isPositive()) {
            return new Outcome.Rejected("NON_POSITIVE_SETTLEMENT: " + e.amount());
        }
        if (e.amount().compareTo(auth.holdAmount()) > 0) {
            // Excess settlement: rejected in full rather than silently clipped
            // (clipping alternative documented in AMBIGUITIES.md).
            return new Outcome.Rejected("SETTLEMENT_EXCEEDS_AUTHORIZED_AMOUNT: "
                    + e.amount() + " > " + auth.holdAmount());
        }
        appendEntry(entries, sequence, envelope.eventId(), account.id(), e.amount().negate(),
                envelope.valueDay(), envelope.processingDay(), EntryKind.POSTING,
                "Settlement of " + e.authorizationId());
        authorizations.put(auth.id(), auth.settle(e.amount(), envelope.processingDay()));
        return new Outcome.Accepted("Settlement posted; hold released (residual "
                + auth.holdAmount().subtract(e.amount()) + " released)");
    }

    private Outcome reverse(EventEnvelope envelope, Account account,
                            LedgerEvent.Reversal e,
                            List<LedgerEntry> entries,
                            Set<String> reversedEventIds,
                            long[] sequence) {
        // Duplicate protection: each source event may be reversed at most once.
        if (!reversedEventIds.add(e.targetEventId())) {
            return new Outcome.Rejected("DUPLICATE_REVERSAL: " + e.targetEventId()
                    + " has already been reversed");
        }
        List<LedgerEntry> targets = entries.stream()
                .filter(en -> en.sourceEventId().equals(e.targetEventId()))
                .toList();
        if (targets.isEmpty()) {
            return new Outcome.Rejected("REVERSAL_TARGET_UNKNOWN: no entries derived from "
                    + e.targetEventId());
        }
        for (LedgerEntry target : targets) {
            if (target.kind() != EntryKind.POSTING && target.kind() != EntryKind.INSTALMENT_POSTING) {
                return new Outcome.Rejected("REVERSAL_UNSUPPORTED_TARGET: "
                        + e.targetEventId() + " produced " + target.kind() + " entries");
            }
            if (!target.accountId().equals(account.id())) {
                return new Outcome.Rejected("REVERSAL_ACCOUNT_MISMATCH: " + e.targetEventId()
                        + " is on " + target.accountId());
            }
        }
        // Exact accounting compensation: reverse the SUM of the target event's
        // postings (so multi-part instalment credits are compensated in full)
        // with a single entry of opposite sign, value date taken from the
        // REVERSAL event. The original event and entries are never mutated
        // or deleted.
        Money compensation = Money.zero(account.currency());
        for (LedgerEntry target : targets) {
            compensation = compensation.add(target.amount());
        }
        appendEntry(entries, sequence, envelope.eventId(), account.id(),
                compensation.negate(), envelope.valueDay(), envelope.processingDay(),
                EntryKind.REVERSAL_COMPENSATION,
                "Reversal of " + e.targetEventId() + " (" + targets.getFirst().description() + ")");
        return new Outcome.Accepted("Reversal posted as compensation of " + e.targetEventId());
    }

    // ------------------------------------------------------------------
    // Derived accounting: fees and interest
    // ------------------------------------------------------------------

    /**
     * End-of-processing-day fee assessment. For every value day v &lt;= today
     * that has never been assessed, the pre-fee closing balance (which already
     * includes all previously derived fee entries) is computed; if negative,
     * exactly one fee entry is derived with value day v. Because historical
     * value days are re-examined whenever new events arrive, a late event such
     * as a day-2-valued debit processed on day 5 correctly creates a day-2 fee
     * — but never twice (idempotency key account|valueDay), and existing fees
     * are never removed.
     */
    private void assessFees(int processingDay,
                            List<LedgerEntry> entries,
                            List<FeeAssessment> feeTrace,
                            Set<String> assessedFeeDays,
                            long[] sequence) {
        for (Account account : accounts) {
            for (int v = 1; v <= processingDay; v++) {
                String key = account.id() + "|" + v;
                if (assessedFeeDays.contains(key)) {
                    continue; // fee already charged for this account-day: idempotent
                }
                Money preFeeBalance = closingBalance(entries, account, v);
                if (preFeeBalance.isNegative()) {
                    // Mark only when a fee is actually charged: a day that is
                    // currently non-negative must be RE-EXAMINED after later
                    // events arrive (a late day-2-valued debit can make day 2
                    // negative on processing day 5). Exactly one fee per
                    // account-day is still guaranteed by the key.
                    assessedFeeDays.add(key);
                    Money fee = Money.of(OVERDRAFT_FEE_AMOUNT, account.currency()).negate();
                    String feeId = "FEE-" + account.id() + "-D" + v;
                    appendEntry(entries, sequence, feeId, account.id(), fee, v, processingDay,
                            EntryKind.FEE_OVERDRAFT,
                            "Overdraft fee for day " + v + " (balance " + preFeeBalance + ")");
                    feeTrace.add(new FeeAssessment(account.id(), v, processingDay, preFeeBalance,
                            fee.negate()));
                }
            }
        }
    }

    /**
     * End-of-window interest capitalization. Each day's accrual is computed on
     * the post-fee closing ledger balance (positive balances only), rounded
     * HALF_UP to the account currency's scale. The capitalized credit is the
     * exact sum of the rounded daily accruals — by construction no rounding
     * remainder can exist, so none is ever discarded (see AMBIGUITIES.md for
     * the aggregate-rounding alternative that was rejected).
     */
    private void capitalizeInterest(List<LedgerEntry> entries,
                                    List<DailyAccrual> interestTrace,
                                    long[] sequence) {
        for (Account account : accounts) {
            Money total = Money.zero(account.currency());
            for (int v = 1; v <= horizonDays; v++) {
                Money base = closingBalance(entries, account, v);
                Money accrual = base.isPositive() ? base.timesRate(DAILY_RATE) : Money.zero(account.currency());
                interestTrace.add(new DailyAccrual(account.id(), v, base, accrual));
                total = total.add(accrual);
            }
            if (total.isPositive()) {
                appendEntry(entries, sequence, "INTEREST-" + account.id() + "-D" + horizonDays,
                        account.id(), total, horizonDays, horizonDays,
                        EntryKind.INTEREST_CAPITALIZATION,
                        "Capitalized interest, days 1-" + horizonDays);
            }
        }
    }

    // ------------------------------------------------------------------
    // Projections
    // ------------------------------------------------------------------

    /**
     * Closing ledger balance for {@code account} on {@code valueDay}: opening
     * balance plus the sum of all derived entries (postings, fees, reversals,
     * interest) whose value day is on or before {@code valueDay}.
     */
    public static Money closingBalance(List<LedgerEntry> entries, Account account, int valueDay) {
        Money balance = account.openingBalance();
        for (LedgerEntry entry : entries) {
            if (entry.accountId().equals(account.id()) && entry.valueDay() <= valueDay) {
                balance = balance.add(entry.amount());
            }
        }
        return balance;
    }

    /** Sum of currently active (approved, unsettled) holds for an account. */
    public static Money activeHolds(Map<String, Authorization> authorizations, Account account) {
        Money total = Money.zero(account.currency());
        for (Authorization auth : authorizations.values()) {
            if (auth.accountId().equals(account.id()) && auth.isActiveHold()) {
                total = total.add(auth.holdAmount());
            }
        }
        return total;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Account findAccount(String accountId) {
        return accounts.stream().filter(a -> a.id().equals(accountId)).findFirst().orElse(null);
    }

    private Outcome checkCurrency(EventEnvelope envelope, Account account, Money amount) {
        if (amount.currency() != account.currency()) {
            // Belt and braces: the engine rejects cross-currency postings with a
            // business error instead of letting Money.add throw deep inside a
            // projection later.
            return new Outcome.Rejected("CURRENCY_MISMATCH: event " + envelope.eventId() + " is "
                    + amount.currency() + " but account " + account.id() + " is " + account.currency());
        }
        return null;
    }

    private void appendEntry(List<LedgerEntry> entries, long[] sequence, String sourceEventId,
                             String accountId, Money amount, int valueDay, int derivedOnDay,
                             EntryKind kind, String description) {
        try {
            entries.add(new LedgerEntry(++sequence[0], sourceEventId, accountId, amount,
                    valueDay, derivedOnDay, kind, description));
        } catch (CurrencyMismatchException ex) {
            throw new IllegalStateException("internal currency violation", ex);
        }
    }
}
