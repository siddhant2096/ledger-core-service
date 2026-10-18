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
2. When the processing date advances, run the **evening fee reconciliation** for
   every completed date; after the stream, run it for the remaining dates up to the
   window end. Reconciliation charges newly negative account-dates and appends
   FEE_REVERSAL compensations for charged dates no longer overdrawn.
3. Last, **capitalize interest** (end of the window).

`closingBalance(entries, account, date)` = opening balance + Σ entries for the
account with `valueDate ≤ date`. Because entries carry value dates, a late event
(E7: processed Day 5, valued Day 2) changes the Day-2 closing in the final
projection, and a late derived compensation (FEE_REVERSAL derived Day 6, valued
Day 2) does the same for fee effects — the property the deliberately failing test
demonstrates from the negative side.

## Processing order vs value-date ordering

Two timelines are kept strictly separate:

- **Processing order** decides *when the engine learns* something: authorization
  decisions, fee-assessment timing, and the point-in-time audit trace.
- **Value-date order** decides *where accounting effects land*: balances, fee value
  dates, interest bases.

The report's per-day balances are end-of-window value-date projections; the fee trace
section shows the point-in-time view (what was knowable when each assessment ran).

## Time model

Every `EventEnvelope` carries three explicit time/order concepts (AMBIGUITIES.md #23):

| Field | Type | Meaning |
|---|---|---|
| `streamPosition` | `long` | Stable 1-based stream position. The authoritative ordering key and the unique key for outcomes. Duplicate positions are a data-contract violation (fail fast). |
| `processingDate` | `LocalDate` | The business date the ledger processes the event. Drives evening boundaries (fee assessment) and authorization decisions. |
| `valueDate` | `LocalDate` | The business date the derived postings belong to. Drives balances, fee value dates, interest bases. May precede the processing date (late/back-valued) or follow it (forward-dated). |

Deliberately absent: `Instant occurredAt`/`receivedAt` — the input stream carries no
instants and no rule consumes them; adding them would fabricate data. When ingestion
gains real timestamps, they must be converted through the engine's configured
`ZoneId` (`LedgerEngine.businessDate(Instant)` → `BusinessDates.businessDate`),
never the JVM default zone; equal instants tie-break by `streamPosition`, never by
the clock. The specification's "Day n" maps to `2026-01-0n` (`Scenario.day(n)`);
any six consecutive dates produce byte-identical results because all rules are
relative to the window, not to a calendar.

Events are applied **strictly in stream order** — never sorted by value date.
An event whose processing date is earlier than one already seen (E10 follows E9:
processing date 2026-01-05 after 2026-01-06) is still applied in stream order and
its effects are picked up by the next evening assessment; this is deterministic
because the stream is fixed. Evening fee assessment runs when the processing date
*advances* (and once at window end); interest capitalization runs last, at window
end. A rejected event derives nothing — no entries, no holds, no state.

## Performance notes

Complexity (E = entries, D = window days, A = accounts):

- `closingBalance`: O(E) per call — a full scan of the entry list.
- `reconcileFees` per evening: O(A · D · E); over a full replay O(A · D² · E) worst case.
- `capitalizeInterest`: O(A · D · E).
- Authorization decision: O(E) (one `closingBalance` call).

For this window (A=2, D=6, E≈14) that is trivially fast, so **no index, projection
cache, or snapshot was added**: any cached per-day projection would need careful
invalidation for late value-dated events — exactly the trap the deliberately failing
test demonstrates. If the window grows (thousands of days/entries), the safe upgrade
is a per-account, value-date-ordered entry index (e.g. `NavigableMap<LocalDate,
Money>` of daily deltas with a running Fenwick/tree sum), rebuilt or
incrementally extended on each append; correctness must be proven against the
same test suite, which asserts final balances derived only from the append-only
entry list.

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
  pre-fee Day-2 balance returns to exactly its pre-E7 value (250.00). The fees E7
  triggered are not erased either — each is neutralized by an append-only
  FEE_REVERSAL entry (see below), so the net Day-2 fee effect is 0.00 while the
  full charge+compensation history stays in the entry list.

## Fee reconciliation, idempotency, and interaction with historical value dates

- At each boundary (evening of a completed processing date, and once at window end),
  the engine **reconciles** fees per account: it recomputes the desired fee set from
  scratch over all NON-fee entries (POSTING / INSTALMENT_POSTING /
  REVERSAL_COMPENSATION), walking value dates in ascending order with each desired
  fee participating in later dates' bases (sequential pre-fee semantics,
  AMBIGUITIES.md #1).
- A desired, never-charged date is charged exactly once from the **explicit
  per-currency fee schedule** (`Scenario.OVERDRAFT_FEE_SCHEDULE`; default: ONLY the
  supplied AED 25.00 — no BHD fee is configured because no requirement authorizes
  one). A currency missing from the schedule yields a recorded
  `SkippedFeeAssessment` and no entry — fees are never charged cross-currency and
  never skipped silently. Fee entries carry `valueDate = assessed date`,
  `derivedOnDate = D` (the discovery date, which can be later than the value date
  when a late back-valued event reveals the overdraft; the report prints both).
- A charged date no longer overdrawn in the corrected books is **compensated**: an
  append-only `FEE_REVERSAL` entry of opposite sign, valued on the ORIGINAL fee's
  value date, derived on the boundary date. Original fee entries are never mutated
  or deleted. Each date is charged at most once and compensated at most once; a
  compensated date is never re-charged (AMBIGUITIES.md #28).
- Idempotency keys: `(accountId, valueDate)` for charges and compensations.
  Non-negative dates are re-examined at every boundary, which is what lets E7
  (arriving Day 5) retroactively create a Day-2 fee — exactly once — and lets E9
  (Day 6) trigger its compensation
  (`exactlyOneFeePerNegativeAccountDayWithAssessedDayValueDate`,
  `e9LeavesFeeEntriesIntactAndCompensatesThem`).

## Interest calculation, rounding, and capitalization

- Daily accrual for day D: `max(closingPostFee(D), 0) × 0.0004`, rounded **HALF_UP**
  to the account currency's scale. Negative and zero balances accrue zero.
- The capitalized credit (Day 6, value date Day 6) is **defined as** the exact sum of
  the rounded daily accruals, so the sum always equals the capitalized total and no
  remainder exists to discard (`capitalizedInterestEqualsSumOfRoundedDailyAccrualsExactly`).
- Because fees, fee compensations, and reversals are value-dated entries, they
  automatically flow into interest bases. In this scenario all fees are compensated,
  so bases equal the no-fee balances (Day-2 base 250.00); where a fee remains
  effective at window end it flows into later bases (see `LateEventFeeTest`).

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
| Late value-dated events rewrite history without mutation | `e7RewritesDay2HistoryWithoutTouchingTheStream` |
| Derived entries are immutable but their effect is compensatable | `IntentionalFailureTest` (negative proof), `e9LeavesFeeEntriesIntactAndCompensatesThem` |
| ≤ 1 fee per account-day; fee value date = assessed day | `exactlyOneFeePerNegativeAccountDayWithAssessedDayValueDate` (value/derived dates), `negativeBalanceEarnsNoInterest` (idempotency across evenings: 6 negative days → exactly 6 distinct-dated fees), `LateEventFeeTest` (charge-once/compensate-once) |
| Positive-balance-only interest; Σ daily accruals = capitalized total | `interestAccruesOnPositivePostFeeBalancesOnly`, `capitalizedInterestEqualsSumOfRoundedDailyAccrualsExactly` |
| Unknown/excess/duplicate settlements post nothing and consume nothing | `authZSettlementIsRejectedAndPostsNothing`, `excessSettlementIsRejectedAndHoldSurvives`, `secondSettlementOfSameAuthorizationIsRejected` |
| Duplicate reversal rejected | `duplicateReversalIsRejected` |
| Deterministic replay (state and report) | `replayIsDeterministic`, `reportIsByteIdenticalAcrossReplays` |
| Full six-day report contains every required section | `ReportTest` |

## Alternatives considered (with trade-offs)

1. **Fee lifecycle: boundary-time assessment with append-only compensation
   (CHOSEN, final review)** vs *fees persist forever once assessed* vs *full
   recompute on every replay*. The engine assesses at processing-time boundaries
   (so the point-in-time audit trail exists), and reconciles at each boundary by
   appending FEE_REVERSAL compensation entries for charges the corrected books no
   longer support (so criterion 6 holds in net terms and fee income follows the
   corrected books rather than event arrival order). *Persist-forever* (the policy
   implemented in phases 1–7) keeps the charge history but fails criterion 6 and
   retains fees for overdrafts the corrected books say never happened.
   *Full recompute* satisfies criterion 6 but discards the point-in-time assessment
   trail the assignment's "derived accounting entry" framing calls for. Trade-off of
   the chosen policy: fee entries are immutable but their EFFECT is not permanent,
   so consumers must read fees net of compensations (the report prints both).
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
   On this dataset the alternatives genuinely differ (1.03 vs 1.02 for ACC-001), so
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
- **No calendar model**: business dates are bare `LocalDate`s; production needs
  holiday calendars, cut-off times, and (when ingesting real-time events) the
  `occurredAt`/`receivedAt` instants the envelope deliberately omits today.
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
