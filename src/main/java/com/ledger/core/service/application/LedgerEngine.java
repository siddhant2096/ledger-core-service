package com.ledger.core.service.application;

import com.ledger.core.service.application.ReplayResult.DailyAccrual;
import com.ledger.core.service.application.ReplayResult.FeeAssessment;
import com.ledger.core.service.application.ReplayResult.Outcome;
import com.ledger.core.service.application.ReplayResult.SkippedFeeAssessment;
import com.ledger.core.service.domain.account.Account;
import com.ledger.core.service.domain.auth.Authorization;
import com.ledger.core.service.domain.auth.AuthorizationStatus;
import com.ledger.core.service.domain.entry.EntryKind;
import com.ledger.core.service.domain.entry.LedgerEntry;
import com.ledger.core.service.domain.event.EventEnvelope;
import com.ledger.core.service.domain.event.LedgerEvent;
import com.ledger.core.service.domain.ledger.EventLog;
import com.ledger.core.service.domain.money.CurrencyCode;
import com.ledger.core.service.domain.money.CurrencyMismatchException;
import com.ledger.core.service.domain.money.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic, in-memory, event-sourced ledger engine.
 *
 * <p>Processing model:
 * <ol>
 *   <li>Source events are applied strictly in stream (list) order; the
 *       envelope's {@code streamPosition} makes that order explicit and unique.
 *       Events are NEVER reordered by value date. Equal processing dates keep
 *       stream order; an event whose processing date is earlier than one
 *       already seen is still applied in stream order and picked up by the
 *       next evening assessment (documented in AMBIGUITIES.md).</li>
 *   <li>Balances are always derived from ledger entries filtered by
 *       <em>value date</em>, so a late event (processed 2026-01-05, valued
 *       2026-01-02) rewrites historical projections without mutating any
 *       event.</li>
 *   <li>Evening boundary: whenever the processing date advances (and once at
 *       the end of the window), overdraft fees are assessed for every value
 *       date up to the completed date whose pre-fee closing balance is
 *       negative and that has not been fee-assessed before (idempotency key:
 *       account + value date). Fee entries are immutable once derived; later
 *       reversals never remove them.</li>
 *   <li>Last lifecycle boundary: interest is capitalized at the window end.
 *       Each day's accrual is the post-fee closing balance x
 *       {@link #DAILY_RATE}, rounded HALF_UP to the account currency's scale
 *       (positive balances only); the capitalized credit is exactly the sum of
 *       the rounded daily accruals.</li>
 * </ol>
 *
 * <p>Business dates are {@link LocalDate}s supplied on the envelopes. When
 * instants must be converted (e.g. real-time ingestion), use
 * {@link #businessDate(Instant)} — the configured zone, never the JVM default.
 *
 * <p>Replaying the same stream always produces byte-identical entries and
 * reports.
 */
public final class LedgerEngine {

    /** Business constant: daily interest rate 0.04% = 0.0004. See NUMBERS.md. */
    public static final String DAILY_RATE = "0.0004";

    private final List<Account> accounts;
    private final LocalDate windowStart;
    private final LocalDate windowEnd;
    private final ZoneId businessZone;
    private final Map<CurrencyCode, Money> overdraftFees;

    /**
     * @param accounts       iteration order = registration order (keeps output deterministic)
     * @param windowStart    first business date of the replay window (inclusive)
     * @param windowEnd      last business date of the replay window (inclusive);
     *                       interest is capitalized on this date
     * @param businessZone   explicit zone for deriving business dates from instants
     * @param overdraftFees  EXPLICIT per-currency overdraft fee schedule. The fee
     *                       is only ever charged in the account's own currency and
     *                       only when that currency is configured: a negative
     *                       account in an unconfigured currency is recorded as a
     *                       {@code SkippedFeeAssessment} — visible in the trace,
     *                       never silently charged and never silently ignored.
     *                       (The specification supplies only "AED 25.00"; any other
     *                       currency's fee is an explicit configuration decision.
     *                       See AMBIGUITIES.md #15.)
     */
    public LedgerEngine(List<Account> accounts, LocalDate windowStart, LocalDate windowEnd,
                        ZoneId businessZone, Map<CurrencyCode, Money> overdraftFees) {
        this.accounts = List.copyOf(accounts);
        this.windowStart = Objects.requireNonNull(windowStart, "windowStart");
        this.windowEnd = Objects.requireNonNull(windowEnd, "windowEnd");
        this.businessZone = Objects.requireNonNull(businessZone, "businessZone");
        this.overdraftFees = Map.copyOf(Objects.requireNonNull(overdraftFees, "overdraftFees"));
        if (windowEnd.isBefore(windowStart)) {
            throw new IllegalArgumentException("windowEnd must not be before windowStart");
        }
        for (Map.Entry<CurrencyCode, Money> fee : overdraftFees.entrySet()) {
            if (fee.getValue().currency() != fee.getKey()) {
                throw new IllegalArgumentException("fee schedule currency mismatch: key "
                        + fee.getKey() + " but amount " + fee.getValue());
            }
            if (!fee.getValue().isPositive()) {
                throw new IllegalArgumentException("fee must be positive: " + fee.getValue());
            }
        }
    }

    /** Derives a business date from an instant using the configured zone. */
    public LocalDate businessDate(Instant instant) {
        return BusinessDates.businessDate(instant, businessZone);
    }

    public ReplayResult replay(List<EventEnvelope> stream) {
        // Data-pipeline contract: stream positions must be unique — they key
        // outcomes and make ordering explicit. Violations are programming
        // errors, not business rejections.
        Set<Long> positions = new HashSet<>();
        for (EventEnvelope envelope : stream) {
            if (!positions.add(envelope.streamPosition())) {
                throw new IllegalArgumentException(
                        "duplicate streamPosition " + envelope.streamPosition());
            }
        }

        EventLog log = new EventLog();
        Map<Long, Outcome> outcomes = new LinkedHashMap<>();
        List<LedgerEntry> entries = new ArrayList<>();
        Map<String, Authorization> authorizations = new LinkedHashMap<>();
        List<FeeAssessment> feeTrace = new ArrayList<>();
        List<SkippedFeeAssessment> skippedFees = new ArrayList<>();
        List<DailyAccrual> interestTrace = new ArrayList<>();
        Set<String> assessedFeeDays = new HashSet<>(); // "accountId|valueDate"
        Set<String> reversedEventIds = new HashSet<>();
        Set<String> seenEventIds = new HashSet<>();
        long[] sequence = {0};

        LocalDate lastProcessedDate = null;
        for (EventEnvelope envelope : stream) {
            if (lastProcessedDate == null || envelope.processingDate().isAfter(lastProcessedDate)) {
                // Evening boundary: every business date that has fully
                // completed, from the last processed date (or window start) up
                // to the day before the new processing date.
                LocalDate first = lastProcessedDate == null ? windowStart : lastProcessedDate;
                for (LocalDate evening = first; evening.isBefore(envelope.processingDate());
                     evening = evening.plusDays(1)) {
                    assessFees(evening, entries, feeTrace, skippedFees, assessedFeeDays, sequence);
                }
                lastProcessedDate = envelope.processingDate();
            }
            log.append(envelope);
            outcomes.put(envelope.streamPosition(), processEvent(
                    envelope, entries, authorizations, reversedEventIds, seenEventIds, sequence));
        }
        // Final evening(s): assess everything up to the window end, then
        // capitalize interest as the last derived entries of the window.
        LocalDate first = lastProcessedDate == null ? windowStart : lastProcessedDate;
        for (LocalDate evening = first; !evening.isAfter(windowEnd); evening = evening.plusDays(1)) {
            assessFees(evening, entries, feeTrace, skippedFees, assessedFeeDays, sequence);
        }
        capitalizeInterest(entries, interestTrace, sequence);

        return new ReplayResult(accounts, log, outcomes, entries, authorizations, feeTrace,
                skippedFees, interestTrace);
    }

    // ------------------------------------------------------------------
    // Event handling (business validation -> derived entries)
    // ------------------------------------------------------------------

    private Outcome processEvent(EventEnvelope envelope,
                                 List<LedgerEntry> entries,
                                 Map<String, Authorization> authorizations,
                                 Set<String> reversedEventIds,
                                 Set<String> seenEventIds,
                                 long[] sequence) {
        // Idempotent ingestion: a replayed/duplicated event id is rejected, the
        // first occurrence's outcome and entries stand. Documented in
        // AMBIGUITIES.md.
        if (!seenEventIds.add(envelope.eventId())) {
            return new Outcome.Rejected("DUPLICATE_EVENT_ID: " + envelope.eventId()
                    + " was already processed at another stream position");
        }
        if (envelope.processingDate().isBefore(windowStart)
                || envelope.processingDate().isAfter(windowEnd)
                || envelope.valueDate().isBefore(windowStart)
                || envelope.valueDate().isAfter(windowEnd)) {
            // Outside the configured window the posting would be invisible to
            // fee assessment and interest, i.e. silently inconsistent — reject
            // loudly instead. See AMBIGUITIES.md.
            return new Outcome.Rejected("EVENT_DATE_OUT_OF_HORIZON: " + envelope.eventId()
                    + " processing=" + envelope.processingDate() + " value=" + envelope.valueDate()
                    + " outside [" + windowStart + ", " + windowEnd + "]");
        }
        LedgerEvent event = envelope.event();
        Account account = findAccount(event.accountId());
        if (account == null) {
            return new Outcome.Rejected("UNKNOWN_ACCOUNT: " + event.accountId());
        }
        return switch (event) {
            case LedgerEvent.Credit e -> postFinancial(envelope, account, e.amount(), true,
                    "Credit", entries, sequence);
            case LedgerEvent.Debit e -> postFinancial(envelope, account, e.amount(), false,
                    "Debit", entries, sequence);
            case LedgerEvent.InstalmentCredit e -> postInstalments(envelope, account, e, entries, sequence);
            case LedgerEvent.AuthorizationRequest e -> decideAuthorization(envelope, account, e, entries,
                    authorizations);
            case LedgerEvent.Settlement e -> settle(envelope, account, e, entries, authorizations, sequence);
            case LedgerEvent.Reversal e -> reverse(envelope, account, e, entries, reversedEventIds, sequence);
        };
    }

    private Outcome postFinancial(EventEnvelope envelope, Account account, Money unsignedAmount,
                                  boolean credit, String description,
                                  List<LedgerEntry> entries, long[] sequence) {
        Outcome currencyCheck = checkCurrency(envelope, account, unsignedAmount);
        if (currencyCheck != null) {
            return currencyCheck;
        }
        // Credit/Debit amounts must be strictly positive: a negative Credit
        // would otherwise post as a backdoor debit (and vice versa). The
        // direction of money flow must come from the event TYPE, never from a
        // smuggled-in sign.
        if (!unsignedAmount.isPositive()) {
            return new Outcome.Rejected("NON_POSITIVE_AMOUNT: " + description
                    + " amount must be positive, got " + unsignedAmount);
        }
        Money signedAmount = credit ? unsignedAmount : unsignedAmount.negate();
        appendEntry(entries, sequence, envelope.eventId(), account.id(), signedAmount,
                envelope.valueDate(), envelope.processingDate(), EntryKind.POSTING, description);
        return new Outcome.Accepted(description + " posted");
    }

    private Outcome postInstalments(EventEnvelope envelope, Account account,
                                    LedgerEvent.InstalmentCredit e,
                                    List<LedgerEntry> entries, long[] sequence) {
        Outcome currencyCheck = checkCurrency(envelope, account, e.totalAmount());
        if (currencyCheck != null) {
            return currencyCheck;
        }
        if (!e.totalAmount().isPositive()) {
            // A zero-total instalment credit would post zero-value entries
            // (noise in the books); negative totals are nonsense for a credit.
            return new Outcome.Rejected("NON_POSITIVE_INSTALMENT_TOTAL: " + e.totalAmount());
        }
        List<Money> parts = InstalmentAllocator.allocate(e.totalAmount(), e.instalmentCount());
        for (int i = 0; i < parts.size(); i++) {
            appendEntry(entries, sequence, envelope.eventId(), account.id(), parts.get(i),
                    envelope.valueDate(), envelope.processingDate(), EntryKind.INSTALMENT_POSTING,
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
        // TIME: entries derived so far (any source value date up to today,
        // plus fee entries already derived), i.e. the current books. See
        // AMBIGUITIES.md — late value-dated events processed earlier DO count,
        // because they are already known facts.
        Money ledgerBalance = closingBalance(entries, account, envelope.processingDate());
        Money activeHolds = activeHolds(authorizations, account);
        Money available = ledgerBalance.subtract(activeHolds);
        boolean approved = !available.subtract(e.holdAmount()).isNegative();

        AuthorizationStatus status = approved ? AuthorizationStatus.APPROVED : AuthorizationStatus.DECLINED;
        authorizations.put(e.authorizationId(),
                Authorization.decided(e.authorizationId(), account.id(), e.holdAmount(),
                        envelope.processingDate(), status));
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
                envelope.valueDate(), envelope.processingDate(), EntryKind.POSTING,
                "Settlement of " + e.authorizationId());
        authorizations.put(auth.id(), auth.settle(e.amount(), envelope.processingDate()));
        return new Outcome.Accepted("Settlement posted; hold released (residual "
                + auth.holdAmount().subtract(e.amount()) + " released)");
    }

    private Outcome reverse(EventEnvelope envelope, Account account,
                            LedgerEvent.Reversal e,
                            List<LedgerEntry> entries,
                            Set<String> reversedEventIds,
                            long[] sequence) {
        // Validation order matters: NOTHING is mutated until every check has
        // passed and the compensation entry has been posted. Marking the target
        // id as reversed before validation would let a FAILED reversal (e.g. of
        // an unknown target) poison the id and block a later, legitimate
        // reversal of an event that subsequently arrives with that id.

        // 1. Duplicate protection: a target id is in this set IFF a reversal of
        //    it has already SUCCEEDED, so membership is proof of a prior
        //    reversal, not of a prior attempt.
        if (reversedEventIds.contains(e.targetEventId())) {
            return new Outcome.Rejected("DUPLICATE_REVERSAL: " + e.targetEventId()
                    + " has already been reversed");
        }
        // 2. Target existence: a reversal of a rejected event also lands here,
        //    because a rejected event derives no entries — consistent with
        //    "rejected means it did not happen, accounting-wise".
        List<LedgerEntry> targets = entries.stream()
                .filter(en -> en.sourceEventId().equals(e.targetEventId()))
                .toList();
        if (targets.isEmpty()) {
            return new Outcome.Rejected("REVERSAL_TARGET_UNKNOWN: no entries derived from "
                    + e.targetEventId());
        }
        // 3. Eligibility and ownership of EVERY derived entry (multi-entry
        //    events such as instalment credits are validated as a whole).
        for (LedgerEntry target : targets) {
            if (target.kind() != EntryKind.POSTING && target.kind() != EntryKind.INSTALMENT_POSTING) {
                // Also covers reversal-of-a-reversal: a compensation entry is
                // not POSTING, so re-reversing it is rejected here.
                return new Outcome.Rejected("REVERSAL_UNSUPPORTED_TARGET: "
                        + e.targetEventId() + " produced " + target.kind() + " entries");
            }
            if (!target.accountId().equals(account.id())) {
                return new Outcome.Rejected("REVERSAL_ACCOUNT_MISMATCH: " + e.targetEventId()
                        + " is on " + target.accountId());
            }
        }
        // 4. Exact accounting compensation: reverse the SUM of the target
        //    event's postings (so a multi-part instalment credit is compensated
        //    in full) with a single entry of opposite sign, value date taken
        //    from the REVERSAL event. The original event and entries are never
        //    mutated or deleted.
        Money compensation = Money.zero(account.currency());
        for (LedgerEntry target : targets) {
            compensation = compensation.add(target.amount());
        }
        appendEntry(entries, sequence, envelope.eventId(), account.id(),
                compensation.negate(), envelope.valueDate(), envelope.processingDate(),
                EntryKind.REVERSAL_COMPENSATION,
                "Reversal of " + e.targetEventId() + " (" + targets.getFirst().description() + ")");
        // 5. State transition last: only a successfully posted reversal marks
        //    the target as reversed. Atomic within the single-threaded
        //    processing model: validation and posting observe and mutate the
        //    same in-memory state in one step, with no interleaving.
        reversedEventIds.add(e.targetEventId());
        return new Outcome.Accepted("Reversal posted as compensation of " + e.targetEventId());
    }

    // ------------------------------------------------------------------
    // Derived accounting: fees and interest
    // ------------------------------------------------------------------

    /**
     * Evening fee assessment for a completed processing date. For every value
     * date v in the window up to that date that has never been fee-assessed,
     * the pre-fee closing balance (which already includes all previously
     * derived fee entries) is computed; if negative, exactly one fee entry is
     * derived with value date v — in the account's own currency and only if
     * that currency has an explicitly configured fee. A negative account-date
     * in an UNCONFIGURED currency is recorded as a {@link SkippedFeeAssessment}
     * (visible, audited) and no fee is posted. Because historical value dates
     * are re-examined whenever new events arrive, a late back-valued debit
     * correctly creates a fee for its value date — but never twice
     * (idempotency key account|valueDate), and existing fees are never
     * removed.
     */
    private void assessFees(LocalDate processingDate,
                            List<LedgerEntry> entries,
                            List<FeeAssessment> feeTrace,
                            List<SkippedFeeAssessment> skippedFees,
                            Set<String> assessedFeeDays,
                            long[] sequence) {
        // Clamp at the window end: value dates beyond the window cannot be
        // posted at all (rejected at ingestion), but a late out-of-window
        // processing date must not trigger pointless assessment loops.
        LocalDate effective = processingDate.isAfter(windowEnd) ? windowEnd : processingDate;
        for (Account account : accounts) {
            for (LocalDate v : BusinessDates.datesBetween(windowStart, effective)) {
                String key = account.id() + "|" + v;
                if (assessedFeeDays.contains(key)) {
                    continue; // decision already made for this account-date: idempotent
                }
                Money preFeeBalance = closingBalance(entries, account, v);
                if (preFeeBalance.isNegative()) {
                    // Mark only when a fee DECISION is made (charge or
                    // documented skip): a date that is currently non-negative
                    // must be RE-EXAMINED after later events arrive (a late
                    // back-valued debit can make a past date negative).
                    // Exactly one fee per account-date is guaranteed by the key.
                    assessedFeeDays.add(key);
                    Money configuredFee = overdraftFees.get(account.currency());
                    if (configuredFee == null) {
                        // No silent cross-currency fee and no silent omission:
                        // the skip is part of the replay's audit trace.
                        skippedFees.add(new SkippedFeeAssessment(account.id(), v, processingDate,
                                preFeeBalance, "NO_FEE_CONFIGURED_FOR_CURRENCY "
                                + account.currency()));
                        continue;
                    }
                    Money fee = configuredFee.negate();
                    String feeId = "FEE-" + account.id() + "-" + v;
                    appendEntry(entries, sequence, feeId, account.id(), fee, v, processingDate,
                            EntryKind.FEE_OVERDRAFT,
                            "Overdraft fee for " + v + " (balance " + preFeeBalance + ")");
                    feeTrace.add(new FeeAssessment(account.id(), v, processingDate, preFeeBalance,
                            configuredFee));
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
            for (LocalDate v : BusinessDates.datesBetween(windowStart, windowEnd)) {
                Money base = closingBalance(entries, account, v);
                Money accrual = base.isPositive() ? base.timesRate(DAILY_RATE) : Money.zero(account.currency());
                interestTrace.add(new DailyAccrual(account.id(), v, base, accrual));
                total = total.add(accrual);
            }
            if (total.isPositive()) {
                appendEntry(entries, sequence, "INTEREST-" + account.id() + "-" + windowEnd,
                        account.id(), total, windowEnd, windowEnd,
                        EntryKind.INTEREST_CAPITALIZATION,
                        "Capitalized interest, " + windowStart + " to " + windowEnd);
            }
        }
    }

    // ------------------------------------------------------------------
    // Projections
    // ------------------------------------------------------------------

    /**
     * Closing ledger balance for {@code account} on {@code valueDate}: opening
     * balance plus the sum of all derived entries (postings, fees, reversals,
     * interest) whose value date is on or before {@code valueDate}.
     */
    public static Money closingBalance(List<LedgerEntry> entries, Account account, LocalDate valueDate) {
        Money balance = account.openingBalance();
        for (LedgerEntry entry : entries) {
            if (entry.accountId().equals(account.id()) && !entry.valueDate().isAfter(valueDate)) {
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
                             String accountId, Money amount, LocalDate valueDate,
                             LocalDate derivedOnDate, EntryKind kind, String description) {
        try {
            entries.add(new LedgerEntry(++sequence[0], sourceEventId, accountId, amount,
                    valueDate, derivedOnDate, kind, description));
        } catch (CurrencyMismatchException ex) {
            throw new IllegalStateException("internal currency violation", ex);
        }
    }
}
