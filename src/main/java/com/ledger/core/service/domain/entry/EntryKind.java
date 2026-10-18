package com.ledger.core.service.domain.entry;

/**
 * Classification of derived ledger entries.
 */
public enum EntryKind {
    /** Direct posting derived from a source CREDIT/DEBIT/SETTLEMENT event. */
    POSTING,
    /** One posting of an instalment credit (source event split into N parts). */
    INSTALMENT_POSTING,
    /** Derived overdraft fee. Value day = the day whose balance went negative. */
    FEE_OVERDRAFT,
    /** Derived compensation created by a REVERSAL event (opposite sign of the original). */
    REVERSAL_COMPENSATION,
    /** Derived interest capitalization credit (posted once, at end of the window). */
    INTEREST_CAPITALIZATION
}
