package com.ledger.core.service;

import com.ledger.core.service.application.ReportGenerator;
import com.ledger.core.service.application.ReplayResult;
import com.ledger.core.service.application.Scenario;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the report-producing replay end to end: the printed report (not
 * just the arithmetic underneath) must contain every required section, the
 * rejected operation, the fee lines, and the final figures. The report is
 * part of the deliverable, so it is tested like code.
 */
class ReportTest {

    private final ReplayResult result = Scenario.replay();
    private final String report = new ReportGenerator(result, Scenario.WINDOW_START,
            Scenario.WINDOW_END).generate();

    @Test
    void reportCoversAllSixDaysAndAllSections() {
        assertTrue(report.contains("Projection convention"));
        for (int day = 1; day <= 6; day++) {
            String header = " DAY " + day + " (" + Scenario.day(day) + ")";
            assertTrue(report.contains(header), "missing " + header);
        }
        assertTrue(report.contains("FEE ASSESSMENT TRACE"));
        assertTrue(report.contains("END-OF-WINDOW SUMMARY"));
        assertTrue(report.contains("Final authorization states"));
        assertTrue(report.contains("Rejected operations"));
    }

    @Test
    void reportPrintsEveryEventIncludingTheRejectedOne() {
        for (String id : List.of("E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8", "E9", "E10")) {
            assertTrue(report.contains(id), "report must mention " + id);
        }
        // The Auth-Z rejection must be visible, not quietly omitted.
        assertTrue(report.contains("E6 SETTLEMENT Auth-Z AED 180.00 (value date "
                + Scenario.day(4) + "): REJECTED — UNKNOWN_AUTHORIZATION"));
        // Auth-B's decline is a decision, printed as such.
        assertTrue(report.contains("AUTH DECLINED"));
    }

    @Test
    void reportShowsFinalFigures() {
        assertTrue(report.contains("final ledger balance:      466.03"));
        assertTrue(report.contains("final ledger balance:      10.008"));
        assertTrue(report.contains("cumulative overdraft fees: 0.00 (charged -75.00, compensated 75.00)"));
        assertTrue(report.contains("total interest capitalized: 1.03"));
        assertTrue(report.contains("total interest capitalized: 0.008"));
        assertTrue(report.contains("2026-01-01=0.10, 2026-01-02=0.10, 2026-01-03=0.26, "
                + "2026-01-04=0.19, 2026-01-05=0.19, 2026-01-06=0.19"));
        assertTrue(report.contains("Auth-A (ACC-001): PARTIALLY_SETTLED hold=200.00 settled=185.00"));
        assertTrue(report.contains("Auth-B (ACC-001): DECLINED hold=90.00 settled=0.00"));
    }

    @Test
    void reportDistinguishesPreFeeAndPostFeeBalances() {
        // D2 line: final projection 250.00 (fee charged then compensated),
        // fee 25.00, pre-fee -370.00.
        assertTrue(report.contains(
                "OVERDRAFT FEE with value date today: 25.00 — pre-fee balance was -370.00"));
        assertTrue(report.contains("closing ledger balance (final projection): 250.00"));
        // Point-in-time trace proves WHEN each fee was derived.
        assertTrue(report.contains("assessed on " + Scenario.day(5)
                + ": ACC-001 value date " + Scenario.day(2) + " pre-fee balance -370.00 -> fee 25.00"));
    }

    @Test
    void reportShowsFeeCompensationsWithBothDates() {
        // Every charged fee is compensated on the Day-6 evening; the report
        // shows the compensation's value date AND derivation date.
        assertTrue(report.contains("OVERDRAFT FEE COMPENSATED with value date today: +25.00"));
        assertTrue(report.contains("compensated on " + Scenario.day(6)
                + ": ACC-001 value date " + Scenario.day(2)
                + " -> fee reversal +25.00"));
        assertEquals(3, result.feeCompensations().size());
    }

    @Test
    void reportNeverClaimsAFeeWasKnowableOnItsValueDate() {
        // The D2 fee was discovered on D5. The report must say so explicitly,
        // not imply the fee existed or was knowable on D2.
        assertTrue(report.contains("fee first discovered and posted on processing date "
                + Scenario.day(5)));
        // The value date (D2) and the discovery date (D5) are distinct fields
        // in the trace line.
        assertTrue(report.contains("assessed on " + Scenario.day(5)
                + ": ACC-001 value date " + Scenario.day(2)));
    }

    @Test
    void reportShowsInstalmentSplit() {
        assertTrue(report.contains("BHD 3.333, BHD 3.333, BHD 3.334"));
    }

    @Test
    void reportIsByteIdenticalAcrossReplays() {
        ReplayResult again = Scenario.replay();
        String againReport = new ReportGenerator(again, Scenario.WINDOW_START,
                Scenario.WINDOW_END).generate();
        assertEquals(report, againReport);
    }
}
