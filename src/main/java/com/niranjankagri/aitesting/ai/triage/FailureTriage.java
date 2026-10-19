package com.niranjankagri.aitesting.ai.triage;

import com.anthropic.models.messages.OutputConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.niranjankagri.aitesting.ai.AiGateway;
import com.niranjankagri.aitesting.ai.AiGateway.Answer;
import com.niranjankagri.aitesting.ai.AiGateway.Part;
import com.niranjankagri.aitesting.ai.AiGateway.Png;
import com.niranjankagri.aitesting.ai.AiGateway.Text;
import com.niranjankagri.aitesting.ai.AiGateway.Turn;
import com.niranjankagri.aitesting.ai.ProjectFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads the evidence folders the test run left in target/test-evidence and asks Claude, per failure,
 * whether it is a product bug, a test bug, an environment problem or a timing issue. Writes
 * target/ai-triage/triage.md (for people) and one JSON per failure (for tooling).
 */
public final class FailureTriage {

    public static final Path OUTPUT = Path.of("target", "ai-triage");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    /** Enough HTML to see the page state; the full DOM of a bigger app would only add cost. */
    private static final int MAX_HTML_CHARS = 20_000;

    /** One triaged failure; {@code sample} = the verdict is a hand-written sample, not Claude's. */
    public record Triaged(String test, String testCase, TriageVerdict verdict, String usage, boolean sample) {
    }

    private final AiGateway ai;
    private final Path evidenceRoot;

    public FailureTriage(AiGateway ai, Path evidenceRoot) {
        this.ai = ai;
        this.evidenceRoot = evidenceRoot;
    }

    /** Triages every failure found; an empty list means the run had none. */
    public List<Triaged> triageAll() {
        List<Path> folders = failureFolders();
        List<Triaged> results = new ArrayList<>();
        for (Path folder : folders) {
            results.add(triage(folder));
        }
        writeReport(results);
        return results;
    }

    private List<Path> failureFolders() {
        if (!Files.isDirectory(evidenceRoot)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(evidenceRoot)) {
            return dirs.filter(d -> Files.exists(d.resolve("failure.json"))).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Triaged triage(Path folder) {
        try {
            JsonNode failure = JSON.readTree(folder.resolve("failure.json").toFile());
            String test = failure.get("test").asText();
            String testCaseId = failure.path("testCase").asText("");
            String className = test.substring(0, test.lastIndexOf('.'));

            String testCase = testCaseId.isBlank() ? "(no test case linked)"
                    : ProjectFiles.testCase(testCaseId).markdown();
            String testSource = ProjectFiles.read(ProjectFiles.sourceOf(className));
            String pages = ProjectFiles.read(ProjectFiles.GENERATION_CONTEXT.get(3)) + "\n"
                    + ProjectFiles.read(ProjectFiles.GENERATION_CONTEXT.get(4));
            String error = failure.get("error").asText();
            String html = trimHtml(Files.exists(folder.resolve("page.html"))
                    ? Files.readString(folder.resolve("page.html")) : "");

            List<Part> parts = new ArrayList<>();
            parts.add(new Text("Test case (the requirement):\n" + testCase));
            parts.add(new Text("Automated test " + test + (failure.get("aiDrafted").asBoolean()
                    ? " (AI-drafted, reviewed by a person)" : " (hand-written)") + ":\n" + testSource));
            parts.add(new Text("Page objects the test uses:\n" + pages));
            parts.add(new Text("Error and stack:\n" + failure.get("stack").asText()
                    + "\n\nBrowser console:\n" + failure.get("console").toString()
                    + "\n\nURL at failure: " + failure.get("url").asText()));
            parts.add(new Text("Page HTML at failure (scripts and styles removed):\n" + html));
            if (Files.exists(folder.resolve("screenshot.png"))) {
                parts.add(new Text("Screenshot at failure:"));
                parts.add(new Png(Files.readAllBytes(folder.resolve("screenshot.png"))));
            }

            String systemPrompt = ProjectFiles.resource("prompts/triage-system.md");
            // The recording key leaves out the screenshot, the port in URLs and retry counts: they
            // change from run to run while the failure stays the same.
            String key = systemPrompt + test + stable(error) + testCase + testSource + pages + stable(html);
            Answer<TriageVerdict> answer = ai.ask(new AiGateway.Request<>("triage",
                    test.substring(test.lastIndexOf('.', test.lastIndexOf('.') - 1) + 1), key,
                    List.of(systemPrompt), List.of(new Turn(true, parts)), OutputConfig.Effort.MEDIUM,
                    TriageVerdict.class));
            Files.createDirectories(OUTPUT);
            JSON.writeValue(OUTPUT.resolve(folder.getFileName() + ".json").toFile(), answer.value());
            return new Triaged(test, testCaseId, answer.value(), answer.usageLine(), answer.sample());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes the volatile bits (ports, "14 ×" retry counts, timings) before hashing. */
    static String stable(String text) {
        return text.replaceAll("127\\.0\\.0\\.1:\\d+", "127.0.0.1:PORT")
                .replaceAll("\\d+ ×", "N ×")
                .replaceAll("\\d+ms", "Nms");
    }

    static String trimHtml(String html) {
        String clean = html.replaceAll("(?is)<script.*?</script>", "").replaceAll("(?is)<style.*?</style>", "");
        return clean.length() <= MAX_HTML_CHARS ? clean : clean.substring(0, MAX_HTML_CHARS) + "\n... (cut)";
    }

    private void writeReport(List<Triaged> results) {
        StringBuilder md = new StringBuilder("# AI failure triage\n\n");
        if (results.stream().anyMatch(Triaged::sample)) {
            md.append(AiGateway.SAMPLE_BANNER).append("\n\n");
        }
        if (results.isEmpty()) {
            md.append("No failed tests in ").append(evidenceRoot).append(".\n");
        } else {
            md.append("| Test | Test case | Verdict | Confidence | Summary |\n|---|---|---|---|---|\n");
            for (Triaged t : results) {
                md.append("| ").append(t.test().substring(t.test().lastIndexOf('.', t.test().lastIndexOf('.') - 1) + 1))
                        .append(" | ").append(t.testCase()).append(" | **").append(t.verdict().category())
                        .append("** | ").append(t.verdict().confidence()).append(" | ")
                        .append(t.verdict().summary().replace("|", "\\|").replace("\n", " ")).append(" |\n");
            }
            for (Triaged t : results) {
                TriageVerdict v = t.verdict();
                md.append("\n## ").append(t.test()).append("\n\n")
                        .append("**").append(v.category()).append("** (").append(v.confidence())
                        .append(" confidence): ").append(v.summary()).append("\n\n### Evidence\n\n");
                v.evidence().forEach(e -> md.append("- ").append(e).append("\n"));
                md.append("\n### Next step\n\n").append(v.nextStep()).append("\n");
                if (!v.bugReportMarkdown().isBlank()) {
                    md.append("\n### Draft bug report\n\n").append(v.bugReportMarkdown()).append("\n");
                }
                md.append("\n_").append(t.usage()).append("_\n");
            }
        }
        md.append("\n---\nAI verdicts are a starting point for a person, not a replacement for one.\n");
        try {
            Files.createDirectories(OUTPUT);
            Files.writeString(OUTPUT.resolve("triage.md"), md.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
