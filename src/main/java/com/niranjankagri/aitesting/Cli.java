package com.niranjankagri.aitesting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niranjankagri.aitesting.ai.AiGateway;
import com.niranjankagri.aitesting.ai.ProjectFiles;
import com.niranjankagri.aitesting.ai.generate.Approver;
import com.niranjankagri.aitesting.ai.generate.TestGenerator;
import com.niranjankagri.aitesting.ai.gate.GateReport;
import com.niranjankagri.aitesting.ai.gate.QualityGate;
import com.niranjankagri.aitesting.ai.triage.FailureTriage;
import com.niranjankagri.aitesting.app.LeaveDeskServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One entry point for everything you run by hand. Through the wrapper scripts:
 * <pre>
 *   ai app [port] [--bug]           start LeaveDesk (default port 8080)
 *   ai status                       which test cases are automated, drafted or open
 *   ai generate TC-103 [TC-104 ...] Claude drafts tests; the quality gate checks them
 *   ai generate --open              draft every test case that has no test yet
 *   ai generate --approved          redo the approved AI tests (CI: from recordings)
 *   ai gate TC-103                  re-run the gate on a draft you edited
 *   ai approve TC-103 Your Name     move a passed draft into src/test with @AiDrafted
 *   ai triage                       Claude triages the failures of the last UI run
 * </pre>
 */
public final class Cli {

    private Cli() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "help" : args[0];
        List<String> rest = Arrays.asList(args).subList(Math.min(1, args.length), args.length);
        int exit = switch (command) {
            case "app" -> {
                int port = !rest.isEmpty() && rest.get(0).matches("\\d+") ? Integer.parseInt(rest.get(0)) : 8080;
                LeaveDeskServer.runForever(port, rest.contains("--bug"));
                yield 0;
            }
            case "status" -> status();
            case "generate" -> generate(rest);
            case "gate" -> gate(rest);
            case "approve" -> approve(rest);
            case "triage" -> triage();
            default -> help();
        };
        System.exit(exit);
    }

    private static int status() {
        List<String> automated = ProjectFiles.automatedTestCaseIds();
        for (String id : ProjectFiles.allTestCaseIds()) {
            String state = automated.contains(id) ? "automated"
                    : Files.exists(ProjectFiles.DRAFTS.resolve(id).resolve("draft.json")) ? "drafted (waiting for review)"
                    : "open";
            System.out.printf("%-7s %-30s %s%n", id, state, ProjectFiles.testCase(id).title());
        }
        return 0;
    }

    private static int generate(List<String> args) {
        List<String> ids = new ArrayList<>(args);
        if (ids.remove("--open")) {
            List<String> automated = ProjectFiles.automatedTestCaseIds();
            ProjectFiles.allTestCaseIds().stream().filter(id -> !automated.contains(id)).forEach(ids::add);
        }
        if (ids.remove("--approved")) {
            // Regenerate the approved AI tests from their recordings: proves in CI that the recorded
            // answers still pass today's gate (and that nothing changed the prompt or its inputs).
            ids.addAll(ProjectFiles.approvedAiTestCaseIds());
        }
        if (ids.isEmpty()) {
            System.out.println("Name the test cases: ai generate TC-103 [TC-104 ...] or ai generate --open");
            return 2;
        }
        AiGateway ai = AiGateway.fromEnvironment();
        System.out.println("Model " + ai.model() + ", mode " + (ai.isLive() ? "live (answers are recorded)"
                : "replay (recorded answers, no API key needed)"));
        TestGenerator generator = new TestGenerator(ai);
        int failed = 0;
        for (String id : ids) {
            TestGenerator.Result r = generator.generate(id);
            System.out.println((r.gate().passed() ? "PASSED " : "FAILED ") + id + " -> " + r.folder()
                    + "/review.md (" + r.rounds() + (r.rounds() == 1 ? " round)" : " rounds)"));
            r.usage().forEach(u -> System.out.println("    " + u));
            if (!r.gate().passed()) {
                failed++;
            }
        }
        System.out.println(failed == 0 ? "All drafts passed the gate. Review them, then: ai approve <id> Your Name"
                : failed + " draft(s) failed the gate; see review.md in their folders.");
        return failed == 0 ? 0 : 1;
    }

    private static int gate(List<String> args) throws Exception {
        if (args.isEmpty()) {
            System.out.println("Name the draft: ai gate TC-103");
            return 2;
        }
        String id = args.get(0);
        Path folder = ProjectFiles.DRAFTS.resolve(id);
        JsonNode meta = new ObjectMapper().readTree(folder.resolve("draft.json").toFile());
        String className = meta.get("className").asText();
        String source = Files.readString(folder.resolve(className + ".java"));
        String review = Files.readString(folder.resolve("review.md"));
        List<String> coverage = review.substring(review.indexOf("## How each"), review.indexOf("## Assumptions"))
                .lines().filter(l -> l.startsWith("- ")).toList();
        GateReport report = new QualityGate(Path.of("target", "ai-gate", id, "manual"))
                .check(ProjectFiles.testCase(id), className, source, coverage);
        System.out.println(report.toMarkdown());
        if (report.passed()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) meta).put("gatePassed", true);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("draft.json").toFile(), meta);
        }
        return report.passed() ? 0 : 1;
    }

    private static int approve(List<String> args) {
        if (args.size() < 2) {
            System.out.println("Usage: ai approve TC-103 Your Name");
            return 2;
        }
        Path target = Approver.approve(args.get(0), String.join(" ", args.subList(1, args.size())));
        System.out.println("Approved: " + target + "\nRun it with the others: mvnw test -Dsuite=ui");
        return 0;
    }

    private static int triage() {
        AiGateway ai = AiGateway.fromEnvironment();
        List<FailureTriage.Triaged> results = new FailureTriage(ai, Path.of("target", "test-evidence")).triageAll();
        if (results.isEmpty()) {
            System.out.println("No failed tests in target/test-evidence. Run the UI tests first: mvnw test -Dsuite=ui");
            return 0;
        }
        for (FailureTriage.Triaged t : results) {
            System.out.println(t.verdict().category() + " (" + t.verdict().confidence() + ")  " + t.test()
                    + "\n    " + t.verdict().summary() + "\n    " + t.usage());
        }
        System.out.println("Full report: " + FailureTriage.OUTPUT.resolve("triage.md"));
        return 0;
    }

    private static int help() {
        System.out.println("""
                Commands (run through ai.cmd on Windows or ./ai.sh elsewhere):
                  app [port] [--bug]            start LeaveDesk (default 8080; --bug plants the overlap bug)
                  status                        which test cases are automated, drafted or open
                  generate TC-103 [TC-104 ...]  Claude drafts tests, the quality gate checks them
                  generate --open               draft every test case that has no test yet
                  generate --approved           redo the approved AI tests (CI does this from recordings)
                  gate TC-103                   re-run the gate on a draft you edited
                  approve TC-103 Your Name      move a passed draft into src/test with @AiDrafted
                  triage                        Claude triages the failures of the last UI run
                Live mode needs ANTHROPIC_API_KEY; without it, recorded answers are replayed.""");
        return 0;
    }
}
