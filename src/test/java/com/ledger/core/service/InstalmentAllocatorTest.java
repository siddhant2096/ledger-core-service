package com.ledger.core.service;

import com.ledger.core.service.application.InstalmentAllocator;
import com.ledger.core.service.domain.money.CurrencyCode;
import com.ledger.core.service.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BHD 10.000 / 3 = 3333 minor units each with remainder 1. The residual minor
 * unit goes to the LAST instalment: [3.333, 3.333, 3.334], totalling exactly
 * 10.000. Three times 3.334 would be 10.002 — money created from nothing.
 */
class InstalmentAllocatorTest {

    @Test
    void tenBhdSplitsExactlyWithResidualOnLastInstalment() {
        List<Money> parts = InstalmentAllocator.allocate(Money.of("10.000", CurrencyCode.BHD), 3);
        assertEquals(List.of(
                Money.of("3.333", CurrencyCode.BHD),
                Money.of("3.333", CurrencyCode.BHD),
                Money.of("3.334", CurrencyCode.BHD)), parts);
        Money sum = parts.stream().reduce(Money.zero(CurrencyCode.BHD), Money::add);
        assertEquals(Money.of("10.000", CurrencyCode.BHD), sum);
    }

    @Test
    void exactlyDivisibleTotalsProduceIdenticalInstalments() {
        List<Money> parts = InstalmentAllocator.allocate(Money.of("9.00", CurrencyCode.AED), 3);
        assertEquals(List.of(
                Money.of("3.00", CurrencyCode.AED),
                Money.of("3.00", CurrencyCode.AED),
                Money.of("3.00", CurrencyCode.AED)), parts);
    }

    @Test
    void remainderOfTwoMinorUnitsGoesToTheLastTwoInstalments() {
        // 1001 minor units / 3 = 333 remainder 2 -> [333, 334, 334].
        List<Money> parts = InstalmentAllocator.allocate(Money.of("10.01", CurrencyCode.AED), 3);
        assertEquals(List.of(
                Money.of("3.33", CurrencyCode.AED),
                Money.of("3.34", CurrencyCode.AED),
                Money.of("3.34", CurrencyCode.AED)), parts);
        Money sum = parts.stream().reduce(Money.zero(CurrencyCode.AED), Money::add);
        assertEquals(Money.of("10.01", CurrencyCode.AED), sum);
    }

    @Test
    void rejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> InstalmentAllocator.allocate(Money.of("10.00", CurrencyCode.AED), 0));
        assertThrows(IllegalArgumentException.class,
                () -> InstalmentAllocator.allocate(Money.of("-1.00", CurrencyCode.AED), 3));
    }
}
