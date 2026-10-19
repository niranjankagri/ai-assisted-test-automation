package com.niranjankagri.aitesting.ai.generate;

import com.anthropic.models.messages.OutputConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.niranjankagri.aitesting.ai.AiGateway;
import com.niranjankagri.aitesting.ai.AiGateway.Answer;
import com.niranjankagri.aitesting.ai.AiGateway.Text;
import com.niranjankagri.aitesting.ai.AiGateway.Turn;
import com.niranjankagri.aitesting.ai.ProjectFiles;
import com.niranjankagri.aitesting.ai.ProjectFiles.TestCaseDoc;
import com.niranjankagri.aitesting.ai.gate.GateReport;
import com.niranjankagri.aitesting.ai.gate.QualityGate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test case in, reviewed-ready draft out:
 * <pre>
 *   test-cases/TC-103.md ──► Claude drafts a test ──► quality gate ──► pass: ai-drafts/TC-103/ for review
 *                                  ▲                        │
 *                                  └── gate findings ◄──────┘ fail: up to MAX_REPAIRS more rounds
 * </pre>
 * The draft folder holds the Java file, review.md (what the reviewer reads) and draft.json (status
 * for the approve step). Nothing is written to src/test here: that is the reviewer's decision.
 */
public final class TestGenerator {

    /** Extra rounds in which Claude sees the gate findings and fixes its own draft. */
    public static final int MAX_REPAIRS = 2;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** What one generate run produced; {@code sample} = the answers were hand-written samples. */
    public record Result(String testCaseId, DraftTest draft, GateReport gate, int rounds, Path folder,
                         List<String> usage, boolean sample) {
    }

    private final AiGateway ai;

    /** The gateway decides the mode, so tests can hand in a replay-only or sample-only one. */
    public TestGenerator(AiGateway ai) {
        this.ai = ai;
    }

    /**
     * Drafts one test case: asks for a draft, runs the gate and, while it fails, sends the findings back for up to
     * MAX_REPAIRS repair rounds. Writes ai-drafts/<id>/ for review and returns what happened.
     */
    public Result generate(String testCaseId) {
        TestCaseDoc testCase = ProjectFiles.testCase(testCaseId);
        String systemPrompt = ProjectFiles.resource("prompts/generate-system.md");
        String context = frameworkContext();
        String baseKey = systemPrompt + "\n" + context + "\n" + testCase.markdown();

        List<Turn> turns = new ArrayList<>();
        turns.add(Turn.user(new Text("Automate this test case.\n\n" + testCase.markdown())));
        List<String> usage = new ArrayList<>();
        Path gateRoot = Path.of("target", "ai-gate", testCaseId);

        DraftTest draft = null;
        GateReport gate = null;
        boolean sample = false;
        int round = 0;
        while (round <= MAX_REPAIRS) {
            round++;
            // Repair rounds are keyed on the draft they repair, not on the gate text: gate messages
            // can contain run-specific details (ports, timings) that would break replay.
            String key = round == 1 ? baseKey
                    : baseKey + "\nrepair " + round + "\n" + AiGateway.sha256(draft.javaSource());
            Answer<DraftTest> answer = ai.ask(new AiGateway.Request<>("generate",
                    testCaseId + (round == 1 ? "" : "-repair" + (round - 1)), key,
                    List.of(systemPrompt, context), List.copyOf(turns), OutputConfig.Effort.HIGH, DraftTest.class));
            draft = answer.value();
            sample |= answer.sample();
            usage.add("round " + round + ": " + answer.usageLine());
            System.out.println("  " + testCaseId + " round " + round + ": " + draft.className()
                    + (answer.sample() ? " (hand-written sample)" : answer.replayed() ? " (replayed)" : "")
                    + ", running the quality gate...");

            gate = new QualityGate(gateRoot.resolve("round" + round))
                    .check(testCase, draft.className(), draft.javaSource(), draft.coverage());
            if (gate.passed()) {
                break;
            }
            System.out.println("  gate failed:\n" + gate.failuresAsText().indent(4));
            turns.add(Turn.assistant(toJson(draft)));
            turns.add(Turn.user(new Text("Your draft did not pass the quality gate:\n" + gate.failuresAsText()
                    + "\n\nFix it and return the whole corrected answer in the same format.")));
        }

        Path folder = writeDraftFolder(testCase, draft, gate, round, usage, sample);
        return new Result(testCaseId, draft, gate, round, folder, usage, sample);
    }

    /** The framework files Claude reads, each under a "=== path ===" header. */
    static String frameworkContext() {
        StringBuilder text = new StringBuilder(
                "Framework code (read-only). Each file starts with a line '=== <path> ==='.\n");
        for (Path file : ProjectFiles.GENERATION_CONTEXT) {
            text.append("\n=== ").append(file.toString().replace('\\', '/')).append(" ===\n")
                    .append(ProjectFiles.read(file));
        }
        return text.toString();
    }

    /** Replaces ai-drafts/<id>/ with the Java file, draft.json (what ai approve reads) and review.md. */
    private Path writeDraftFolder(TestCaseDoc testCase, DraftTest draft, GateReport gate, int rounds,
                                  List<String> usage, boolean sample) {
        Path folder = ProjectFiles.DRAFTS.resolve(testCase.id());
        try {
            Files.createDirectories(folder);
            try (var old = Files.list(folder)) {
                for (Path p : old.toList()) {
                    Files.delete(p);
                }
            }
            Files.writeString(folder.resolve(draft.className() + ".java"), draft.javaSource());

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("testCase", testCase.id());
            meta.put("className", draft.className());
            meta.put("model", sample ? "hand-written sample" : ai.model());
            // The approve step refuses samples: a hand-written draft must never be marked @AiDrafted.
            meta.put("source", sample ? "sample" : "claude");
            meta.put("gatePassed", gate.passed());
            meta.put("rounds", rounds);
            JSON.writeValue(folder.resolve("draft.json").toFile(), meta);

            Files.writeString(folder.resolve("review.md"),
                    reviewMarkdown(testCase, draft, gate, rounds, usage, sample));
            return folder;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The page a reviewer reads: the test case next to what the test claims to check. */
    private String reviewMarkdown(TestCaseDoc testCase, DraftTest draft, GateReport gate, int rounds,
                                  List<String> usage, boolean sample) {
        StringBuilder md = new StringBuilder();
        md.append("# Review: ").append(testCase.id()).append(" ").append(testCase.title()).append("\n\n");
        if (sample) {
            md.append(AiGateway.SAMPLE_BANNER).append("\n\n");
        }
        md.append("Draft `").append(draft.className()).append(".java` ")
                .append(sample ? "(hand-written sample)" : "by " + ai.model()).append(", ")
                .append(rounds).append(rounds == 1 ? " round" : " rounds").append(". Quality gate: **")
                .append(gate.passed() ? "passed" : "FAILED").append("**.\n\n");
        md.append("## Review checklist\n\n")
                .append("- [ ] Every expected result below is really asserted in the code (not just mentioned)\n")
                .append("- [ ] The assertions would fail if the feature broke (no always-true checks)\n")
                .append("- [ ] The assumptions are acceptable, or the test case gets clarified\n")
                .append("- [ ] Names and comments read like our own tests\n\n");
        md.append("## How each expected result is checked\n\n");
        draft.coverage().forEach(c -> md.append("- ").append(c).append("\n"));
        md.append("\n## Assumptions\n\n");
        appendList(md, draft.assumptions());
        md.append("\n## Missing page object methods\n\n");
        appendList(md, draft.missingPageMethods());
        md.append("\n## Quality gate\n\n").append(gate.toMarkdown());
        md.append("\n## Claude usage\n\n");
        usage.forEach(u -> md.append("- ").append(u).append("\n"));
        md.append("\n## Test case\n\n").append(testCase.markdown().replaceFirst("(?m)^# ", "### "));
        if (sample) {
            md.append("\n## Next step\n\nNone: sample drafts show the pipeline and are never approved into src/test."
                    + " Generate with ANTHROPIC_API_KEY set to get a draft from Claude that can be approved.\n");
        } else {
            md.append("\n## Next step\n\nApprove: `ai approve ").append(testCase.id())
                    .append(" Your Name` (copies the test into src/test with @AiDrafted). Or edit the draft first.\n");
        }
        return md.toString();
    }

    /** A Markdown bullet list, or "- none" for an empty one. */
    private static void appendList(StringBuilder md, List<String> items) {
        if (items.isEmpty()) {
            md.append("- none\n");
        } else {
            items.forEach(i -> md.append("- ").append(i).append("\n"));
        }
    }

    /** The draft as JSON, sent back as Claude's own earlier answer in a repair round. */
    private static String toJson(DraftTest draft) {
        try {
            return JSON.writeValueAsString(draft);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
