package com.ledger.core.service.application;

import com.ledger.core.service.domain.money.Money;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministically splits a monetary total into N instalments using exact
 * minor-unit integer arithmetic:
 *
 * <pre>
 *   base      = totalMinorUnits / count        (integer division)
 *   remainder = totalMinorUnits % count        (0 &lt;= remainder &lt; count)
 *   instalment i (0-based) = base + (i &gt;= count - remainder ? 1 : 0) minor units
 * </pre>
 *
 * Residual minor units are allocated to the LAST instalment(s), so the sum of
 * the parts always equals the total exactly — money is neither created nor
 * destroyed. Example: BHD 10.000 (10000 minor units) / 3
 * = 3333 remainder 1 → [3.333, 3.333, 3.334].
 *
 * <p>Rounding every instalment independently upward (3 x BHD 3.334 = 10.002)
 * would CREATE BHD 0.002 from nothing and is therefore rejected as a policy.
 */
public final class InstalmentAllocator {

    private InstalmentAllocator() {
    }

    public static List<Money> allocate(Money total, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("instalment count must be >= 1, got " + count);
        }
        if (total.isNegative()) {
            throw new IllegalArgumentException("instalment total must not be negative: " + total);
        }
        long totalMinor = total.toMinorUnits();
        long base = totalMinor / count;
        long remainder = totalMinor % count;

        List<Money> parts = new ArrayList<>(count);
        long allocated = 0;
        for (int i = 0; i < count; i++) {
            long units = base + (i >= count - remainder ? 1 : 0);
            allocated += units;
            parts.add(Money.ofMinorUnits(units, total.currency()));
        }
        if (allocated != totalMinor) {
            // Defensive: with the formulas above this is unreachable, but an
            // accounting allocator must prove conservation, not assume it.
            throw new IllegalStateException(
                    "instalment allocation lost money: total=" + totalMinor + " allocated=" + allocated);
        }
        return parts;
    }
}
