package com.niranjankagri.aitesting.tooling;

import com.niranjankagri.aitesting.ai.ProjectFiles;
import com.niranjankagri.aitesting.ai.gate.GateReport;
import com.niranjankagri.aitesting.ai.gate.QualityGate;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Stages 2 and 3 of the gate for real: javac, then three TestNG runs in a browser against LeaveDesk.
 * Uses hand-made "drafts", so it needs no API key. Starts Chromium, so it takes some seconds.
 */
@Test(groups = "tooling-browser")
public class QualityGateRunTest {

    private static final List<String> TWO_LINES = List.of("error text asserted", "empty list asserted");

    /** The good draft compiles and passes all three browser runs. */
    public void goodDraftCompilesAndPassesThreeRuns() {
        GateReport report = new QualityGate(Path.of("target", "gate-test", "good"))
                .check(ProjectFiles.testCase("TC-107"), "UnpaidLeaveNeedsReasonTest", QualityGateRulesTest.GOOD,
                        TWO_LINES);
        assertTrue(report.passed(), report.toMarkdown());
        assertEquals(report.checks().stream().filter(c -> c.stage().equals("run")).count(), QualityGate.RUNS);
    }

    /** A compile error comes back with its line number, so a repair round knows where to look. */
    public void compileErrorsComeBackWithLineNumbers() {
        String broken = QualityGateRulesTest.GOOD.replace("dashboardPage.formError()", "dashboardPage.formErrorText()");
        GateReport report = new QualityGate(Path.of("target", "gate-test", "broken"))
                .check(ProjectFiles.testCase("TC-107"), "UnpaidLeaveNeedsReasonTest", broken, TWO_LINES);
        assertFalse(report.passed());
        assertTrue(report.failuresAsText().contains("[compile] compiles: line 22: cannot find symbol"),
                report.failuresAsText());
    }

    /** A wrong expectation fails run 1 and the gate stops there instead of spending two more browser runs. */
    public void wrongExpectationFailsTheFirstRunAndStops() {
        String wrong = QualityGateRulesTest.GOOD.replace("Unpaid leave needs a reason", "Reason is required");
        GateReport report = new QualityGate(Path.of("target", "gate-test", "wrong"))
                .check(ProjectFiles.testCase("TC-107"), "UnpaidLeaveNeedsReasonTest", wrong, TWO_LINES);
        assertFalse(report.passed());
        String failures = report.failuresAsText();
        assertTrue(failures.contains("[run] run 1 of 3") && failures.contains("Expected: Reason is required"), failures);
        assertEquals(report.checks().stream().filter(c -> c.stage().equals("run")).count(), 1L,
                "after a failed run the gate stops");
    }
}
