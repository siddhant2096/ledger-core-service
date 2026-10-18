package com.ledger.core.service.domain.entry;

/**
 * Classification of derived ledger entries.
 */
public enum EntryKind {
    /** Direct posting derived from a source CREDIT/DEBIT/SETTLEMENT event. */
    POSTING,
    /** One posting of an instalment credit (source event split into N parts). */
    INSTALMENT_POSTING,
    /** Derived overdraft fee. Value date = the date whose balance went negative. */
    FEE_OVERDRAFT,
    /**
     * Derived compensation of a previously assessed overdraft fee, posted when
     * the corrected books no longer show an overdraft for the fee's value date
     * (e.g. after a back-valued reversal). Append-only: the original fee entry
     * is retained; this entry mirrors its value date with the opposite sign.
     */
    FEE_REVERSAL,
    /** Derived compensation created by a REVERSAL event (opposite sign of the original). */
    REVERSAL_COMPENSATION,
    /** Derived interest capitalization credit (posted once, at end of the window). */
    INTEREST_CAPITALIZATION
}
