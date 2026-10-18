# AMBIGUITIES

Every meaningful ambiguity found in the specification, the policy chosen, why, at
least one plausible alternative, the consequence, and the test that enforces the
choice. None of these policies is presented as mandated by the requirements; each is
a documented, defensible decision.

---

## 1. Does fee assessment use the pre-fee or post-fee closing balance?

1. **Ambiguity**: "that day's closing ledger balance is negative" — closing balance
   computed before or after that day's own fee?
2. **Chosen policy**: pre-fee. A day's own fee cannot participate in the balance that
   triggers it (that would be circular). Previously assessed fees from *earlier* value
   days DO participate.
3. **Why**: the circular version has no fixed point (a −10 balance + 25 fee is still
   negative, so charge again?). Pre-fee is the standard overdraft-assessment semantic:
   the fee is a consequence of the day's activity, not a cause of itself.
4. **Alternative**: post-fee assessment with a max-one-fee-per-day guard — same result
   for the first fee, but conceptually circular and fragile.
5. **Consequence**: deterministic, terminating assessment; exactly one fee per
   account-day.
6. **Test**: `ReplayTest.exactlyOneFeePerNegativeAccountDayWithAssessedDayValueDate`.

## 2. Can a fee itself trigger another fee?

1. **Ambiguity**: if a fee pushes an account deeper into the negative, does it cascade?
2. **Chosen policy**: within one day, no (the idempotency key allows one fee per
   account-day). Across days, yes: a still-negative balance the next day — even if
   negativity is partly caused by earlier fees — earns that day its own fee.
3. **Why**: matches "once per account per day"; the cross-day effect is not a cascade
   but a new assessment of a new day.
4. **Alternative**: never let fees contribute to fee bases (fee-exempt balances).
   Rejected: the spec says closing balance includes "previously generated fee entries".
5. **Consequence**: a persistent −10 overdraft costs 25/day, and fees compound the
   debt day over day (BoundaryAndValidationTest shows −10.00 becoming 6 × 25.00 of
   fees over six days).
6. **Test**: `BoundaryAndValidationTest.negativeBalanceEarnsNoInterest`.

## 3. When is an authorization validated against balances, given late value-dated events?

1. **Ambiguity**: Auth-B (Day 5) is requested after E7 (valued Day 2) is processed.
   Should validation see E7? Should it see E9 (arrives Day 6)?
2. **Chosen policy**: validation uses the books as **known at processing time**: all
   entries derived from events processed so far (value day ≤ processing day) plus fees
   already derived. E7 counts; E9 cannot count (it has not happened yet).
3. **Why**: causal consistency — a decision can only use information that exists when
   it is made. Authorization is an operational risk decision, not a projection.
4. **Alternatives**: (a) validate against the final end-of-window projection — would
   approve Auth-B (390.00 final Day-5 balance) but is acausal; (b) validate against
   value-date-only balances excluding late events — approves Auth-B (465.00), ignoring
   a known fact (E7), which is operationally indefensible.
5. **Consequence**: Auth-B is DECLINED (known ledger −155.00; −155.00 − 90.00 < 0).
   This is visible in the report's Day-5 section with the exact decision inputs.
6. **Test**: `ReplayTest.authBIsDeclinedBecauseTheAccountWasOverdrawnAtProcessingTime`.

## 4. Is authorization state based on processing time or value date?

1. **Ambiguity**: E3 carries "value date Day 2" equal to its processing day, but in
   general they can differ; which timeline activates a hold?
2. **Chosen policy**: processing time. A hold is active from the processing day of the
   approval until the processing day of its settlement. The value date is recorded but
   does not shift the hold window.
3. **Why**: holds exist to reserve spending power *now*; a hold cannot reserve funds
   retroactively. Value dates order accounting entries, not operational controls.
4. **Alternative**: value-date-effective holds — would let a back-valued authorization
   change historical available balances, making past approval decisions unstable.
5. **Consequence**: Auth-A's hold is active on Days 2–3 and released on Day 4 (its
   settlement's processing day).
6. **Test**: `ReplayTest.authASettlesPartiallyAndReleasesTheHold` + report assertions.

## 5. Does settlement require a known authorization?

1. **Ambiguity**: is a settlement with sufficient account funds but no matching
   authorization valid?
2. **Chosen policy**: no. Validity derives from the authorization, never from the
   account balance ("do not assume a settlement is valid merely because its account
   has sufficient funds").
3. **Why**: the authorization is the contract the settlement executes; without it the
   settlement is an unmatched funds movement — operationally an exception.
4. **Alternative**: accept-and-reconcile-later (post the debit, flag unmatched).
   Rejected: silently moves money without a mandate.
5. **Consequence**: E6 (Auth-Z) is REJECTED; AED 180.00 never leaves ACC-001.
6. **Test**: `ReplayTest.authZSettlementIsRejectedAndPostsNothing`.

## 6. Does a settlement consume the hold, release the entire hold, or keep a residual?

1. **Ambiguity**: settling 185.00 against a 200.00 hold — what happens to the 15.00?
2. **Chosen policy**: the settlement posts −185.00 and releases the **entire** hold;
   the residual 15.00 is released and the authorization becomes terminal
   (PARTIALLY_SETTLED).
3. **Why**: matches the common card-acquiring model (one capture per authorization;
   remaining hold drops off). It makes double consumption structurally impossible and
   keeps the state machine tiny.
4. **Alternative**: multi-capture — residual stays active for further settlements up
   to the hold total. More flexible (partial shipments), but requires tracking
   remaining-capturable amounts and is unnecessary for this window.
5. **Consequence**: a second settlement against Auth-A would be REJECTED
   (`AUTHORIZATION_NOT_SETTLEABLE`); available balance rises by the released residual.
6. **Test**: `BoundaryAndValidationTest.secondSettlementOfSameAuthorizationIsRejected`.

## 7. Can a settlement exceed the authorized amount?

1. **Ambiguity**: is a 100.01 settlement against a 100.00 hold accepted, clipped, or
   rejected?
2. **Chosen policy**: REJECTED in full (`SETTLEMENT_EXCEEDS_AUTHORIZED_AMOUNT`).
3. **Why**: silently clipping changes the amount the merchant asked for — a data
   integrity issue; accepting overages defeats the purpose of the hold.
4. **Alternative**: clip to the hold amount and settle 100.00. Plausible in card
   systems with tolerance percentages, but it hides a discrepancy that should surface.
5. **Consequence**: the hold survives an excess settlement attempt; no funds move.
6. **Test**: `BoundaryAndValidationTest.excessSettlementIsRejectedAndHoldSurvives`.

## 8. How are unknown/invalid settlements recorded?

1. **Ambiguity**: rejected silently, rejected with an error result, or posted with a
   flag?
2. **Chosen policy**: the source event stays in the append-only log; the replay
   records an `Outcome.Rejected` with a machine-readable reason; no ledger entry and
   no authorization state change result; the report prints the rejection.
3. **Why**: full audit trail + clean books. The event happened (input fact); its
   rejection is the derived decision.
4. **Alternative**: drop the event from the log entirely. Rejected: loses evidence and
   breaks "every input has an outcome" totality.
5. **Consequence**: E6 appears in the Day-4 section and in the summary's rejected
   operations list.
6. **Test**: `ReportTest.reportPrintsEveryEventIncludingTheRejectedOne`.

## 9. Is a reversal a compensating entry or a historical correction?

1. **Ambiguity**: does reversal delete/neutralize the original or add an opposite entry?
2. **Chosen policy**: exact accounting compensation — a new immutable
   `REVERSAL_COMPENSATION` entry with the negated sum of the target's postings, value
   date taken from the reversal event (Day 2 for E9). The original stays, untouched.
3. **Why**: the spec forbids deletion; compensation preserves the complete audit trail
   and makes "what happened and when" reconstructable.
4. **Alternative**: mark-and-suppress (original kept but excluded from projections).
   Rejected: projections then depend on a mutable flag; compensation is pure
   double-entry bookkeeping.
5. **Consequence**: Day-2 pre-fee balance returns to exactly +250.00 after E9; both
   E7's −620.00 and E9's +620.00 are visible in the entry list.
6. **Test**: `ReplayTest.e9CompensatesE7ExactlyWithoutDeletingIt`.

## 10. How does a reversal affect previously assessed fees?

1. **Ambiguity**: E9 cancels E7's debit — is the Day-2 overdraft fee (assessed because
   of E7) refunded?
2. **Chosen policy**: no. Assessed fee entries are immutable; they stand. A refund
   would require an explicit new business event (e.g. FEE_REVERSAL), which is not in
   the stream.
3. **Why**: accounting-grade conservatism — a fee legitimately assessed against the
   books as they stood is revenue that was earned/notified; rewriting it erases audit
   history. Fee persistence is the whole point of "derived entry, replayable,
   idempotent".
4. **Alternative**: recompute fees on every replay (fees as pure function of the final
   stream) — then E9 would erase all three fees and criterion 6 would hold. Rejected:
   makes fee income depend on event arrival order and rewrites customer-visible
   charges retroactively.
5. **Consequence**: final Day-2 closing is 225.00, not 250.00; cumulative fees are
   75.00 after E9 (criterion 6 is therefore REJECTED — see REJECTED.md).
6. **Test**: `ReplayTest.e9DoesNotRemoveAssessedFees`.

## 11. Are fee assessments recomputed after late events and reversals?

1. **Ambiguity**: when E7 arrives on Day 5, do we revisit Days 1–4?
2. **Chosen policy**: yes for days never fee-assessed (a late event can reveal a
   historical overdraft — assessed on the current evening, valued on the historical
   day); never for days already assessed (no duplicates, no removals).
3. **Why**: combines correctness (back-valued overdrafts are caught) with stability
   (charges never change once made).
4. **Alternative**: never revisit — misses E7's Day-2 overdraft entirely and fails the
   spec's core "late event affects earlier days" requirement.
5. **Consequence**: all three fees are derived on the evening of processing Day 5,
   valued on Days 2, 4, 5.
6. **Test**: `BoundaryAndValidationTest.lateEventIsReflectedInFeeAssessmentOnlyAfterItIsProcessed`.

## 12. Which balance is the interest base?

1. **Ambiguity**: pre-fee or post-fee closing balance?
2. **Chosen policy**: post-fee closing (the actual ledger balance, per the spec's
   definition of ledger balance as including "previously generated fee entries").
3. **Why**: consistency — one definition of ledger balance used everywhere (fees,
   interest, available balance, report).
4. **Alternative**: pre-fee base — would make Day-2 accrual 0.10 instead of 0.09 and
   the capitalized total 0.94 instead of 0.93.
5. **Consequence**: interest bases 250.00 / 225.00 / 625.00 / 415.00 / 390.00 / 390.00;
   capitalized AED 0.93.
6. **Test**: `ReplayTest.interestAccruesOnPositivePostFeeBalancesOnly`.

## 13. How is daily interest rounded and how is any residual reconciled?

1. **Ambiguity**: round each day, or round the aggregate? If both, which wins?
2. **Chosen policy**: round each day's accrual HALF_UP to the currency scale; the
   capitalized total is **defined as** the exact sum of the rounded dailies. No
   remainder can exist, so nothing is discarded.
3. **Why**: the spec requires Σ rounded dailies == capitalized total; defining the
   total as the sum satisfies it by construction and matches per-day statements a
   customer could verify.
4. **Alternative**: round the aggregate (2295.00 × 0.0004 = 0.918 → 0.92) and allocate
   the residual to the last day — gives 0.92 vs our 0.93, an observable difference,
   and makes some daily statement line differ from what was capitalized for it.
5. **Consequence**: ACC-001 capitalized 0.93; ACC-002 capitalized 0.008.
6. **Test**: `ReplayTest.capitalizedInterestEqualsSumOfRoundedDailyAccrualsExactly`.

## 14. How are three "equal" BHD instalments allocated?

1. **Ambiguity**: 10.000 / 3 is not representable at 3dp.
2. **Chosen policy**: minor-unit integer split; residual minor units to the LAST
   instalment(s): 3.333 + 3.333 + 3.334 = 10.000 exactly.
3. **Why**: conservation of money is non-negotiable; putting the residual last matches
   common billing practice (final instalment absorbs rounding).
4. **Alternatives**: residual to the FIRST instalment (equally valid convention);
   round each up (3 × 3.334 = 10.002) — creates BHD 0.002 from nothing, rejected as
   an accounting error, not a policy choice.
5. **Consequence**: E10 derives three INSTALMENT_POSTING entries whose sum is exactly
   the event total; the allocator defensively re-verifies conservation.
6. **Test**: `InstalmentAllocatorTest`, `ReplayTest.bhdInstalmentsConserveExactlyTenBhd`.

## 15. Do fees apply to non-AED accounts?

1. **Ambiguity**: the fee is specified as "AED 25.00", but ACC-002 is a BHD account.
2. **Chosen policy**: the fee is 25 **in the account's own currency** (BHD 25.000 for
   ACC-002). An AED amount is never charged to a BHD account — there is no FX rate in
   the system, so any cross-currency charge would be inventing data.
3. **Why**: the least-surprise policy that respects currency safety; the numeric
   amount is the business constant, the currency follows the account.
4. **Alternatives**: (a) no overdraft fees for non-AED accounts (fee policy is
   AED-only) — defensible but arbitrary; (b) convert via an FX table — requires data
   the spec does not supply.
5. **Consequence**: none in this scenario (ACC-002 never goes negative); the behavior
   is pinned by a dedicated test with a synthetic negative BHD account.
6. **Test**: `BoundaryAndValidationTest.bhdAccountIsChargedBhdFeeNeverAed`.

## 16. Is an event accepted into the log if business validation fails?

1. **Ambiguity**: does "append-only event log" contain rejected events?
2. **Chosen policy**: yes — every input event is appended; the REJECTED outcome is
   derived state recorded alongside (not in) the log. Rejected events derive no ledger
   entries.
3. **Why**: totality (every input has exactly one outcome) and auditability; the log
   records what *happened*, outcomes record what was *decided*.
4. **Alternative**: keep only valid events — loses the record that Auth-Z was
   attempted (a fraud-relevant fact).
5. **Consequence**: the log always contains exactly E1..E10; `rejectedEvents()` is
   derived by filtering outcomes.
6. **Test**: `ReplayTest.allTenEventsAreInTheLogInStreamOrder`,
   `ReplayTest.onlyE6IsRejected`.

## 17. How does same-day event ordering work?

1. **Ambiguity**: multiple events share a processing day (E1/E2, E5/E6, E7/E8/E10).
2. **Chosen policy**: strict stream order within the day; evening assessment runs
   after the day's last event.
3. **Why**: the spec mandates "the exact stream order supplied"; any intra-day
   reordering (e.g. credits before debits) would be inventing a rule.
4. **Alternative**: sort by value date or by type within a day — rejected as
   speculative.
5. **Consequence**: E7 is applied before E8 on Day 5, so Auth-B's decline reflects the
   overdraft; E5 is applied before E6 on Day 4 (order irrelevant there, but preserved).
6. **Test**: `ReplayTest.allTenEventsAreInTheLogInStreamOrder` (fails if the engine
   reorders — it caught exactly that defect during development, see WORKLOG).

## 18. Do rejected events affect derived projections?

1. **Ambiguity**: does a rejected settlement (E6) influence balances, holds, fees, or
   interest?
2. **Chosen policy**: no, in no way. Rejected events contribute zero entries, zero
   hold changes, and therefore cannot influence fees or interest.
3. **Why**: rejection means "this did not happen, accounting-wise".
4. **Alternative**: record a zero-amount marker entry — rejected as noise in the
   books.
5. **Consequence**: ACC-001 Day-4 closing is 415.00 (only E5's −185.00 and fees), not
   235.00 (which would wrongly include E6's −180.00).
6. **Test**: `ReplayTest.authZSettlementIsRejectedAndPostsNothing` asserts the Day-4
   balance.

## 19. What value date do E10's instalments carry?

1. **Ambiguity**: E10 specifies a processing day (Day 5) but no value date.
2. **Chosen policy**: each instalment is valued on the processing day (Day 5). The
   event carries `instalmentCount` and the total; the schedule is derived
   deterministically.
3. **Why**: the least-invention default (value date = processing date is the norm when
   unspecified); the assumption is stated on `Scenario` and in the report.
4. **Alternative**: spread instalments across Days 5/6/7 — invents a schedule the
   spec does not mention and pushes postings outside the window.
5. **Consequence**: ACC-002 is 0.000 on Days 1–4 and 10.000 from Day 5; interest
   accrues on Days 5–6 only.
6. **Test**: `ReplayTest.bhdInstalmentsConserveExactlyTenBhd` asserts valueDay == 5
   for all three.

## 20. What happens when an event's processing day is EARLIER than one already seen?

1. **Ambiguity**: E10 (processing Day 5) appears in the stream AFTER E9 (processing
   Day 6). Strictly increasing days cannot be assumed.
2. **Chosen policy**: stream order is authoritative; the event is applied when
   reached. Evening assessments run when the processing day *advances*; a
   late-for-its-day event is simply picked up by the next assessment run (its effects
   are still correct in the final projection because projections are value-date based).
3. **Why**: the spec's numbered list is explicit ("replay in this precise processing
   order") and processing days are metadata on the event, not a reordering license.
   (Grouping by processing day was implemented first and abandoned when the
   stream-order test caught it reordering E10 before E9 — see WORKLOG.)
4. **Alternative**: reject non-monotone processing days — would reject a spec-mandated
   input; grouping by day — violates the mandated order.
5. **Consequence**: for this stream, numbers are identical under both readings (E10
   touches only ACC-002 and no fee ever applies), but the log order now matches the
   mandated E1..E10 sequence.
6. **Test**: `ReplayTest.allTenEventsAreInTheLogInStreamOrder`.

## 21. Can a reversal target a non-financial or multi-part event?

1. **Ambiguity**: may one reverse an authorization, a fee, or a 3-part instalment?
2. **Chosen policy**: only financial postings (POSTING, INSTALMENT_POSTING) are
   reversible; the compensation is the negated SUM of the target event's entries (so a
   multi-part event is compensated in full, as one entry). Reversing a fee or interest
   entry is REJECTED (`REVERSAL_UNSUPPORTED_TARGET`). Reversal-of-a-reversal is
   REJECTED the same way (a compensation entry is not a POSTING). Reversing a rejected
   event or an authorization (neither derives entries) lands on
   `REVERSAL_TARGET_UNKNOWN` — consistent with "rejected means it did not happen".
3. **Why**: authorizations are lifecycle objects, not amounts — "reversing" one is a
   release/cancel concept the spec does not define; fees/interest are derived and must
   be corrected by explicit business events, not raw reversal.
4. **Alternative**: allow reversing anything including derived entries — would let
   callers bypass the fee-persistence policy by hand. Also considered: per-entry
   compensation for multi-part events (3 separate compensations for an instalment
   credit) — rejected in favour of one sum-compensation because the reversal event
   carries a single value date and the sum is exactly conserved either way; one entry
   keeps the books cleaner.
5. **Consequence**: E9 (targeting E7, a simple DEBIT) compensates exactly −620.00 →
   +620.00; reversing E10 would compensate exactly −10.000 BHD.
6. **Test**: `ReversalTest` (all eight cases), including
   `multiEntryInstalmentCreditIsReversedInFullAsOneCompensation`,
   `reversalOfAReversalIsRejected`, `reversingDerivedFeeEntriesIsUnsupported`,
   `crossAccountReversalIsRejected`.

## 22. When is reversal state mutated? (ordering of validation vs state update)

1. **Ambiguity**: is the target id marked "reversed" when the reversal is RECEIVED or
   when it has been validated and posted?
2. **Chosen policy**: validate-then-post-then-mark. The duplicate-protection set is
   updated only after the compensation entry is appended; a rejected reversal mutates
   nothing.
3. **Why**: the original implementation marked the id first — a failed reversal of a
   not-yet-existing target poisoned the id, and a later legitimate reversal (after the
   real event arrived) was wrongly rejected as DUPLICATE_REVERSAL. State that records
   "X has been reversed" must only ever describe successful reversals.
4. **Alternative**: keep a separate "attempted" set — adds state without value; the
   only meaningful fact is "successfully reversed".
5. **Consequence**: idempotent and order-safe reversals; see the regression test.
6. **Test**: `ReversalTest.failedReversalOfUnknownTargetDoesNotPoisonTheTargetId`,
   `rejectedReversalMutatesNothing`.

## 23. Time model: days as ints, LocalDate, or Instant?

1. **Ambiguity**: the spec speaks of "Day 1..6" — integer ordinals, calendar dates,
   or timestamps?
2. **Chosen policy** (updated 2026-10-18): `LocalDate processingDate` +
   `LocalDate valueDate` + `long streamPosition` on every envelope; a configured
   `ZoneId` on the engine is the ONLY sanctioned Instant→date conversion
   (`BusinessDates.businessDate`). `Instant occurredAt/receivedAt` are deliberately
   NOT modeled: the input stream carries no instants and no business rule consumes
   them, so adding them would fabricate data. Specification "Day n" maps to
   2026-01-0n (arbitrary but fixed; any six consecutive dates give identical results).
3. **Why**: `LocalDate` is the honest granularity (all rules are per business day),
   immune to DST/zone ambiguity; `streamPosition` separates ORDERING (a stream
   concern) from TIME (a business concern); the explicit zone prevents
   machine-dependent replays.
4. **Alternatives**: (a) keep ints — simple but conflates "day number" with "date"
   and cannot express real calendars; (b) `LocalDateTime`/Instant for everything —
   false precision, DST/zone hazards, ordering-by-clock bugs; (c) full
   occurredAt/receivedAt/processingDate/valueDate — rejected as redundant for this
   input set (documented, not forgotten: add them when ingestion actually has them).
5. **Consequence**: reports show real dates (" DAY 2 (2026-01-02)"); fee/entry ids
   embed ISO dates; replay stays byte-deterministic.
6. **Test**: `BusinessDatesTest` (zone behaviour, inclusive ranges),
   `ReplayTest.allTenEventsAreInTheLogInStreamOrder` (positions 1..10).

## 24. Events whose dates fall outside the configured window

1. **Ambiguity**: accept or reject an event whose processing date or value date lies
   outside [windowStart, windowEnd]?
2. **Chosen policy**: REJECT with `EVENT_DATE_OUT_OF_HORIZON`.
3. **Why**: an accepted posting valued outside the window would be invisible to fee
   assessment (which covers window dates up to the processing date) and to interest
   (window dates only) — silently inconsistent books. Loud rejection surfaces a data
   problem at ingestion.
4. **Alternative**: widen the window implicitly — hides configuration errors; clamp
   the value date into the window — falsifies accounting dates.
5. **Consequence**: callers must configure the window to cover all known events; the
   scenario window 2026-01-01..2026-01-06 covers E1..E10 exactly.
6. **Test**: `BoundaryAndValidationTest.eventDatesOutsideTheWindowAreRejected`.

## 25. Duplicate event ids (ingestion idempotency)

1. **Ambiguity**: what happens if two envelopes share an event id?
2. **Chosen policy**: the first occurrence is processed normally; subsequent
   envelopes with the same id are REJECTED with `DUPLICATE_EVENT_ID` (no entries, no
   state change). Outcomes are keyed by `streamPosition` so both outcomes survive.
   Duplicate `streamPosition` values are a data-contract violation and fail fast
   (`IllegalArgumentException`) — positions are the pipeline's ordering key.
3. **Why**: real feeds redeliver; idempotent ingestion requires exactly-once effect.
   Silently double-posting would corrupt balances; silently overwriting the first
   outcome would corrupt the audit trail.
4. **Alternative**: reject the whole stream on any duplicate — too coarse; the
   duplicate is the anomaly, not the stream.
5. **Consequence**: replays of the same logical event are safe; redelivered events
   are visible as rejections in the report.
6. **Test**: `BoundaryAndValidationTest.duplicateEventIdIsRejectedAndKeepsBothOutcomes`,
   `duplicateStreamPositionIsADataContractViolation`.

## 26. Can Credit/Debit carry a zero or negative amount?

1. **Ambiguity**: is `Credit(-50.00)` a debit?
2. **Chosen policy**: REJECT with `NON_POSITIVE_AMOUNT`. The direction of money flow
   comes from the event TYPE, never from a sign smuggled into the amount. Zero-total
   instalment credits are likewise rejected (`NON_POSITIVE_INSTALMENT_TOTAL`).
3. **Why**: signed amounts flowing through typed events makes every posting's
   direction auditable at the type level and prevents double-negation bugs (a negative
   Debit would negate to a credit).
4. **Alternative**: accept and normalize negative amounts to the opposite event type —
   silently changes the input; hides upstream bugs.
5. **Consequence**: malformed events surface as rejections instead of inverted
   postings.
6. **Test**: `BoundaryAndValidationTest.negativeAndZeroAmountsAreRejected`,
   `zeroTotalInstalmentCreditIsRejected`.
