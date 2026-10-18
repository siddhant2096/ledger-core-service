package com.ledger.core.service.domain.money;

/**
 * Thrown when arithmetic is attempted between two different currencies.
 * Cross-currency arithmetic is never performed silently; there is no FX
 * rate table in this system, so mixing currencies is a programming error.
 */
public final class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(CurrencyCode left, CurrencyCode right) {
        super("Currency mismatch: " + left + " vs " + right
                + ". Cross-currency arithmetic requires an explicit FX conversion, which this ledger does not support.");
    }
}
