# NUMBERS — every meaningful numeric constant

For each constant: what it is, why the value is appropriate, what halving it would
mean, and whether it is a **supplied requirement** or an **implementation choice**.

## Business constants (supplied requirements)

### Overdraft fee: explicit per-currency schedule — default contains ONLY AED 25.00
- **Source**: AED 25.00 is the supplied requirement. The default
  `Scenario.OVERDRAFT_FEE_SCHEDULE` contains nothing else: the requirements do not
  authorize a BHD fee, so none is configured (AMBIGUITIES.md #15). The engine
  validates the schedule at construction (fee currency must equal the key, fee must
  be positive); a currency absent from the schedule is charged NOTHING, and the skip
  is recorded in the replay trace (`SkippedFeeAssessment`) — no silent fees, no
  silent omissions. A BHD fee (e.g. BHD 25.000, same numeric value in account
  currency) exists only as a tested configuration mechanism awaiting a business
  decision.
- **Why appropriate**: AED 25.00 is the contract value; everything else is
  configuration rather than code.
- **Halving it** (12.50): would halve every fee and compensation line in the trace
  (−12.50/+12.50 instead of −25.00/+25.00). In THIS scenario the final ACC-001
  balance is unchanged (466.03) because all three fees are compensated after E9 —
  the fee amount moves the final balance only when a window ends with an effective
  (uncompensated) fee. Meaningful change, wrong contract.

### Daily interest rate: 0.0004 (0.04%)
- **Source**: supplied requirement.
- **Why appropriate**: contract value; encoded once as `LedgerEngine.DAILY_RATE`.
  Applied as exact decimal multiplication, rounded HALF_UP to currency scale.
- **Halving it** (0.0002): halves all accruals; e.g. Day-3 accrual 650.00 × 0.0002 =
  0.1300 → 0.13 (HALF_UP) vs 0.26 today; capitalized total becomes 0.51 for ACC-001.
  Meaningful, and not the contract.

### Currency decimal scales: AED = 2, BHD = 3
- **Source**: supplied requirement (matches ISO-4217 minor units).
- **Why appropriate**: defines the representable quantum; `Money` pins scale at
  construction and rejects anything finer (`RoundingMode.UNNECESSARY`).
- **Halving it**: meaningless — a scale of 1 for AED would make fils unrepresentable;
  scale is a fact about the currency, not a tunable. No halving applies.

### Opening balances: 0.00 AED / 0.000 BHD
- **Source**: supplied requirement ("zero opening balances").
- **Halving it**: halving zero is zero; meaningless. The value is a requirement.

### Event amounts (E1..E10)
1200.00 / 950.00 (E1, E2); hold 200.00 (E3); 400.00 (E4); settlement 185.00 (E5);
settlement 180.00 (E6, rejected); 620.00 (E7, valued Day 2); hold 90.00 (E8);
E9 reverses E7; 10.000 in 3 instalments (E10).
- **Source**: supplied requirements, transcribed verbatim into `Scenario`.
- **Halving them**: changes the scenario; these are inputs, not policy. Not applicable
  beyond noting that the tests derive expectations from these inputs by arithmetic,
  so changing an input changes the derived expectations mechanically.

## Implementation-choice constants

### Horizon: 6 days, mapped to business dates 2026-01-01 .. 2026-01-06
- **Source**: the length (6 days) is an implied requirement (report Days 1–6;
  interest capitalized end of Day 6). The concrete calendar dates are an
  implementation choice: the engine works on relative windows, so any six
  consecutive dates produce identical results. Encoded as `Scenario.WINDOW_START` /
  `WINDOW_END`; `Scenario.day(n)` maps the spec's "Day n".
- **Why**: the evaluation window is load-bearing for the scenario. Events with
  dates outside the window are REJECTED (`EVENT_DATE_OUT_OF_HORIZON`,
  AMBIGUITIES.md #24) because their postings would be invisible to fee and
  interest derivation.
- **Halving it** (3 days): no fees would ever be assessed (E7 arrives Day 5) and no
  interest capitalized — the window is load-bearing for the scenario.

### Business time zone: UTC (`Scenario.BUSINESS_ZONE`)
- **Source**: implementation choice. The engine requires an explicit `ZoneId`; it is
  used only for deriving business dates from instants (`BusinessDates.businessDate`),
  which the current date-only inputs never trigger.
- **Why UTC**: neutral, no DST transitions, deterministic everywhere.
- **Halving it**: not applicable to a zone. Choosing a different zone only matters
  when instants are converted (same instant can map to different dates in different
  zones — see `BusinessDatesTest`).

### Rounding mode: HALF_UP, only for rate multiplication
- **Source**: implementation choice (spec requires "a consistent, explicit rounding
  policy" without naming one).
- **Why**: HALF_UP is the most common commercial rounding and symmetric for positive
  amounts; restricting rounding to `Money.timesRate` makes it the single, auditable
  rounding site. Everything else (add/subtract/allocation) is exact.
- **Halving it**: not applicable to a rounding mode. The boundary behavior is pinned
  by `MoneyTest.interestRoundingIsHalfUpAtCurrencyScale` (12.50 × 0.0004 = 0.005 →
  0.01; 12.49 × 0.0004 → 0.00; BHD 1.250 × 0.0004 = 0.0005 → 0.001).

### Interest reconciliation: capitalized total = Σ rounded daily accruals
- **Source**: implementation choice (spec demands exact equality and no discarded
  remainder; this policy achieves it by construction).
- **Consequence on this dataset**: ACC-001 1.03 (vs 1.02 if the aggregate were rounded
  — an observable difference, documented in REJECTED.md #8).
- **Halving it**: not applicable; it is a rule, not an amount.

### Instalment count: 3; allocation base 3333 minor units + remainder to last
- **Source**: count 3 is a supplied requirement; the allocation strategy is an
  implementation choice (spec prefers residual allocation over independent rounding).
- **Why**: 10000 minor units / 3 = 3333 remainder 1 → [3.333, 3.333, 3.334]; sum is
  exactly 10.000. Conservation is asserted in the allocator itself (defensive
  postcondition) and by tests.
- **Halving the count**: not meaningful (3 is required). Halving the total (5.000)
  would allocate [1.666, 1.667, 1.667] — the algorithm is unchanged.

### Available-balance threshold: ≥ 0 (exactly zero approves)
- **Source**: supplied requirement ("at least zero").
- **Consequence**: `exactZeroAvailableBalanceIsApproved` pins the boundary (100.00
  ledger, 100.00 hold → APPROVED; 99.99 → DECLINED one fils below).
- **Halving it**: not applicable to a threshold of zero.

### Idempotency / duplicate guards
- Fee key `(accountId, valueDay)` and reversed-event-id set are sets, not numbers;
  halving is not applicable. They encode "exactly once" semantics, enforced by
  `negativeBalanceEarnsNoInterest` (6 negative days → exactly 6 fees, all distinct
  value days) and `duplicateReversalIsRejected`.

## Test-only constants that encode business assumptions

- `BoundaryAndValidationTest`: synthetic accounts "A" (AED) and "B" (BHD), amounts
  10.00 / 100.00 / 99.99 / 100.01 / 500.00 / 300.00 etc. These exist to isolate one
  rule each (zero balance, negative balance, exact-zero approval, one-fils decline,
  excess settlement, duplicate settlement/reversal, BHD fee currency). They are
  assumptions about *rule boundaries*, not about the E1..E10 scenario; each test
  comment derives its expected value from these inputs.
- `IntentionalFailureTest`: compares the Day-2 net fee effect as of the Day-5
  evening (−25.00, stream without E9) with the final one (0.00, after the Day-6
  compensation). Both numbers are derived from the stream; their difference is the
  demonstrated lesson (immutable entries, compensatable effects).
