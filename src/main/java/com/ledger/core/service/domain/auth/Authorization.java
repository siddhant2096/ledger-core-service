package com.ledger.core.service.domain.auth;

import com.ledger.core.service.domain.money.Money;

import java.util.Objects;

/**
 * Immutable snapshot of an authorization's state. The registry replaces the
 * stored snapshot on each transition; nothing is mutated in place, which
 * keeps replay deterministic and every transition auditable.
 *
 * <p>{@code processedDay} is the day the authorization request was processed.
 * Holds are processing-time constructs (see AMBIGUITIES.md): a hold affects
 * available balance from its processing day onward, regardless of value date.
 *
 * @param settledAmount total amount settled so far; zero until settled
 * @param settledOnDay  processing day of the settlement; {@code null} if unsettled
 */
public record Authorization(
        String id,
        String accountId,
        Money holdAmount,
        int processedDay,
        AuthorizationStatus status,
        Money settledAmount,
        Integer settledOnDay) {

    public Authorization {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(holdAmount, "holdAmount");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(settledAmount, "settledAmount");
    }

    public static Authorization decided(String id, String accountId, Money holdAmount,
                                        int processedDay, AuthorizationStatus status) {
        if (status != AuthorizationStatus.APPROVED && status != AuthorizationStatus.DECLINED) {
            throw new IllegalArgumentException("initial status must be APPROVED or DECLINED");
        }
        return new Authorization(id, accountId, holdAmount, processedDay, status,
                Money.zero(holdAmount.currency()), null);
    }

    /** True while this authorization still contributes an active hold. */
    public boolean isActiveHold() {
        return status.isActive();
    }

    /**
     * Returns the post-settlement snapshot. The hold is fully released at
     * settlement time (residual included): after any settlement the
     * authorization is terminal and contributes no further active hold.
     */
    public Authorization settle(Money amount, int processingDay) {
        if (!status.isSettleable()) {
            throw new IllegalStateException("Authorization " + id + " is not settleable: " + status);
        }
        AuthorizationStatus next = amount.compareTo(holdAmount) == 0
                ? AuthorizationStatus.FULLY_SETTLED
                : AuthorizationStatus.PARTIALLY_SETTLED;
        return new Authorization(id, accountId, holdAmount, processedDay, next, amount, processingDay);
    }

    /** Residual hold that would be released by a settlement of {@code settledAmount}. */
    public Money residualHold() {
        if (!status.isActive()) {
            return Money.zero(holdAmount.currency());
        }
        return holdAmount.subtract(settledAmount);
    }
}
