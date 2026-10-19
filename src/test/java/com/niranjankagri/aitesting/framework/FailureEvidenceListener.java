package com.niranjankagri.aitesting.framework;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.microsoft.playwright.Page;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Saves the evidence of every failed UI test into target/test-evidence/&lt;Class&gt;.&lt;method&gt;/
 * (the quality gate points {@code -Devidence.dir} elsewhere for its own runs):
 * failure.json (test case id, error, stack, URL, console lines), screenshot.png, page.html and,
 * from {@link BaseUiTest}, trace.zip. The AI triage step reads exactly these files, so a person
 * and Claude look at the same evidence.
 */
public class FailureEvidenceListener implements ITestListener {

    /** Evidence root: target/test-evidence unless the system property evidence.dir says otherwise. */
    public static Path root() {
        return Path.of(System.getProperty("evidence.dir", "target/test-evidence"));
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** The evidence folder of one test result (created if missing). */
    public static Path folderFor(ITestResult result) {
        Path dir = root().resolve(result.getTestClass().getRealClass().getSimpleName()
                + "." + result.getMethod().getMethodName());
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Saves what the triage step needs for a failed UI test: full-page screenshot, page HTML and failure.json
     * (test case, test, AI-drafted or not, error, stack, URL, console).
     */
    @Override
    public void onTestFailure(ITestResult result) {
        if (!(result.getInstance() instanceof BaseUiTest test) || test.page() == null) {
            return;
        }
        Path dir = folderFor(result);
        Page page = test.page();
        try {
            page.screenshot(new Page.ScreenshotOptions().setPath(dir.resolve("screenshot.png")).setFullPage(true));
            Files.writeString(dir.resolve("page.html"), page.content());

            TestCase testCase = result.getMethod().getConstructorOrMethod().getMethod().getAnnotation(TestCase.class);
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("testCase", testCase == null ? null : testCase.value());
            failure.put("test", result.getTestClass().getRealClass().getName() + "." + result.getMethod().getMethodName());
            failure.put("aiDrafted", result.getTestClass().getRealClass().isAnnotationPresent(AiDrafted.class));
            failure.put("error", String.valueOf(result.getThrowable()));
            failure.put("stack", stackTop(result.getThrowable()));
            failure.put("url", page.url());
            failure.put("console", test.consoleLines());
            failure.put("durationMs", result.getEndMillis() - result.getStartMillis());
            JSON.writeValue(dir.resolve("failure.json").toFile(), failure);
        } catch (IOException | RuntimeException e) {
            // Evidence is best effort: never hide the real test failure behind an evidence problem.
            System.err.println("Could not save failure evidence for " + result.getName() + ": " + e);
        }
    }

    /** The first stack lines, enough to see the failing assertion and the test line. */
    private static String stackTop(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString().lines().limit(25).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
