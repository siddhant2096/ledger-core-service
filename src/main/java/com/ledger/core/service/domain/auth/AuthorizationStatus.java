package com.ledger.core.service.domain.auth;

/**
 * Lifecycle of an authorization:
 *
 * <pre>
 *   (request) --> APPROVED --settle( amount == hold )--> FULLY_SETTLED
 *              |          |
 *              |          --settle( amount <  hold )--> PARTIALLY_SETTLED
 *              v
 *           DECLINED   (terminal; never creates a hold)
 * </pre>
 *
 * A settled authorization (partial or full) is terminal: the chosen lifecycle
 * releases any residual hold at settlement time, so no further settlement is
 * possible. See AMBIGUITIES.md for the alternatives considered.
 */
public enum AuthorizationStatus {
    APPROVED,
    DECLINED,
    PARTIALLY_SETTLED,
    FULLY_SETTLED;

    public boolean isActive() {
        return this == APPROVED;
    }

    public boolean isSettleable() {
        return this == APPROVED;
    }
}
