package com.ledger.core.service.domain.ledger;

import com.ledger.core.service.domain.event.EventEnvelope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Append-only log of source events. There is deliberately no mutation or
 * removal API: {@link #events()} exposes an unmodifiable view and
 * {@link #append} is the only write operation. Business validation failures
 * do not remove an event from the log; the event remains as a fact and its
 * REJECTED outcome is derived state (recorded in the replay result).
 */
public final class EventLog {

    private final List<EventEnvelope> events = new ArrayList<>();

    public void append(EventEnvelope envelope) {
        events.add(envelope);
    }

    /** Unmodifiable, in append (stream) order. */
    public List<EventEnvelope> events() {
        return Collections.unmodifiableList(events);
    }

    public Optional<EventEnvelope> findById(String eventId) {
        return events.stream().filter(e -> e.eventId().equals(eventId)).findFirst();
    }

    public int size() {
        return events.size();
    }
}
