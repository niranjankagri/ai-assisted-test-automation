package com.niranjankagri.aitesting.framework;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.RequestOptions;
import com.niranjankagri.aitesting.app.LeaveDeskServer;
import com.niranjankagri.aitesting.framework.pages.DashboardPage;
import com.niranjankagri.aitesting.framework.pages.LoginPage;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Listeners;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class of every UI test, hand-written or AI-drafted.
 *
 * <p>Per suite: starts LeaveDesk on a free port inside this JVM and launches one browser.
 * Per test: empties the app's data, opens a fresh browser context (no shared cookies or storage),
 * records a Playwright trace and keeps the browser console. On failure,
 * {@link FailureEvidenceListener} saves what the AI triage step needs.
 *
 * <p>System properties: {@code -Dheadless=false} shows the browser, {@code -Dbrowser=firefox|webkit}
 * switches engines, {@code -Dleavedesk.bug=true} starts the app with the planted overlap bug.
 */
@Listeners(FailureEvidenceListener.class)
public abstract class BaseUiTest {

    private static LeaveDeskServer app;
    private static Playwright playwright;
    private static Browser browser;

    private BrowserContext context;
    private Page page;
    private final List<String> console = new ArrayList<>();

    protected LoginPage loginPage;
    protected DashboardPage dashboardPage;

    /**
     * Once per suite: the app on a free port (with the planted bug if -Dleavedesk.bug=true) and one browser of the
     * chosen engine.
     */
    @BeforeSuite(alwaysRun = true)
    public void startAppAndBrowser() throws IOException {
        app = LeaveDeskServer.start(0, Boolean.getBoolean("leavedesk.bug"));
        playwright = Playwright.create();
        BrowserType type = switch (System.getProperty("browser", "chromium")) {
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> playwright.chromium();
        };
        browser = type.launch(new BrowserType.LaunchOptions()
                .setHeadless(!"false".equals(System.getProperty("headless"))));
    }

    /** Closes Playwright (and with it the browser), then the app. */
    @AfterSuite(alwaysRun = true)
    public void stopAppAndBrowser() {
        if (playwright != null) {
            playwright.close();
        }
        if (app != null) {
            app.close();
        }
    }

    /**
     * Before every test: empty calendar, fresh browser context with the app's base URL, trace recording, console
     * capture and new page objects.
     */
    @BeforeMethod(alwaysRun = true)
    public void freshContext() {
        resetAppData();
        console.clear();
        context = browser.newContext(new Browser.NewContextOptions().setBaseURL(app.baseUrl()));
        context.setDefaultTimeout(10_000);
        context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
        page = context.newPage();
        page.onConsoleMessage(msg -> console.add(msg.type() + ": " + msg.text()));
        loginPage = new LoginPage(page);
        dashboardPage = new DashboardPage(page);
    }

    /** After every test: keeps the trace only when the test failed, then closes the context. */
    @AfterMethod(alwaysRun = true)
    public void closeContext(ITestResult result) {
        if (context == null) {
            return;
        }
        // Keep the trace only for failures: it is the evidence a person opens after the AI triage.
        Path trace = result.isSuccess() ? null : FailureEvidenceListener.folderFor(result).resolve("trace.zip");
        context.tracing().stop(new Tracing.StopOptions().setPath(trace));
        context.close();
        context = null;
    }

    /** The page of the running test; the evidence listener uses it for the screenshot and the DOM. */
    public Page page() {
        return page;
    }

    /** Browser console lines of the running test. */
    public List<String> consoleLines() {
        return List.copyOf(console);
    }

    /** Logs in through the UI and waits until the dashboard shows. */
    protected void loginAs(String username) {
        loginPage.open();
        loginPage.loginAs(username, TestUsers.PASSWORD);
        dashboardPage.waitUntilShown();
    }

    /** Every test starts with an empty calendar (POST /api/test/reset). */
    private static void resetAppData() {
        APIRequestContext api = playwright.request().newContext();
        try {
            int status = api.post(app.baseUrl() + "/api/test/reset", RequestOptions.create()).status();
            if (status != 200) {
                throw new IllegalStateException("Data reset answered HTTP " + status);
            }
        } finally {
            api.dispose();
        }
    }
}
