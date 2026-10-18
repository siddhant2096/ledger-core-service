package com.ledger.core.service.domain.event;

import com.ledger.core.service.domain.money.Money;

/**
 * Immutable source event payloads. Source events are facts supplied from
 * outside the ledger; they are never mutated or deleted. All derived
 * accounting effects (postings, fees, interest, compensations) are modelled
 * separately as {@code LedgerEntry} records so the event/entry distinction
 * stays explicit.
 *
 * <p>Sealed so the engine's event handling is exhaustive at compile time.
 */
public sealed interface LedgerEvent {

    String accountId();

    /** A simple credit posting. Amount must be positive. */
    record Credit(String accountId, Money amount) implements LedgerEvent {
    }

    /** A simple debit posting. Amount must be positive; it is posted negative. */
    record Debit(String accountId, Money amount) implements LedgerEvent {
    }

    /** Requests a hold of {@code holdAmount} on the account. */
    record AuthorizationRequest(String accountId, String authorizationId, Money holdAmount)
            implements LedgerEvent {
    }

    /** Settles (fully or partially) a previously approved authorization. */
    record Settlement(String accountId, String authorizationId, Money amount)
            implements LedgerEvent {
    }

    /**
     * Reverses a previous financial event by ID. Reversal is exact accounting
     * compensation: a new entry of opposite sign and equal amount is derived;
     * the original event is never touched.
     */
    record Reversal(String accountId, String targetEventId) implements LedgerEvent {
    }

    /**
     * A credit posted as N equal instalments. The instalment schedule is
     * derived deterministically by the engine (residual minor units go to the
     * last instalment(s)); the event carries only the total and the count,
     * which is sufficient metadata to reproduce the schedule exactly.
     */
    record InstalmentCredit(String accountId, Money totalAmount, int instalmentCount)
            implements LedgerEvent {
    }
}
