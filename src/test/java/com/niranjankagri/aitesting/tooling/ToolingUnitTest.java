package com.niranjankagri.aitesting.tooling;

import com.anthropic.models.messages.OutputConfig;
import com.niranjankagri.aitesting.ai.AiGateway;
import com.niranjankagri.aitesting.ai.AiGateway.AiException;
import com.niranjankagri.aitesting.ai.AiGateway.Request;
import com.niranjankagri.aitesting.ai.AiGateway.Text;
import com.niranjankagri.aitesting.ai.AiGateway.Turn;
import com.niranjankagri.aitesting.ai.generate.DraftTest;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** The parts of the AI tooling that need neither a browser nor the API. */
@Test(groups = "tooling")
public class ToolingUnitTest {

    private static Request<DraftTest> request(String keyMaterial) {
        return new Request<>("unit-test", "TC-000", keyMaterial, List.of("system"),
                List.of(Turn.user(new Text("question"))), OutputConfig.Effort.LOW, DraftTest.class);
    }

    public void recordingNameChangesWhenTheInputsChange() {
        AiGateway ai = AiGateway.replayOnly("claude-opus-5-5");
        Path a = ai.recordingFile(request("test case v1"));
        assertEquals(a, ai.recordingFile(request("test case v1")), "same inputs, same recording");
        assertNotEquals(a, ai.recordingFile(request("test case v2")), "edited test case, new recording");
        assertNotEquals(a, AiGateway.replayOnly("claude-sonnet-5-5").recordingFile(request("test case v1")),
                "other model, new recording");
        assertTrue(a.toString().replace('\\', '/').matches("ai/recordings/unit-test/TC-000-[0-9a-f]{12}\\.json"), a.toString());
    }

    public void replayWithoutRecordingExplainsHowToRecord() {
        AiException e = expectThrows(AiException.class,
                () -> AiGateway.replayOnly("claude-opus-5-5").ask(request("never recorded " + System.nanoTime())));
        assertTrue(e.getMessage().contains("set ANTHROPIC_API_KEY and run the command again"), e.getMessage());
    }

    public void replayReturnsTheRecordedTypedAnswer() throws Exception {
        AiGateway ai = AiGateway.replayOnly("claude-opus-5-5");
        Request<DraftTest> req = request("replay fixture");
        Path file = ai.recordingFile(req);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"answer": {"className": "XTest", "javaSource": "class XTest {}", "coverage": ["a"],
                 "assumptions": [], "missingPageMethods": ["DashboardPage.balanceText()"]}}""");
        try {
            AiGateway.Answer<DraftTest> answer = ai.ask(req);
            assertTrue(answer.replayed());
            assertEquals(answer.value().className(), "XTest");
            assertEquals(answer.value().missingPageMethods(), List.of("DashboardPage.balanceText()"));
        } finally {
            Files.delete(file);
        }
    }

    public void sampleAnswersComeFromAiSamplesAndAreMarkedAsSamples() {
        Request<DraftTest> req = new Request<>("generate", "TC-103", "ignored in sample mode", List.of("system"),
                List.of(Turn.user(new Text("question"))), OutputConfig.Effort.LOW, DraftTest.class);
        AiGateway.Answer<DraftTest> answer = AiGateway.samplesOnly("claude-opus-5-5").ask(req);
        assertTrue(answer.sample(), "a sample must say it is one");
        assertTrue(answer.replayed(), "no API call was made");
        assertEquals(answer.value().className(), "SubmitAnnualLeaveTest");
        assertTrue(answer.usageLine().contains("no Claude call"), answer.usageLine());
    }

    public void everySampleFileSaysItIsHandWritten() throws Exception {
        List<Path> samples;
        try (var files = Files.walk(AiGateway.SAMPLES)) {
            samples = files.filter(p -> p.toString().endsWith(".json")).toList();
        }
        assertTrue(samples.size() >= 4, "generate and triage samples are in the repository: " + samples);
        for (Path p : samples) {
            String text = Files.readString(p);
            assertTrue(text.contains("NOT produced by Claude") && text.contains("\"writtenBy\""), p.toString());
        }
    }

    public void sampleDraftsStillMeetTheGateRules() {
        // Rule stage only (no browser): if a rule changes, the samples must be updated with it.
        var tc103 = com.niranjankagri.aitesting.ai.ProjectFiles.testCase("TC-103");
        var tc104 = com.niranjankagri.aitesting.ai.ProjectFiles.testCase("TC-104");
        AiGateway ai = AiGateway.samplesOnly("claude-opus-5-5");
        DraftTest good103 = ai.ask(sampleRequest("TC-103")).value();
        DraftTest bad104 = ai.ask(sampleRequest("TC-104")).value();
        DraftTest fixed104 = ai.ask(sampleRequest("TC-104-repair1")).value();
        assertTrue(rulesPass(tc103, good103), "TC-103 sample passes the rules");
        assertTrue(rulesPass(tc104, fixed104), "repaired TC-104 sample passes the rules");
        assertTrue(com.niranjankagri.aitesting.ai.gate.QualityGate.ruleChecks(tc104, bad104.className(),
                        bad104.javaSource(), bad104.coverage()).stream()
                .anyMatch(c -> !c.passed() && c.name().equals("no raw selectors or page access")),
                "the first TC-104 sample breaks the selector rule on purpose, so the repair loop shows");
    }

    private static Request<DraftTest> sampleRequest(String label) {
        return new Request<>("generate", label, "", List.of("system"), List.of(Turn.user(new Text("q"))),
                OutputConfig.Effort.LOW, DraftTest.class);
    }

    private static boolean rulesPass(com.niranjankagri.aitesting.ai.ProjectFiles.TestCaseDoc tc, DraftTest d) {
        return com.niranjankagri.aitesting.ai.gate.QualityGate.ruleChecks(tc, d.className(), d.javaSource(),
                d.coverage()).stream().allMatch(c -> c.passed());
    }

    public void approveRefusesASampleDraft() throws Exception {
        Path folder = com.niranjankagri.aitesting.ai.ProjectFiles.DRAFTS.resolve("TC-990");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("draft.json"), """
                {"testCase": "TC-990", "className": "XTest", "source": "sample", "gatePassed": true}""");
        try {
            IllegalStateException e = expectThrows(IllegalStateException.class,
                    () -> com.niranjankagri.aitesting.ai.generate.Approver.approve("TC-990", "Niranjan Kumar Agri"));
            assertTrue(e.getMessage().contains("hand-written sample"), e.getMessage());
        } finally {
            Files.delete(folder.resolve("draft.json"));
            Files.delete(folder);
        }
    }

    public void approveAddsImportAndAnnotation() throws Exception {
        Method mark = Class.forName("com.niranjankagri.aitesting.ai.generate.Approver")
                .getDeclaredMethod("markApproved", String.class, String.class, String.class, String.class);
        mark.setAccessible(true);
        String out = (String) mark.invoke(null, QualityGateRulesTest.GOOD, "claude-opus-5-5", "Niranjan Kumar Agri",
                "2026-10-17");
        assertTrue(out.startsWith("package com.niranjankagri.aitesting.tests.ai;\n\n"
                + "import com.niranjankagri.aitesting.framework.AiDrafted;\n"), out);
        assertTrue(out.contains("@AiDrafted(model = \"claude-opus-5-5\", reviewedBy = \"Niranjan Kumar Agri\","
                + " reviewedOn = \"2026-10-17\")\npublic class UnpaidLeaveNeedsReasonTest"), out);
    }

    public void triageKeyIgnoresPortsAndRetryCounts() throws Exception {
        Method stable = Class.forName("com.niranjankagri.aitesting.ai.triage.FailureTriage")
                .getDeclaredMethod("stable", String.class);
        stable.setAccessible(true);
        String run1 = "at http://127.0.0.1:52011/ 14 × locator resolved, waited 5000ms";
        String run2 = "at http://127.0.0.1:61377/ 9 × locator resolved, waited 5000ms";
        assertEquals(stable.invoke(null, run1), stable.invoke(null, run2));
    }
}
