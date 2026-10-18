# WORKLOG

All timestamps are real, taken from the environment clock (`date`), Asia/Kolkata (IST).

## 2026-10-18 14:38 IST — Repository and toolchain inspection

- `git status`: clean working tree, branch `main`, tracking `origin/main`.
- `git log --oneline`: 3 existing commits (`4102fc3` merge, `e327ccf` initial, `de3eca5` initial). No relevant prior implementation work; history will be preserved untouched.
- Existing files: `pom.xml` (groupId `com.ledger.core.service`, Java 21 source/target, no dependencies), `README.md` (one-line stub), `.gitignore`, `.idea/`, empty `.mvn/`, `src/main/java/com/ledger/core/service/Main.java` (IntelliJ template "Hello and welcome").
- Toolchain: OpenJDK 23.0.1 installed; `mvn` and `gradle` NOT installed. `.mvn/wrapper` exists but contains no wrapper jar.
- Decision: downloaded Apache Maven 3.9.16 binary into the isolated temp directory `/var/folders/hw/5jf7d2f95v5chmllqf63pkch0000gp/T/opencode/apache-maven-3.9.16` (verified `mvn -version` works with Java 23). Maven local repo will also be kept in the temp dir (`-Dmaven.repo.local=...`) to avoid touching anything outside the workspace. JDK 23 compiles `--release 21` fine, so the pom's Java 21 target is honoured.
- Network access to Maven Central confirmed (`curl -sI https://repo.maven.apache.org/maven2/` → HTTP 200).

## 2026-10-18 14:40 IST — Implementation plan and key policy decisions

Plan (committed to before writing code):

1. Project setup: extend `pom.xml` with JUnit 5, Surefire (tag-based exclusion of the intentional-failure test), exec-maven-plugin for the replay report.
2. Domain model (DDD packages): `domain.money` (CurrencyCode, Money), `domain.event` (sealed LedgerEvent hierarchy + EventEnvelope), `domain.entry` (LedgerEntry, EntryKind), `domain.auth` (Authorization, AuthorizationStatus), `domain.ledger` (append-only EventLog).
3. Application layer: `LedgerEngine` (deterministic replay, end-of-processing-day fee assessment, interest capitalization), `ReportGenerator`.
4. `Main` replay executable printing the six-day report.
5. Tests (normal suite) + one deliberately failing test under JUnit tag `intentional-failure`.
6. Documentation: README, ARCHITECTURE, AMBIGUITIES, REJECTED, NUMBERS.

Key policy decisions (each to be documented in AMBIGUITIES.md with alternatives):

- Projection convention: per-day balances in the report are end-of-window value-date projections over the complete stream; a separate point-in-time fee-assessment trace is printed for auditability.
- Fees: assessed at end of each processing day for every value day whose pre-fee closing balance (including previously assessed fee entries) is negative; at most one fee per (account, value day); fee entries are immutable once derived and are NOT retroactively removed by later reversals; fee denominated in the account's own currency (BHD account would be charged BHD 25.000 — never fires in this scenario).
- Interest: daily accrual = post-fee closing balance x 0.0004, rounded HALF_UP to currency scale, positive balances only; capitalized total is DEFINED as the exact sum of rounded daily accruals (no remainder ever discarded); one capitalization credit per account at end of Day 6 (value date Day 6).
- Instalments: minor-unit integer split, residual minor units allocated to the LAST instalment(s): BHD 10.000 -> 3.333 + 3.333 + 3.334.
- Authorization validation uses the ledger balance known at processing time (all events processed so far, value-date filtered up to the processing day, plus fee entries derived so far). Holds are processing-time constructs. Consequence: Auth-B is DECLINED (available balance is negative when E8 is processed, after E7).
- Settlement validity comes from the authorization (exists, status APPROVED, amount <= hold), not from account funds. Valid settlement posts the debit and releases the hold entirely (residual released). Unknown/excess/duplicate settlements are rejected and recorded; no ledger entry, no auth state change.
- Reversal = new immutable compensating entry (opposite sign, same amount, value date from the reversal event); originals never mutated; duplicate reversals of the same event rejected.
- Rejected business events are kept in the event log with a derived REJECTED outcome; they produce no ledger entries and are printed in the report.

Hand-computed expected results (to be cross-checked against the implementation):

- End-of-Day-5 pre-fee value-date balances, ACC-001: D1 250.00, D2 -370.00, D3 5.00 (includes FEE-D2), D4 -180.00, D5 -205.00 -> fees assessed that evening for value days 2, 4, 5 (3 x AED 25.00 = 75.00).
- Final post-fee closings, ACC-001: D1 250.00, D2 225.00, D3 625.00, D4 415.00, D5 390.00, D6 390.00.
- Interest accruals ACC-001: 0.10, 0.09, 0.25, 0.17, 0.16, 0.16 -> capitalized 0.93 -> final 390.93.
- ACC-002: closings 0.000 (D1-D4), 10.000 (D5), 10.000 (D6); accruals 0.004 + 0.004 = 0.008 capitalized -> final 10.008.
- Auth-A: APPROVED (D2) -> PARTIALLY_SETTLED (185/200, residual 15.00 released). Auth-B: DECLINED. Auth-Z settlement: REJECTED (unknown authorization).

## 2026-10-18 14:55 IST — Domain model implemented and committed

- Wrote `domain.money` (CurrencyCode, Money, CurrencyMismatchException), `domain.account`, `domain.event` (sealed LedgerEvent + EventEnvelope), `domain.entry`, `domain.auth`, `domain.ledger` (append-only EventLog).
- `mvn compile`: BUILD SUCCESS. Committed as `0c9ff10`.

## 2026-10-18 15:00 IST — Engine, report, Main implemented and committed

- Wrote `InstalmentAllocator`, `LedgerEngine`, `ReplayResult`, `ReportGenerator`, `Scenario`, `Main`.
- First compile failed: missing import of `AuthorizationStatus` in ReportGenerator. Fixed.
- Caught a real defect by reading my own code before running: the fee idempotency set marked account-days as "assessed" even when NO fee was charged, which would have prevented the Day-2 fee from ever being assessed when E7 arrived on Day 5. Fixed: only account-days with an actually charged fee are marked; non-negative days are re-examined on later evenings.
- Ran `mvn compile exec:java`. Full six-day report matched the hand-derived numbers from the plan exactly (ACC-001 final 390.93, fees 3 x 25.00 valued on days 2/4/5, interest 0.93; ACC-002 final 10.008, instalments 3.333/3.333/3.334).
- Correction to plan arithmetic: I had hand-computed the ledger balance at E8 processing time as -555.00 (forgot E4 +400.00); the engine's -155.00 is correct. Auth-B is DECLINED either way.
- Committed as `f259ece`.

## 2026-10-18 15:13 IST — Tests written; three failures found and fixed

- Wrote MoneyTest (7), InstalmentAllocatorTest (4), ReplayTest (16), BoundaryAndValidationTest (12), ReportTest (6), IntentionalFailureTest (1, tagged `intentional-failure`).
- First `mvn test` run: 45 tests, 3 genuine failures:
  1. `ReplayTest.allTenEventsAreInTheLogInStreamOrder`: log order was E1..E8,E10,E9. Root cause: the engine grouped events by processing day, which reordered the stream (E10, processing day 5, was applied before E9, position 9 in the stream). The specification mandates the precise stream order as the processing order. FIX (production code): engine now applies events strictly in stream order and runs end-of-day fee assessment when the processing day advances; an event with an earlier processing day than one already seen (E10 after E9) is still applied in order and picked up by the next assessment. Documented in AMBIGUITIES.md.
  2. `BoundaryAndValidationTest.negativeBalanceEarnsNoInterest`: my expected value was internally inconsistent (asserted 1 fee, then reasoned 6). Correct derived value: 6 fees (every day closes negative). Fixed the test.
  3. `InstalmentAllocatorTest.remainderOfTwoMinorUnitsGoesToTheLastTwoInstalments`: arithmetic slip — 1000 minor units / 3 = 333 remainder 1, not 2. Changed the case to AED 10.01 (1001 = 333 r 2) so it actually tests a remainder of 2. Fixed the test.
- After fixes: `mvn test` → Tests run: 45, Failures: 0, Errors: 0 — BUILD SUCCESS.

## 2026-10-18 15:20 IST — Intentional-failure test verified

- Ran `mvn test -Dsurefire.groups=intentional-failure -Dsurefire.excludedGroups=`: exactly 1 test run, 1 failure, with the documented message (Day-2 closing 250.00 as known at end of Day 2 vs 225.00 in the final projection). Failure is the intended demonstration; normal suite stays green and isolated.

## 2026-10-18 15:22 IST — Independent recomputation of key figures

Cross-checked engine output by hand (value-date order, ACC-001):
- Source postings: +1200.00@1, -950.00@1, -620.00@2 (E7), +620.00@2 (E9), +400.00@3, -185.00@4.
- Pre-fee closings as computable at end of Day 5: D1 250.00; D2 -370.00; D3 -370-25+400=5.00 (fee participation); D4 5-185=-180.00; D5 -180-25=-205.00 -> fees D2, D4, D5 = 75.00 total.
- Final closings: D1 250.00, D2 250-25=225.00, D3 625.00, D4 415.00, D5 390.00, D6 390.00.
- Interest: 250x0.0004=0.1000->0.10; 225->0.09; 625->0.25; 415->0.1660->0.17; 390->0.1560->0.16; 390->0.16. Sum 0.93 -> final 390.93.
- ACC-002: 3.333+3.333+3.334=10.000 exactly; 10.000x0.0004=0.004000->0.004 (D5, D6); capitalized 0.008 -> final 10.008.
- All match the engine report. No discrepancies.

## 2026-10-18 15:35 IST — Engine fix committed

- Committed stream-order processing + full test suite as `a497608`.

## 2026-10-18 15:50 IST — Documentation written

- Rewrote README.md (purpose, prerequisites, commands incl. the isolated
  intentional-failure command, actual replay output with sections explained, key
  assumptions, layout).
- Wrote ARCHITECTURE.md (domain objects, event/entry split, replay model, auth state
  machine, settlement atomicity, reversal semantics, fee idempotency, interest
  rounding, currency safety, invariants mapped to tests, six real alternatives with
  trade-offs, production gaps).
- Wrote AMBIGUITIES.md — 21 ambiguities, each with policy/rationale/alternative/
  consequence/enforcing test.
- Wrote REJECTED.md — verdicts: 1 ACCEPTED, 2 REJECTED, 3 ACCEPTED, 4 ACCEPTED,
  5 ACCEPTED WITH QUALIFICATION, 6 REJECTED, 7 REJECTED, 8 REJECTED, each with
  arithmetic and stated assumptions; plus the six actually-abandoned approaches.
- Wrote NUMBERS.md — all constants with source (requirement vs choice) and
  halving analysis.
- Committed documentation as `9f44c3c`.

## 2026-10-18 16:05 IST — Final verification pass (clean state)

- `mvn clean test` → Tests run: 45, Failures: 0, Errors: 0 — BUILD SUCCESS.
- `mvn test -Dsurefire.groups=intentional-failure -Dsurefire.excludedGroups=` →
  Tests run: 1, Failures: 1 — fails with exactly the documented message
  (AED 250.00 vs AED 225.00). Expected outcome; isolated from the normal suite.
- `mvn -q compile exec:java` → full 169-line report printed; summary figures match
  the independent recomputation above.
- Git identity: inherited from the existing repository configuration (prior commits
  share the same author); no history was rewritten, no squashing performed.

## Summary of actual results

- ACC-001 (AED): final 390.93; fees 3 × 25.00 valued D2/D4/D5 (total 75.00);
  interest 0.10/0.09/0.25/0.17/0.16/0.16, capitalized 0.93.
- ACC-002 (BHD): final 10.008; instalments 3.333/3.333/3.334; interest
  0.004/0.004, capitalized 0.008.
- Auth-A PARTIALLY_SETTLED (185.00/200.00, residual released); Auth-B DECLINED;
  E6 (Auth-Z) REJECTED — unknown authorization, no funds moved.

---

# Phase 2 — Review and improvement task

## 2026-10-18 17:38 IST — Baseline

- Repo clean on `main` at `954ca46`, 6 commits ahead of origin.
- Baseline run: `mvn clean test` → 45 tests, 0 failures — BUILD SUCCESS.

## 2026-10-18 17:40 IST — Review findings and plan

Bugs / gaps found by inspection of `LedgerEngine` and domain classes:

1. **Reversal state-mutation bug (real bug)**: `reverse()` calls
   `reversedEventIds.add(targetEventId)` BEFORE validating the target exists and is
   eligible. A failed reversal (e.g. unknown target) poisons the id so a later,
   legitimate reversal of an event with that id is rejected as DUPLICATE_REVERSAL.
2. **Negative/zero amounts**: `postFinancial` rejects zero but not NEGATIVE amounts —
   a `Credit(-50)` would post as a backdoor debit. `InstalmentCredit` with a zero
   total posts zero-value entries. No business rule permits either.
3. **Duplicate event IDs**: a second event with an existing id would double-post and
   silently overwrite the first event's outcome in the `Map<String, Outcome>` — a
   data-integrity and idempotency hole.
4. **Out-of-horizon dates**: an event whose value day is outside the window is
   accepted but its posting is invisible to fee assessment and reports (assessment
   only covers days up to the processing day, interest only up to the horizon) —
   silent inconsistency. No validation existed.
5. **Time model**: integer days work but the review task asks for a clearer model.
   Plan (minimal, documented): `LocalDate processingDate` + `LocalDate valueDate` +
   explicit `long streamPosition` on `EventEnvelope`; a configured `ZoneId` on the
   engine as the ONLY sanctioned way to derive business dates from instants
   (utility + test). `Instant occurredAt/receivedAt` deliberately NOT added: the
   input stream carries no instants and no rule consumes them — adding them would
   fabricate data (documented in AMBIGUITIES.md).

Plan: (A) correctness fixes + reversal tests; (B) time-model migration + duplicate
event-id rejection + out-of-horizon validation + outcome keying by stream position;
(C) documentation updates. Existing business rules (fee persistence, settlement
policy, interest policy) are preserved — review found no contradiction.

## 2026-10-18 17:44 IST — Stage A committed (861b31f)

- Fixed the reversal state-poisoning bug: `reversedEventIds.add()` now happens only
  AFTER successful validation and posting (validate-then-post-then-mark).
- Added NON_POSITIVE_AMOUNT validation for Credit/Debit and
  NON_POSITIVE_INSTALMENT_TOTAL for instalment credits.
- New ReversalTest (8 tests): unknown-target poisoning regression, rejected-reversal
  mutates nothing, duplicate reversal, cross-account reversal, reversal of
  authorization/rejected events, reversal of derived fee ids, reversal-of-reversal,
  multi-entry instalment reversal in full (single -10.000 compensation).
- `mvn test`: 55 tests, 0 failures — BUILD SUCCESS.

## 2026-10-18 18:00 IST — Stage B committed (4b51884)

- Time model migrated: `EventEnvelope(eventId, streamPosition, processingDate,
  valueDate, event)` — LocalDate processing/value dates + explicit 1-based stream
  position. Specification Day n -> 2026-01-0n via `Scenario.day(n)`; window is
  `Scenario.WINDOW_START..WINDOW_END`; engine takes an explicit `ZoneId`
  (BUSINESS_ZONE = UTC) with `businessDate(Instant)` as the only sanctioned
  Instant->date conversion (BusinessDates utility + BusinessDatesTest proving the
  same instant maps to different dates in different zones).
- Outcomes keyed by streamPosition (duplicate event ids can no longer overwrite each
  other); duplicate event id -> DUPLICATE_EVENT_ID rejection; duplicate
  streamPosition -> fail-fast IllegalArgumentException (data-contract violation).
- Out-of-horizon processing/value dates -> EVENT_DATE_OUT_OF_HORIZON rejection.
- ReportGenerator renders " DAY n (yyyy-MM-dd)" sections, date-labelled traces and
  summaries; replay output figures unchanged (390.93 / 10.008 / fees 3x25 / 0.93 /
  0.008) — verified by full run.
- `mvn test`: 62 tests, 0 failures — BUILD SUCCESS. Intentional-failure test still
  fails for its documented reason when run via its tag (250.00 vs 225.00).

## 2026-10-18 18:10 IST — Stage C committed (77dc38d) and final verification

- Docs updated: AMBIGUITIES.md gained #22 (reversal validate-then-post ordering),
  #23 (time model), #24 (out-of-horizon rejection), #25 (duplicate event ids),
  #26 (non-positive amounts); #21 rewritten for the corrected reversal rules.
  ARCHITECTURE.md gained a Time model section and a Performance notes section
  (complexity of closingBalance/assessFees/capitalizeInterest documented; no index
  added — the snapshot-invalidation trap for late value-dated events is exactly what
  the intentional-failure test demonstrates). NUMBERS.md updated (window dates,
  business zone). README.md updated (date-bearing report example, time model section,
  layout, test count 45 -> 62).
- Final verification from clean state: `mvn clean test` -> 62 tests, 0 failures,
  BUILD SUCCESS; `mvn test -Dsurefire.groups=intentional-failure
  -Dsurefire.excludedGroups=` -> 1 test, 1 failure (expected, documented);
  `mvn -q compile exec:java` -> report figures unchanged (390.93 / 10.008 / -75.00 /
  0.93 / 0.008).

---

# Phase 3 — correctness review pass

## 2026-10-18 18:26 IST — Baseline

- Repo clean on `main` at `70b0a18`, 10 commits ahead of origin. Baseline:
  `mvn clean test` -> 62 tests, 0 failures — BUILD SUCCESS.

## 2026-10-18 18:30 IST — Review findings and plan

Reviewed LedgerEngine, domain classes, and tests against the phase-3 task list:

1. Late backdated events (E7): already handled — evening assessment after E7 posts
   reassesses D2..D5; covered by existing tests. No change needed.
2. **Overdraft fee policy**: the engine applies a single hardcoded numeric "25" in
   the ACCOUNT's currency. Although documented (AMBIGUITIES #15), the fee schedule is
   implicit — a reviewer's "do not silently charge BHD 25.000" point stands: the BHD
   amount was never an explicit decision point in code. FIX: make the fee an explicit
   per-currency schedule configured on the engine; AED 25.00 is the supplied
   requirement, BHD 25.000 becomes an explicit, documented configuration entry;
   currencies WITHOUT a configured fee produce a visible skipped-assessment record
   (no silent fee, no silent omission).
3. Fee idempotency: already keyed by account|valueDate, marked only on an actual
   decision. No duplicates possible; extend to the new skip path.
4. Reversal behavior: fixed in phase 2 (validate-then-post); covered by
   ReversalTest. No change needed.
5. **Date-boundary logic**: traced the evening-boundary loop for backward-moving
   processing dates — assessments are not skipped (the final loop always covers
   windowStart..windowEnd for un-assessed dates) and duplicates are impossible
   (idempotency key). Adding an explicit regression test: a backward-dated-processing
   event creating a D1 overdraft is caught at the window-end evening.
6. Authorization lifecycle: Auth-A residual 15.00 released (documented policy);
   Auth-B is DECLINED (available -155.00 < 90.00), so "retain hold through Day 6 if
   approved" does not apply — conditional antecedent is false. Verified by existing
   tests; adding explicit per-day available-balance assertions.
7. Interest: policy already sum-of-rounded-dailies, HALF_UP, post-fee bases.
8. Money precision: BigDecimal everywhere; MoneyTest pins scales.

Plan: (A) explicit per-currency fee schedule + skipped-fee trace + tests;
(B) per-event E1..E10 regression tests + all-six-days projection test + backward-date
fee-catch test; (C) docs (AMBIGUITIES #15 rewrite + #27, NUMBERS, README touch-ups,
WORKLOG); (D) final verification and focused commits.

## 2026-10-18 18:50 IST — Stages A+B implemented and committed (538d814)

- LedgerEngine now takes an explicit `Map<CurrencyCode, Money> overdraftFees`
  schedule (validated at construction: currency==key, positive). AED 25.00 =
  supplied requirement; BHD 25.000 = explicit documented choice in
  `Scenario.OVERDRAFT_FEE_SCHEDULE`. Unconfigured currency -> no fee entry, and a
  `SkippedFeeAssessment` (NO_FEE_CONFIGURED_FOR_CURRENCY) is recorded in the replay
  trace and printed in the report's fee-trace section. `OVERDRAFT_FEE_AMOUNT`
  constant removed.
- Idempotency key extended: account|valueDate is marked when a fee DECISION is made
  (charge or skip); non-negative dates stay re-examinable. Duplicate skips
  impossible.
- New tests: EventStreamTest (9 — one per event E1..E10 with inline derivations),
  DailyProjectionTest (3 — both accounts x all six days: closing, active holds,
  available, accrual, plus single-capitalization check),
  BoundaryAndValidationTest gained
  `accountInUnconfiguredCurrencyIsChargedNoFeeButTheSkipIsRecorded` and
  `backwardProcessingDateEventIsCaughtAtWindowEndWithoutDuplicates`.
- Test-run defect (mine, not the engine's): EventStreamTest.e3 asserted final auth
  state APPROVED, but the registry snapshot after E5 is PARTIALLY_SETTLED; fixed the
  test to assert E3's decision NOTE ("AUTH APPROVED") instead.
- `mvn test`: 76 tests, 0 failures — BUILD SUCCESS.

## 2026-10-18 18:55 IST — Stage C docs and final verification

- Docs updated: AMBIGUITIES.md #15 rewritten (explicit per-currency fee schedule +
  recorded skip; unresolved residue noted), NUMBERS.md fee entry rewritten,
  ARCHITECTURE.md fee section updated, README.md assumptions/layout/test count (76).
- Final verification from clean state: `mvn clean test` -> 76 tests, 0 failures,
  BUILD SUCCESS. Intentional-failure tag run -> 1 test, 1 failure (documented,
  expected). Full replay figures unchanged: ACC-001 390.93, fees -75.00, interest
  0.93; ACC-002 10.008, interest 0.008; Auth-A PARTIALLY_SETTLED, Auth-B DECLINED,
  E6 REJECTED (unknown authorization).

---

# Phase 4 — review pass against the original requirements

## 2026-10-18 18:45 IST — Baseline

- Repo clean on `main` at `b3d29f1`, 12 commits ahead of origin.
- Baseline: `mvn clean test` -> 76 tests, 0 failures — BUILD SUCCESS.

## 2026-10-18 18:50 IST — Review findings and plan

1. **BHD fee in default scenario**: the reviewer is right that the DEFAULT scenario
   should retain only the specified AED 25.00. The engine keeps its explicit
   per-currency schedule mechanism (that part is a genuine improvement), but the
   default schedule drops BHD 25.000. BHD behaviour is then: negative BHD account ->
   no fee, visible SkippedFeeAssessment. A mechanism test keeps a BHD-configured
   engine proving the schedule works when a business decision authorizes it.
2. Non-monotonic processing dates (E9@D6 before E10@D5): already handled in stream
   order; adding an explicit regression test pinning fee trace size (3), distinct
   value dates, and E10's D5-valued instalments.
3. Fee value date vs discovery date: report fee line reworded so the Day-2 fee is
   explicitly "first discovered and posted on processing date 2026-01-05" — no claim
   it was known on Day 2.
4. Auth tests: Auth-A assertions exist (e5 test); adding Auth-B zero-hold assertions
   and a synthetic approved-never-settled hold persisting through Day 6.
5. Interest policy: already pinned (post-fee bases, HALF_UP per day, capitalized =
   exact sum). No change.
6. **Point-6 figures discrepancy (important, honest finding)**: the requested
   "Days 4-6 = 440.00 pre-capitalization" figures correspond to a ONE-fee model
   (only the D2 fee). Under the documented policy — every value date is re-examined
   every evening (this task's own point 2: "ensure all relevant value dates are
   examined"), and assessed fees are immutable (point 2/Step 3) — D4 and D5 were
   genuinely negative on the D5 evening (-180.00 / -205.00 with fee participation;
   -155.00 / -155.00 even without it), so D4 and D5 fees fire and persist. The 440
   model requires either erasing assessed fees at E9 (forbidden) or never assessing
   D4/D5 despite negative closings (violates the fee rule and the "examine all
   relevant value dates" instruction). Keeping the three-fee policy; asserting the
   requested D1/D2/D3 figures (which match under both models: 250.00 / 225.00 /
   625.00) and documenting the D4-D6 discrepancy with full arithmetic in REJECTED.md.
7. Instalment conservation: already pinned (3.333/3.333/3.334 = 10.000).
8. Report auth states and projection-vs-point-in-time distinction: already present;
   strengthened by the fee-line rewording.

## 2026-10-18 18:55 IST — Phase-4 changes implemented and committed (8ce2caa)

- Default `Scenario.OVERDRAFT_FEE_SCHEDULE` now contains ONLY AED 25.00 (the supplied
  requirement). BHD fee removed from defaults; mechanism retained as configuration.
  Tests restructured: `bhdAccountIsChargedNothingUnderTheDefaultAedOnlySchedule`
  (skip recorded, no fee) and
  `bhdAccountIsChargedConfiguredFeeOnlyWhenScheduleAuthorizesIt` (custom schedule).
- Report fee line reworded: "OVERDRAFT FEE with value date today: 25.00 — pre-fee
  balance was -370.00; fee first discovered and posted on processing date
  2026-01-05" — value date vs discovery date are now unmistakable.
- New tests: EventStreamTest.e9ThenE10NonMonotonicProcessingDatesAreHandledInStreamOrder;
  BoundaryAndValidationTest.approvedButNeverSettledHoldPersistsThroughWindowEnd;
  ReportTest.reportNeverClaimsAFeeWasKnowableOnItsValueDate; Auth-B zero-hold
  assertions added to EventStreamTest.e8.
- One test-authoring slip fixed before commit: missing `Authorization` import in
  BoundaryAndValidationTest (compile error, fixed immediately).
- `mvn test`: 79 tests, 0 failures — BUILD SUCCESS.

## 2026-10-18 19:00 IST — Docs updated and final verification

- AMBIGUITIES.md #15 rewritten (default schedule AED-only; BHD fee only as tested
  configuration mechanism); NUMBERS.md fee entry updated; ARCHITECTURE.md fee
  bullet updated (AED-only default, value-date vs discovery-date explicitness);
  README.md example fee lines regenerated to the new wording, test count 79.
- REJECTED.md gained a phase-4 section evaluating the requested "D4-D6 = 440.00"
  figures: ACCEPTED for D1-D3 (250.00/225.00/625.00 — asserted in
  DailyProjectionTest), REJECTED for D4-D6 with full arithmetic (D4/D5 closings were
  -180.00/-205.00 on the D5 evening under any participation model, so their fees are
  mandatory; 440.00 matches no self-consistent policy we could construct).
- Final verification (all actually executed): `mvn clean test` -> 79 tests,
  0 failures, BUILD SUCCESS; intentional-failure tag run -> 1 test, 1 failure with
  the documented message; `mvn -q compile exec:java` -> report inspected, figures
  unchanged (390.93 / 10.008 / fees 3x25.00 discovered 2026-01-05 / interest 0.93 /
  0.008).

---

# Phase 5 — REJECTED.md revision (documentation only)

## 2026-10-18 19:15 IST — Inspection and verification before editing

- Re-read REJECTED.md, AMBIGUITIES.md (#1, #4, #7, #10, #13), NUMBERS.md, and the
  fee-assessment code in LedgerEngine.assessFees.
- Verified every figure against a fresh `mvn -q compile exec:java` run:
  Auth-A approval inputs (ledger 250.00, holds 0.00, requested 200.00 -> available
  50.00); Auth-B decline input (ledger -155.00); fee trace -370.00/-180.00/-205.00
  valued D2/D4/D5 all assessed 2026-01-05; finals 390.93 / 10.008; interest
  0.93 / 0.008.
- Verified the batch-pre-fee series by hand: D3 = -370+400 = 30.00, D4 = -155.00,
  D5 = -155.00 (no fees posted).
- WORKLOG evidence check for abandoned approaches: grouping-by-day (15:13 test
  failure) and mark-only-when-charged (15:00 code-review catch) have direct records;
  full-recompute, clipping, aggregate-rounding, value-date holds were design-time
  considerations documented in AMBIGUITIES.md, never implemented — the revised
  section now says exactly that instead of implying experiments.

## 2026-10-18 19:20 IST — Targeted edits to REJECTED.md (only file changed)

1. Reference series renamed to "Sequential fee-assessment balances (before each
   date's own fee)" with the sequential computation shown; added the distinct
   batch-pre-fee series (250.00 / -370.00 / 30.00 / -155.00 / -155.00) and an
   explicit note that neither series is called bare "pre-fee".
2. Criterion 3 rewritten around the approval-time figures (250.00 ledger, 200.00
   hold, 50.00 available, APPROVED), with the 225.00/25.00 pair explicitly labelled
   a later projected view. Criterion 5 restructured the same way: decline explained
   from the processing-time -155.00; Auth-A mechanism shown in both views without
   conflating timelines.
3. Criterion 2 reworded: rejection tied to the documented per-negative-account-day
   policy; "every reasonable model" claim removed; the lagged/targeted
   single-fee model acknowledged and cross-referenced; three-fee result verified
   against the fee trace and tests.
4. Criterion 6: kept REJECTED under the immutable-fee policy; now states explicitly
   that pre-fee balances return while post-fee balances do not, that immutability
   is a deliberate auditability choice (corrections via explicit compensating
   entries), and that full recompute is also defensible with policy-dependent
   outcomes.
5. Criterion 8: now leads with the specification's explicit prohibition on
   discarding remainders, conservation as supporting reasoning; arithmetic
   preserved (0.93 vs 0.918 -> 0.92, difference 0.01); aggregate-rounding described
   as valid if paired with an explicit deterministic reconciliation policy.
6. Abandoned-approaches section: added an honesty note on evidence; item 1 explains
   the E10-before-E9 stream-order violation (grouping itself not condemned); item 3
   describes immutable fees as the chosen policy with corrections via explicit
   compensation; items 3-6 qualified as design-time considerations, not experiments.

## 2026-10-18 19:25 IST — Validation

- `git diff --stat`: only REJECTED.md changed (163 insertions, 86 deletions). No
  application code, tests, or other docs touched.
- `mvn clean test`: 79 tests, 0 failures — BUILD SUCCESS (re-run after the edits;
  REJECTED.md is documentation, but the suite was executed to confirm the
  repository remains green).
- Terminology cross-check: AMBIGUITIES.md #1 defines "pre-fee" exactly as the
  sequential before-own-fee meaning used in REJECTED.md; NUMBERS.md contains no
  conflicting series.

---

# Phase 6 — AMBIGUITIES.md revision + late-event regression test

## 2026-10-18 19:11 IST — Inspection

- Repo clean at `03dfd1e`. Re-read AMBIGUITIES.md sections 1, 2, 6, 9, 10, 11, 12,
  20; inspected `Authorization`/`AuthorizationStatus` (PARTIALLY_SETTLED is terminal:
  `settle()` throws when not settleable — code already matches the docs), the
  fee-assessment loop in `LedgerEngine.replay`/`assessFees`, and the interest
  capitalization code.

## 2026-10-18 19:30 IST — New regression test (point 3 of the review)

- Added `LateEventFeeTest` (6 tests) with a synthetic AED stream: D1 overdraft
  (-20.00) -> fee assessed on D1 evening; a LATE credit (proc D4, value D1) makes
  D1's final reconstruction positive (+55.00) while the D1 fee remains; a LATE debit
  (proc D5, value D3) makes the previously-examined-but-uncharged D3 negative ->
  fee valued D3, derived D5, exactly once. Passed on first run: 6/6 — the
  implementation behaved exactly as derived (fee trace [(D1,discovered D1,-20.00),
  (D3,discovered D5,-45.00)], finals 55.00/255.00/-70.00/230.00/230.00/230.39).

## 2026-10-18 19:35 IST — Targeted AMBIGUITIES.md edits

- Added a "How to read" legend distinguishing requirement / accounting invariant /
  business-policy choice / implementation choice (review point 7).
- #1: appended the complete fee lifecycle (sequential basis, boundary timing,
  late-event discovery, reconsideration rule, immutability, valueDate vs
  derivedOnDate) plus the explicit statement that the final projection is NOT a
  claim every historical fee would fire under full prior knowledge.
- #2: cross-referenced #1's sequential basis; alternative reworded as "not chosen
  because it contradicts the spec's balance definition" (requirement-grounded).
- #6: PARTIALLY_SETTLED terminality made literal (verified against code); 185/200/15
  figures re-verified.
- #9: added the explicit reversal processing-date vs value-date vs fee-history
  breakdown for E9, cross-referencing #10/#11.
- #10: added the "fee remains though final reconstructed balance is positive"
  explanation; recompute alternative reframed as a defensible trade-off, not an
  error; added LateEventFeeTest reference.
- #11: noted assessments run only at defined boundaries; cross-ref to #20; added
  LateEventFeeTest reference.
- #12: bases re-verified (250/225/625/415/390/390, accruals sum 0.93, single
  capitalization at window end); added "final historical projection, not the
  balance known on each processing day"; terminology matches README/REJECTED.
- #20: rewritten — four distinct concepts (stream order, processing date, value
  date, assessment boundaries) and the ACTUAL implemented trigger behavior for
  backwards-moving processing dates (no boundary fires for E10; the window-end
  evening scans the full window prefix for uncharged dates; idempotency via the
  account|valueDate key). Removed the vague "correct because value-date based"
  claim.

## 2026-10-18 19:40 IST — Validation

- `mvn clean test`: 85 tests, 0 failures — BUILD SUCCESS (includes the 6 new
  LateEventFeeTest tests).
- `mvn -q compile exec:java`: figures unchanged (390.93 / 10.008 / fees 3x25.00
  discovered 2026-01-05 / interest 0.93 / 0.008; Auth-A PARTIALLY_SETTLED, Auth-B
  DECLINED).
- Docs cross-check: AMBIGUITIES "final projection / point-in-time" wording matches
  README report header and REJECTED reference series; no contradictions found.
- No application-code changes in this phase (inspection found no defect); the only
  code-side change is the added test file.

---

# Phase 7 — reconciliation with updated AMBIGUITIES.md

## 2026-10-18 19:21 IST — Inspection and baseline

- Repo clean at `3f2ff11`. Baseline: `mvn clean test` -> 85 tests, 0 failures.
- Section-by-section reconciliation of code vs AMBIGUITIES.md:
  - #23 time model: consistent. No stale int-day fields in production code (grep for
    processingDay/valueDay finds only the report's display-label counter
    `int dayNumber`, which renders alongside the real LocalDate — display label, not
    a time model). No occurredAt/receivedAt anywhere except the javadoc explaining
    their deliberate absence. Day n -> 2026-01-0n mapping in Scenario.day(n).
  - Fees (#1/#2/#10/#11/#15/#20): assessFees scans the full window prefix
    (windowStart..min(processingDate, windowEnd)) at every boundary; idempotency key
    account|valueDate; fee trace carries valueDate AND assessedOnDate; default
    schedule AED-only; SkippedFeeAssessment for unconfigured currencies. All
    consistent with code and tests.
  - Reversals (#9/#21/#22): validate -> post -> mark order confirmed in code;
    ReversalTest covers unknown/poisoning, no-mutation, duplicate, cross-account,
    unsupported kinds (fee ids, reversal-of-reversal), multi-entry compensation.
  - Ordering/idempotency (#16/#17/#20/#25): covered by existing tests; found one
    coverage gap worth closing: a duplicate event id carrying a DIFFERENT event type
    (authorization) was not explicitly proven to leave auth state and interest
    untouched.
  - Validation order: deterministic in code (duplicate id -> horizon -> account ->
    currency -> business rules) but not yet documented as a numbered ambiguity.

## 2026-10-18 19:45 IST — Changes

- Added `BoundaryAndValidationTest.duplicateEventIdCannotAffectEntriesAuthFeesOrInterest`
  (duplicate id with a different event type: rejected, no authorization created, no
  extra entries, interest unchanged: 6 x 0.04 = 0.24 -> closing 100.24) and
  `validationOrderIsDeterministic` (duplicate-id beats currency-mismatch; horizon
  beats unknown-account). Both passed on first run.
- Added AMBIGUITIES.md #27 documenting the fixed validation order and its rationale.
- No implementation changes — reconciliation found no code/doc contradictions.
- Full suite after changes: `mvn clean test` -> 87 tests, 0 failures — BUILD SUCCESS.
- Replay re-run: figures unchanged (ACC-001 390.93, fees 3x25.00 discovered
  2026-01-05, interest 0.93; ACC-002 10.008, interest 0.008; Auth-A
  PARTIALLY_SETTLED, Auth-B DECLINED, E6 rejected).

---

# Phase 8 — final acceptance review (policy change driven by requirement hierarchy)

## 2026-10-18 19:49 IST — Baseline and hierarchy analysis

- Repo clean at `7491730`. Baseline: `mvn clean test` -> 87 tests, 0 failures.
- Established the requirement hierarchy explicitly: (1) explicit assignment
  requirements; (2) acceptance criteria — the assignment itself says some are
  intentionally incorrect and must be evaluated, so they are NOT automatically
  binding; (3) ambiguities to resolve; (4) chosen policies.
- **Key finding against my own earlier work**: "assessed fees are immutable and
  never compensated" was a phase-1 CHOSEN policy, not a requirement. No tier-1
  requirement mandates fee persistence after the causing debit is reversed:
  append-only applies to EVENTS and ENTRIES (a compensating fee-reversal entry
  preserves both), and fee derivation must be replayable/idempotent (a
  deterministic reconciliation satisfies both). The reviewer's point stands:
  rejecting criterion 6 because of a policy I chose was circular.
- **Decision**: adopt deterministic fee RECONCILIATION with append-only
  compensation. At every assessment boundary, the desired fee set is recomputed
  from non-fee entries (same sequential, fee-participating semantics); newly
  negative dates are charged (once per account-date ever); already-charged dates
  whose corrected books are no longer negative receive a FEE_REVERSAL
  compensation entry valued on the original fee's value date. Original fee
  entries are never mutated or deleted; each fee is charged at most once and
  compensated at most once (no re-charge after compensation — documented).
- Comparison recorded (REJECTED.md / AMBIGUITIES.md #10): persist-forever vs
  compensate vs full-recompute. Compensation satisfies criterion 6 in net terms
  while keeping the complete audit trail; full recompute discards point-in-time
  history; persist-forever fails criterion 6 without a tier-1 justification.
- Criterion 2 reinterpretation (documented in REJECTED.md): "exactly one fee
  ... on Day 2" read as value-date/idempotency claim (exactly one fee ever
  assessed FOR Day 2) is TRUE; read as a totality claim (one fee overall) is
  false — three fees are assessed on the Day-5 evening under both old and new
  policy.
- Expected figure changes under the new policy (derived by hand BEFORE coding):
  fees charged -25 x3 (valued D2/D4/D5, derived D5 evening), compensated +25 x3
  (same value dates, derived D6 evening); final ACC-001 closings
  250.00/250.00/650.00/465.00/465.00/465.00 pre-capitalization; interest bases
  250/250/650/465/465/465 -> accruals 0.10/0.10/0.26/0.19/0.19/0.19 (650x0.0004
  =0.2600; 465x0.0004=0.1860 -> HALF_UP 0.19) -> capitalized 1.03 -> final
  466.03. ACC-002 unchanged (10.008). Net cumulative fees 0.00.
- CONSEQUENCE for the deliberate-failure test: under fee compensation, the final
  Day-2 closing (250.00) equals the as-known-on-Day-2 value, so the old
  intentional failure would PASS. It must be re-aimed at the new naive
  assumption: "an assessed fee remains effective forever" (truncated stream
  without E9 -> D2 = 225.00; full stream -> D2 = 250.00).

## 2026-10-18 20:05 IST — Implementation and test migration

- Engine: new EntryKind.FEE_REVERSAL; ReplayResult.FeeCompensation trace record;
  assessFees replaced by reconcileFees (desired-set recomputation from non-fee
  entries per boundary; compensate charged-unsupported dates with append-only
  FEE_REVERSAL valued on the original fee's date; charge never-charged desired
  dates; charge-once/compensate-once/never-recharge lifecycle sets). ReportGenerator
  prints compensation lines per date, in the trace, and shows charged/compensated
  breakdown in the summary.
- First full replay matched the hand-derived figures exactly: fees 3x25.00 charged
  (D2/D4/D5, discovered D5), 3x25.00 compensated (D6), finals
  250/250/650/465/465/466.03, interest 1.03, ACC-002 unchanged.
- Test migration: ReplayTest (figures, e9 rewrite, interest 1.03), DailyProjectionTest
  table, EventStreamTest (e3/e4/e9 figures), ReportTest (new strings + compensation
  test), LateEventFeeTest rewritten for the compensation lifecycle (F1 compensated
  on the D4 evening; finals 80/280/-45/255/255/255.44),
  IntentionalFailureTest re-aimed at fee-effect permanence.
- Real test-authoring failure during migration (honest record): the first re-aimed
  IntentionalFailureTest asserted the mid-window Day-2 closing as 225.00 and failed
  on the SANITY line with "expected 225.00 but was -395.00" — I had forgotten that
  the truncated stream still contains E7's -620.00, so mid-window D2 = -395.00,
  not 225.00. Fixed by reformulating the test on the NET FEE EFFECT (-25.00 ->
  0.00), which isolates fee effectiveness from E7's principal compensation; the
  javadoc records this.
- `mvn clean test`: 88 tests, 0 failures — BUILD SUCCESS. Intentional-failure tag
  run: 1 test, 1 failure with the intended message (-25.00 -> 0.00).
- Committed as `ad284aa`.

## 2026-10-18 20:25 IST — Documentation reconciliation

- REJECTED.md: reference figures updated; criterion 1 parenthetical fixed;
  criterion 2 rewritten as ACCEPTED WITH QUALIFICATION (three readings analyzed:
  value-date/idempotency TRUE, totality FALSE with arithmetic, processing-date
  FALSE); criterion 6 rewritten as ACCEPTED WITH QUALIFICATION with the
  three-approach comparison table (persist / compensate / recompute);
  criterion 4 figures (465.00/285.00); criterion 5 projected-view figures
  (250.00/50.00); criterion 8 figures (1.03 vs aggregate 2545.00 x 0.0004 = 1.0180
  -> 1.02, delta 0.01); 440-section updated (under compensation the finals are
  465.00; only D1 of the requested figures matches; 440 needs an incoherent
  keep-only-the-D2-fee rule); abandoned approaches gained fee persistence
  (explicitly recorded as implemented in phases 1-7 and abandoned in phase 8).
- AMBIGUITIES.md: #1 lifecycle bullet rewritten (immutability of entries +
  compensation of effects); #10 rewritten (compensation policy, why, alternatives
  as trade-offs); #11 rewritten (reconciliation semantics); #12 figures
  (bases 250/250/650/465/465/465, total 1.03; pre/post-fee distinction unobservable
  in this scenario, observable via LateEventFeeTest); #13 aggregate figure updated;
  new #28 (no re-charge after compensation + the decision-basis corner
  limitation); #3/#18 figure touch-ups (465.00).
- README.md: business-model paragraph (reconciliation wording), assumption #1
  rewritten, example report regenerated figures (all days, trace, summary).
- NUMBERS.md: fee halving analysis updated for compensation; interest halving
  example (650.00 base, 0.51 total); reconciliation consequence (1.03 vs 1.02);
  IntentionalFailureTest constant description updated.
- ARCHITECTURE.md: replay section, fee section (reconciliation), reversal section
  (net effect), interest bases note, invariants table (precise attribution for
  negativeBalanceEarnsNoInterest after the reviewer's challenge: it proves
  idempotency-across-evenings; value/derived dates are proven by
  exactlyOneFeePerNegativeAccountDayWithAssessedDayValueDate), alternatives #1
  rewritten (three-way comparison).
- Verified no stale references to renamed tests or old figures remain (grep for
  225.00/390.93/0.93/e9DoesNotRemoveAssessedFees across docs: only intentional
  historical references remain).

---

# Phase 9 — final correctness review of LedgerEngine (boundary gating fix)

## 2026-10-18 20:31 IST — Review findings (verified against the actual code)

Inspected `LedgerEngine.replay`/`reconcileFees` against the four reported issues:

1. **Non-monotonic processing dates / boundary coverage**: the boundary loop and
   final loop already cover the full window prefix and are idempotent, so pure
   backward movement cannot skip a date — EXCEPT via the defect in (3).
2. **Fee reconciliation consistency (desired-vs-effective participation)**:
   inspected `reconcileFees` pass structure. The claim "subsequent dates are
   calculated using a fee that is about to be compensated" does NOT reproduce:
   pass 1 builds bases from non-fee entries plus participation of exactly the
   fees that will be effective after the boundary (desired membership ==
   will-be-effective; charged-and-compensated dates are explicitly excluded).
   Pinned the correct behavior with a cascade test that WOULD fail under the
   described leak (F2's corrected D2 base is +15.00 only if F1's
   about-to-be-compensated fee is excluded; a leaking implementation would see
   -10.00 and wrongly retain F2). The test passes — no code change needed.
3. **REAL DEFECT — out-of-window events advance the lifecycle clock**: the
   evening-advance block ran BEFORE horizon validation, and
   `lastProcessedDate` was set from any envelope. A first event with a
   processing date beyond the window end (D7) pushed `lastProcessedDate` past
   the window, so the final reconciliation loop ran ZERO evenings and a valid
   late overdraft was never fee-assessed. Verified against the pre-fix engine
   (ad284aa) in a scratch worktree: new regression test failed there with
   "expected: <6> but was: <0>". FIX: `isWithinWindow` helper shared by the
   boundary gate and the rejection path; out-of-window events are still logged
   and rejected with exactly one outcome, but never advance the clock.
4. **Determinism**: audited every collection feeding emitted output —
   `chargedFeeDays`/`compensatedFeeDays` are LinkedHashSet (iteration = charge
   order = ascending value date); `skippedFeeDays`, `desired`,
   `desiredPreFeeBases`, `reversedEventIds`, `seenEventIds` are used for
   membership/lookup only, never iterated for emission; `outcomes` and
   `authorizations` are LinkedHashMap; accounts iterate in registration order.
   No reliance on unspecified iteration order for any emitted entry or trace.
   No code change needed; verified by replayIsDeterministic,
   reportIsByteIdenticalAcrossReplays, and the new byte-determinism test on a
   compensation-bearing replay.

## 2026-10-18 20:45 IST — Implementation and tests

- Fix: boundary gate on `isWithinWindow` + shared horizon helper (the only
  production-code change; ~15 lines).
- New `FeeReconciliationTest` (6 tests), all expectations derived by hand before
  running:
  - compensationOfEarlierFeeFlowsIntoLaterDatesAndCompensatesThemToo
    (cascade: 3 charges then 3 compensations; finals 20.00/15.00/.../15.06);
  - compensatedDateIsNeverRechargedButNewNegativeDatesAreCharged
    (documented #28 policy: D1-D3 keep exactly one charge + one compensation;
    new negatives D4/D5/D6 charged with derivedOn D5/D5/D6);
  - outOfWindowEventDoesNotAdvanceBoundariesOrSuppressReconciliation
    (the phase-9 defect regression);
  - forwardBackwardForwardProcessingDatesReconcileAtNextBoundary
    (backward D3 event's historical D2 overdraft caught at the D4 evening;
    4 fees with exact value/derived dates; single capitalization 0.04);
  - justifiedFeesAreNeverCompensated (6 fees, 0 compensations);
  - replayWithCompensationsIsByteDeterministic.
- `mvn test -Dtest=FeeReconciliationTest`: 6/6 pass on first run.
- Pre-fix verification: same test file copied into a scratch worktree at
  ad284aa -> the boundary-poisoning test fails with "expected: <6> but was:
  <0>", proving the defect and the fix. Worktree removed afterwards.
- Full suite: `mvn clean test` -> 94 tests, 0 failures — BUILD SUCCESS.
- Intentional-failure tag run: 1 test, 1 failure (documented, expected).
- Replay: figures unchanged (466.03 / 1.03 / 10.008 / 0.008) — the fix only
  affects the defective out-of-window path. README test count 87 -> 94.

---

# Phase 10 — reconcileFees audit and stream-contract hardening

## 2026-10-18 20:46 IST — Audit (code read from disk, not memory)

- Re-read `reconcileFees` in full. Verdict on the reported primary defect: the
  claim "pass 1 may include fee effects that are about to be compensated" does NOT
  reproduce. Pass 1 participation is keyed on `desired` membership (dates negative
  in THIS pass); an about-to-be-compensated fee belongs to a date that is NOT
  negative in the recomputation, hence not desired, hence non-participating.
  Charged-and-compensated dates are explicitly excluded from participation, so
  decision bases equal the post-reconciliation effective books. No reorder of
  passes was needed or made.
- PROOF instead of assertion: added the sharp discriminating test
  `compensationOfEarlierFeeChangesLaterDatesEligibility` (D1 -10 -> F1; D2
  -25 -> F2; late +15@D1 -> F1 compensated; correct D2 base = +15.00 -> F2
  compensated; a leaking implementation would compute -10.00 and retain F2).
  PASSES on the current engine — would fail on the defective variant described in
  the review.
- Genuine gap found by the review: `streamPosition` uniqueness was enforced but
  ORDER was not — a [2, 1] position sequence was silently accepted, contradicting
  the explicit-ordering contract. New `outOfOrderStreamPositionsAreRejected`
  FAILED on the existing code (as designed), then the engine was changed to require
  strictly increasing positions (fail-fast IllegalArgumentException; never sorts).
- Also pinned by new tests after verifying the code: settlement reversal
  compensates funds but leaves the authorization terminal and the hold released
  (documented policy consequence, #21); a rejected first occurrence still consumes
  its event id (#25, documented explicitly now); empty stream and
  only-out-of-window streams derive nothing but still produce well-formed traces;
  default fee schedule contains exactly AED 25.00.
- One compile slip during test authoring (missing LedgerEntry import in
  BoundaryAndValidationTest) — fixed immediately, recorded honestly.

## 2026-10-18 21:00 IST — Verification

- `mvn clean test`: 101 tests, 0 failures — BUILD SUCCESS.
- Intentional-failure tag run: 1 test, 1 failure (documented, expected).
- `mvn -q compile exec:java`: figures unchanged (466.03 / 1.03 / 10.008 / 0.008).
- Docs: AMBIGUITIES.md #11 (compensation-aware recomputation + fixed-point note +
  discriminating test reference), #21 (settlement-reversal consequence), #25
  (strictly increasing positions; rejected events consume ids); README test count
  94 -> 101.

---

# Phase 11 — strict accounting review pass (mostly verification)

## 2026-10-18 20:55 IST — Baseline and audit

- Repo clean at `d9183c3`. Baseline: `mvn clean test` -> 101 tests, 0 failures.
- reconcileFees audited line by line for the FOURTH time, this round specifically
  hunting: (a) later fee that should not exist / missing fee that should exist —
  none found, pass-1 participation provably tracks post-reconciliation effective
  fees; (b) compensation flowing into later dates' eligibility — already proven by
  `compensationOfEarlierFeeChangesLaterDatesEligibility`; (c) HashSet iteration
  feeding output — none (audit repeated from phase 9).
- Documentation-accuracy check ("pure function" claims): LedgerEngine's javadoc
  already says "entries list PLUS the (charged, compensated) lifecycle sets" —
  accurate. AMBIGUITIES #11 said "recomputed deterministically from non-fee
  entries" WITHOUT mentioning lifecycle state — corrected (wording fix only).
- AMBIGUITIES #28 item 5 claimed a "known limitation" (decision bases could differ
  from the books in the re-negativing corner). The phase-9 audit suspected this was
  wrong; the new re-negativing test's exact figures now PROVE it wrong (bases
  exclude charged-and-compensated fees, matching the effective books). Corrected
  the section and recorded the correction honestly.
- #28 classification strengthened per the reviewer's question: "once per account
  per day" constrains assessment frequency; whether a compensated date may earn a
  NEW fee after re-negativing is a POLICY CHOICE not addressed by the spec or any
  acceptance criterion (criterion 6 unaffected either way — no re-negativing occurs
  in E1..E10). Policy kept; no code change.

## 2026-10-18 21:05 IST — New regression tests (all hand-derived, passed first run)

- `FeeReconciliationTest.compensatedDateIsNotRechargedWhenReversalCausedTheCompensation`:
  the exact review scenario — debit -> fee -> REVERSAL event -> fee compensated
  (D3 evening) -> new back-valued debit -> date negative again -> NOT re-charged.
  Figures: F1 -25@D1 (derivedOn D1, preFee -10.00); compensation +25@D1
  (derivedOn D3); final D1 -50.00, D2 50.00, D6 50.10 (5 x 0.02 interest).
- `ReplayTest.e9RestoresExactlyThePreE7Projection_criterion6`: replays the stream
  WITHOUT E7/E9 and asserts all six per-day closings AND the ACC-001 interest
  trace are identical to the full stream (fees net to zero), while the full
  stream's audit trail retains 3 charge + 3 compensation entries. Also documents
  that authorization decisions are point-in-time and legitimately differ (Auth-B
  would be approved without E7) — criterion 6 covers balances and fees, not
  operational decisions.
- `ReversalTest.reversingAFeeCompensationEntryIsUnsupported` (FEE_REVERSAL source
  id -> REVERSAL_UNSUPPORTED_TARGET, compensation intact) and
  `reversingInterestIsUnreachableAndHarmless` (interest entries are derived after
  the last event, so the deterministic id resolves to REVERSAL_TARGET_UNKNOWN;
  capitalization intact, balance 100.24).

## 2026-10-18 21:10 IST — Verification

- `mvn clean test`: 105 tests, 0 failures — BUILD SUCCESS.
- Intentional-failure tag run: 1 test, 1 failure (documented, expected).
- `mvn -q compile exec:java`: figures unchanged (466.03 / 1.03 / 10.008 / 0.008).
- README test count 101 -> 105.
- No production-code changes this phase: the audit found no reproducible defect
  beyond those already fixed in phases 9-10.

---

# Phase 12 — fifth reconcileFees audit with mutation experiment

## 2026-10-18 21:03 IST — Audit and new reversal-cascade test

- Re-read reconcileFees from disk (unchanged since phase 9 fix). Formal invariant
  verified by construction: after each boundary, effective(v) iff
  sequentialBase(v) < 0 AND v is lifecycle-eligible (not charged-and-compensated),
  because pass 1 participates exactly the fees that will be effective after the
  boundary. Deterministic single ascending pass; no iteration.
- Added `reversalCompensationCascadesToDependentFeeEligibility`: the sharpest
  requested cascade — X1 -10@D1 -> F1; X2 +10@D2 -> F2 exists ONLY because of F1
  (preFee -25.00); X3 = reversal of X1 -> F1 compensated on the D3 evening and F2
  compensated with it; finals D1 0.00, D2..D6 10.00, no interest (0.0040 rounds
  to 0.00 at AED scale). Passed first run on the current engine (committed
  06a8bf9).

## 2026-10-18 21:15 IST — Mutation experiment (honest negative result)

- To verify the new test's discriminating power, patched a scratch worktree
  (removed the willCarryFee guard so desired dates ALWAYS participate) and ran
  FeeReconciliationTest there.
- Result: the new reversal-cascade test PASSED even on the mutant — and the
  phase-10 cascade test would too. Root cause of my earlier overclaim: pass 1 only
  lets dates that are negative IN THE CURRENT PASS participate, and a date about
  to be compensated is by definition non-negative in that pass, so the feared leak
  ("later dates computed with a fee about to be compensated") is structurally
  impossible in this loop shape. The mutant DID fail
  compensatedDateIsNeverRechargedButNewNegativeDatesAreCharged with exactly the
  expected signature (D3 = -140.00 phantom-cascade vs correct -65.00) — that test
  is the genuine discriminator of the participation guard.
- Corrected the overclaiming comments in BOTH cascade tests to say precisely what
  each pins and which test discriminates the guard. (My phase-10 comment had
  described a leak mechanism that cannot occur; recorded here rather than
  silently edited away.)
- Worktree removed. No production code changed in this phase.

## 2026-10-18 21:20 IST — Final verification

- Pending: full suite, replay, commit of comment corrections.
