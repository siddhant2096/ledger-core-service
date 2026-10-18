# AMBIGUITIES

Every meaningful ambiguity found in the specification, the policy chosen, why, at
least one plausible alternative, the consequence, and the test that enforces the
choice. None of these policies is presented as mandated by the requirements; each is
a documented, defensible decision.

**How to read this document** — four kinds of statements appear and are labelled as
such:

1. **Requirement** — explicit in the assignment (e.g. "fee: AED 25.00", "do not
   discard a rounding remainder", "never mutate or delete an original event").
2. **Accounting invariant** — not negotiable regardless of policy (conservation of
   minor units; no silent creation/destruction of money; exact decimal arithmetic).
3. **Business-policy choice** — the spec permits multiple defensible policies; we
   picked one and show the trade-off (e.g. immutable assessed fees, residual-hold
   release, fee schedule per currency). Alternatives here are *not* "wrong"; they
   are different defensible policies with different consequences.
4. **Implementation choice** — arbitrary but must be deterministic and documented
   (e.g. residual minor units go to the LAST instalment; window dates
   2026-01-01..06).

Where an alternative would violate an explicit requirement or an accounting
invariant (e.g. rounding 3 × BHD 3.334 = 10.002, discarding an interest remainder),
the text says so plainly instead of calling it a policy difference.

---

## 1. Does fee assessment use the pre-fee or post-fee closing balance?

1. **Ambiguity**: "that day's closing ledger balance is negative" — closing balance
   computed before or after that day's own fee?
2. **Chosen policy** (business-policy choice): pre-fee. A day's own fee cannot
   participate in the balance that triggers it (that would be circular). Previously
   assessed fees from *earlier* value dates DO participate.
3. **Why**: the circular version has no fixed point (a −10 balance + 25 fee is still
   negative, so charge again?). Pre-fee is the standard overdraft-assessment semantic:
   the fee is a consequence of the day's activity, not a cause of itself.
4. **Alternative**: post-fee assessment with a max-one-fee-per-day guard — same result
   for the first fee, but conceptually circular and fragile.
5. **Consequence**: deterministic, terminating assessment; exactly one fee per
   account-day.
6. **Test**: `ReplayTest.exactlyOneFeePerNegativeAccountDayWithAssessedDayValueDate`.

**The complete fee lifecycle, precisely** (consistent with #2, #10, #11, #20 and with
the "sequential fee-assessment balances" terminology in REJECTED.md):

- **Basis**: each value date v is assessed on the balance *immediately before v's own
  fee* — which INCLUDES fees already posted for earlier value dates, because
  assessment walks value dates in ascending order. Example from the scenario: D4's
  assessment balance is −180.00 = −370.00(D2) − 25.00(D2 fee, assessed moments
  earlier) + 400.00 − 185.00.
- **Timing**: assessment runs only at defined processing-time boundaries — the
  evening of each completed processing date, and once at the window end (see #20 for
  the exact trigger logic).
- **Late events**: a late event (processing date later than its value date) can
  reveal an overdraft on a historical value date; that date is then assessed at the
  next boundary, and the resulting fee carries `valueDate` = the overdrawn
  historical date and `derivedOnDate` = the processing date on which it was actually
  discovered and posted. These two dates are always distinct fields; the report
  prints both.
- **Reconsideration**: an account-date not previously *charged* may be re-examined
  at every later boundary; an account-date already charged is never charged twice
  (idempotency key account|valueDate).
- **Immutability and compensation** (policy revised in the final review — see #10):
  fee ENTRIES are immutable — never mutated, never deleted. But their financial
  EFFECT is not necessarily permanent: at every assessment boundary the engine
  recomputes the desired fee set from non-fee entries, and a charged date whose
  corrected books no longer show an overdraft (e.g. after E9) receives an
  append-only FEE_REVERSAL compensation entry valued on the original fee's value
  date. A compensated date is never re-charged (#28). So the fee trace shows what
  was charged when, and the entry list shows the full charge+compensation history;
  the final projection reflects the net effect. The final projection is NOT a claim
  that every historical fee would still fire if the complete stream had been known
  beforehand: under hypothetical full prior knowledge (E7 and E9 both known), no
  date is ever negative pre-fee and no fee would have been triggered at all. The
  report separates the two views deliberately: per-date balances are end-of-window
  projections; the FEE ASSESSMENT TRACE shows what was knowable when each fee was
  actually derived and when it was compensated.

## 2. Can a fee itself trigger another fee?

1. **Ambiguity**: if a fee pushes an account deeper into the negative, does it cascade?
2. **Chosen policy**: within one day, no (the idempotency key allows one fee per
   account-day). Across days, yes: a still-negative balance the next day — even if
   negativity is partly caused by earlier fees — earns that day its own fee. This is
   the "sequential fee-assessment" basis of #1: earlier fees participate in later
   dates' balances.
3. **Why**: matches "once per account per day"; the cross-day effect is not a cascade
   but a new assessment of a new day.
4. **Alternative**: never let fees contribute to fee bases (fee-exempt balances). Not
   chosen because it contradicts the spec's definition of the ledger balance, which
   explicitly includes "previously generated fee entries" (a requirement, not a
   policy option).
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
   approve Auth-B (465.00 final Day-5 balance) but is acausal; (b) validate against
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
2. **Chosen policy** (business-policy choice): the settlement posts −185.00 and
   releases the **entire** hold; the residual 15.00 is released and the authorization
   becomes **terminal** in status `PARTIALLY_SETTLED`. "Terminal" is literal in the
   implementation: `AuthorizationStatus.isSettleable()` is true only for APPROVED, and
   `Authorization.settle()` throws on any non-settleable state, so PARTIALLY_SETTLED
   means exactly "captured below the authorized amount; remaining hold released; no
   further captures allowed". Verified against `Authorization`/
   `AuthorizationStatus` (no enum or lifecycle change was needed — the code already
   implements this).
3. **Why**: matches the common card-acquiring model (one capture per authorization;
   remaining hold drops off). It makes double consumption structurally impossible and
   keeps the state machine tiny.
4. **Alternative**: multi-capture — residual stays active for further settlements up
   to the hold total. A defensible policy (supports partial shipments), not an error;
   it requires tracking remaining-capturable amounts and is unnecessary for this
   window, so it was not chosen.
5. **Consequence**: settling 185.00 against the 200.00 Auth-A hold posts exactly
   −185.00 (no more, no less), releases the residual 15.00, leaves zero active hold,
   and a second settlement against Auth-A would be REJECTED
   (`AUTHORIZATION_NOT_SETTLEABLE`).
6. **Test**: `BoundaryAndValidationTest.secondSettlementOfSameAuthorizationIsRejected`,
   `EventStreamTest.e5SettlesAuthAFor185AndReleasesResidualHold`.

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
3. **Why**: the spec forbids deletion (a requirement); compensation preserves the
   complete audit trail and makes "what happened and when" reconstructable.
4. **Alternative**: mark-and-suppress (original kept but excluded from projections).
   Not chosen: projections would then depend on a mutable flag, and the flag would
   have to be maintained consistently with the append-only rule; compensation is pure
   double-entry bookkeeping.
5. **Consequence**: Day-2 pre-fee balance returns to exactly +250.00 after E9; both
   E7's −620.00 and E9's +620.00 are visible in the entry list.
6. **Test**: `ReplayTest.e9CompensatesE7ExactlyWithoutDeletingIt`.

**The two dates of a reversal, explicitly** (E9 as the example):

- **Reversal processing date (Day 6)**: when the reversal event is received,
  validated (target exists, eligible kind, same account, not already reversed), and
  posted. Authorization-style "what was known" questions use this date.
- **Reversal value date (Day 2)**: the accounting date the compensating entry
  applies to. Because E9's compensation is valued Day 2, the Day-2 historical
  projection changes (pre-fee 250.00 restored) without rewriting anything.
- **Fee-assessment history**: untouched. Fees already assessed remain assessed
  under the immutable-fee policy — see #10 (reversal vs fees) and #11 (when
  assessments run); the reversal changes balances, not the assessment history.

## 10. How does a reversal affect previously assessed fees?

1. **Ambiguity**: E9 cancels E7's debit — is the Day-2 overdraft fee (assessed because
   of E7) refunded?
2. **Chosen policy** (business-policy choice; REVISED in the final acceptance review
   — the earlier policy, persistence without compensation, is recorded as an
   abandoned approach in REJECTED.md): YES, effectively — by append-only
   compensation. Fee ENTRIES are immutable (never mutated or deleted), but at every
   assessment boundary the desired fee set is recomputed from non-fee entries, and
   any charged fee whose date is no longer overdrawn in the corrected books is
   neutralized by a FEE_REVERSAL entry of the opposite sign, valued on the original
   fee's value date and derived on the boundary's processing date. E9 therefore
   leaves all three fee entries intact AND appends three compensation entries on the
   Day-6 evening; net fees return to zero.
3. **Why**: the acceptance review established that no explicit requirement mandates
   fee persistence — append-only governs events and entries, not their permanent
   financial effect — and that criterion 6 is satisfiable without violating any
   higher-priority requirement. Compensation beats both alternatives: it satisfies
   criterion 6, preserves the COMPLETE audit trail (charge and correction both
   visible, each with its own dates), and keeps replay deterministic and idempotent.
   It also matches accounting practice: when the debit that caused an overdraft is
   reversed as erroneous, the overdraft charge caused by it is reversed too.
4. **Alternatives**: (a) persistence (fees, once assessed, stay effective forever) —
   the implemented policy in phases 1–7; defensible on point-in-time grounds but
   fails criterion 6 and charges for an overdraft the corrected books say never
   happened; (b) full recompute (fees as a pure projection of the final stream) —
   satisfies criterion 6 but discards the point-in-time assessment history that the
   assignment's "derived accounting entry" framing calls for. Both remain
   documented trade-offs; neither is arithmetically wrong.
5. **Consequence**: final Day-2 closing is 250.00; net cumulative fees are 0.00
   (charged −75.00, compensated +75.00); criterion 6 is ACCEPTED in net terms — see
   REJECTED.md for the exact qualification. The final projection is not a claim
   that each historical fee would have fired under full prior knowledge (under full
   prior knowledge, no fee would have fired at all); the FEE ASSESSMENT TRACE
   preserves what was knowable at each boundary.
6. **Test**: `ReplayTest.e9LeavesFeeEntriesIntactAndCompensatesThem`, and the focused
   `LateEventFeeTest.chargedFeeIsCompensatedWhenALateEventCorrectsItsDate`.

## 11. Are fee assessments recomputed after late events and reversals?

1. **Ambiguity**: when E7 arrives on Day 5, do we revisit Days 1–4?
2. **Chosen policy**: yes — every boundary runs a full RECONCILIATION over the
   window prefix: the desired fee set is recomputed deterministically from non-fee
   entries (sequential, fee-participating semantics, #1), then (a) never-charged
   desired dates are charged (at most once ever), (b) charged dates no longer
   desired are compensated via FEE_REVERSAL (at most once ever), (c) desired dates
   already charged stay charged. Reconciliation happens ONLY at the defined
   processing-time boundaries (evenings of completed processing dates and the
   window end — the exact trigger logic is in #20); there is no per-event or
   mid-day assessment.
3. **Why**: combines correctness (back-valued overdrafts are caught; erroneous
   charges are corrected) with stability (each date is charged at most once and
   compensated at most once — no oscillation, no duplicates) and full auditability
   (charges and compensations are separate immutable entries).
4. **Alternative**: never revisit — misses E7's Day-2 overdraft entirely and fails the
   spec's core "late event affects earlier days" requirement (a requirement, so this
   alternative is non-conforming, not merely different).
5. **Consequence**: all three fees are derived on the evening of processing Day 5,
   valued on Days 2, 4, 5; all three compensations are derived on the evening of
   Day 6, valued on Days 2, 4, 5. The full lifecycle (basis, boundaries,
   reconsideration, immutability-plus-compensation, valueDate vs derivedOnDate) is
   specified in #1.
6. **Test**: `BoundaryAndValidationTest.lateEventIsReflectedInFeeAssessmentOnlyAfterItIsProcessed`,
   `LateEventFeeTest` (both directions: charge of an uncharged date, compensation of
   a charged date).

**Consistency of the recomputation (audited in the final review)**: the desired fee
set is computed so that a compensation CORRECTLY affects later dates' balances and
eligibility — a fee that is about to be compensated (charged, but its date is no
longer negative) does not participate in later dates' bases in that recomputation;
participation follows exactly the fees that will be effective after the boundary.
This is the intended reading of the sequential semantics of #1 applied to
reconciliation: the alternative reading (evaluating each date against the
as-currently-posted fee history before compensation) would let a dying fee justify
a later fee that then loses its own justification — an inconsistent state the
recomputation avoids by construction. Circularity note: because fees are a fixed
amount and participation only ever makes balances MORE negative, the sequential
recomputation has a unique least fixed point (charge date v iff its base, including
effective fees of earlier dates, is negative); no iterative solving or iteration
limits are needed. Enforced by the discriminating regression test
`FeeReconciliationTest.compensationOfEarlierFeeChangesLaterDatesEligibility`,
which fails on any implementation that leaks an about-to-be-compensated fee into a
later date's eligibility (it would retain a fee the test proves must be
compensated).

## 12. Which balance is the interest base?

1. **Ambiguity**: pre-fee or post-fee closing balance?
2. **Chosen policy**: post-fee closing (the actual ledger balance, per the spec's
   definition of ledger balance as including "previously generated fee entries").
   Precisely: the base for date D is the FINAL end-of-window value-date projection
   of the post-fee closing balance for D, taken BEFORE interest capitalization
   (capitalization is computed from it, so it cannot include itself).
3. **Why**: consistency — one definition of ledger balance used everywhere (fees,
   interest, available balance, report).
4. **Alternative**: pre-fee base (exclude fee entries from the base). A defensible
   business policy (some banks pay interest on the pre-charge balance); not chosen
   because the spec's ledger-balance definition includes fee entries. Note that in
   THIS scenario's final projection all fees are compensated, so fee charges and
   compensations net to zero and the two policies coincide here; the distinction is
   observable whenever a fee remains effective at window end (e.g.
   `LateEventFeeTest`: Day-4 base is 255.00 including the effective Day-3 fee;
   pre-fee it would be 280.00 → accrual 0.11 instead of 0.10).
5. **Consequence** (verified against the implementation and the actual report):
   bases D1 250.00, D2 250.00, D3 650.00, D4 465.00, D5 465.00, D6 465.00; daily
   rounded accruals 0.10 / 0.10 / 0.26 / 0.19 / 0.19 / 0.19 summing to exactly
   AED 1.03, capitalized ONCE as a single credit valued on the window-end date
   (Day 6). These bases are **final historical projections** — the balances as
   reconstructed from the complete stream (including E7/E9's compensating pair and
   the fee charge/compensation pairs) — NOT necessarily the balances known on each
   processing day. REJECTED.md and README.md use the same "final projection"
   terminology.
6. **Test**: `ReplayTest.interestAccruesOnPositivePostFeeBalancesOnly`,
   `ReplayTest.capitalizedInterestEqualsSumOfRoundedDailyAccrualsExactly`,
   `DailyProjectionTest`.

## 13. How is daily interest rounded and how is any residual reconciled?

1. **Ambiguity**: round each day, or round the aggregate? If both, which wins?
2. **Chosen policy**: round each day's accrual HALF_UP to the currency scale; the
   capitalized total is **defined as** the exact sum of the rounded dailies. No
   remainder can exist, so nothing is discarded.
3. **Why**: the spec requires Σ rounded dailies == capitalized total; defining the
   total as the sum satisfies it by construction and matches per-day statements a
   customer could verify.
4. **Alternative**: round the aggregate (2545.00 × 0.0004 = 1.0180 → 1.02) and
   allocate the residual to the last day — gives 1.02 vs our 1.03, an observable
   difference, and makes some daily statement line differ from what was capitalized
   for it.
5. **Consequence**: ACC-001 capitalized 1.03; ACC-002 capitalized 0.008.
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
   Charging AED 25.00 to a BHD account is impossible (no FX data); charging
   BHD 25.000 implicitly would be an undocumented assumption.
2. **Chosen policy** (tightened twice, latest 2026-10-18): the overdraft fee is an
   **explicit per-currency schedule** injected into the engine
   (`Scenario.OVERDRAFT_FEE_SCHEDULE`). The DEFAULT schedule contains ONLY the
   supplied requirement, AED 25.00 — the requirements do not authorize a BHD fee, so
   none is configured. A currency absent from the schedule is charged nothing: the
   engine records a `SkippedFeeAssessment` (`NO_FEE_CONFIGURED_FOR_CURRENCY`) in the
   replay trace, so the non-charge is itself audited — never a silent fee, never a
   silent omission. The schedule mechanism supports a BHD fee purely as
   configuration, for when the business explicitly authorizes one.
3. **Why**: an AED-denominated charge on a BHD account would be inventing FX data;
   shipping a BHD amount in the default configuration would be inventing a business
   rule. Explicit, minimal configuration makes the decision visible, reviewable, and
   changeable without touching the engine. The engine validates the schedule at
   construction (fee currency == key, fee positive).
4. **Alternatives**: (a) BHD 25.000 in the default schedule (same numeric value,
   account currency — the least-surprise amount if a fee were authorized) — kept as
   a tested mechanism but removed from the default because no requirement authorizes
   it; (b) FX conversion — requires data the spec does not supply.
5. **Consequence**: none in this scenario (ACC-002 never goes negative); behavior is
   pinned by dedicated tests with synthetic negative BHD accounts, both WITHOUT a
   configured BHD fee (default: skip recorded, no fee) and WITH one (mechanism test:
   charged BHD 25.000).
6. **Test**: `BoundaryAndValidationTest.bhdAccountIsChargedNothingUnderTheDefaultAedOnlySchedule`,
   `bhdAccountIsChargedConfiguredFeeOnlyWhenScheduleAuthorizesIt`.

**Unresolved residue**: whether the business intends BHD overdrafts to be charged at
all, and if so at what amount, remains a business decision — the schedule makes the
assumption explicit but cannot eliminate it.

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
5. **Consequence**: ACC-001 Day-4 closing is 465.00 (E5's −185.00 and the
   compensated-to-zero fees), not 285.00 (which would wrongly include E6's
   −180.00).
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

## 20. What happens when an event's processing date is EARLIER than one already seen?

1. **Ambiguity**: E10 (processing Day 5) appears in the stream AFTER E9 (processing
   Day 6). Strictly increasing processing dates cannot be assumed.
2. **Chosen policy**: the supplied stream order is authoritative — events are NEVER
   sorted, by processing date or by value date. Four concepts stay distinct:
   - **Stream order**: the order events are applied in (the spec's "precise
     processing order"); the envelope's `streamPosition` makes it explicit.
   - **Processing date**: controls WHEN an event is processed and what is known at
     each decision point (authorization inputs, fee-assessment timing).
   - **Value date**: controls the accounting date accepted financial entries apply
     to; balances and interest bases are always value-date projections.
   - **Assessment boundaries**: fee assessment runs only when the processing date
     ADVANCES past a completed date (evenings) and once at the window end.
3. **The exact implemented behavior for a backwards-moving processing date**
   (verified against `LedgerEngine.replay`, not aspirational): when E10 is reached
   after E9, no boundary triggers (its processing date does not advance past Day 6,
   the max seen). E10 is applied immediately, in stream order, deriving its three
   instalment postings valued Day 5. Its effects are then considered at the NEXT
   boundary that actually runs — here the window-end evening, whose assessment loop
   examines EVERY window value date (windowStart..windowEnd) that has not been
   fee-charged before, including Day 5. So nothing is skipped and nothing is
   double-charged: coverage comes from the boundary assessments always scanning the
   full window prefix, and idempotency comes from the account|valueDate key. It is
   NOT "correct merely because projections are value-date based" — it is correct
   because every boundary re-examines the whole window prefix for uncharged dates
   and charged dates are never revisited.
4. **Why**: the spec's numbered list is explicit ("replay in this precise processing
   order") and processing dates are metadata on the event, not a reordering license.
   (Grouping by processing day was implemented first and abandoned when the
   stream-order test caught it applying E10 before E9 — see WORKLOG.)
5. **Alternative**: reject non-monotone processing dates — would reject a
   spec-mandated input, so non-conforming; grouping by day — the abandoned
   implementation violated the mandated order (though grouping with preserved
   stream order would be valid).
6. **Consequence**: for this stream, figures are identical to a day-ordered reading
   (E10 touches only ACC-002, which never goes negative), but the log order matches
   the mandated E1..E10 sequence, and the worst case (a backward-dated-processing
   event that creates a historical overdraft) is covered by the window-end
   assessment — proven by test.
7. **Test**: `ReplayTest.allTenEventsAreInTheLogInStreamOrder`,
   `EventStreamTest.e9ThenE10NonMonotonicProcessingDatesAreHandledInStreamOrder`,
   `BoundaryAndValidationTest.backwardProcessingDateEventIsCaughtAtWindowEndWithoutDuplicates`,
   and `LateEventFeeTest` (late back-valued events vs assessed/unassessed dates).

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
   +620.00; reversing E10 would compensate exactly −10.000 BHD. **Settlement
   reversal**: reversing a settlement's POSTING entry is allowed and compensates
   the funds (+amount), but under the one-capture policy (#6) the authorization
   remains terminal (PARTIALLY/FULLY_SETTLED) and the released hold is NOT
   resurrected — the reversal restores money, not capturability. (Alternative:
   re-open the hold on settlement reversal, as some card systems do for refunds —
   rejected here because it contradicts the documented terminal-settlement
   lifecycle; a real refund flow would be a separate explicit event type.)
6. **Test**: `ReversalTest` (all eight cases), including
   `multiEntryInstalmentCreditIsReversedInFullAsOneCompensation`,
   `reversalOfAReversalIsRejected`, `reversingDerivedFeeEntriesIsUnsupported`,
   `crossAccountReversalIsRejected`; settlement-reversal policy pinned by
   `BoundaryAndValidationTest.settlementReversalCompensatesPostingButAuthorizationStaysTerminal`.

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
   Two sharpenings from the final review:
   - **A rejected first occurrence still consumes its id.** Ids are consumed on
     first SIGHT, not on acceptance: redelivering an id with different content is a
     data conflict, and silently reprocessing it would break exactly-once semantics.
   - **Positions must STRICTLY INCREASE along the list** (uniqueness alone did not
     enforce order). A non-increasing position contradicts the list order it claims
     to describe, so the engine fails fast with `IllegalArgumentException` rather
     than silently sorting (the spec mandates the supplied order; sorting is not
     permitted).
3. **Why**: real feeds redeliver; idempotent ingestion requires exactly-once effect.
   Silently double-posting would corrupt balances; silently overwriting the first
   outcome would corrupt the audit trail.
4. **Alternatives**: (a) consume ids only on acceptance — rejected; a rejected event
   is still a seen fact and re-using its id must surface as a conflict; (b) reject
   the whole stream on any duplicate — too coarse; the duplicate is the anomaly,
   not the stream; (c) sort envelopes by position — not permitted (stream order is
   authoritative).
5. **Consequence**: replays of the same logical event are safe; redelivered events
   are visible as rejections in the report; malformed position sequences fail fast
   at the pipeline boundary.
6. **Test**: `BoundaryAndValidationTest.duplicateEventIdIsRejectedAndKeepsBothOutcomes`,
   `duplicateEventIdCannotAffectEntriesAuthFeesOrInterest`,
   `rejectedEventStillConsumesItsEventId`,
   `duplicateStreamPositionIsADataContractViolation`,
   `outOfOrderStreamPositionsAreRejected`.

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

## 27. In which order are validation rules applied (when an event violates several)?

1. **Ambiguity**: an event can violate multiple rules at once (e.g. duplicate id AND
   wrong currency). Which reason is reported?
2. **Chosen policy** (implementation choice, deterministic): checks run in a fixed
   order, first failure wins:
   1. `DUPLICATE_EVENT_ID` — envelope identity (idempotent ingestion, #25);
   2. `EVENT_DATE_OUT_OF_HORIZON` — window membership (#24);
   3. `UNKNOWN_ACCOUNT` — account existence;
   4. `CURRENCY_MISMATCH` — event currency vs account currency (first check inside
      every handler);
   5. handler-specific business rules in their documented order (e.g. settlement:
      unknown authorization → account mismatch → not settleable → non-positive →
      exceeds authorized amount; reversal: duplicate → unknown target →
      unsupported kind → account mismatch).
3. **Why**: structural/identity checks before business rules mirrors how the failure
   would be diagnosed (is this even our event? is it in scope? does the account
   exist? does the currency fit? is the business action valid?). A fixed order makes
   rejection reasons deterministic across replays and testable.
4. **Alternative**: collect ALL violations per event — more informative for bulk data
   cleaning, but overkill for a strict ledger core and complicates the outcome model;
   not chosen.
5. **Consequence**: rejection reasons are stable and single; a caller fixing one
   problem may reveal the next (acceptable for a strict ingestion path).
6. **Test**: `BoundaryAndValidationTest.validationOrderIsDeterministic`.

## 28. Is a fee re-charged if a compensated date goes negative again?

1. **Ambiguity**: under reconciliation (#10, #11), a date can be charged, later
   compensated, and later still go negative again. Does it earn a second fee?
2. **Chosen policy** (business-policy choice): no. "Assess once per account per day"
   is read as ONE fee lifecycle per account-date: charge at most once, compensate
   at most once, never re-charge. A date that goes negative after compensation
   simply stays negative without a new fee.
3. **Why**: the spec's "once per account per day" is the strongest textual anchor;
   re-charging would also let a single date accumulate multiple charge entries,
   complicating the audit story (which fee does a second compensation refer to?).
4. **Alternative**: re-charge after compensation (a fresh fee episode). Defensible —
   the date IS negative again — but it stretches "once per account per day" and adds
   lifecycle complexity for a case that does not occur in the scenario.
5. **Consequence / known limitation**: in the charge → compensate → negative-again
   corner, the reconciliation's decision bases (which include desired-fee
   participation) can differ slightly from the actual books (which contain no new
   fee for that date). The corner is deterministic and documented; the scenario and
   all tests exercise it only up to compensation, never re-negativing.
6. **Test**: the charge-once/compensate-once invariants are enforced by
   `LateEventFeeTest` (charge and compensation counts) and
   `ReplayTest.replayIsDeterministic`.
