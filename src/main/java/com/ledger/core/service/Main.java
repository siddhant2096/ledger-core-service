package com.ledger.core.service;

import com.ledger.core.service.application.LedgerEngine;
import com.ledger.core.service.application.ReportGenerator;
import com.ledger.core.service.application.ReplayResult;
import com.ledger.core.service.application.Scenario;

/**
 * Entry point: replays the exact specification event stream (E1..E10) over
 * the six-day horizon and prints the full report to stdout.
 *
 * <p>Run with: {@code mvn -q compile exec:java}
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        ReplayResult result = Scenario.replay();
        String report = new ReportGenerator(result, Scenario.HORIZON_DAYS).generate();
        System.out.println(report);
    }
}
