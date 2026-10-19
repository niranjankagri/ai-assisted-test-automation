package com.niranjankagri.aitesting.ai.gate;

import com.niranjankagri.aitesting.ai.ProjectFiles;
import com.niranjankagri.aitesting.ai.ProjectFiles.TestCaseDoc;
import com.niranjankagri.aitesting.ai.gate.GateReport.Check;
import org.testng.ITestResult;
import org.testng.TestListenerAdapter;
import org.testng.TestNG;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether an AI draft is good enough to show a reviewer. Three stages, each one only when the
 * one before passed:
 * <ol>
 *   <li><b>rules</b>: text checks on the source (framework conventions and safety: generated code
 *       must not touch files, the network or processes);</li>
 *   <li><b>compile</b>: javac against the project's classpath;</li>
 *   <li><b>run</b>: the test runs {@value #RUNS} times against a fresh LeaveDesk and must pass every
 *       time (a test that passes 2 of 3 times is flaky, and a flaky test is not a review candidate).</li>
 * </ol>
 * The rules are a cheap first filter, not a sandbox: the person who reviews the draft is the real
 * control, which is why nothing reaches src/test without the approve step.
 */
public final class QualityGate {

    public static final int RUNS = 3;

    /** A text rule on the draft's source. */
    record Rule(String name, Pattern pattern, boolean mustMatch, String why) {
    }

    static final List<Rule> RULES = List.of(
            must("package is " + ProjectFiles.AI_TEST_PACKAGE,
                    "^package " + Pattern.quote(ProjectFiles.AI_TEST_PACKAGE) + ";", "drafts live in one package"),
            must("extends BaseUiTest", "class \\w+ extends BaseUiTest\\b", "app, browser and evidence come from it"),
            must("has a @Test method", "@Test\\b", "TestNG must find the test"),
            must("asserts with assertThat", "assertThat\\(", "a test without an assertion proves nothing"),
            never("no raw selectors or page access",
                    "\\bpage\\(\\)|getByTestId|getByText|getByRole|getByLabel|\\.locator\\(|querySelector",
                    "only page objects know selectors"),
            never("no sleeps or manual waits", "Thread\\.sleep|waitForTimeout|TimeUnit\\.\\w+\\.sleep",
                    "Playwright assertions already wait"),
            never("no JavaScript in the page", "\\.evaluate\\(|evaluateHandle|addInitScript",
                    "a UI test must use the UI"),
            never("no file, network, process or reflection access",
                    "\\bjava\\.(io|nio|net)\\.|java\\.lang\\.reflect|ProcessBuilder|Runtime\\.getRuntime|System\\.exit"
                            + "|System\\.getenv|ClassLoader",
                    "generated code stays inside the test"),
            never("no disabled or skipped tests", "enabled\\s*=\\s*false|SkipException|throw new SkipException",
                    "a draft must run"),
            never("no swallowed exceptions", "catch\\s*\\(\\s*(Throwable|Exception|AssertionError|Error)\\b",
                    "catching hides failures"));

    private final Path workRoot;

    /** @param workRoot folder for sources, classes and evidence of gate runs (under target/) */
    public QualityGate(Path workRoot) {
        this.workRoot = workRoot;
    }

    /** A rule the draft must match (multi-line regex). */
    private static Rule must(String name, String regex, String why) {
        return new Rule(name, Pattern.compile(regex, Pattern.MULTILINE), true, why);
    }

    /** A rule the draft must not match (multi-line regex). */
    private static Rule never(String name, String regex, String why) {
        return new Rule(name, Pattern.compile(regex, Pattern.MULTILINE), false, why);
    }

    /**
     * Runs all stages on one draft.
     *
     * @param testCase      the manual test case the draft automates
     * @param className     simple class name the draft declares
     * @param source        full Java source
     * @param coverageLines Claude's "how each expected result is checked" list
     */
    public GateReport check(TestCaseDoc testCase, String className, String source, List<String> coverageLines) {
        List<Check> checks = new ArrayList<>(ruleChecks(testCase, className, source, coverageLines));
        if (checks.stream().allMatch(Check::passed)) {
            Path classes = compile(className, source, checks);
            if (classes != null) {
                run(className, classes, checks);
            }
        }
        return new GateReport(checks);
    }

    /** Stage 1, public for the unit tests: conventions and safety, decided from the text alone. */
    public static List<Check> ruleChecks(TestCaseDoc testCase, String className, String source,
                                         List<String> coverageLines) {
        List<Check> checks = new ArrayList<>();
        for (Rule rule : RULES) {
            var m = rule.pattern().matcher(source);
            boolean found = m.find();
            boolean ok = rule.mustMatch() == found;
            String detail = ok ? "" : rule.mustMatch() ? "missing (" + rule.why() + ")"
                    : "found \"" + m.group() + "\" on line " + lineOf(source, m.start()) + " (" + rule.why() + ")";
            checks.add(new Check("rules", rule.name(), ok, detail));
        }
        boolean idOk = source.contains("@TestCase(\"" + testCase.id() + "\")");
        checks.add(new Check("rules", "linked to " + testCase.id(), idOk,
                idOk ? "" : "the @Test method needs @TestCase(\"" + testCase.id() + "\")"));
        boolean nameOk = className.matches("[A-Z]\\w*Test") && source.contains("class " + className + " ");
        checks.add(new Check("rules", "class name " + className, nameOk,
                nameOk ? "" : "must end in Test and match the class declared in the source"));
        long expected = expectedResultCount(testCase.markdown());
        boolean coverageOk = coverageLines.size() == expected;
        checks.add(new Check("rules", "coverage lists every expected result", coverageOk,
                coverageOk ? "" : expected + " expected results in the test case, " + coverageLines.size()
                        + " coverage lines in the answer"));
        return checks;
    }

    /** Bullets under "## Expected result" in the test case. */
    static long expectedResultCount(String markdown) {
        int start = markdown.indexOf("## Expected result");
        if (start < 0) {
            return 0;
        }
        String section = markdown.substring(start).lines().skip(1)
                .takeWhile(l -> !l.startsWith("## "))
                .reduce("", (a, b) -> a + b + "\n");
        return section.lines().filter(l -> l.startsWith("- ")).count();
    }

    /** Stage 2: javac in memory of this JVM, classpath = the project's test classpath. */
    private Path compile(String className, String source, List<Check> checks) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            checks.add(new Check("compile", "compiles", false, "no Java compiler: run on a JDK, not a JRE"));
            return null;
        }
        try {
            Path src = workRoot.resolve("src").resolve(ProjectFiles.AI_TEST_PACKAGE.replace('.', '/'));
            Path classes = workRoot.resolve("classes");
            Files.createDirectories(src);
            Files.createDirectories(classes);
            Path file = src.resolve(className + ".java");
            Files.writeString(file, source);

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
                List<String> options = List.of("-classpath", System.getProperty("java.class.path"),
                        "-d", classes.toString(), "--release", "21", "-proc:none", "-Xlint:none");
                boolean ok = javac.getTask(null, files, diagnostics, options, null,
                        files.getJavaFileObjects(file.toFile())).call();
                List<String> errors = diagnostics.getDiagnostics().stream()
                        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                        .map(d -> "line " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT))
                        .toList();
                checks.add(new Check("compile", "compiles", ok, String.join("\n", errors)));
                return ok ? classes : null;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Stage 3: TestNG in this JVM, RUNS times, each run with its own app, browser and evidence folder. */
    private void run(String className, Path classes, List<Check> checks) {
        String previousEvidence = System.getProperty("evidence.dir");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                Thread.currentThread().getContextClassLoader())) {
            Class<?> testClass = loader.loadClass(ProjectFiles.AI_TEST_PACKAGE + "." + className);
            for (int run = 1; run <= RUNS; run++) {
                System.setProperty("evidence.dir", workRoot.resolve("evidence-run" + run).toString());
                TestListenerAdapter results = new TestListenerAdapter();
                TestNG testng = new TestNG(false);
                testng.setTestClasses(new Class<?>[] {testClass});
                testng.setOutputDirectory(workRoot.resolve("testng-run" + run).toString());
                testng.addListener(results);
                testng.setVerbose(0);
                testng.run();

                int passed = results.getPassedTests().size();
                List<String> problems = new ArrayList<>();
                for (ITestResult r : results.getFailedTests()) {
                    problems.add(r.getName() + ": " + firstLines(String.valueOf(r.getThrowable()), 12));
                }
                for (ITestResult r : results.getConfigurationFailures()) {
                    problems.add("setup " + r.getName() + ": " + firstLines(String.valueOf(r.getThrowable()), 6));
                }
                boolean ok = problems.isEmpty() && passed > 0 && results.getSkippedTests().isEmpty();
                checks.add(new Check("run", "run " + run + " of " + RUNS, ok,
                        ok ? passed + " passed" : problems.isEmpty() ? "nothing ran" : String.join("\n", problems)));
                if (!ok) {
                    return; // one failed run is enough; no need to spend two more browser runs
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            checks.add(new Check("run", "loads", false, e.toString()));
        } finally {
            if (previousEvidence == null) {
                System.clearProperty("evidence.dir");
            } else {
                System.setProperty("evidence.dir", previousEvidence);
            }
        }
    }

    /** 1-based line number of a character offset, so a failed rule can say where it found the problem. */
    private static int lineOf(String text, int offset) {
        return (int) text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }

    /** The first n lines of a stack trace: enough to see the failure without flooding the report. */
    private static String firstLines(String text, int n) {
        return String.join("\n", text.lines().limit(n).toList());
    }
}
