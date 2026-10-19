package com.niranjankagri.aitesting.tooling;

import com.niranjankagri.aitesting.ai.ProjectFiles;
import com.niranjankagri.aitesting.ai.ProjectFiles.TestCaseDoc;
import com.niranjankagri.aitesting.ai.gate.GateReport;
import com.niranjankagri.aitesting.ai.gate.QualityGate;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Stage 1 of the quality gate: every rule must catch what it is meant to catch, and a clean draft
 * must pass all of them. Each bad example changes one thing in an otherwise good draft.
 */
@Test(groups = "tooling")
public class QualityGateRulesTest {

    private static final TestCaseDoc TC_107 = ProjectFiles.testCase("TC-107");
    private static final List<String> TWO_LINES = List.of("error text asserted", "empty list asserted");

    static final String GOOD = """
            package com.niranjankagri.aitesting.tests.ai;

            import com.niranjankagri.aitesting.framework.BaseUiTest;
            import com.niranjankagri.aitesting.framework.TestCase;
            import com.niranjankagri.aitesting.framework.TestUsers;
            import org.testng.annotations.Test;

            import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

            public class UnpaidLeaveNeedsReasonTest extends BaseUiTest {

                @Test(groups = "ui")
                @TestCase("TC-107")
                public void unpaidLeaveWithoutReasonIsRefused() {
                    // Arrange
                    loginAs(TestUsers.EMPLOYEE);

                    // Act
                    dashboardPage.submitRequest("UNPAID", "2026-11-02", "2026-11-02", "");

                    // Assert
                    assertThat(dashboardPage.formError()).hasText("Unpaid leave needs a reason");
                    assertThat(dashboardPage.emptyListMessage()).isVisible();
                }
            }
            """;

    /** The good draft passes every rule. */
    public void cleanDraftPassesEveryRule() {
        GateReport report = new GateReport(QualityGate.ruleChecks(TC_107, "UnpaidLeaveNeedsReasonTest", GOOD, TWO_LINES));
        assertTrue(report.passed(), report.failuresAsText());
    }

    /** The good draft with one forbidden thing added each time, and the rule that must catch it. */
    @DataProvider
    public Object[][] badDrafts() {
        return new Object[][] {
                {"raw selector", GOOD.replace("dashboardPage.formError()", "page().getByTestId(\"leave-error\")"),
                        "no raw selectors or page access"},
                {"sleep", GOOD.replace("// Act", "Thread.sleep(2000);\n        // Act"), "no sleeps or manual waits"},
                {"javascript", GOOD.replace("// Act", "dashboardPage.view().evaluate(\"x => x\");\n        // Act"),
                        "no JavaScript in the page"},
                {"file access", GOOD.replace("import org.testng", "import java.nio.file.Files;\nimport org.testng"),
                        "no file, network, process or reflection access"},
                {"process", GOOD.replace("// Act", "new ProcessBuilder(\"cmd\").start();\n        // Act"),
                        "no file, network, process or reflection access"},
                {"disabled", GOOD.replace("@Test(groups = \"ui\")", "@Test(groups = \"ui\", enabled = false)"),
                        "no disabled or skipped tests"},
                {"swallowed", GOOD.replace("// Act", "try { } catch (Exception e) { }\n        // Act"),
                        "no swallowed exceptions"},
                {"no assertion", GOOD.replaceAll("(?m)^\\s*assertThat.*$", ""), "asserts with assertThat"},
                {"wrong package", GOOD.replace("tests.ai;", "tests.handwritten;"),
                        "package is com.niranjankagri.aitesting.tests.ai"},
                {"no base class", GOOD.replace(" extends BaseUiTest", ""), "extends BaseUiTest"},
                {"wrong test case id", GOOD.replace("TC-107", "TC-999"), "linked to TC-107"},
        };
    }

    /** Each broken draft fails the gate on exactly the expected rule. */
    @Test(dataProvider = "badDrafts")
    public void ruleCatchesIt(String what, String source, String expectedRule) {
        GateReport report = new GateReport(
                QualityGate.ruleChecks(TC_107, "UnpaidLeaveNeedsReasonTest", source, TWO_LINES));
        assertFalse(report.passed(), what + " should fail the gate");
        assertTrue(report.failuresAsText().contains(expectedRule),
                what + ": expected rule '" + expectedRule + "' in\n" + report.failuresAsText());
    }

    /** A coverage list shorter than the test case's expected results fails, with both counts in the message. */
    public void coverageMustListEveryExpectedResult() {
        GateReport report = new GateReport(QualityGate.ruleChecks(TC_107, "UnpaidLeaveNeedsReasonTest", GOOD,
                List.of("only the error")));
        assertEquals(report.failuresAsText(),
                "- [rules] coverage lists every expected result: 2 expected results in the test case, 1 coverage lines"
                        + " in the answer");
    }

    /** A class name that is not the one declared in the source fails. */
    public void classNameMustMatchTheSource() {
        GateReport report = new GateReport(QualityGate.ruleChecks(TC_107, "SomethingElseTest", GOOD, TWO_LINES));
        assertTrue(report.failuresAsText().contains("class name SomethingElseTest"));
    }

    /** A failed rule quotes what it found, the line and the reason. */
    public void failedRuleSaysWhereAndWhy() {
        String bad = GOOD.replace("// Act", "Thread.sleep(2000);\n        // Act");
        String text = new GateReport(QualityGate.ruleChecks(TC_107, "UnpaidLeaveNeedsReasonTest", bad, TWO_LINES))
                .failuresAsText();
        assertTrue(text.contains("found \"Thread.sleep\" on line 18 (Playwright assertions already wait)"), text);
    }
}
