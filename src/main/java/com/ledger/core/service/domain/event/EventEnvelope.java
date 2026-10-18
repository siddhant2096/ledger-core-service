package com.ledger.core.service.domain.event;

import java.util.Objects;

/**
 * Wraps a source event with its two timelines:
 * <ul>
 *   <li>{@code processingDay} — when the event reached the ledger
 *       (processing/stream order). Events are applied in this order.</li>
 *   <li>{@code valueDay} — the accounting day the event belongs to
 *       (value date). Historical balance projections respect this date, so a
 *       late event can change an earlier day's closing balance.</li>
 * </ul>
 *
 * For {@link LedgerEvent.InstalmentCredit}, {@code valueDay} is the value date
 * assigned to every derived instalment posting (documented assumption: the
 * specification supplies no per-instalment value dates).
 */
public record EventEnvelope(String eventId, int processingDay, int valueDay, LedgerEvent event) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(event, "event");
        if (processingDay < 1) {
            throw new IllegalArgumentException("processingDay must be >= 1");
        }
        if (valueDay < 1) {
            throw new IllegalArgumentException("valueDay must be >= 1");
        }
    }
}
