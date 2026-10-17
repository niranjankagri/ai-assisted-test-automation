package com.niranjankagri.aitesting.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Paths of the repo that the AI tooling reads and writes. All relative to the project root. */
public final class ProjectFiles {

    public static final Path TEST_CASES = Path.of("test-cases");
    public static final Path DRAFTS = Path.of("ai-drafts");
    public static final Path TEST_SOURCES = Path.of("src", "test", "java");
    public static final Path AI_TESTS = TEST_SOURCES.resolve("com/niranjankagri/aitesting/tests/ai");
    public static final String AI_TEST_PACKAGE = "com.niranjankagri.aitesting.tests.ai";

    /**
     * The framework code Claude gets as context for a draft: the base class, the page objects, the
     * annotations, the users, one hand-written test as the style reference, and the UI's HTML (to
     * understand the screen; the tests themselves may only use page objects).
     */
    public static final List<Path> GENERATION_CONTEXT = List.of(
            TEST_SOURCES.resolve("com/niranjankagri/aitesting/framework/BaseUiTest.java"),
            TEST_SOURCES.resolve("com/niranjankagri/aitesting/framework/TestUsers.java"),
            TEST_SOURCES.resolve("com/niranjankagri/aitesting/framework/TestCase.java"),
            TEST_SOURCES.resolve("com/niranjankagri/aitesting/framework/pages/LoginPage.java"),
            TEST_SOURCES.resolve("com/niranjankagri/aitesting/framework/pages/DashboardPage.java"),
            TEST_SOURCES.resolve("com/niranjankagri/aitesting/tests/handwritten/LoginTest.java"),
            Path.of("src", "main", "resources", "web", "index.html"));

    private static final Pattern HEADING = Pattern.compile("^# (TC-\\d+) (.+)$", Pattern.MULTILINE);

    /** A manual test case from test-cases/: id, title and the whole Markdown text. */
    public record TestCaseDoc(String id, String title, String markdown) {
    }

    private ProjectFiles() {
    }

    public static String read(Path file) {
        try {
            // Line endings normalised, so a Windows checkout and CI hash the same text.
            return Files.readString(file).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    public static TestCaseDoc testCase(String id) {
        Path file = TEST_CASES.resolve(id + ".md");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("No test case " + file);
        }
        String markdown = read(file);
        Matcher m = HEADING.matcher(markdown);
        if (!m.find() || !m.group(1).equals(id)) {
            throw new IllegalArgumentException(file + " must start with '# " + id + " <title>'");
        }
        return new TestCaseDoc(id, m.group(2).trim(), markdown);
    }

    /** Ids of all test cases, sorted. */
    public static List<String> allTestCaseIds() {
        try (Stream<Path> files = Files.list(TEST_CASES)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("TC-\\d+\\.md"))
                    .map(n -> n.substring(0, n.length() - 3))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Test case ids that already have an automated test (hand-written or approved AI draft). */
    public static List<String> automatedTestCaseIds() {
        Pattern annotation = Pattern.compile("@TestCase\\(\"(TC-\\d+)\"\\)");
        try (Stream<Path> files = Files.walk(TEST_SOURCES.resolve("com/niranjankagri/aitesting/tests"))) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .flatMap(p -> annotation.matcher(read(p)).results().map(r -> r.group(1)))
                    .distinct()
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Test case ids of the approved AI tests in src/test (CI regenerates these from recordings). */
    public static List<String> approvedAiTestCaseIds() {
        if (!Files.isDirectory(AI_TESTS)) {
            return List.of();
        }
        Pattern annotation = Pattern.compile("@TestCase\\(\"(TC-\\d+)\"\\)");
        try (Stream<Path> files = Files.list(AI_TESTS)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .flatMap(p -> annotation.matcher(read(p)).results().map(r -> r.group(1)))
                    .distinct()
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Finds the source file of a test class by its fully qualified name. */
    public static Path sourceOf(String className) {
        return TEST_SOURCES.resolve(className.replace('.', '/') + ".java");
    }

    /** A system resource from src/main/resources (the prompts). */
    public static String resource(String name) {
        try (var in = ProjectFiles.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
