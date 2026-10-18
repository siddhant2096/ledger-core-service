package com.ledger.core.service.domain.entry;

import com.ledger.core.service.domain.money.Money;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A derived, immutable ledger entry (a posting line). Entries — not events —
 * are what balances are computed from. Every entry carries:
 * <ul>
 *   <li>{@code sourceEventId} — the source event it derives from, or a
 *       synthetic deterministic id (e.g. {@code FEE-ACC-001-2026-01-02}) for
 *       engine-generated entries, keeping the full audit trail
 *       reproducible.</li>
 *   <li>{@code valueDate} — the business date the entry belongs to. This is
 *       what historical projections filter on.</li>
 *   <li>{@code derivedOnDate} — the processing date on which the engine
 *       created the entry. For direct postings this equals the source event's
 *       processing date; for fees it is the evening the assessment ran.</li>
 *   <li>{@code amount} — signed: credits positive, debits/fees negative.</li>
 * </ul>
 */
public record LedgerEntry(
        long sequence,
        String sourceEventId,
        String accountId,
        Money amount,
        LocalDate valueDate,
        LocalDate derivedOnDate,
        EntryKind kind,
        String description) {

    public LedgerEntry {
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(derivedOnDate, "derivedOnDate");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(description, "description");
    }
}
