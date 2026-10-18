package com.ledger.core.service.application;

import com.ledger.core.service.application.ReplayResult.DailyAccrual;
import com.ledger.core.service.application.ReplayResult.FeeAssessment;
import com.ledger.core.service.application.ReplayResult.FeeCompensation;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 *       the end of the window), overdraft fees are RECONCILED: the desired fee
 *       set is recomputed deterministically from all non-fee entries (same
 *       sequential, fee-participating semantics as a fresh assessment), newly
 *       negative account-dates are charged (at most once per account-date,
 *       ever), and already-charged dates whose corrected books no longer show
 *       an overdraft receive an append-only FEE_REVERSAL compensation entry
 *       valued on the original fee's value date. Fee entries are never mutated
 *       or deleted; each fee is charged at most once and compensated at most
 *       once (a compensated date is never re-charged). See AMBIGUITIES.md
 *       #1/#10/#11/#28.</li>
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
        List<FeeCompensation> feeCompensations = new ArrayList<>();
        List<DailyAccrual> interestTrace = new ArrayList<>();
        Set<String> chargedFeeDays = new LinkedHashSet<>();      // "accountId|valueDate", ever charged
        Set<String> compensatedFeeDays = new LinkedHashSet<>();  // charged and later compensated
        Set<String> skippedFeeDays = new HashSet<>();            // negative but currency unconfigured
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
                    reconcileFees(evening, entries, feeTrace, skippedFees, feeCompensations,
                            chargedFeeDays, compensatedFeeDays, skippedFeeDays, sequence);
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
            reconcileFees(evening, entries, feeTrace, skippedFees, feeCompensations,
                    chargedFeeDays, compensatedFeeDays, skippedFeeDays, sequence);
        }
        capitalizeInterest(entries, interestTrace, sequence);

        return new ReplayResult(accounts, log, outcomes, entries, authorizations, feeTrace,
                skippedFees, feeCompensations, interestTrace);
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
     * Evening fee RECONCILIATION for a completed processing date.
     *
     * <p>For each account, the desired fee set is recomputed from scratch over
     * all NON-fee entries (POSTING / INSTALMENT_POSTING / REVERSAL_COMPENSATION)
     * using the same sequential semantics as a fresh assessment: value dates in
     * ascending order, each desired fee participating in later dates' bases.
     * Then, deterministically:
     *
     * <ol>
     *   <li>Every previously charged, not-yet-compensated fee whose date is no
     *       longer desired (the corrected books do not show an overdraft for
     *       it) is COMPENSATED: an append-only FEE_REVERSAL entry of the
     *       opposite sign, valued on the ORIGINAL fee's value date, derived on
     *       this boundary date. The original fee entry is never mutated or
     *       deleted.</li>
     *   <li>Every desired date never charged before is charged exactly once
     *       (idempotency key account|valueDate). A date once charged and later
     *       compensated is NEVER re-charged — "assess once per account per
     *       day" is read as one fee lifecycle per account-date
     *       (AMBIGUITIES.md #28).</li>
     *   <li>A negative date in a currency with no configured fee produces a
     *       {@link SkippedFeeAssessment} once (audited, no entry).</li>
     * </ol>
     *
     * <p>Because reconciliation is a pure function of the entries list plus the
     * (charged, compensated) lifecycle sets, replay is deterministic, and a
     * back-valued reversal (E9) correctly neutralizes the fees its target
     * caused — restoring pre-E7 balances in net terms while preserving the
     * complete fee history (charge + compensation) in the entry list.
     */
    private void reconcileFees(LocalDate processingDate,
                               List<LedgerEntry> entries,
                               List<FeeAssessment> feeTrace,
                               List<SkippedFeeAssessment> skippedFees,
                               List<FeeCompensation> feeCompensations,
                               Set<String> chargedFeeDays,
                               Set<String> compensatedFeeDays,
                               Set<String> skippedFeeDays,
                               long[] sequence) {
        // Clamp at the window end: value dates beyond the window cannot be
        // posted at all (rejected at ingestion), but a late out-of-window
        // processing date must not trigger pointless assessment loops.
        LocalDate effective = processingDate.isAfter(windowEnd) ? windowEnd : processingDate;
        List<LocalDate> dates = BusinessDates.datesBetween(windowStart, effective);
        for (Account account : accounts) {
            Money configuredFee = overdraftFees.get(account.currency());
            // Pass 1: desired fee set from non-fee entries, sequential with
            // participation of fees that WILL be effective after this boundary
            // (never-charged-and-negative -> will charge; charged-uncompensated
            // -and-negative -> stays; charged-and-compensated -> never returns).
            Money base = account.openingBalance();
            Set<String> desired = new HashSet<>();
            Map<String, Money> desiredPreFeeBases = new HashMap<>();
            for (LocalDate v : dates) {
                for (LedgerEntry e : entries) {
                    if (e.accountId().equals(account.id()) && e.valueDate().equals(v)
                            && isFinancialKind(e.kind())) {
                        base = base.add(e.amount());
                    }
                }
                String key = account.id() + "|" + v;
                if (base.isNegative()) {
                    if (configuredFee == null) {
                        if (skippedFeeDays.add(key)) {
                            skippedFees.add(new SkippedFeeAssessment(account.id(), v,
                                    processingDate, base, "NO_FEE_CONFIGURED_FOR_CURRENCY "
                                    + account.currency()));
                        }
                    } else {
                        desired.add(key);
                        // The trace records the base BEFORE this date's own fee
                        // (pre-fee semantics, AMBIGUITIES.md #1).
                        desiredPreFeeBases.put(key, base);
                        boolean willCarryFee = !(chargedFeeDays.contains(key)
                                && compensatedFeeDays.contains(key));
                        if (willCarryFee) {
                            base = base.add(configuredFee.negate());
                        }
                    }
                }
            }
            if (configuredFee == null) {
                continue;
            }
            // Pass 2: compensate charged fees that are no longer supported by
            // the corrected books. Iteration order = charge order (LinkedHashSet)
            // = ascending value date, keeping the replay byte-deterministic.
            for (String key : chargedFeeDays) {
                if (!key.startsWith(account.id() + "|") || desired.contains(key)) {
                    continue;
                }
                LocalDate v = LocalDate.parse(key.substring(key.indexOf('|') + 1));
                if (compensatedFeeDays.add(key)) {
                    appendEntry(entries, sequence, "FEE-REVERSAL-" + account.id() + "-" + v,
                            account.id(), configuredFee, v, processingDate,
                            EntryKind.FEE_REVERSAL,
                            "Compensation of overdraft fee for " + v
                                    + " (corrected books no longer show an overdraft)");
                    feeCompensations.add(new FeeCompensation(account.id(), v, processingDate,
                            configuredFee));
                }
            }
            // Pass 3: charge desired dates never charged before.
            for (LocalDate v : dates) {
                String key = account.id() + "|" + v;
                if (!desired.contains(key) || !chargedFeeDays.add(key)) {
                    continue; // not negative, already charged (idempotent), or compensated
                }
                Money preFeeBalance = desiredPreFeeBases.get(key);
                appendEntry(entries, sequence, "FEE-" + account.id() + "-" + v,
                        account.id(), configuredFee.negate(), v, processingDate,
                        EntryKind.FEE_OVERDRAFT,
                        "Overdraft fee for " + v + " (balance " + preFeeBalance + ")");
                feeTrace.add(new FeeAssessment(account.id(), v, processingDate, preFeeBalance,
                        configuredFee));
            }
        }
    }

    private static boolean isFinancialKind(EntryKind kind) {
        return kind == EntryKind.POSTING || kind == EntryKind.INSTALMENT_POSTING
                || kind == EntryKind.REVERSAL_COMPENSATION;
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
