package com.ledger.core.service.application;

import com.ledger.core.service.domain.account.Account;
import com.ledger.core.service.domain.auth.Authorization;
import com.ledger.core.service.domain.entry.LedgerEntry;
import com.ledger.core.service.domain.event.EventEnvelope;
import com.ledger.core.service.domain.ledger.EventLog;
import com.ledger.core.service.domain.money.Money;

import java.util.List;
import java.util.Map;

/**
 * Everything derived from one deterministic replay of the event stream.
 * Re-running the engine over the same stream must produce an identical result.
 */
public record ReplayResult(
        List<Account> accounts,
        EventLog log,
        Map<String, Outcome> outcomes,
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
    public record FeeAssessment(String accountId, int valueDay, int assessedOnDay,
                                Money preFeeBalance, Money feeAmount) {
    }

    /** One day's interest accrual line, kept for the report (not a ledger entry). */
    public record DailyAccrual(String accountId, int day, Money base, Money accrual) {
    }

    public Outcome outcomeOf(String eventId) {
        return outcomes.get(eventId);
    }

    public List<EventEnvelope> rejectedEvents() {
        return log.events().stream()
                .filter(e -> outcomes.get(e.eventId()) instanceof Outcome.Rejected)
                .toList();
    }
}
