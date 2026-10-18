# ledger-core-service

An accounting-grade, **event-sourced, in-memory account ledger core**. It replays a
fixed six-day stream of ten source events (E1–E10) for two accounts — ACC-001 (AED, 2dp)
and ACC-002 (BHD, 3dp) — and derives ledger entries, overdraft fees, daily interest
accruals, a single Day-6 interest capitalization, authorization hold lifecycles,
settlement validation, and reversal compensation, all with exact decimal arithmetic.

Scope: in-memory only. No web layer, database, persistence, UI, or network access.
The event stream is replayable from scratch and every replay is byte-identical.

## Prerequisites

- JDK 21 or newer (developed and verified on OpenJDK 23; the build targets `--release 21`).
- Apache Maven 3.9+ (`mvn` on the PATH).

## Commands

```bash
# Run the normal test suite (105 tests; the deliberately failing test is excluded)
mvn test

# Replay the full event stream and print the six-day report
mvn -q compile exec:java

# Run ONLY the deliberately failing demonstration test (expected: 1 failure)
mvn test -Dsurefire.groups=intentional-failure -Dsurefire.excludedGroups=
```

The normal-suite exclusion is done via the JUnit tag `intentional-failure` on
`IntentionalFailureTest` and Surefire's `excludedGroups` property default in `pom.xml`;
overriding the properties from the CLI (empty `excludedGroups`, `groups` set to the tag)
runs exactly that one test.

## Business model in one paragraph

Source **events** are immutable facts with two dates: a *processing day* (stream order
position) and a *value day* (accounting date). Events never change once appended. All
accounting effects are derived **ledger entries** carrying a value day; balances are
computed from entries filtered by value day, so a late event (E7: processed Day 5,
valued Day 2) rewrites historical projections without touching history. Each evening,
the engine reconciles overdraft fees: every never-charged value date whose pre-fee
closing balance is negative earns exactly one fee (25.00, AED only in the default
schedule, value date = assessed date), and every previously charged date that is no
longer overdrawn in the corrected books (e.g. after E9 reverses E7) receives an
append-only FEE_REVERSAL compensation entry — the original fee entry is never
mutated or deleted. Interest accrues daily on positive post-fee balances at 0.0004,
rounded HALF_UP to the currency's scale, and the exact sum of the rounded daily
accruals is capitalized as one credit on Day 6. Settlements
are validated against their authorization (unknown/excess/duplicate ⇒ REJECTED, no
posting); reversals are new compensating entries (duplicate reversal ⇒ REJECTED);
the BHD 10.000 credit is split 3.333 / 3.333 / 3.334 (residual minor unit to the last
instalment) so the total is exact.

All policy choices, alternatives, and consequences are documented in
`AMBIGUITIES.md`; every numeric constant in `NUMBERS.md`; the eight acceptance
criteria verdicts in `REJECTED.md`; the design in `ARCHITECTURE.md`; the development
timeline in `WORKLOG.md`.

## Example six-day report (actual output of `mvn -q compile exec:java`)

```
================================================================
 IN-MEMORY ACCOUNT LEDGER — SIX-DAY REPLAY REPORT
================================================================
Projection convention:
  * Balances are END-OF-WINDOW value-date projections over the
    complete stream (E1..E10). Late events with historical value
    dates (E7, E9) therefore change earlier days' balances here.
  * Fee lines carry the value day they were assessed FOR and the
    processing day they were derived ON.
  * Interest accrues daily but is capitalized once, on Day 6.
  * Rejected operations are printed as REJECTED with reasons.

----------------------------------------------------------------
 DAY 1 (2026-01-01)
----------------------------------------------------------------
 Events processed today:
   E1 CREDIT AED 1200.00 (value date 2026-01-01): ACCEPTED — Credit posted
   E2 DEBIT AED 950.00 (value date 2026-01-01): ACCEPTED — Debit posted
 Account ACC-001 (AED)
   closing ledger balance (final projection): 250.00
     of which overdraft fees valued on/before today: 0.00
   active holds: 0.00
   available balance: 250.00
   interest accrual for today: 0.10 (base 250.00 @ 0.0004 daily, positive balances only)
 Account ACC-002 (BHD)
   closing ledger balance (final projection): 0.000
     of which overdraft fees valued on/before today: 0.000
   active holds: 0.000
   available balance: 0.000
   interest accrual for today: 0.000 (base 0.000 @ 0.0004 daily, positive balances only)
 Authorization states (end of day):
   (none)

----------------------------------------------------------------
 DAY 2 (2026-01-02)
----------------------------------------------------------------
 Events processed today:
   E3 AUTHORIZATION Auth-A hold AED 200.00 (value date 2026-01-02): ACCEPTED — AUTH APPROVED: ledger=AED 250.00 activeHolds=AED 0.00 requested=AED 200.00
 Account ACC-001 (AED)
   closing ledger balance (final projection): 250.00
     of which overdraft fees valued on/before today: 0.00
   OVERDRAFT FEE with value date today: 25.00 — pre-fee balance was -370.00; fee first discovered and posted on processing date 2026-01-05
   OVERDRAFT FEE COMPENSATED with value date today: +25.00 — original fee entry retained; compensated on processing date 2026-01-06
   active holds: 200.00 (Auth-A)
   available balance: 50.00
   interest accrual for today: 0.10 (base 250.00 @ 0.0004 daily, positive balances only)
 Account ACC-002 (BHD)
   closing ledger balance (final projection): 0.000
   ...
 Authorization states (end of day):
   Auth-A (ACC-001): APPROVED (hold 200.00 active)

----------------------------------------------------------------
 DAY 3 (2026-01-03)
----------------------------------------------------------------
 Events processed today:
   E4 CREDIT AED 400.00 (value date 2026-01-03): ACCEPTED — Credit posted
 Account ACC-001 (AED)
   closing ledger balance (final projection): 650.00
   active holds: 200.00 (Auth-A)
   available balance: 450.00
   interest accrual for today: 0.26 (base 650.00 @ 0.0004 daily, positive balances only)
 ...

----------------------------------------------------------------
 DAY 4 (2026-01-04)
----------------------------------------------------------------
 Events processed today:
   E5 SETTLEMENT Auth-A AED 185.00 (value date 2026-01-04): ACCEPTED — Settlement posted; hold released (residual AED 15.00 released)
   E6 SETTLEMENT Auth-Z AED 180.00 (value date 2026-01-04): REJECTED — UNKNOWN_AUTHORIZATION: no authorization Auth-Z exists for account ACC-001
 Account ACC-001 (AED)
   closing ledger balance (final projection): 465.00
     of which overdraft fees valued on/before today: 0.00
   OVERDRAFT FEE with value date today: 25.00 — pre-fee balance was -180.00; fee first discovered and posted on processing date 2026-01-05
   OVERDRAFT FEE COMPENSATED with value date today: +25.00 — original fee entry retained; compensated on processing date 2026-01-06
   active holds: 0.00
   available balance: 465.00
   interest accrual for today: 0.19 (base 465.00 @ 0.0004 daily, positive balances only)
 ...
 Authorization states (end of day):
   Auth-A (ACC-001): PARTIALLY_SETTLED (settled 185.00 of 200.00; residual hold released)

----------------------------------------------------------------
 DAY 5 (2026-01-05)
----------------------------------------------------------------
 Events processed today:
   E7 DEBIT AED 620.00 (value date 2026-01-02): ACCEPTED — Debit posted
   E8 AUTHORIZATION Auth-B hold AED 90.00 (value date 2026-01-05): ACCEPTED — AUTH DECLINED: ledger=AED -155.00 activeHolds=AED 0.00 requested=AED 90.00
   E10 INSTALMENT CREDIT BHD 10.000 in 3 parts (value date 2026-01-05): ACCEPTED — Instalment credit posted in 3 parts: [BHD 3.333, BHD 3.333, BHD 3.334]
 Account ACC-001 (AED)
   closing ledger balance (final projection): 465.00
     of which overdraft fees valued on/before today: 0.00
   OVERDRAFT FEE with value date today: 25.00 — pre-fee balance was -205.00; fee first discovered and posted on processing date 2026-01-05
   OVERDRAFT FEE COMPENSATED with value date today: +25.00 — original fee entry retained; compensated on processing date 2026-01-06
   active holds: 0.00
   available balance: 465.00
   interest accrual for today: 0.19 (base 465.00 @ 0.0004 daily, positive balances only)
 Account ACC-002 (BHD)
   closing ledger balance (final projection): 10.000
   interest accrual for today: 0.004 (base 10.000 @ 0.0004 daily, positive balances only)
 Authorization states (end of day):
   Auth-A (ACC-001): PARTIALLY_SETTLED (settled 185.00 of 200.00; residual hold released)
   Auth-B (ACC-001): DECLINED (no hold created)

----------------------------------------------------------------
 DAY 6 (2026-01-06)
----------------------------------------------------------------
 Events processed today:
   E9 REVERSAL of E7 (value date 2026-01-02): ACCEPTED — Reversal posted as compensation of E7
 Account ACC-001 (AED)
   closing ledger balance (final projection): 466.03
   interest accrual for today: 0.19 (base 465.00 @ 0.0004 daily, positive balances only)
   INTEREST CAPITALIZED today: 1.03 (sum of rounded daily accruals)
 Account ACC-002 (BHD)
   closing ledger balance (final projection): 10.008
   INTEREST CAPITALIZED today: 0.008 (sum of rounded daily accruals)
 ...

================================================================
 FEE ASSESSMENT TRACE (point-in-time view)
================================================================
 assessed on 2026-01-05: ACC-001 value date 2026-01-02 pre-fee balance -370.00 -> fee 25.00 (fee value date = assessed date 2026-01-02)
 assessed on 2026-01-05: ACC-001 value date 2026-01-04 pre-fee balance -180.00 -> fee 25.00 (fee value date = assessed date 2026-01-04)
 assessed on 2026-01-05: ACC-001 value date 2026-01-05 pre-fee balance -205.00 -> fee 25.00 (fee value date = assessed date 2026-01-05)
 compensated on 2026-01-06: ACC-001 value date 2026-01-02 -> fee reversal +25.00 (original fee entry retained; corrected books show no overdraft)
 compensated on 2026-01-06: ACC-001 value date 2026-01-04 -> fee reversal +25.00 (original fee entry retained; corrected books show no overdraft)
 compensated on 2026-01-06: ACC-001 value date 2026-01-05 -> fee reversal +25.00 (original fee entry retained; corrected books show no overdraft)

================================================================
 END-OF-WINDOW SUMMARY (after 2026-01-06)
================================================================
 ACC-001 (AED)
   final ledger balance:      466.03
   cumulative overdraft fees: 0.00 (charged -75.00, compensated 75.00)
   daily interest accruals:   2026-01-01=0.10, 2026-01-02=0.10, 2026-01-03=0.26, 2026-01-04=0.19, 2026-01-05=0.19, 2026-01-06=0.19
   total interest capitalized: 1.03
 ACC-002 (BHD)
   final ledger balance:      10.008
   cumulative overdraft fees: 0.000 (charged 0.000, compensated 0.000)
   daily interest accruals:   2026-01-01=0.000, 2026-01-02=0.000, 2026-01-03=0.000, 2026-01-04=0.000, 2026-01-05=0.004, 2026-01-06=0.004
   total interest capitalized: 0.008
 Final authorization states:
   Auth-A (ACC-001): PARTIALLY_SETTLED hold=200.00 settled=185.00
   Auth-B (ACC-001): DECLINED hold=90.00 settled=0.00
 Rejected operations:
   E6 (processing date 2026-01-04): UNKNOWN_AUTHORIZATION: no authorization Auth-Z exists for account ACC-001
```

(Elided lines marked `...` are zero-balance ACC-002 blocks and repeated authorization
state lines; run the command for the full 169-line report.)

## Report sections explained

- **Projection convention header** — states that per-day balances are end-of-window
  value-date projections, so late events (E7/E9) are visible in earlier days' lines.
- **DAY n** — everything attributable to that day:
  - *Events processed today*: the source events whose **processing day** is n, with
    ACCEPTED/REJECTED outcomes and reasons/notes (E10 is shown under Day 5, its
    processing day, even though it sits after E9 in the stream).
  - *Account blocks*: closing ledger balance (final projection, fees included), the
    cumulative fee portion, any fee assessed **for that value day** together with the
    pre-fee balance recorded when the assessment ran, active holds and available
    balance (closing − active holds), and the day's interest accrual.
- **Authorization states (end of day)** — reconstructed per-day lifecycle states.
- **FEE ASSESSMENT TRACE** — the point-in-time view: which processing-day evening
  derived each fee, against which pre-fee balance. This is where "evaluated at end of
  Day 5" questions are answered.
- **END-OF-WINDOW SUMMARY** — final balances, cumulative fees, daily accruals,
  capitalized interest, final authorization states, and all rejected operations.

## Key business assumptions (all defended in AMBIGUITIES.md)

1. Fees are assessed from **pre-fee** closing balances that **include earlier dates'
   fees**; a fee never triggers a second fee on the same date (idempotency key
   account+value date; each date is charged at most once ever). Fee ENTRIES are
   immutable, but their effect is not necessarily permanent: when later events (e.g.
   E9's reversal of E7) correct the books so a charged date is no longer overdrawn,
   the next assessment boundary appends an append-only **FEE_REVERSAL compensation**
   valued on the original fee's value date (charge and correction both stay visible).
2. The overdraft fee is an **explicit per-currency schedule**
   (`Scenario.OVERDRAFT_FEE_SCHEDULE`) containing ONLY the supplied AED 25.00. An
   AED amount is never charged to a BHD account; a currency missing from the
   schedule (e.g. BHD) is charged nothing, and the skip is recorded in the fee
   trace. No fee fires for ACC-002 here (never negative).
3. Authorization decisions use the books as **known at processing time** (E8 sees E7
   and is therefore DECLINED at −155.00 available). Holds are processing-time
   constructs; a settlement releases the entire hold (residual included).
4. Settlement validity comes from the authorization, not the account balance;
   invalid settlements are recorded as REJECTED and post nothing.
5. Reversal = immutable compensating entry (opposite sign, value date from the
   reversal event); duplicates rejected.
6. Interest base = post-fee closing balance; capitalized total = exact sum of rounded
   daily accruals (HALF_UP); nothing is discarded.
7. E10 has no supplied value date; all three instalments are valued on its processing
   day (Day 5).

## Reproducing results

`mvn -q compile exec:java` is deterministic: same stream in ⇒ byte-identical report
out (verified by `ReportTest.reportIsByteIdenticalAcrossReplays`). Every figure in the
report can be recomputed by hand from the entry list using the formulas in NUMBERS.md;
the test suite encodes those hand derivations as assertions.

## Project layout (DDD)

```
src/main/java/com/ledger/core/service/
├── domain/
│   ├── money/      CurrencyCode, Money (BigDecimal, fixed scale), CurrencyMismatchException
│   ├── account/    Account (id, currency, opening balance)
│   ├── event/      LedgerEvent (sealed), EventEnvelope (streamPosition, processingDate, valueDate)
│   ├── entry/      LedgerEntry, EntryKind (derived postings)
│   ├── auth/       Authorization, AuthorizationStatus (state machine)
│   └── ledger/     EventLog (append-only)
├── application/
│   ├── LedgerEngine        deterministic replay, fees, interest
│   ├── InstalmentAllocator exact minor-unit split
│   ├── BusinessDates       explicit-zone Instant→LocalDate derivation, date ranges
│   ├── ReplayResult        all derived state from one replay
│   ├── ReportGenerator     the printable report
│   └── Scenario            the exact E1..E10 stream (shared with tests)
└── Main.java               replays and prints
src/test/java/com/ledger/core/service/
├── MoneyTest, InstalmentAllocatorTest, ReplayTest, ReversalTest,
├── BoundaryAndValidationTest, ReportTest, BusinessDatesTest,
├── EventStreamTest (per-event E1..E10), DailyProjectionTest (all six days),
├── LateEventFeeTest (late back-valued events vs assessed/unassessed fee days)
└── IntentionalFailureTest  (@Tag("intentional-failure"), excluded by default)
```

## Time model

Each event carries a `long streamPosition` (authoritative ordering + outcome key), a
`LocalDate processingDate` (drives evening fee assessment and authorization
decisions), and a `LocalDate valueDate` (drives balances, fee value dates, interest
bases). The specification's "Day n" maps to `2026-01-0n` (`Scenario.day(n)`); the
choice of concrete dates is arbitrary and produces identical results for any six
consecutive dates. `Instant occurredAt/receivedAt` are deliberately not modeled (the
input has none; no rule consumes them — see AMBIGUITIES.md #23); when ingestion gains
instants, they must be converted via the engine's explicit `ZoneId`
(`LedgerEngine.businessDate`), never the JVM default zone.
