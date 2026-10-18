package com.ledger.core.service.domain.money;

/**
 * ISO-4217 currencies supported by the ledger, each with its minor-unit scale.
 * AED has 2 decimal places, BHD has 3. The scale is part of the currency's
 * identity: every {@link Money} value is stored at exactly this scale.
 */
public enum CurrencyCode {
    AED(2),
    BHD(3);

    private final int scale;

    CurrencyCode(int scale) {
        this.scale = scale;
    }

    /** Number of decimal places (minor-unit precision) for this currency. */
    public int scale() {
        return scale;
    }
}
