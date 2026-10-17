package com.niranjankagri.aitesting.ai.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niranjankagri.aitesting.ai.ProjectFiles;
import com.niranjankagri.aitesting.ai.gate.GateReport;
import com.niranjankagri.aitesting.ai.gate.QualityGate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * The human step: a reviewer approves a draft, and only then it moves into src/test, marked with
 * {@code @AiDrafted(model, reviewedBy, reviewedOn)}. The reviewer may have edited the draft; the rule
 * stage of the gate runs again so an edit cannot sneak in a raw selector or a sleep.
 */
public final class Approver {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Approver() {
    }

    /** Copies the approved draft into src/test and returns its new path. */
    public static Path approve(String testCaseId, String reviewer) {
        if (reviewer == null || reviewer.isBlank()) {
            throw new IllegalArgumentException("Name the reviewer: ai approve " + testCaseId + " Your Name");
        }
        Path folder = ProjectFiles.DRAFTS.resolve(testCaseId);
        try {
            JsonNode meta = JSON.readTree(folder.resolve("draft.json").toFile());
            if (!meta.get("gatePassed").asBoolean()) {
                throw new IllegalStateException(testCaseId + " did not pass the quality gate; generate it again"
                        + " or fix the draft and run: ai gate " + testCaseId);
            }
            String className = meta.get("className").asText();
            String source = Files.readString(folder.resolve(className + ".java"));

            GateReport rules = new GateReport(QualityGate.ruleChecks(ProjectFiles.testCase(testCaseId), className,
                    source, coverageOf(folder)));
            if (!rules.passed()) {
                throw new IllegalStateException("The edited draft breaks the rules:\n" + rules.failuresAsText());
            }

            Path target = ProjectFiles.AI_TESTS.resolve(className + ".java");
            if (Files.exists(target)) {
                throw new IllegalStateException(target + " already exists; delete it first to replace it");
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, markApproved(source, meta.get("model").asText(), reviewer.trim(),
                    LocalDate.now().toString()));
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Adds the import and the {@code @AiDrafted} annotation in front of the class declaration. */
    static String markApproved(String source, String model, String reviewer, String date) {
        String annotation = "@AiDrafted(model = \"" + model + "\", reviewedBy = \"" + reviewer.replace("\"", "")
                + "\", reviewedOn = \"" + date + "\")\n";
        // "package ...;" + blank line, then our import ahead of the draft's own imports.
        String withImport = source.replaceFirst("(?m)^(package [\\w.]+;\\R\\R)",
                "$1import com.niranjankagri.aitesting.framework.AiDrafted;\n");
        return withImport.replaceFirst("(?m)^public class ", annotation + "public class ");
    }

    /** The coverage list from review.md, so the rule "coverage lists every expected result" can rerun. */
    private static List<String> coverageOf(Path folder) throws IOException {
        String review = Files.readString(folder.resolve("review.md"));
        int start = review.indexOf("## How each expected result is checked");
        int end = review.indexOf("## Assumptions");
        return review.substring(start, end).lines().filter(l -> l.startsWith("- ")).toList();
    }
}
