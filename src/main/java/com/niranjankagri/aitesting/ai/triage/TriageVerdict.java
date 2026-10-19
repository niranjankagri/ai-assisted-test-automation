package com.niranjankagri.aitesting.ai.triage;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Claude's verdict on one failed test. The record is the JSON schema of the answer. */
public record TriageVerdict(
        @JsonPropertyDescription("Most likely cause of the failure")
        Category category,

        @JsonPropertyDescription("How strongly the evidence supports the category")
        Confidence confidence,

        @JsonPropertyDescription("One or two sentences: what failed and why")
        String summary,

        @JsonPropertyDescription("The evidence the verdict rests on, quoted from the error, HTML, console or screenshot")
        List<String> evidence,

        @JsonPropertyDescription("What a person should do next")
        String nextStep,

        @JsonPropertyDescription("Bug ticket in Markdown when the category is PRODUCT_BUG, otherwise an empty string")
        String bugReportMarkdown) {

    /** The possible causes; NEEDS_HUMAN is the honest answer when the evidence does not decide. */
    public enum Category { PRODUCT_BUG, TEST_BUG, ENVIRONMENT, FLAKY_OR_TIMING, NEEDS_HUMAN }

    /** How strongly the evidence supports the category. */
    public enum Confidence { HIGH, MEDIUM, LOW }
}
