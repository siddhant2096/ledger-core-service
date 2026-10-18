# ARCHITECTURE

## Overview

A deterministic, in-memory, event-sourced ledger. One pass over an immutable event
stream derives: ledger entries, overdraft fee entries, an interest capitalization
entry, authorization lifecycle state, and rejection records. Replaying the same
stream always reproduces byte-identical state (proven by
`ReplayTest.replayIsDeterministic` and `ReportTest.reportIsByteIdenticalAcrossReplays`).

## Core domain objects and responsibilities

| Type | Package | Responsibility |
|---|---|---|
| `CurrencyCode` | `domain.money` | AED (2dp) / BHD (3dp); scale is part of identity |
| `Money` | `domain.money` | Immutable exact decimal (`BigDecimal`), fixed currency scale, same-currency arithmetic only, single rounding site (`timesRate`, HALF_UP) |
| `Account` | `domain.account` | Identity + currency + opening balance (an implicit day-0 entry) |
| `LedgerEvent` (sealed) | `domain.event` | Credit / Debit / AuthorizationRequest / Settlement / Reversal / InstalmentCredit payloads |
| `EventEnvelope` | `domain.event` | eventId + **processingDay** + **valueDay** around a payload |
| `LedgerEntry` | `domain.entry` | Derived posting: signed amount, valueDay, derivedOnDay, kind, source id |
| `Authorization` | `domain.auth` | Immutable lifecycle snapshot (APPROVED/DECLINED/PARTIALLY_SETTLED/FULLY_SETTLED) |
| `EventLog` | `domain.ledger` | Append-only source-event store (unmodifiable view, no delete API) |
| `LedgerEngine` | `application` | The replay: validation → entries; evening fee assessment; final interest capitalization |
| `InstalmentAllocator` | `application` | Exact minor-unit split with residual on the last instalment(s) |
| `ReplayResult` | `application` | Everything derived by one replay (entries, outcomes, auths, traces) |
| `ReportGenerator` | `application` | Deterministic plain-text rendering of a `ReplayResult` |

## Immutable source events vs derived ledger entries

This is the central modelling decision. Source events (`EventEnvelope`) are facts:
they are appended to `EventLog` in stream order and never mutated or deleted —
**even when business validation fails** (a rejected event stays in the log; its
`Outcome.Rejected` is derived state). Every balance-relevant effect is a separate
`LedgerEntry` carrying `sourceEventId`, `valueDay`, `derivedOnDay`, and `EntryKind`
(POSTING, INSTALMENT_POSTING, FEE_OVERDRAFT, REVERSAL_COMPENSATION,
INTEREST_CAPITALIZATION). E9 therefore does not "undo" E7: it derives a new
`REVERSAL_COMPENSATION` entry of +620.00 valued on Day 2, while E7's −620.00 entry
remains. Proven by `ReplayTest.e9CompensatesE7ExactlyWithoutDeletingIt`.

## Event replay and projection construction

`LedgerEngine.replay(stream)`:

1. Apply events **strictly in stream order** (the specification's "precise
   processing order"; same-day order = stream order). Each event is validated and
   either derives entries or is recorded as rejected.
2. When the processing day advances, run the **evening fee assessment** for every
   completed day; after the stream, run it for the remaining days up to the horizon.
3. Last, **capitalize interest** (end of Day 6).

`closingBalance(entries, account, day)` = opening balance + Σ entries for the account
with `valueDay ≤ day`. Because entries carry value days, a late event (E7: processed
Day 5, valued Day 2) changes the Day-2 closing in the final projection. This is the
replay-correctness property the deliberately failing test demonstrates from the
negative side.

## Processing order vs value-date ordering

Two timelines are kept strictly separate:

- **Processing order** decides *when the engine learns* something: authorization
  decisions, fee-assessment timing, and the point-in-time audit trace.
- **Value-date order** decides *where accounting effects land*: balances, fee value
  dates, interest bases.

The report's per-day balances are end-of-window value-date projections; the fee trace
section shows the point-in-time view (what was knowable when each assessment ran).

## Authorization state machine and active-hold calculation

```
request → APPROVED —settle(amount == hold)→ FULLY_SETTLED
        → APPROVED —settle(amount <  hold)→ PARTIALLY_SETTLED
        → DECLINED (terminal)
```

- Approval rule: `available = ledgerBalanceKnownAtProcessing − activeHolds`; approve
  iff `available − hold ≥ 0` (exact-zero is approved — `BoundaryAndValidationTest.
  exactZeroAvailableBalanceIsApproved`).
- A hold is a processing-time construct: it is active from its processing day until
  its settlement day. Active holds never alter the ledger balance — only available
  balance (`ReportGenerator` shows both).
- Settlement releases the **entire** hold, including any residual (185 of 200 settles
  Auth-A as PARTIALLY_SETTLED and releases the residual 15.00). Settled states are
  terminal, which structurally prevents double consumption.

## Settlement validation and posting atomicity

Validation happens fully **before** any entry is appended (validate-then-post; there
is no partial posting to roll back — atomicity by construction):

1. authorization exists (else `UNKNOWN_AUTHORIZATION`),
2. belongs to the same account,
3. status is settleable (APPROVED),
4. amount positive,
5. amount ≤ hold (else `SETTLEMENT_EXCEEDS_AUTHORIZED_AMOUNT` — rejected in full,
   never clipped).

Only then is the debit entry appended and the authorization transitioned atomically
in the same processing step. An invalid settlement creates **no** entry and consumes
**no** auth state (`BoundaryAndValidationTest.excessSettlementIsRejectedAndHoldSurvives`).

## Reversal semantics and duplicate protection

- Reversal = **exact accounting compensation**: a new entry with the negated sum of
  the target event's postings, value date taken from the reversal event. Deletion or
  mutation of the original is forbidden by design (no API exists for it).
- Duplicate protection: a `Set` of already-reversed event ids; a second reversal of
  the same target is REJECTED (`duplicateReversalIsRejected`).
- Only financial postings (POSTING / INSTALMENT_POSTING) are reversible; reversing a
  fee or an unknown id is rejected.
- Effect on history: because the compensation carries E9's value date (Day 2), the
  pre-fee Day-2 balance returns to exactly its pre-E7 value (250.00). Assessed fees
  are **not** removed (see below), so the post-fee Day-2 closing is 225.00.

## Fee assessment idempotency and interaction with historical value dates

- Each evening (processing day D), every account's value days `1..D` are examined in
  ascending order. A day whose **pre-fee** closing balance (which includes previously
  derived fee entries) is negative earns exactly one fee: 25 in the account's own
  currency, `valueDay = assessed day`, `derivedOnDay = D`.
- Idempotency key: `(accountId, valueDay)`, marked **only when a fee is actually
  charged**. Non-negative days are re-examined on later evenings, which is what lets
  E7 (arriving Day 5) retroactively create a Day-2 fee — exactly once
  (`exactlyOneFeePerNegativeAccountDayWithAssessedDayValueDate`).
- Fees are never retroactively removed: E9 restores the underlying Day-2 balance but
  the Day-2 fee stands (`e9DoesNotRemoveAssessedFees`). Refunding would be a separate
  explicit business event, not a rewrite.

## Interest calculation, rounding, and capitalization

- Daily accrual for day D: `max(closingPostFee(D), 0) × 0.0004`, rounded **HALF_UP**
  to the account currency's scale. Negative and zero balances accrue zero.
- The capitalized credit (Day 6, value date Day 6) is **defined as** the exact sum of
  the rounded daily accruals, so the sum always equals the capitalized total and no
  remainder exists to discard (`capitalizedInterestEqualsSumOfRoundedDailyAccrualsExactly`).
- Because fees and reversals are value-dated entries, they automatically flow into
  interest bases: Day-2 base is 225.00 (post-fee), not 250.00.

## Currency-safe decimal arithmetic

`Money` wraps `BigDecimal`, pinned to the currency's scale at construction with
`RoundingMode.UNNECESSARY` (a value that doesn't fit the scale is a bug and throws,
never silently rounded). All binary operations check currency equality and throw
`CurrencyMismatchException`; the engine additionally pre-validates event currency
against account currency and rejects mismatches as business errors
(`crossCurrencyEventIsRejectedNotPosted`). There is no FX conversion anywhere, so
cross-currency arithmetic is impossible rather than merely guarded against.

## Invariants and how the tests prove them

| Invariant | Test |
|---|---|
| AED=2dp, BHD=3dp; exact decimal arithmetic | `MoneyTest` |
| Money conservation in instalments (Σ parts = total exactly) | `InstalmentAllocatorTest`, `bhdInstalmentsConserveExactlyTenBhd` |
| Event log append-only | `eventLogIsAppendOnly` |
| Late value-dated events rewrite history without mutation | `e7RewritesDay2HistoryWithoutTouchingTheStream`, `IntentionalFailureTest` (negative proof) |
| ≤ 1 fee per account-day; fee value date = assessed day | `exactlyOneFeePerNegativeAccountDayWithAssessedDayValueDate`, `negativeBalanceEarnsNoInterest` |
| Positive-balance-only interest; Σ daily accruals = capitalized total | `interestAccruesOnPositivePostFeeBalancesOnly`, `capitalizedInterestEqualsSumOfRoundedDailyAccrualsExactly` |
| Unknown/excess/duplicate settlements post nothing and consume nothing | `authZSettlementIsRejectedAndPostsNothing`, `excessSettlementIsRejectedAndHoldSurvives`, `secondSettlementOfSameAuthorizationIsRejected` |
| Duplicate reversal rejected | `duplicateReversalIsRejected` |
| Deterministic replay (state and report) | `replayIsDeterministic`, `reportIsByteIdenticalAcrossReplays` |
| Full six-day report contains every required section | `ReportTest` |

## Alternatives considered (with trade-offs)

1. **Fee lifecycle: point-in-time assessment, fees persist (CHOSEN)** vs *full
   recompute on every replay*. Chosen because accounting systems do not erase
   assessed charges — a fee legitimately assessed against the books as known stands,
   and a correction is a new event. Trade-off: final balances differ from a
   "clean-slate" recompute (criterion 6 fails), and downstream consumers must
   understand that fees are sticky. Recompute would be simpler to reason about but
   makes fee income depend on event arrival order and erases audit history.
2. **Authorization validation against processing-time books (CHOSEN)** vs *against
   the final value-date projection* or *against the authorization's value date*.
   Processing-time books is the only option that is causally consistent (you cannot
   decline a hold using information that has not arrived). Consequence: Auth-B is
   DECLINED because E7 was already processed when E8 arrived — even though E9 later
   compensates E7. Alternative (re-evaluate after late events) would make
   authorization decisions unstable and was rejected.
3. **Residual hold released at settlement (CHOSEN)** vs *residual stays active for
   further partial settlements (multi-capture)*. Chosen for simplicity and because
   the window contains one settlement per authorization; multi-capture would require
   per-settlement partial release tracking and a "remaining capturable" invariant.
   Trade-off documented in AMBIGUITIES.md (#6).
4. **Capitalized interest = Σ rounded daily accruals (CHOSEN)** vs *round the
   aggregate and allocate the residual to the last day*. Chosen because it makes the
   identity Σ = capitalized exact by construction and matches per-day statements.
   On this dataset the alternatives genuinely differ (0.93 vs 0.92 for ACC-001), so
   the choice is observable, not cosmetic.
5. **Rejected events stay in the log with derived REJECTED outcomes (CHOSEN)** vs
   *excluding them from the log*. Keeping them preserves the complete audit trail and
   keeps replay total (every input event has an outcome); exclusion would make the
   log "cleaner" but lose evidence and complicate idempotent reprocessing.
6. **Stream order is the processing order (CHOSEN after a test failure)** vs
   *grouping by processing day*. Grouping reorders the stream (E10 before E9) and was
   abandoned; stream-order processing with evening assessments on day advancement is
   both spec-faithful and handles the deliberately out-of-day-order E10.

## Known limitations / production-readiness gaps

- **No persistence, concurrency, or I/O**: a real system needs a durable event store
  (with idempotent consumer keys), optimistic/pessimistic concurrency on account
  aggregates, and snapshotting for long streams (snapshots must be invalidated for
  historical value days — the trap the intentional-failure test demonstrates).
- **No calendar model**: "day" is an integer; production needs business calendars,
  time zones, and cut-off times.
- **Fee policy is minimal**: no fee caps, no fee-on-fee compounding rules beyond the
  documented one, no fee refunds/waivers (would be explicit events).
- **Interest**: no day-count conventions (ACT/360 etc.), no tiered/negative rates,
  capitalization timing hard-wired to the horizon end.
- **No multi-capture settlements, hold expiry, or auth amount adjustments**; the auth
  state machine would need extension events (release, expire, increment).
- **Out-of-order processing days** (like E10 after E9) are handled by the next
  evening's assessment, not by re-opening the earlier evening; a production engine
  would define a lateness policy explicitly.
- **No currency conversion**: multi-currency transfers would require an FX rate
  source and a conversion event type.
