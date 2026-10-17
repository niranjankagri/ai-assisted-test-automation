package com.niranjankagri.aitesting.ai.gate;

import java.util.ArrayList;
import java.util.List;

/** Result of the quality gate: every check with pass/fail and the detail a person (or Claude) needs. */
public record GateReport(List<Check> checks) {

    /** One check, for example "compiles" or "rule: no raw selectors". */
    public record Check(String stage, String name, boolean passed, String detail) {
    }

    public boolean passed() {
        return checks.stream().allMatch(Check::passed);
    }

    /** Only the failed checks, as the text the repair round sends back to Claude. */
    public String failuresAsText() {
        List<String> lines = new ArrayList<>();
        for (Check c : checks) {
            if (!c.passed()) {
                lines.add("- [" + c.stage() + "] " + c.name() + (c.detail().isBlank() ? "" : ": " + c.detail()));
            }
        }
        return String.join("\n", lines);
    }

    /** Markdown table for the draft's review file. */
    public String toMarkdown() {
        StringBuilder md = new StringBuilder("| Stage | Check | Result | Detail |\n|---|---|---|---|\n");
        for (Check c : checks) {
            md.append("| ").append(c.stage()).append(" | ").append(c.name()).append(" | ")
                    .append(c.passed() ? "pass" : "**FAIL**").append(" | ")
                    .append(c.detail().replace("|", "\\|").replace("\n", "<br>")).append(" |\n");
        }
        return md.toString();
    }
}
