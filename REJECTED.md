# REJECTED — Evaluation of the eight supplied acceptance criteria

Each criterion is evaluated independently against the documented model
(AMBIGUITIES.md). Statuses: **ACCEPTED**, **REJECTED**, **ACCEPTED WITH
QUALIFICATION**. Reference figures (derived in NUMBERS.md and verified by the test
suite):

- End-of-Day-5 pre-fee value-date closings, ACC-001: D1 250.00, D2 −370.00,
  D3 5.00, D4 −180.00, D5 −205.00.
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

## Criterion 2 — REJECTED (incorrect arithmetic, not a policy dispute)

> "E7 causes exactly one overdraft fee to be assessed, on Day 2."

**Arithmetic.** E7 makes Day 2 negative (−370.00 → fee F1). But the negativity
propagates forward regardless of whether fees participate in later bases:

- Day 3 pre-fee (fee-participating model): −370.00 − 25.00 + 400.00 = **+5.00** → no fee.
- Day 4 pre-fee: 5.00 − 185.00 = **−180.00** → fee F2.
- Day 5 pre-fee: −180.00 − 25.00 = **−205.00** → fee F3.

Even under the alternative model where fees are excluded from subsequent bases, Day 4
is 250.00 − 620.00 + 400.00 − 185.00 = −155.00 → still negative, still a fee. So
"exactly one fee" fails under every reasonable model: E7 causes **three** fees (value
days 2, 4, 5), totalling AED 75.00. The "on Day 2" part is correct for the first fee
only. This is incorrect arithmetic in the criterion, not an underspecified rule.

**Assumption stated**: fees are assessed on the evening of processing Day 5, once per
negative account-day, and persist.

**Enforced by**: `ReplayTest.exactlyOneFeePerNegativeAccountDayWithAssessedDayValueDate`
(asserts exactly 3 fees with value days 2/4/5 and pre-fee balances
−370.00/−180.00/−205.00).

## Criterion 3 — ACCEPTED

> "The Day 4 settlement of Auth-A must be accepted."

**State-transition reasoning.** E3 (Day 2): Auth-A approved — ledger 250.00, active
holds 0.00, available after hold 50.00 ≥ 0. E5 (Day 4): authorization exists, status
APPROVED (settleable), amount 185.00 > 0, 185.00 ≤ 200.00 (hold) → accepted. Posts
−185.00 valued Day 4; authorization → PARTIALLY_SETTLED; hold released (residual
15.00 released).

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
processed (Day 5, after E7), the known ledger balance is 1200.00 − 950.00 + 400.00 −
185.00 − 620.00 = −155.00; −155.00 − 90.00 < 0 → Auth-B is **DECLINED** and creates
no hold. The mechanism the criterion describes is nonetheless exactly the implemented
semantics, demonstrated by Auth-A: its approved 200.00 hold makes Day-2 available
25.00 (= 225.00 − 200.00) while the ledger balance is untouched by the hold. The
report's Day-5 section prints the decline with its exact inputs.

**Enforced by**: `ReplayTest.authBIsDeclinedBecauseTheAccountWasOverdrawnAtProcessingTime`
and the Day-2 available-balance line in `ReportTest`.

## Criterion 6 — REJECTED (policy-dependent; the claim overreaches)

> "After E9, all balances and fees return to their pre-E7 values."

**Arithmetic and reasoning.** E9 compensates E7 exactly: the *pre-fee* value-date
balances do return to pre-E7 values (Day-2 pre-fee = +250.00 again). But the fees
assessed on the evening of Day 5 (3 × 25.00, value days 2/4/5) are immutable derived
entries under the chosen policy (AMBIGUITIES.md #10) and are NOT removed by E9.
Therefore post-fee balances do NOT return: Day-2 final = 225.00 ≠ 250.00, and
cumulative fees are 75.00 ≠ 0.00. Interest bases change too (Day-2 base 225.00, not
250.00).

**This is an underspecified business rule, not bad arithmetic in the criterion**:
under the alternative "full recompute" fee policy, E9 would erase the fee cause and
the criterion would hold. We rejected that alternative because retroactively erasing
assessed charges destroys audit history and couples fee income to event arrival
order; refunds should be explicit events. The criterion is rejected *because* of its
consequences under sound accounting, not because it conflicts with an arbitrary
implementation choice.

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

**Reasoning.** Discarding a remainder destroys money silently — unacceptable in an
accounting core and explicitly forbidden by the spec ("do not discard a rounding
remainder"). The chosen policy eliminates the discrepancy class entirely: the
capitalized total is **defined as** the exact sum of the rounded daily accruals, so
the invariant Σ daily == capitalized holds by construction (ACC-001: 0.10 + 0.09 +
0.25 + 0.17 + 0.16 + 0.16 = 0.93; ACC-002: 0.004 + 0.004 = 0.008).

**The discrepancy is real under the alternative**: rounding the aggregate gives
2295.00 × 0.0004 = 0.918 → 0.92 ≠ 0.93. Had we chosen aggregate-rounding, the 0.01
residual would have been allocated deterministically to the final day's accrual —
never discarded. This criterion is rejected on accounting principle (conservation),
independent of which reconciliation policy is in force.

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
evening of processing Day 5 (after E7 posted, before E9 existed) the pre-fee
closings were:

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

(Only approaches actually evaluated while building this codebase; see WORKLOG.md for
when.)

1. **Grouping events by processing day instead of honoring stream order.**
   Implemented first; abandoned when
   `ReplayTest.allTenEventsAreInTheLogInStreamOrder` failed — grouping applied E10
   (Day 5) before E9 (stream position 9), violating the mandated "precise processing
   order". Replaced by stream-order processing with evening assessments triggered on
   processing-day advancement. (AMBIGUITIES.md #20.)
2. **Marking account-days as fee-assessed even when no fee was charged.**
   Caught by code review before the first successful run: it would have made the
   Day-2 fee impossible to assess when E7 arrived on Day 5. Replaced by marking only
   days where a fee is actually charged, with re-examination of the others.
   (AMBIGUITIES.md #11.)
3. **Full-recompute fee projection (fees as a pure function of the final stream).**
   Evaluated while deciding criterion 6; abandoned because it retroactively erases
   customer-visible charges and makes fee income depend on event arrival order.
   Replaced by point-in-time assessment + persistence. (AMBIGUITIES.md #10.)
4. **Clipping excess settlements to the hold amount.** Evaluated for settlement
   validation; abandoned because silently changing the merchant's amount hides
   discrepancies. Replaced by full rejection. (AMBIGUITIES.md #7.)
5. **Rounding the aggregate interest and allocating the residual to the last day.**
   Evaluated for criterion 8; abandoned in favor of sum-of-rounded-dailies, which
   makes the reconciliation identity exact by construction and keeps daily statement
   lines equal to what was capitalized for them. (AMBIGUITIES.md #13.)
6. **Value-date-effective authorization holds.** Evaluated; abandoned because holds
   are operational reservations of current spending power and back-valued holds would
   destabilize past approval decisions. Replaced by processing-time holds.
   (AMBIGUITIES.md #4.)
