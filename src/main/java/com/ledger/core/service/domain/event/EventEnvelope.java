package com.ledger.core.service.domain.event;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable source event with its time model. Four concepts are deliberately
 * kept distinct because they are NOT interchangeable:
 *
 * <ul>
 *   <li>{@code streamPosition} — stable 1-based position in the source stream.
 *       The engine processes events in list (stream) order; this field makes
 *       that order explicit, survives re-keying and re-batching, breaks ties
 *       between events sharing a processing date, and uniquely keys the
 *       event's outcome (so duplicate event ids cannot silently overwrite
 *       each other's results).</li>
 *   <li>{@code processingDate} — the business date on which the event is
 *       processed by the ledger. Drives lifecycle boundaries (evening fee
 *       assessment) and authorization decisions (the books as known at this
 *       point).</li>
 *   <li>{@code valueDate} — the business date the event's derived postings
 *       belong to. Balances, fee value dates, and interest bases respect it.
 *       It may be EARLIER than the processing date (late/back-valued postings,
 *       e.g. E7) or LATER (forward-dated postings); both are valued into
 *       projections without reordering the stream.</li>
 * </ul>
 *
 * <p>Deliberately NOT modeled: {@code Instant occurredAt} / {@code receivedAt}.
 * The input stream carries no instants and no business rule consumes them, so
 * adding them would fabricate data. When real-time ingestion arrives, instants
 * must be converted to business dates via the engine's configured time zone
 * ({@code LedgerEngine.businessDate(Instant)}) — never the JVM default zone.
 * See AMBIGUITIES.md (time model entry).
 *
 * <p>For {@link LedgerEvent.InstalmentCredit}, {@code valueDate} applies to
 * every derived instalment posting (the specification supplies no
 * per-instalment value dates).
 */
public record EventEnvelope(
        String eventId,
        long streamPosition,
        LocalDate processingDate,
        LocalDate valueDate,
        LedgerEvent event) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(processingDate, "processingDate");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(event, "event");
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (streamPosition < 1) {
            throw new IllegalArgumentException("streamPosition must be >= 1");
        }
    }
}
