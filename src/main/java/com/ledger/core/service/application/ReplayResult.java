package com.ledger.core.service.application;

import com.ledger.core.service.domain.account.Account;
import com.ledger.core.service.domain.auth.Authorization;
import com.ledger.core.service.domain.entry.LedgerEntry;
import com.ledger.core.service.domain.event.EventEnvelope;
import com.ledger.core.service.domain.ledger.EventLog;
import com.ledger.core.service.domain.money.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Everything derived from one deterministic replay of the event stream.
 * Re-running the engine over the same stream must produce an identical result.
 *
 * <p>Outcomes are keyed by {@code streamPosition} (unique per envelope), not
 * by event id: a duplicate event id is itself a rejection case, and both the
 * original and the duplicate must keep their own outcomes.
 */
public record ReplayResult(
        List<Account> accounts,
        EventLog log,
        Map<Long, Outcome> outcomes,
        List<LedgerEntry> entries,
        Map<String, Authorization> authorizations,
        List<FeeAssessment> feeTrace,
        List<DailyAccrual> interestTrace) {

    /** Outcome of processing one source event. */
    public sealed interface Outcome {
        /** Event accepted; {@code note} carries decisions (e.g. AUTH APPROVED/DECLINED). */
        record Accepted(String note) implements Outcome {
        }

        /** Event failed business validation. No ledger entries, no auth state changes. */
        record Rejected(String reason) implements Outcome {
        }
    }

    /** One overdraft fee assessment, kept for the audit trace in the report. */
    public record FeeAssessment(String accountId, LocalDate valueDate, LocalDate assessedOnDate,
                                Money preFeeBalance, Money feeAmount) {
    }

    /** One day's interest accrual line, kept for the report (not a ledger entry). */
    public record DailyAccrual(String accountId, LocalDate date, Money base, Money accrual) {
    }

    /** Outcome of the FIRST envelope carrying {@code eventId} (duplicates are rejected anyway). */
    public Outcome outcomeOf(String eventId) {
        return log.events().stream()
                .filter(e -> e.eventId().equals(eventId))
                .findFirst()
                .map(e -> outcomes.get(e.streamPosition()))
                .orElse(null);
    }

    public Outcome outcomeAt(long streamPosition) {
        return outcomes.get(streamPosition);
    }

    public List<EventEnvelope> rejectedEvents() {
        return log.events().stream()
                .filter(e -> outcomes.get(e.streamPosition()) instanceof Outcome.Rejected)
                .toList();
    }
}
