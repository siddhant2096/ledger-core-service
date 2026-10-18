package com.ledger.core.service.domain.account;

import com.ledger.core.service.domain.money.CurrencyCode;
import com.ledger.core.service.domain.money.Money;

import java.util.Objects;

/**
 * A ledger account. Opening balance is part of the account's identity and is
 * treated as an implicit ledger entry with value day 0, so it participates in
 * every daily closing balance by construction.
 */
public record Account(String id, CurrencyCode currency, Money openingBalance) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(openingBalance, "openingBalance");
        if (openingBalance.currency() != currency) {
            throw new CurrencyMismatchOnAccountException(id, currency, openingBalance.currency());
        }
    }

    public static Account zeroBalance(String id, CurrencyCode currency) {
        return new Account(id, currency, Money.zero(currency));
    }

    /** Raised when an account is created with an opening balance in the wrong currency. */
    public static final class CurrencyMismatchOnAccountException extends RuntimeException {
        public CurrencyMismatchOnAccountException(String accountId, CurrencyCode account, CurrencyCode balance) {
            super("Account " + accountId + " is " + account + " but opening balance is " + balance);
        }
    }
}
