package com.ledger.core.service.application;

import com.ledger.core.service.application.ReplayResult.DailyAccrual;
import com.ledger.core.service.application.ReplayResult.FeeAssessment;
import com.ledger.core.service.application.ReplayResult.Outcome;
import com.ledger.core.service.application.ReplayResult.SkippedFeeAssessment;
import com.ledger.core.service.domain.account.Account;
import com.ledger.core.service.domain.auth.Authorization;
import com.ledger.core.service.domain.auth.AuthorizationStatus;
import com.ledger.core.service.domain.entry.EntryKind;
import com.ledger.core.service.domain.entry.LedgerEntry;
import com.ledger.core.service.domain.event.EventEnvelope;
import com.ledger.core.service.domain.event.LedgerEvent;
import com.ledger.core.service.domain.money.Money;

import java.time.LocalDate;
import java.util.List;

/**
 * Renders a {@link ReplayResult} as a deterministic plain-text report.
 *
 * <p>Projection convention (stated in the report header): all per-day
 * balances are END-OF-WINDOW value-date projections — they are computed over
 * the complete event stream, so a late event such as E7 (processed
 * 2026-01-05, valued 2026-01-02) is already reflected in the 2026-01-02 line.
 * The point-in-time fee assessment trace (which shows what the books looked
 * like when each assessment actually ran) is printed in a dedicated section.
 */
public final class ReportGenerator {

    private final ReplayResult result;
    private final LocalDate windowStart;
    private final LocalDate windowEnd;

    public ReportGenerator(ReplayResult result, LocalDate windowStart, LocalDate windowEnd) {
        this.result = result;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    public String generate() {
        StringBuilder out = new StringBuilder();
        header(out);
        int dayNumber = 1;
        for (LocalDate date : BusinessDates.datesBetween(windowStart, windowEnd)) {
            daySection(out, dayNumber++, date);
        }
        feeAssessmentTrace(out);
        summary(out);
        return out.toString();
    }

    private void header(StringBuilder out) {
        out.append("""
                ================================================================
                 IN-MEMORY ACCOUNT LEDGER — SIX-DAY REPLAY REPORT
                ================================================================
                Projection convention:
                  * Balances are END-OF-WINDOW value-date projections over the
                    complete stream (E1..E10). Late events with historical value
                    dates (E7, E9) therefore change earlier dates' balances here.
                  * Fee lines carry the value date they were assessed FOR and the
                    processing date they were derived ON.
                  * Interest accrues daily but is capitalized once, on the final
                    window date.
                  * Rejected operations are printed as REJECTED with reasons.
                """);
    }

    private void daySection(StringBuilder out, int dayNumber, LocalDate date) {
        out.append("\n----------------------------------------------------------------\n");
        out.append(" DAY ").append(dayNumber).append(" (").append(date).append(")\n");
        out.append("----------------------------------------------------------------\n");

        out.append(" Events processed today:\n");
        List<EventEnvelope> todays = result.log().events().stream()
                .filter(e -> e.processingDate().equals(date))
                .toList();
        if (todays.isEmpty()) {
            out.append("   (none)\n");
        }
        for (EventEnvelope e : todays) {
            Outcome outcome = result.outcomeAt(e.streamPosition());
            String line = switch (outcome) {
                case Outcome.Accepted a -> "ACCEPTED — " + a.note();
                case Outcome.Rejected r -> "REJECTED — " + r.reason();
            };
            out.append("   ").append(e.eventId()).append(" ")
                    .append(describe(e)).append(" (value date ").append(e.valueDate()).append("): ")
                    .append(line).append("\n");
        }

        for (Account account : result.accounts()) {
            accountDaySection(out, account, date);
        }

        out.append(" Authorization states (end of day):\n");
        boolean anyAuth = false;
        for (Authorization auth : result.authorizations().values()) {
            String state = stateOn(auth, date);
            if (state != null) {
                anyAuth = true;
                out.append("   ").append(auth.id()).append(" (").append(auth.accountId())
                        .append("): ").append(state).append("\n");
            }
        }
        if (!anyAuth) {
            out.append("   (none)\n");
        }
    }

    private void accountDaySection(StringBuilder out, Account account, LocalDate date) {
        Money closing = LedgerEngine.closingBalance(result.entries(), account, date);
        Money activeHolds = activeHoldsOn(account, date);
        Money available = closing.subtract(activeHolds);
        Money feesToDate = feesValuedUpTo(account, date);

        out.append(" Account ").append(account.id()).append(" (").append(account.currency()).append(")\n");
        out.append("   closing ledger balance (final projection): ").append(fmt(closing)).append("\n");
        out.append("     of which overdraft fees valued on/before today: ").append(fmt(feesToDate)).append("\n");
        for (FeeAssessment fee : result.feeTrace()) {
            if (fee.accountId().equals(account.id()) && fee.valueDate().equals(date)) {
                out.append("   OVERDRAFT FEE assessed for today: ").append(fmt(fee.feeAmount()))
                        .append(" — balance before this fee was ").append(fmt(fee.preFeeBalance()))
                        .append(" (assessment ran on processing date ").append(fee.assessedOnDate())
                        .append(")\n");
            }
        }
        out.append("   active holds: ").append(fmt(activeHolds)).append(activeHoldNames(account, date)).append("\n");
        out.append("   available balance: ").append(fmt(available)).append("\n");
        for (DailyAccrual accrual : result.interestTrace()) {
            if (accrual.accountId().equals(account.id()) && accrual.date().equals(date)) {
                out.append("   interest accrual for today: ").append(fmt(accrual.accrual()))
                        .append(" (base ").append(fmt(accrual.base())).append(" @ ")
                        .append(LedgerEngine.DAILY_RATE).append(" daily, positive balances only)\n");
            }
        }
        for (LedgerEntry entry : result.entries()) {
            if (entry.accountId().equals(account.id()) && entry.valueDate().equals(date)
                    && entry.kind() == EntryKind.INTEREST_CAPITALIZATION) {
                out.append("   INTEREST CAPITALIZED today: ").append(fmt(entry.amount()))
                        .append(" (sum of rounded daily accruals)\n");
            }
        }
    }

    private void feeAssessmentTrace(StringBuilder out) {
        out.append("\n================================================================\n");
        out.append(" FEE ASSESSMENT TRACE (point-in-time view)\n");
        out.append("================================================================\n");
        if (result.feeTrace().isEmpty()) {
            out.append(" (no overdraft fees assessed)\n");
        }
        for (FeeAssessment fee : result.feeTrace()) {
            out.append(" assessed on ").append(fee.assessedOnDate())
                    .append(": ").append(fee.accountId())
                    .append(" value date ").append(fee.valueDate())
                    .append(" pre-fee balance ").append(fmt(fee.preFeeBalance()))
                    .append(" -> fee ").append(fmt(fee.feeAmount()))
                    .append(" (fee value date = assessed date ").append(fee.valueDate()).append(")\n");
        }
        for (SkippedFeeAssessment skip : result.skippedFees()) {
            out.append(" assessed on ").append(skip.assessedOnDate())
                    .append(": ").append(skip.accountId())
                    .append(" value date ").append(skip.valueDate())
                    .append(" pre-fee balance ").append(fmt(skip.preFeeBalance()))
                    .append(" -> NO FEE CHARGED (").append(skip.reason()).append(")\n");
        }
    }

    private void summary(StringBuilder out) {
        out.append("\n================================================================\n");
        out.append(" END-OF-WINDOW SUMMARY (after ").append(windowEnd).append(")\n");
        out.append("================================================================\n");
        for (Account account : result.accounts()) {
            Money finalBalance = LedgerEngine.closingBalance(result.entries(), account, windowEnd);
            Money totalFees = feesValuedUpTo(account, windowEnd);
            Money totalInterest = Money.zero(account.currency());
            StringBuilder daily = new StringBuilder();
            for (DailyAccrual accrual : result.interestTrace()) {
                if (accrual.accountId().equals(account.id())) {
                    totalInterest = totalInterest.add(accrual.accrual());
                    if (!daily.isEmpty()) {
                        daily.append(", ");
                    }
                    daily.append(accrual.date()).append("=").append(accrual.accrual().amount().toPlainString());
                }
            }
            out.append(" ").append(account.id()).append(" (").append(account.currency()).append(")\n");
            out.append("   final ledger balance:      ").append(fmt(finalBalance)).append("\n");
            out.append("   cumulative overdraft fees: ").append(fmt(totalFees)).append("\n");
            out.append("   daily interest accruals:   ").append(daily).append("\n");
            out.append("   total interest capitalized:").append(" ").append(fmt(totalInterest)).append("\n");
        }
        out.append(" Final authorization states:\n");
        for (Authorization auth : result.authorizations().values()) {
            out.append("   ").append(auth.id()).append(" (").append(auth.accountId()).append("): ")
                    .append(auth.status())
                    .append(" hold=").append(auth.holdAmount().amount().toPlainString())
                    .append(" settled=").append(auth.settledAmount().amount().toPlainString())
                    .append("\n");
        }
        out.append(" Rejected operations:\n");
        List<EventEnvelope> rejected = result.rejectedEvents();
        if (rejected.isEmpty()) {
            out.append("   (none)\n");
        }
        for (EventEnvelope e : rejected) {
            Outcome.Rejected r = (Outcome.Rejected) result.outcomeAt(e.streamPosition());
            out.append("   ").append(e.eventId()).append(" (processing date ").append(e.processingDate())
                    .append("): ").append(r.reason()).append("\n");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String describe(EventEnvelope e) {
        return switch (e.event()) {
            case LedgerEvent.Credit c -> "CREDIT " + c.amount();
            case LedgerEvent.Debit d -> "DEBIT " + d.amount();
            case LedgerEvent.AuthorizationRequest a ->
                    "AUTHORIZATION " + a.authorizationId() + " hold " + a.holdAmount();
            case LedgerEvent.Settlement s ->
                    "SETTLEMENT " + s.authorizationId() + " " + s.amount();
            case LedgerEvent.Reversal r -> "REVERSAL of " + r.targetEventId();
            case LedgerEvent.InstalmentCredit i ->
                    "INSTALMENT CREDIT " + i.totalAmount() + " in " + i.instalmentCount() + " parts";
        };
    }

    /**
     * Authorization state as it stood at end of the given date, reconstructed
     * from the immutable registry snapshot (handy for the per-day report).
     * Returns null if the authorization did not exist yet on that date.
     */
    private static String stateOn(Authorization auth, LocalDate date) {
        if (date.isBefore(auth.processedDate())) {
            return null;
        }
        if (auth.status() == AuthorizationStatus.DECLINED) {
            return "DECLINED (no hold created)";
        }
        if (auth.settledOnDate() != null && !date.isBefore(auth.settledOnDate())) {
            return auth.status() + " (settled " + auth.settledAmount().amount().toPlainString()
                    + " of " + auth.holdAmount().amount().toPlainString()
                    + "; residual hold released)";
        }
        return "APPROVED (hold " + auth.holdAmount().amount().toPlainString() + " active)";
    }

    private Money activeHoldsOn(Account account, LocalDate date) {
        Money total = Money.zero(account.currency());
        for (Authorization auth : result.authorizations().values()) {
            if (holdActiveOn(auth, account, date)) {
                total = total.add(auth.holdAmount());
            }
        }
        return total;
    }

    private String activeHoldNames(Account account, LocalDate date) {
        StringBuilder names = new StringBuilder();
        for (Authorization auth : result.authorizations().values()) {
            if (holdActiveOn(auth, account, date)) {
                if (!names.isEmpty()) {
                    names.append(", ");
                }
                names.append(auth.id());
            }
        }
        return names.isEmpty() ? "" : " (" + names + ")";
    }

    /** A hold is active from its processing date until (and excluding) its settlement date. */
    private static boolean holdActiveOn(Authorization auth, Account account, LocalDate date) {
        return auth.accountId().equals(account.id())
                && auth.status() != AuthorizationStatus.DECLINED
                && !date.isBefore(auth.processedDate())
                && (auth.settledOnDate() == null || date.isBefore(auth.settledOnDate()));
    }

    private Money feesValuedUpTo(Account account, LocalDate date) {
        Money total = Money.zero(account.currency());
        for (LedgerEntry entry : result.entries()) {
            if (entry.accountId().equals(account.id())
                    && entry.kind() == EntryKind.FEE_OVERDRAFT
                    && !entry.valueDate().isAfter(date)) {
                total = total.add(entry.amount());
            }
        }
        return total;
    }

    private static String fmt(Money money) {
        return money.amount().toPlainString();
    }
}
