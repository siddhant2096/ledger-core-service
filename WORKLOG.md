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
