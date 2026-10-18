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
  document never calls either of them just "pre-fee" without qualification.
- Final (post-fee, post-reversal, post-compensation) closings, ACC-001:
  D1 250.00, D2 250.00, D3 650.00, D4 465.00, D5 465.00, D6 466.03.
- Fees: 3 × AED 25.00 CHARGED (valued Days 2, 4, 5; derived on the evening of
  processing Day 5) and 3 × AED 25.00 COMPENSATED (same value dates, derived on
  the Day-6 evening after E9) → net 0.00. All six entries remain in the
  append-only entry list (policy: AMBIGUITIES.md #10, #11, #28).
- Interest: bases 250/250/650/465/465/465 → accruals
  0.10 / 0.10 / 0.26 / 0.19 / 0.19 / 0.19 → 1.03 capitalized (ACC-001);
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
final end-of-window projection the Day-2 closing is 250.00 again (E9's +620.00
compensation, plus the −25.00 fee and its +25.00 compensation, all valued Day 2).

**Enforced by**: `ReplayTest.exactlyOneFeePerNegativeAccountDayWithAssessedDayValueDate`
(fee trace records preFeeBalance −370.00 for value day 2, assessed on day 5) and the
report's FEE ASSESSMENT TRACE.

## Criterion 2 — ACCEPTED WITH QUALIFICATION (interpretation-dependent)

> "E7 causes exactly one overdraft fee to be assessed, on Day 2."

**The criterion is genuinely ambiguous between materially different readings**, and
the verdict depends on the reading:

- **Reading (i) — value-date/idempotency reading: "exactly one fee is ever assessed
  FOR Day 2."** TRUE. Exactly one fee with value date Day 2 is ever assessed: it is
  charged on the Day-5 evening and never duplicated across any of the later
  boundary re-examinations (idempotency key account|valueDate). After E9 it is
  compensated — but compensation is not a second assessment, and the date is never
  re-charged. This reading fits the fee rule's own phrasing ("assess ONCE PER
  ACCOUNT PER DAY") and the assignment's pervasive value-date discipline.
- **Reading (ii) — totality reading: "E7 causes exactly one fee in total."** FALSE.
  On the Day-5 evening, E7's knock-on produced THREE assessments — arithmetic:
  D2: 250.00 − 620.00 = −370.00 → fee; D3: −370.00 − 25.00 + 400.00 = +5.00 → none;
  D4: 5.00 − 185.00 = −180.00 → fee; D5: −180.00 − 25.00 = −205.00 → fee (sequential
  fee-assessment balances; even excluding earlier fees from later bases, D4 =
  −155.00 and D5 = −155.00, still negative). All three are compensated after E9, but
  they WERE assessed.
- **Reading (iii) — processing-date reading: "the assessment happens on Day 2."**
  FALSE: on Day 2's own evening the books showed +250.00 (E7 had not arrived); the
  Day-2 fee was discovered and derived on processing Day 5.

**Chosen interpretation**: (i), and the criterion is ACCEPTED under it. Rationale:
the fee rule is defined per account-day, criterion 1 already established the
convention of naming the fee by its value date, and (i) is the only reading that is
both non-trivially true and consistent with the assignment's value-date framing.
**Qualification**: if the intended reading was (ii), the criterion is incorrect on
the arithmetic above (three fees, not one); if (iii), it misstates WHEN assessment
happens. All three readings are recorded here rather than silently picking one.

**Verified against code and tests**: exactly three `FEE_OVERDRAFT` entries (value
dates D2/D4/D5, derived on the Day-5 evening, pre-fee balances
−370.00/−180.00/−205.00), exactly one of them valued Day 2 — asserted by
`ReplayTest.exactlyOneFeePerNegativeAccountDayWithAssessedDayValueDate`.

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
ledger=AED 250.00 activeHolds=AED 0.00 requested=AED 200.00".) The report's Day-2
line shows ledger 250.00 and available 50.00 — an **end-of-window projected view**
computed from the complete stream (E7, E9, and the charged-then-compensated Day-2
fee included), not what was knowable at approval time. In this stream the projected
ledger balance coincides numerically with the approval-time one (the late debit and
all its fee effects net out), but the two views answer different questions and are
never mixed in the engine: the approval decision used only processing-time
knowledge.

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
(465.00, not 285.00), proving the funds did not move.

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
- **End-of-window projection view**: the report's Day-2 line shows ledger 250.00
  and available 50.00 (= 250.00 − 200.00). This is a *later projected* Day-2 figure
  that includes E7, E9, and the charged-then-compensated Day-2 fee — coincidentally
  equal to the approval-time ledger balance in this stream, but computed from the
  complete stream, not from what was known at approval time.

**Enforced by**: `ReplayTest.authBIsDeclinedBecauseTheAccountWasOverdrawnAtProcessingTime`
and the Day-2 available-balance line in `ReportTest`.

## Criterion 6 — ACCEPTED WITH QUALIFICATION (policy changed in the final review)

> "After E9, all balances and fees return to their pre-E7 values."

**History of this verdict**: earlier phases of this project REJECTED the criterion
on the basis of a *chosen* policy ("assessed fees are immutable and remain
effective forever"). The final acceptance review established that this reasoning
was circular: no explicit requirement mandates fee persistence. Append-only applies
to events and entries — and an append-only ledger can keep the original fee entries
AND neutralize them with compensating fee-reversal entries. The policy was changed
accordingly (WORKLOG.md, phase 8).

**Approaches compared** (all replayable, deterministic, append-only):

| Approach | Criterion 6 | Auditability | Verdict |
|---|---|---|---|
| Persist fees forever | FAILS (D2 = 225.00, fees 75.00) | full history, but keeps charges for an overdraft the corrected books say never happened | rejected: fails the criterion without a tier-1 justification; makes fee income depend on event arrival order |
| **Compensate via FEE_REVERSAL entries (chosen)** | HOLDS in net terms (D2 = 250.00, net fees 0.00) | fullest history: charge AND compensation both visible | satisfies the criterion without violating append-only, replayability, or idempotency |
| Full recompute (fees as pure projection) | HOLDS | loses point-in-time history (the fact that fees were assessed on Day 5 disappears) | not chosen: the assignment wants fee assessments as derived accounting ENTRIES with an audit trail |

**Result under the chosen policy**: E9 compensates E7 exactly; the Day-6 evening
reconciliation finds no date overdrawn and appends +25.00 compensations valued on
Days 2/4/5. Final balances D1–D6 = 250.00 / 250.00 / 650.00 / 465.00 / 465.00 /
466.03 — the pre-E7 values (D5/D6 did not exist pre-E7; 465.00 is their pre-E7
would-be value). Net cumulative fees = 0.00 = the pre-E7 value. The criterion HOLDS.

**Qualification**: "return" holds in NET terms. The six fee-related entries (3
charges + 3 compensations) remain in the entry list — that is what append-only
means. If the criterion were read as "the log contains no trace of the fees", it
would conflict with the explicit append-only requirement, and that requirement
would take precedence. Under the only reading compatible with append-only (net
financial effect), the criterion is satisfied.

**Enforced by**: `ReplayTest.e9LeavesFeeEntriesIntactAndCompensatesThem`,
`ReplayTest.finalDailyClosingsMatchHandDerivation`.

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

**Arithmetic (unchanged policy; figures reflect the fee-compensation policy).** The
daily AED accruals (each rounded HALF_UP to 2dp) are 0.10 + 0.10 + 0.26 + 0.19 +
0.19 + 0.19 = **1.03**, and the capitalized total is defined as exactly that sum,
so no remainder ever arises under the chosen policy. The discrepancy is real under
the alternative aggregate-rounding approach: the sum of the daily bases is
250.00 + 250.00 + 650.00 + 465.00 + 465.00 + 465.00 = 2545.00, and
2545.00 × 0.0004 = 1.0180 → rounded **1.02** ≠ 1.03 — a difference of **AED 0.01**
that must not be silently discarded under the specification.
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

**Verdict (updated for the fee-compensation policy adopted in the final review):
only D1 matches; the D2/D3 (225.00/625.00) and D4–D6 (440.00) figures are REJECTED
as incompatible with the stated fee rules.** The requested set assumes exactly one
PERSISTENT fee (D2). Under the documented policy the Day-2 fee is assessed on the
Day-5 evening and then compensated on the Day-6 evening, so the final projections
are D2 = 250.00, D3 = 650.00, D4–D6 = 465.00. This is an arithmetic finding, not a
policy preference — and it held under the old persistence policy too (which gave
225.00/625.00/415.00/390.00/390.00):

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
the plain fee rule whenever all value dates are examined on the D5 evening. (Under
the fee-compensation policy adopted in the final review, all three fees are
compensated after E9 and the final pre-capitalization projections are
D4 = D5 = D6 = 465.00 — still not 440.00, because 440.00 needs the D2 fee to persist
while the D4/D5 fees do not.)

The 440.00 figures can only be produced by a model that either:

1. **erases the D4/D5 fees when E9 arrives but keeps the D2 fee** — no coherent
   trigger rule exists for compensating exactly the knock-on fees while keeping the
   direct one (E9's compensation neutralizes D2's overdraft just as much as D4's);
   retroactive erasure of entries would also violate append-only; or
2. **never assesses D4/D5 on the D5 evening despite their negative closings** —
   violating "assess … when that day's closing ledger balance is negative" and the
   review's own requirement that "all relevant value dates are examined"; or
3. **assesses the current date using only events processed BEFORE that date**
   (lagged assessment) plus targeted reassessment of back-valued dates only — a
   coherent but contrived policy under which the D5 evening would ignore E7 (a known
   fact) when closing D5, and which would still have charged D5 = −155.00 on any
   non-lagged reading.

All three were evaluated and rejected: (1) has no coherent rule and breaks
append-only, (2) contradicts the fee rule's plain text, (3) trades one
inconsistency for a stranger one (a date reported as −180.00 point-in-time yet
never fee-charged).

**Consequence** (updated for the fee-compensation policy): D1 = 250.00,
D2 = 250.00, D3 = 650.00 are asserted (`DailyProjectionTest`); D4–D6 are asserted
as 465.00 / 465.00 / 465.00 pre-capitalization (D6 = 466.03 after the 1.03
capitalization). Note: 440.00 matches no self-consistent policy we could construct
— persistence of only the D2 fee (415/390/390 under the old policy), full
compensation (465), and persistence of all fees (415/390/390) all disagree with it.
This mirrors the totality reading of criterion 2 (addressed above).

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
   Evaluated arithmetically when deciding criterion 8 (the 1.0180 → 1.02 vs 1.03
   computation above); abandoned in favor of sum-of-rounded-dailies, which makes the
   reconciliation identity exact by construction and keeps daily statement lines
   equal to what was capitalized for them. (AMBIGUITIES.md #13.)
6. **Value-date-effective authorization holds.** Considered at design time
   (documented in AMBIGUITIES.md #4); rejected without implementation because holds
   are operational reservations of current spending power and back-valued holds
   would destabilize past approval decisions. Replaced by processing-time holds.
7. **Fee persistence (assessed fees remain effective forever).** This one WAS the
   implemented policy for most of the project (phases 1–7, visible in the Git
   history and WORKLOG) — not merely considered. It was abandoned in the final
   acceptance review (WORKLOG phase 8): the review established that no explicit
   requirement mandates persistence, that append-only history is fully preserved by
   compensating fee-reversal entries, and that persistence failed acceptance
   criterion 6 while keeping charges for an overdraft the corrected books say never
   happened. Replaced by deterministic fee reconciliation with append-only
   FEE_REVERSAL compensation (AMBIGUITIES.md #10, #11, #28). Its trade-off remains
   documented in AMBIGUITIES.md #10 as a defensible alternative.
