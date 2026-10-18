package com.ledger.core.service.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable monetary value with currency-aware precision.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Internally a {@link BigDecimal} — never binary floating point.</li>
 *   <li>Always stored at exactly the currency's minor-unit scale
 *       (AED = 2, BHD = 3). Construction rejects any value that cannot be
 *       represented at that scale without rounding; rounding is a business
 *       decision and must happen explicitly (see {@link #times}).</li>
 *   <li>All arithmetic is same-currency only; a mismatch throws
 *       {@link CurrencyMismatchException}.</li>
 * </ul>
 *
 * <p>Rounding policy (documented in NUMBERS.md): the only place rounding
 * occurs is multiplication by a rate (interest accrual), which uses
 * {@link RoundingMode#HALF_UP} to the currency's minor-unit precision.
 */
public final class Money implements Comparable<Money> {

    private final BigDecimal amount;
    private final CurrencyCode currency;

    private Money(BigDecimal amount, CurrencyCode currency) {
        this.amount = amount;
        this.currency = currency;
    }

    /**
     * Creates a Money from a decimal string. The value must be exactly
     * representable at the currency's scale; otherwise
     * {@link ArithmeticException} is thrown (RoundingMode.UNNECESSARY).
     */
    public static Money of(String amount, CurrencyCode currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        BigDecimal raw = new BigDecimal(amount);
        return new Money(raw.setScale(currency.scale(), RoundingMode.UNNECESSARY), currency);
    }

    /** Creates a Money from a BigDecimal, applying the same exactness rule. */
    public static Money of(BigDecimal amount, CurrencyCode currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        return new Money(amount.setScale(currency.scale(), RoundingMode.UNNECESSARY), currency);
    }

    /** Zero at the currency's scale. */
    public static Money zero(CurrencyCode currency) {
        return new Money(BigDecimal.ZERO.setScale(currency.scale()), currency);
    }

    /** Builds a Money from whole minor units (e.g. fils for AED, thousandths for BHD). */
    public static Money ofMinorUnits(long minorUnits, CurrencyCode currency) {
        return new Money(BigDecimal.valueOf(minorUnits, currency.scale()), currency);
    }

    public CurrencyCode currency() {
        return currency;
    }

    public BigDecimal amount() {
        return amount;
    }

    /** Exact whole minor units; throws if the value has a fractional minor unit. */
    public long toMinorUnits() {
        return amount.movePointRight(currency.scale()).longValueExact();
    }

    private void requireSameCurrency(Money other) {
        if (this.currency != other.currency) {
            throw new CurrencyMismatchException(this.currency, other.currency);
        }
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        // Same-scale BigDecimal addition is exact; no rounding can occur.
        return new Money(this.amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(this.amount.negate(), currency);
    }

    /**
     * Multiplies by a decimal rate and rounds HALF_UP to the currency's
     * minor-unit precision. This is the ONLY rounding site in the system;
     * it exists for interest accrual. The rate itself is exact decimal.
     */
    public Money timesRate(String rate) {
        BigDecimal product = this.amount.multiply(new BigDecimal(rate));
        return new Money(product.setScale(currency.scale(), RoundingMode.HALF_UP), currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money other)) return false;
        // BigDecimal.compareTo-based equality: 1.0 and 1.00 are equal. Scale is
        // fixed per currency anyway, so this is mostly defensive.
        return currency == other.currency && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(currency, amount.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return currency + " " + amount.toPlainString();
    }
}
