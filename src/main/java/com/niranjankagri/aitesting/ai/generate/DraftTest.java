package com.niranjankagri.aitesting.ai.generate;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Claude's answer to "automate this test case". The SDK turns this record into the JSON schema
 * of the request, so every field below is guaranteed to be present in the reply.
 */
public record DraftTest(
        @JsonPropertyDescription("Simple name of the test class, for example SubmitAnnualLeaveTest")
        String className,

        @JsonPropertyDescription("Complete Java source file of the test class, package line included")
        String javaSource,

        @JsonPropertyDescription("One line per expected result of the test case: how the test checks it")
        List<String> coverage,

        @JsonPropertyDescription("Decisions the test case did not make and the test had to take; may be empty")
        List<String> assumptions,

        @JsonPropertyDescription("Page object methods the test would need but that do not exist; may be empty")
        List<String> missingPageMethods) {
}
