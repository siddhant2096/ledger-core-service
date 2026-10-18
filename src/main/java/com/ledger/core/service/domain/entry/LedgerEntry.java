package com.ledger.core.service.domain.entry;

import com.ledger.core.service.domain.money.Money;

import java.util.Objects;

/**
 * A derived, immutable ledger entry (a posting line). Entries — not events —
 * are what balances are computed from. Every entry carries:
 * <ul>
 *   <li>{@code sourceEventId} — the source event it derives from, or a
 *       synthetic deterministic id (e.g. {@code FEE-ACC-001-D2}) for engine
 *       generated entries, keeping the full audit trail reproducible.</li>
 *   <li>{@code valueDay} — the accounting day the entry belongs to. This is
 *       what historical projections filter on.</li>
 *   <li>{@code derivedOnDay} — the processing day on which the engine created
 *       the entry. For direct postings this equals the source event's
 *       processing day; for fees it is the day the assessment ran.</li>
 *   <li>{@code amount} — signed: credits positive, debits/fees negative.</li>
 * </ul>
 */
public record LedgerEntry(
        long sequence,
        String sourceEventId,
        String accountId,
        Money amount,
        int valueDay,
        int derivedOnDay,
        EntryKind kind,
        String description) {

    public LedgerEntry {
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(description, "description");
    }
}
