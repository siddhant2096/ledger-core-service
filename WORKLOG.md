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
