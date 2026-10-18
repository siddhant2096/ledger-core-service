package com.ledger.core.service;

import com.ledger.core.service.domain.money.CurrencyCode;
import com.ledger.core.service.domain.money.CurrencyMismatchException;
import com.ledger.core.service.domain.money.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Currency-aware decimal arithmetic: precision, rounding policy (HALF_UP at
 * minor-unit scale, only for rate multiplication), and cross-currency guards.
 */
class MoneyTest {

    @Test
    void aedHasTwoDecimalPlacesAndBhdThree() {
        assertEquals("1200.00", Money.of("1200.00", CurrencyCode.AED).amount().toPlainString());
        assertEquals("10.000", Money.of("10.000", CurrencyCode.BHD).amount().toPlainString());
        assertEquals(2, CurrencyCode.AED.scale());
        assertEquals(3, CurrencyCode.BHD.scale());
    }

    @Test
    void constructionRejectsValuesNotRepresentableAtCurrencyScale() {
        // AED 1.005 cannot exist without rounding; rounding is never silent.
        assertThrows(ArithmeticException.class, () -> Money.of("1.005", CurrencyCode.AED));
        // BHD can represent three decimals, so 1.005 is fine there.
        assertEquals("1.005", Money.of("1.005", CurrencyCode.BHD).amount().toPlainString());
    }

    @Test
    void crossCurrencyArithmeticIsRejected() {
        Money aed = Money.of("1.00", CurrencyCode.AED);
        Money bhd = Money.of("1.000", CurrencyCode.BHD);
        assertThrows(CurrencyMismatchException.class, () -> aed.add(bhd));
        assertThrows(CurrencyMismatchException.class, () -> aed.subtract(bhd));
        assertThrows(CurrencyMismatchException.class, () -> aed.compareTo(bhd));
    }

    @Test
    void sameCurrencyAdditionIsExact() {
        // 0.10 + 0.20 in binary floating point is 0.30000000000000004; here it
        // must be exactly 0.30. This is why BigDecimal is used throughout.
        Money sum = Money.of("0.10", CurrencyCode.AED).add(Money.of("0.20", CurrencyCode.AED));
        assertEquals(Money.of("0.30", CurrencyCode.AED), sum);
    }

    @Test
    void interestRoundingIsHalfUpAtCurrencyScale() {
        // 250.00 x 0.0004 = 0.1000 -> 0.10 exactly.
        assertEquals(Money.of("0.10", CurrencyCode.AED),
                Money.of("250.00", CurrencyCode.AED).timesRate("0.0004"));
        // Rounding boundary: 12.50 x 0.0004 = 0.00500 -> HALF_UP -> 0.01.
        assertEquals(Money.of("0.01", CurrencyCode.AED),
                Money.of("12.50", CurrencyCode.AED).timesRate("0.0004"));
        // Just below the boundary: 12.49 x 0.0004 = 0.004996 -> 0.00.
        assertEquals(Money.of("0.00", CurrencyCode.AED),
                Money.of("12.49", CurrencyCode.AED).timesRate("0.0004"));
        // BHD boundary at the third decimal: 1.250 x 0.0004 = 0.00050 -> 0.001.
        assertEquals(Money.of("0.001", CurrencyCode.BHD),
                Money.of("1.250", CurrencyCode.BHD).timesRate("0.0004"));
    }

    @Test
    void minorUnitConversionIsExact() {
        assertEquals(10000L, Money.of("10.000", CurrencyCode.BHD).toMinorUnits());
        assertEquals(Money.of("3.334", CurrencyCode.BHD), Money.ofMinorUnits(3334, CurrencyCode.BHD));
    }

    @Test
    void signPredicates() {
        assertTrue(Money.of("-0.01", CurrencyCode.AED).isNegative());
        assertTrue(Money.zero(CurrencyCode.AED).isZero());
        assertTrue(Money.of("0.01", CurrencyCode.AED).isPositive());
    }
}
