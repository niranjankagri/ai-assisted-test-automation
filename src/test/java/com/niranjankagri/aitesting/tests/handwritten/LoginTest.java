package com.niranjankagri.aitesting.tests.handwritten;

import com.niranjankagri.aitesting.framework.BaseUiTest;
import com.niranjankagri.aitesting.framework.TestCase;
import com.niranjankagri.aitesting.framework.TestUsers;
import org.testng.annotations.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Hand-written tests for TC-101 and TC-102. They are the style reference the generator sends to
 * Claude: page objects only, Playwright's auto-waiting assertThat, one behaviour per test.
 */
public class LoginTest extends BaseUiTest {

    /** TC-101: after login the dashboard shows the name, the role, the full 24 days and an empty request list. */
    @Test(groups = "ui")
    @TestCase("TC-101")
    public void tc101_employeeLogsInAndSeesTheirBalance() {
        // Arrange: start page
        loginPage.open();

        // Act: sign in as the employee
        loginPage.loginAs(TestUsers.EMPLOYEE, TestUsers.PASSWORD);

        // Assert: dashboard with name, role and the full annual allowance
        assertThat(dashboardPage.view()).isVisible();
        assertThat(dashboardPage.currentUser()).hasText("Alice Meyer (Employee)");
        assertThat(dashboardPage.annualRemaining()).hasText("24");
        assertThat(dashboardPage.emptyListMessage()).isVisible();
    }

    /** TC-102: a wrong password shows the error and stays on the login form. */
    @Test(groups = "ui")
    @TestCase("TC-102")
    public void tc102_wrongPasswordShowsAnErrorAndStaysOnTheLoginForm() {
        loginPage.open();

        loginPage.loginAs(TestUsers.EMPLOYEE, "not-the-password");

        assertThat(loginPage.errorMessage()).hasText("Wrong username or password");
        assertThat(loginPage.view()).isVisible();
        assertThat(dashboardPage.view()).isHidden();
    }
}
