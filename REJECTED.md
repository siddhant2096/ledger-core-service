# REJECTED — Evaluation of the eight supplied acceptance criteria

Each criterion is evaluated independently against the documented model
(AMBIGUITIES.md). Statuses: **ACCEPTED**, **REJECTED**, **ACCEPTED WITH
QUALIFICATION**. Reference figures (derived in NUMBERS.md and verified by the test
suite):

- **Sequential fee-assessment balances (before each date's own fee)**, ACC-001, as
  computed on the evening of Day 5: D1 250.00, D2 −370.00, D3 5.00, D4 −180.00,
  D5 −205.00. The assessment walks value dates in ascending order and each balance
  INCLUDES the fees assessed moments earlier for prior dates (AMBIGUITIES.md #1):
  D3 = −370.00 − 25.00(D2 fee) + 400.00 = 5.00; D4 = 5.00 − 185.00 = −180.00;
  D5 = −180.00 − 25.00(D4 fee) = −205.00. Confirmed against the implementation's
  fee trace (`ReplayTest.exactlyOneFeePerNegativeAccountDayWithAssessedDayValueDate`
  asserts the pre-fee balances −370.00 / −180.00 / −205.00 for value dates 2/4/5).
- Distinct from the above: the **balances before the entire fee-assessment batch**
  (no fees posted anywhere yet): D1 250.00, D2 −370.00, D3 30.00, D4 −155.00,
  D5 −155.00 (D3 = −370.00 + 400.00; D4 = 30.00 − 185.00; D5 = −155.00). The two
  series answer different questions — "is this date negative when assessed in
  sequence?" versus "what were the books before any fee existed?" — and this
  document never calls either of them just "pre-fee" without saying which.
- Final (post-fee, post-reversal) closings, ACC-001: D1 250.00, D2 225.00,
  D3 625.00, D4 415.00, D5 390.00, D6 390.93.
- Fees: 3 × AED 25.00, valued on Days 2, 4, 5; all derived on processing Day 5.
- Interest: 0.10 / 0.09 / 0.25 / 0.17 / 0.16 / 0.16 → 0.93 capitalized (ACC-001);
  0.004 + 0.004 → 0.008 capitalized (ACC-002).

---

## Criterion 1 — ACCEPTED

> "The Day 2 closing ledger balance, evaluated at end of Day 5 and before any fee is
> assessed, is AED −370.00."

**Arithmetic.** Events processed by end of Day 5 affecting ACC-001 with value day ≤ 2:
E1 +1200.00@1, E2 −950.00@1, E7 −620.00@2. Sum: 1200.00 − 950.00 − 620.00 =
**−370.00**. At that moment no fee has yet been assessed for Day 2 (fees run on the
evening of Day 5, i.e. "after evaluation"), so the pre-fee qualification is satisfied.

**Assumptions stated explicitly**: this is the *point-in-time* view (only events
processed by end of Day 5) and *pre-fee* (the Day-2 fee of 25.00 is derived during
the Day-5 evening assessment, immediately after this balance is computed). Under the
final end-of-window projection the Day-2 closing is instead 225.00 (E9's +620.00
compensation and the −25.00 fee, both valued Day 2).

**Enforced by**: `ReplayTest.exactlyOneFeePerNegativeAccountDayWithAssessedDayValueDate`
(fee trace records preFeeBalance −370.00 for value day 2, assessed on day 5) and the
report's FEE ASSESSMENT TRACE.

## Criterion 2 — REJECTED (under the documented policy; arithmetic below)

> "E7 causes exactly one overdraft fee to be assessed, on Day 2."

**Verdict under the documented policy.** Under the documented policy of assessing a
fee for each negative account-day (AMBIGUITIES.md #1, #2, #11), the event history
results in fees valued on Days 2, 4, and 5 — three fees, not one. The outcome is
determined by the fee schedule and the assessment timing, so this rejection is
tied to that documented policy rather than to a claim about every conceivable model.

**Arithmetic (sequential fee-assessment balances, evening of Day 5).** E7 makes
Day 2 negative: 250.00 − 620.00 = −370.00 → fee F1 (valued Day 2). The assessment
then walks forward with previously assessed fees included:

- Day 3: −370.00 − 25.00 + 400.00 = **+5.00** → no fee.
- Day 4: 5.00 − 185.00 = **−180.00** → fee F2.
- Day 5: −180.00 − 25.00 = **−205.00** → fee F3.

Even if previously assessed fees were EXCLUDED from later dates' bases (the
alternative reading considered in AMBIGUITIES.md #1), Day 4 would be
250.00 − 620.00 + 400.00 − 185.00 = −155.00 → still negative, still charged, so the
"exactly one fee" claim fails under both participation readings of the documented
policy. A model in which only Day 2 is ever charged (e.g. the lagged/targeted
reassessment variant discussed in the 440.00 section below) would make the
criterion true, but that model contradicts the fee rule's plain text ("when that
day's closing ledger balance is negative") and the requirement to examine all
relevant value dates; it was not chosen. Total under the documented policy:
3 × AED 25.00 = AED 75.00.

**Verified against code and tests**: the replay produces exactly three
`FEE_OVERDRAFT` entries with value dates 2/4/5, all derived on the evening of
processing Day 5, with sequential pre-fee balances −370.00 / −180.00 / −205.00
(`ReplayTest.exactlyOneFeePerNegativeAccountDayWithAssessedDayValueDate`;
report FEE ASSESSMENT TRACE).

## Criterion 3 — ACCEPTED

> "The Day 4 settlement of Auth-A must be accepted."

**State-transition reasoning.** At the original authorization decision (E3,
processing Day 2), the figures actually known were:

- Ledger balance before authorization: **AED 250.00** (E1 +1200.00, E2 −950.00)
- Active holds before authorization: AED 0.00
- Requested hold: **AED 200.00**
- Available balance after applying the hold: 250.00 − 200.00 = **AED 50.00 ≥ 0**
- Decision: **APPROVED**

(These are the figures the engine printed in the decision note: "AUTH APPROVED:
ledger=AED 250.00 activeHolds=AED 0.00 requested=AED 200.00".) The AED 225.00
Day-2 ledger balance and AED 25.00 Day-2 available balance that appear elsewhere in
the report are a **later projected view**: they incorporate E7, E9, and the Day-2
fee of 25.00, none of which existed when Auth-A was approved. They describe how
Day 2 looks in the end-of-window projection, not what was knowable at approval
time, and are never used for the approval decision.

E5 (Day 4): authorization exists, status APPROVED (settleable), amount 185.00 > 0,
185.00 ≤ 200.00 (hold) → accepted. Posts −185.00 valued Day 4; authorization →
PARTIALLY_SETTLED; hold released (residual 15.00 released).

**Assumption stated**: settlement validity derives from the authorization, not the
account balance at settlement time (AMBIGUITIES.md #5–#7); that policy is what makes
"must be accepted" true, and it is also the policy under which the criterion's intent
(a well-formed settlement against a sufficient hold) holds.

**Enforced by**: `ReplayTest.authASettlesPartiallyAndReleasesTheHold`.

## Criterion 4 — ACCEPTED

> "Any settlement referencing an authorization ID not present in the ledger must be
> rejected and the funds must not leave the account."

**Reasoning.** Auth-Z has no authorization record. The engine rejects E6 with
`UNKNOWN_AUTHORIZATION`, appends no ledger entry, and creates/consumes no
authorization state. ACC-001's Day-4 closing reflects only the Auth-A settlement
(415.00, not 235.00), proving the funds did not move.

**Scope note**: "must be rejected" is a policy choice (AMBIGUITIES.md #5), not an
arithmetical truth — the alternative (post-and-flag-unmatched) exists. We chose
rejection because settlement validity derives from the authorization; the criterion
matches that defensible policy.

**Enforced by**: `ReplayTest.authZSettlementIsRejectedAndPostsNothing`.

## Criterion 5 — ACCEPTED WITH QUALIFICATION

> "If Auth-B is approved, its hold reduces available balance but not ledger balance."

**Qualification**: the conditional's antecedent is FALSE in this stream. When E8 is
processed (Day 5, after E7), the ledger balance **known at that processing point** is
1200.00 − 950.00 + 400.00 − 185.00 − 620.00 = **−155.00**; −155.00 − 90.00 < 0 →
Auth-B is **DECLINED** and creates no hold. (Verified against the implementation and
pinned by test; the report's Day-5 section prints the decline with these exact
inputs.) The mechanism the criterion describes is nonetheless exactly the implemented
semantics: an approved hold reduces available balance only. Two views of Auth-A
demonstrate this without conflating timelines:

- **At approval time (Day 2, processing-time view)**: ledger 250.00, hold 200.00 →
  available 50.00; the ledger balance is untouched by the hold.
- **End-of-window projection view**: the report's Day-2 line shows ledger 225.00
  and available 25.00 (= 225.00 − 200.00). This is a *later projected* Day-2 figure
  that includes the Day-2 fee discovered on Day 5 — it is not what was known when
  the hold was approved.

**Enforced by**: `ReplayTest.authBIsDeclinedBecauseTheAccountWasOverdrawnAtProcessingTime`
and the Day-2 available-balance line in `ReportTest`.

## Criterion 6 — REJECTED (under the documented immutable-fee policy)

> "After E9, all balances and fees return to their pre-E7 values."

**Arithmetic and reasoning.** E9 reverses E7's debit by exact compensation
(+620.00 valued Day 2, matching E7's −620.00 valued Day 2; neither entry is ever
mutated or deleted). Consequently the *pre-fee* value-date balances DO return to
their pre-E7 values — Day-2 pre-fee is +250.00 again. But the fees already booked
on the evening of Day 5 (3 × 25.00, value dates 2/4/5) remain as ledger entries
under the documented policy (AMBIGUITIES.md #10) and are not removed by E9. So
*post-fee* balances do NOT return: Day-2 final = 225.00 ≠ 250.00, cumulative fees =
75.00 ≠ 0.00, and interest bases shift accordingly (Day-2 base 225.00, not 250.00).
The criterion is rejected because it claims BOTH balances and fees return; under the
documented policy only the pre-fee balances do.

**Policy status stated plainly**: fee immutability is a deliberate business-policy
and auditability choice — an assessed charge is a customer-visible fact, and
correcting one requires a new explicit compensating entry (e.g. a fee-refund event),
never a retroactive erasure. It is NOT the only accounting approach that could be
considered sound: a full-recompute policy would also be defensible, and under it the
outcome would depend on the chosen fee policy and on whether previously assessed
charges are reversed via compensating entries or recalculated from scratch. Under
pure recalculation with this stream, E9 removes the cause of every fee (no final
closing is negative), so all three fees would vanish and the criterion would hold —
which is precisely why this criterion is policy-dependent rather than arithmetically
wrong. We chose immutability for auditability; the trade-off is documented in
AMBIGUITIES.md #10 and the abandoned-approaches section below.

**Enforced by**: `ReplayTest.e9DoesNotRemoveAssessedFees`.

## Criterion 7 — REJECTED (money conservation violation)

> "The three BHD instalments in E10 must each be BHD 3.334."

**Arithmetic.** 3 × 3.334 = **10.002 ≠ 10.000**: BHD 0.002 would be created from
nothing. Conversely 3 × 3.333 = 9.999 would destroy BHD 0.001. The chosen allocation
(minor-unit integer split, residual to the last instalment) is 3.333 + 3.333 + 3.334
= **10.000 exactly**. "Equal instalments" is interpreted as "as equal as the
currency's precision permits, conserving the total" — the only reading compatible
with double-entry conservation.

**Enforced by**: `InstalmentAllocatorTest.tenBhdSplitsExactlyWithResidualOnLastInstalment`,
`ReplayTest.bhdInstalmentsConserveExactlyTenBhd`.

## Criterion 8 — REJECTED

> "If the rounded daily interest accruals do not sum to the capitalized total, the
> remainder is discarded."

**Reasoning.** The assignment explicitly prohibits this: "Do not discard a rounding
remainder." That specification requirement alone settles the criterion. Supporting
reasoning: discarding a remainder silently destroys money, which violates the
conservation principle any accounting core must satisfy — every minor unit must be
accounted for.

**Arithmetic (unchanged).** The daily AED accruals (each rounded HALF_UP to 2dp)
are 0.10 + 0.09 + 0.25 + 0.17 + 0.16 + 0.16 = **0.93**, and the capitalized total is
defined as exactly that sum, so no remainder ever arises under the chosen policy.
The discrepancy is real under the alternative aggregate-rounding approach: the sum
of the daily bases is 250.00 + 225.00 + 625.00 + 415.00 + 390.00 + 390.00 =
2295.00, and 2295.00 × 0.0004 = 0.918 → rounded **0.92** ≠ 0.93 — a difference of
**AED 0.01** that must not be silently discarded under the specification.
Aggregate-rounding is not inherently invalid: it would need an explicit,
deterministic reconciliation policy (e.g. allocating the 0.01 residual to the final
day's accrual). The sum-of-rounded-dailies policy was chosen because it makes the
identity exact by construction and keeps each daily statement line equal to what was
capitalized for it (AMBIGUITIES.md #13).

**Enforced by**: `ReplayTest.capitalizedInterestEqualsSumOfRoundedDailyAccrualsExactly`.

---

## Additional evaluated expectation (phase-4 review): "Days 4–6 = AED 440.00 pre-capitalization"

A later review requested assertions for final AED value-date projections of
D1 250.00, D2 225.00, D3 625.00, **D4–D6 440.00** before capitalization, "assuming
E7 and E9 cancel and the AED 25 Day-2 fee remains".

**Verdict: D1–D3 ACCEPTED (they match); the D4–D6 = 440.00 figure is REJECTED as
incompatible with the stated fee rules.** This is an arithmetic finding, not a
policy preference:

The 440.00 figure implies exactly one fee (D2). But the fee rule is "assess once per
account per day when that day's closing ledger balance is negative", and on the
evening of processing Day 5 (after E7 posted, before E9 existed) the sequential
fee-assessment balances (each before its own fee, after earlier fees — see the
terminology note at the top of this document) were:

- D2: 250.00 − 620.00 = **−370.00** → fee (this is the D2 fee, value date D2)
- D3: −370.00 − 25.00 + 400.00 = +5.00 → no fee
- D4: 5.00 − 185.00 = **−180.00** → fee
- D5: −180.00 − 25.00 = **−205.00** → fee

(Even WITHOUT fee participation in later bases, D4 = 30.00 − 185.00 = −155.00 and
D5 = −155.00 — still negative, still charged.) So D4 and D5 fees are mandatory under
the plain fee rule whenever all value dates are examined on the D5 evening. With
those fees immutable (the documented policy this review also mandates), the final
pre-capitalization projections are D4 = 415.00, D5 = 390.00, D6 = 390.00.

The 440.00 figures can only be produced by a model that either:

1. **erases the D4/D5 fees when E9 arrives** — retroactive erasure of assessed,
   customer-visible charges, forbidden by the immutable-derived-entry policy; or
2. **never assesses D4/D5 on the D5 evening despite their negative closings** —
   violating "assess … when that day's closing ledger balance is negative" and the
   review's own requirement that "all relevant value dates are examined"; or
3. **assesses the current date using only events processed BEFORE that date**
   (lagged assessment) plus targeted reassessment of back-valued dates only — a
   coherent but contrived policy under which the D5 evening would ignore E7 (a known
   fact) when closing D5, and which would still have charged D5 = −155.00 on any
   non-lagged reading.

All three were evaluated and rejected: (1) destroys audit history, (2) contradicts
the fee rule's plain text, (3) trades one inconsistency for a stranger one (a date
reported as −180.00 point-in-time yet never fee-charged).

**Consequence**: D1 = 250.00, D2 = 225.00, D3 = 625.00 are asserted exactly as
requested (`DailyProjectionTest`); D4–D6 are asserted as 415.00 / 390.00 / 390.00
(pre-capitalization; D6 = 390.93 after the 0.93 capitalization). Note the
interesting coincidence: 440.00 IS the correct figure under the alternative
"recompute fees at window end" policy ONLY if the D2 fee were also erased — which
would give 465.00, not 440.00 — so 440.00 matches no self-consistent policy we could
construct. This mirrors original criterion 2 ("exactly one fee, on Day 2"), rejected
above on the same arithmetic.

---

## Approaches considered and abandoned during development

Honesty note on evidence: items 1 and 2 were actually implemented/hit and are
recorded with their failures in WORKLOG.md (2026-10-18 15:13 and 15:00 entries).
Items 3–6 were considered at design time and documented as alternatives in
AMBIGUITIES.md, but were never implemented; the criterion-6 and criterion-8 analyses
above contain the arithmetic on which 3 and 5 were rejected. Nothing here is a
fabricated experiment.

1. **Grouping events by processing day instead of honoring stream order.**
   This specific implementation was abandoned because it violated the required
   stream order: grouping by processing day applied E10 (processing Day 5) ahead of
   E9 (stream position 9, processing Day 6), and
   `ReplayTest.allTenEventsAreInTheLogInStreamOrder` caught the reordering
   (WORKLOG.md, 15:13 entry). Grouping per se is not invalid — a correct
   implementation could group while preserving stream order within and across
   groups — but it added an ordering layer whose only effect was the defect.
   Replaced by stream-order processing with evening assessments triggered on
   processing-date advancement. (AMBIGUITIES.md #20.)
2. **Marking account-days as fee-assessed even when no fee was charged.**
   Caught by code review before the first successful run (WORKLOG.md, 15:00 entry):
   it would have made the Day-2 fee impossible to assess when E7 arrived on Day 5.
   Replaced by marking only days where a fee decision is actually made, with
   re-examination of the others. (AMBIGUITIES.md #11.)
3. **Full-recompute fee projection (fees as a pure function of the final stream).**
   Considered at design time and analyzed arithmetically in the criterion-6
   evaluation above; never implemented. Under the CHOSEN policy, assessed fees are
   immutable derived entries: correcting an assessed charge would require a new,
   explicit compensating entry (e.g. a fee-refund event), never a recalculation that
   erases it. Full recompute remains a defensible alternative whose outcome depends
   on the fee policy in force and on whether prior charges are reversed via
   compensation or recalculated; it was not chosen because retroactively changing
   customer-visible charges weakens the audit trail and couples fee income to event
   arrival order. (AMBIGUITIES.md #10.)
4. **Clipping excess settlements to the hold amount.** Considered at design time as
   an alternative (documented in AMBIGUITIES.md #7); rejected without implementation
   because silently changing the merchant's amount hides discrepancies. Replaced by
   full rejection of excess settlements.
5. **Rounding the aggregate interest and allocating the residual to the last day.**
   Evaluated arithmetically when deciding criterion 8 (the 0.918 → 0.92 vs 0.93
   computation above); abandoned in favor of sum-of-rounded-dailies, which makes the
   reconciliation identity exact by construction and keeps daily statement lines
   equal to what was capitalized for them. (AMBIGUITIES.md #13.)
6. **Value-date-effective authorization holds.** Considered at design time
   (documented in AMBIGUITIES.md #4); rejected without implementation because holds
   are operational reservations of current spending power and back-valued holds
   would destabilize past approval decisions. Replaced by processing-time holds.
